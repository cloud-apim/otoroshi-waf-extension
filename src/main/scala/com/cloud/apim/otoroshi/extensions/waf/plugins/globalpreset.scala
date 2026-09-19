package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.next.models.{NgPluginInstance, NgPlugins, NgRoute}
import otoroshi.next.plugins.api.*
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

/**
 * One condition on the route being expanded.
 *
 * Both sides share the operator language of [[JsonPathValidator]] — `ContainedIn(a, b)`,
 * `Contains(x)`, `Regex(…)`, `Wildcard(…)`, `StartsWith(…)`, `Size(n)`, `IsDefined()` and their
 * negative forms — because the comparison *is* a `JsonPathValidator`, applied either to the route
 * entity or to a one-field document holding the result of an expression. Nothing of that grammar is
 * reimplemented here, so a target reads exactly like a predicate anywhere else in Otoroshi.
 *
 * `path` is preferred when it can express the question: it reads the route entity directly, so
 * `$.tags` and `$.groups` come back as real arrays and `Contains(…)` means what it says, while the
 * expression language has no accessor for either and flattens everything to a string.
 */
final case class CloudApimSecuritySuiteTarget(
    path: Option[String] = None,
    expression: Option[String] = None,
    value: JsValue = JsNull
) {

  /**
   * Whether the answer depends on the route alone, which is what makes a decision cacheable across
   * requests. A JSONPath reads the route entity by construction. An expression only qualifies when
   * every one of its tokens does — `${req.path}` or `${apikey.metadata.x}` disqualifies the whole
   * rule set, conservatively, since the classification is a static scan and not an evaluation.
   */
  lazy val routeOnly: Boolean =
    path.isDefined || expression.forall(CloudApimSecuritySuiteTarget.routeOnlyExpression)

  def matches(routeJson: JsValue, resolve: String => String)(using env: Env): Boolean =
    (path, expression) match {
      case (Some(p), _) => JsonPathValidator(p, value).validate(routeJson)
      case (_, Some(e)) => JsonPathValidator("$.value", value).validate(Json.obj("value" -> resolve(e)))
      // a target that selects on nothing selects nothing. the other way round — a malformed target
      // matching everything — would silently widen a rule to the whole fleet, which for a plugin
      // that arms a WAF is the dangerous direction
      case _            => false
    }
}

object CloudApimSecuritySuiteTarget {

  private val elToken = """\$\{([^}]*)\}""".r

  /** Every token of the expression, and every branch of a `||` chain, reads off the route. */
  def routeOnlyExpression(expr: String): Boolean =
    elToken
      .findAllMatchIn(expr)
      .forall(_.group(1).split("\\|\\|").forall { branch =>
        val trimmed = branch.trim
        trimmed.startsWith("route.") || trimmed.startsWith("service.")
      })

  val format: Format[CloudApimSecuritySuiteTarget] = new Format[CloudApimSecuritySuiteTarget] {
    override def writes(o: CloudApimSecuritySuiteTarget): JsValue = Json.obj(
      "path"       -> o.path.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "expression" -> o.expression.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "value"      -> o.value
    )
    override def reads(json: JsValue): JsResult[CloudApimSecuritySuiteTarget] = Try {
      CloudApimSecuritySuiteTarget(
        path = json.select("path").asOpt[String].map(_.trim).filter(_.nonEmpty),
        expression = json.select("expression").asOpt[String].map(_.trim).filter(_.nonEmpty),
        value = json.select("value").asOpt[JsValue].getOrElse(JsNull)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }
}

/**
 * One row of the table: which routes, and what to lay down on them.
 *
 * An empty `targets` list matches every route — the explicit "all" entry, which belongs at the
 * bottom since the search stops at the first match.
 */
final case class CloudApimSecuritySuiteGlobalRule(
    id: String = "",
    name: String = "",
    enabled: Boolean = true,
    skip: Boolean = false,
    targets: Seq[CloudApimSecuritySuiteTarget] = Seq.empty,
    preset: CloudApimSecuritySuitePresetConfig = CloudApimSecuritySuitePresetConfig.default
) {
  lazy val routeOnly: Boolean = targets.forall(_.routeOnly)
  lazy val readsRouteJson: Boolean = targets.exists(_.path.isDefined)
  def matches(routeJson: JsValue, resolve: String => String)(using env: Env): Boolean =
    targets.forall(_.matches(routeJson, resolve))
}

object CloudApimSecuritySuiteGlobalRule {

  val format: Format[CloudApimSecuritySuiteGlobalRule] = new Format[CloudApimSecuritySuiteGlobalRule] {
    override def writes(o: CloudApimSecuritySuiteGlobalRule): JsValue = Json.obj(
      "id"      -> o.id,
      "name"    -> o.name,
      "enabled" -> o.enabled,
      "skip"    -> o.skip,
      "targets" -> JsArray(o.targets.map(CloudApimSecuritySuiteTarget.format.writes)),
      "preset"  -> o.preset.json
    )
    override def reads(json: JsValue): JsResult[CloudApimSecuritySuiteGlobalRule] = Try {
      CloudApimSecuritySuiteGlobalRule(
        id = json.select("id").asOpt[String].map(_.trim).getOrElse(""),
        name = json.select("name").asOpt[String].getOrElse(""),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        skip = json.select("skip").asOpt[Boolean].getOrElse(false),
        targets = json
          .select("targets")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(CloudApimSecuritySuiteTarget.format.reads(_).asOpt))
          .getOrElse(Seq.empty)
          .toSeq,
        preset = CloudApimSecuritySuitePresetConfig.format
          .reads(json.select("preset").asOpt[JsValue].getOrElse(Json.obj()))
          .getOrElse(CloudApimSecuritySuitePresetConfig.default)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }
}

final case class CloudApimSecuritySuiteGlobalPresetConfig(
    rules: Seq[CloudApimSecuritySuiteGlobalRule] = Seq.empty,
    skipProtectedRoutes: Boolean = true
) extends NgPluginConfig {

  override def json: JsValue = CloudApimSecuritySuiteGlobalPresetConfig.format.writes(this)

  /** Set when no selector anywhere reads anything but the route, so a decision can be kept per route. */
  lazy val routeOnly: Boolean = rules.forall(_.routeOnly)

  private lazy val liveRules: Seq[CloudApimSecuritySuiteGlobalRule] = rules.filter(_.enabled)
  private lazy val readsRouteJson: Boolean = liveRules.exists(_.readsRouteJson)

  /**
   * Every live rule whose targets match this route, in table order.
   *
   * The expansion only ever needs the first one. A console needs them all: "this route is governed
   * by A, and would also have matched B" is the difference between a table someone can reason about
   * and one they have to simulate in their head.
   */
  def matching(route: NgRoute, resolve: String => String)(using env: Env): Seq[CloudApimSecuritySuiteGlobalRule] = {
    // NgRoute.json rebuilds the whole entity, frontend and backend included, so it is paid once per
    // call and only when some selector actually reads it
    val routeJson = if (readsRouteJson) route.json else JsNull
    liveRules.filter(_.matches(routeJson, resolve))
  }

  /** Whether the table stands down on this route because it already carries the fabric. */
  def standsDownOn(route: NgRoute): Boolean =
    skipProtectedRoutes && CloudApimSecuritySuiteGlobalPresetConfig.carriesOwnProtection(route)

  /** The first rule that claims this route, or none — in which case the route is left untouched. */
  def select(route: NgRoute, resolve: String => String)(using env: Env): Option[CloudApimSecuritySuiteGlobalRule] =
    if (standsDownOn(route)) None
    else {
      val routeJson = if (readsRouteJson) route.json else JsNull
      liveRules.find(_.matches(routeJson, resolve))
    }
}

object CloudApimSecuritySuiteGlobalPresetConfig {

  val default: CloudApimSecuritySuiteGlobalPresetConfig = CloudApimSecuritySuiteGlobalPresetConfig()

  /**
   * Every plugin the fabric is made of, the route-level preset included.
   *
   * A route carrying any of them has been protected on purpose by whoever designed it, and laying a
   * second chain over that one is not additive: two WAF slots means two rule engines with two
   * configurations and nothing to say which is authoritative, and the second threat response reads
   * a score the first one has already acted on.
   */
  private val fabric: Set[String] = Set(
    NgPluginHelper.pluginId[CloudApimSecuritySuitePreset],
    NgPluginHelper.pluginId[CloudApimThreatGate],
    NgPluginHelper.pluginId[CloudApimBotGuard],
    NgPluginHelper.pluginId[CloudApimIpReputation],
    NgPluginHelper.pluginId[CloudApimWaf],
    NgPluginHelper.pluginId[CloudApimFail2Ban],
    NgPluginHelper.pluginId[CloudApimThreatResponse]
  )

  def carriesOwnProtection(route: NgRoute): Boolean =
    route.plugins.slots.exists(slot => slot.enabled && fabric.contains(slot.plugin))

  val format: Format[CloudApimSecuritySuiteGlobalPresetConfig] =
    new Format[CloudApimSecuritySuiteGlobalPresetConfig] {
      override def writes(o: CloudApimSecuritySuiteGlobalPresetConfig): JsValue = Json.obj(
        "rules"                 -> JsArray(o.rules.map(CloudApimSecuritySuiteGlobalRule.format.writes)),
        "skip_protected_routes" -> o.skipProtectedRoutes
      )
      override def reads(json: JsValue): JsResult[CloudApimSecuritySuiteGlobalPresetConfig] = Try {
        CloudApimSecuritySuiteGlobalPresetConfig(
          // a table written by hand carries no id. one derived from the position is enough for
          // everything but attributing entities to a rule, and that only happens from the studio,
          // which always writes ids — the first save materialises them
          rules = json
            .select("rules")
            .asOpt[Seq[JsValue]]
            .map(_.flatMap(CloudApimSecuritySuiteGlobalRule.format.reads(_).asOpt))
            .getOrElse(Seq.empty)
            .toSeq
            .zipWithIndex
            .map { case (rule, idx) => if (rule.id.isEmpty) rule.copy(id = s"rule_$idx") else rule },
          skipProtectedRoutes = json.select("skip_protected_routes").asOpt[Boolean].getOrElse(true)
        )
      } match {
        case Success(value) => JsSuccess(value)
        case Failure(err)   => JsError(err.getMessage)
      }
    }

  val configFlow: Seq[String] = Seq("rules", "skip_protected_routes")

  private val targetSchema: JsObject = Json.obj(
    "type"   -> "object",
    "array"  -> true,
    "format" -> "form",
    "label"  -> "Targets",
    "help"   -> "Every target must match. A rule with no target at all matches every route.",
    "schema" -> Json.obj(
      "path"       -> Json.obj(
        "type"  -> "string",
        "label" -> "Route path (JSONPath)",
        "props" -> Json.obj(
          "subTitle" -> "Read off the route entity: $.tags, $.groups, $.metadata.env, $.id. Arrays stay arrays."
        )
      ),
      "expression" -> Json.obj(
        "type"  -> "string",
        "label" -> "Expression (EL)",
        "props" -> Json.obj(
          "subTitle" -> "Used when no JSONPath is set: ${route.metadata.env}, ${route.id}, ${req.host}."
        )
      ),
      "value"      -> Json.obj(
        "type"  -> "code",
        "label" -> "Value",
        "props" -> Json.obj(
          "label"      -> "Value",
          "type"       -> "json",
          "editorOnly" -> true,
          "help"       -> "\"ContainedIn(route_a, route_b)\", \"Contains(public)\", \"Regex(prod|staging)\", \"IsDefined()\"…"
        )
      )
    ),
    "flow"   -> Json.arr("path", "expression", "value")
  )

  val configSchema: JsObject = Json.obj(
    "rules" -> Json.obj(
      "type"    -> "object",
      "array"   -> true,
      "format"  -> "form",
      "label"   -> "Rules",
      "help"    -> "Read top to bottom. The first rule whose targets all match decides, and the rules below it are never considered.",
      "default" -> Json.arr(),
      "schema"  -> Json.obj(
        "name"    -> Json.obj(
          "type"  -> "string",
          "label" -> "Name",
          "props" -> Json.obj("help" -> "Free text, for whoever reads this table next")
        ),
        "enabled" -> Json.obj(
          "type"  -> "bool",
          "label" -> "Enabled",
          "props" -> Json.obj("help" -> "A disabled rule is skipped entirely and the search carries on below it")
        ),
        "skip"    -> Json.obj(
          "type"  -> "bool",
          "label" -> "Leave these routes alone",
          "props" -> Json.obj(
            "help" -> "Match, lay down nothing at all and stop. This is the opt-out: put such a rule above the ones that protect."
          )
        ),
        "targets" -> targetSchema,
        "preset"  -> Json.obj(
          "type"   -> "object",
          "format" -> "form",
          "label"  -> "Protection",
          "schema" -> CloudApimSecuritySuitePresetConfig.configSchema,
          "flow"   -> JsArray(CloudApimSecuritySuitePresetConfig.configFlow.map(JsString.apply))
        )
      ),
      "flow"    -> Json.arr("name", "enabled", "skip", "targets", "preset")
    ),
    "skip_protected_routes" -> Json.obj(
      "type"  -> "bool",
      "label" -> "Leave already-protected routes alone",
      "props" -> Json.obj(
        "help" -> "Skip any route that already carries the route-level preset or one of the fabric plugins, so a route protected on purpose is never given a second chain."
      )
    )
  )
}

/**
 * The fabric laid down across a fleet of routes from one place, instead of route by route.
 *
 * `CloudApimSecuritySuitePreset` answers "what runs on this route, and in which order". It still has
 * to be added to every route that wants it, which for a hundred routes means a hundred identical
 * slots and a hundred chances to forget one — and a route added next month is protected by nobody
 * until someone remembers.
 *
 * This one is meant for the global plugins of the danger zone, where Otoroshi runs it against every
 * route it has just matched. Its configuration is therefore not a protection but a *table*: rules
 * pairing a selector with a protection, read top to bottom, first match wins. A route no rule claims
 * expands into nothing at all, so the plugin is inert until the table says otherwise — the right
 * default for something installed fleet-wide.
 *
 * First match wins rather than accumulate, and that is the whole reason the table is ordered: two
 * rules both contributing a WAF would put two rule engines with two configurations on one route,
 * with nothing to say which is authoritative. Ordering makes the narrow rule win by sitting above
 * the broad one, and makes `skip` a real opt-out rather than a subtraction.
 *
 * A route that already carries the fabric is left to it. Otoroshi expands the global slots and the
 * route's own slots into one chain, so without that stand-down a route protected on purpose would
 * end up with two rule engines and two threat responses, the second one reading a score the first
 * has already acted on.
 *
 * What it does not change: the expansion itself is delegated to the route-level preset, so both
 * produce the exact same chain with the exact same ordering indices, and the honeypot and the
 * incoming-request-validator variants remain out of reach for either — those run before routing,
 * where no route is known yet.
 */
class CloudApimSecuritySuiteGlobalPreset extends NgPresetPlugin {

  override def steps: Seq[NgStep]                          =
    Seq(NgStep.ValidateAccess, NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           =
    Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Threat Protection - Global Preset"
  override def description: Option[String]                 =
    ("Lays the detection fabric across a fleet of routes from the global plugins, picking what each route gets " +
      "from a table of selectors instead of from a slot on the route").some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimSecuritySuiteGlobalPresetConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimSecuritySuiteGlobalPresetConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimSecuritySuiteGlobalPresetConfig.configSchema.some

  // the expansion is the route-level preset's, verbatim: one chain, one ordering, one place where
  // the indices are decided
  private val fabric = new CloudApimSecuritySuitePreset()

  override def expand(ctx: NgPresetPluginContext): Seq[NgPluginInstance] = {
    given env: Env = OtoroshiEnvHolder.get()
    val hash       = ctx.config.hashCode()
    val config     = CloudApimSecuritySuiteGlobalPreset.configOf(hash, ctx.config)
    val resolve    = (expr: String) => expr.evaluateEl(ctx.attrs)
    if (config.routeOnly) {
      CloudApimSecuritySuiteGlobalPreset.decisions
        .get(
          s"${CloudApimSecuritySuiteGlobalPreset.cacheKeyOf(ctx.route)}::$hash",
          _ => instances(config, ctx.route, resolve)
        )
    } else {
      instances(config, ctx.route, resolve)
    }
  }

  /**
   * The table applied to one route, kept clear of the request context so the selection can be
   * asserted on directly. `resolve` is the expression language, injected rather than called, because
   * it needs a live Env and the selection logic does not.
   */
  def instances(
      config: CloudApimSecuritySuiteGlobalPresetConfig,
      route: NgRoute,
      resolve: String => String
  )(using env: Env): Seq[NgPluginInstance] =
    config.select(route, resolve) match {
      case None                    => Seq.empty
      case Some(rule) if rule.skip => Seq.empty
      case Some(rule)              => fabric.instances(rule.preset)
    }
}

object CloudApimSecuritySuiteGlobalPreset {

  // Otoroshi expands presets on every request and caches nothing of it, and this one is read for
  // every route of the fleet. Both caches follow the five seconds of Otoroshi's own cachedConfig, so
  // an edit in the danger zone takes effect within the same delay as any other plugin config.
  private val ttl = 5.seconds

  private val configs: Cache[Int, CloudApimSecuritySuiteGlobalPresetConfig] = Scaffeine()
    .expireAfterWrite(ttl)
    .maximumSize(64)
    .build[Int, CloudApimSecuritySuiteGlobalPresetConfig]()

  // only consulted for a table whose selectors read the route alone. one whose selectors look at the
  // request is re-evaluated every time, since two requests on the same route can then disagree
  private val decisions: Cache[String, Seq[NgPluginInstance]] = Scaffeine()
    .expireAfterWrite(ttl)
    .maximumSize(10000)
    .build[String, Seq[NgPluginInstance]]()

  /** The plugin id of the global preset, as it appears in the global plugins. */
  val pluginId: String = NgPluginHelper.pluginId[CloudApimSecuritySuiteGlobalPreset]

  /**
   * The global preset slot as it sits in the global plugins, if it is there at all.
   *
   * Read from the live global config rather than kept anywhere: the table is edited in the danger
   * zone, by the studio, or by whatever wrote the config, and none of them go through here.
   */
  def installedSlot(using env: Env): Option[NgPluginInstance] = {
    val gc = env.datastores.globalConfigDataStore.latest()(using env.otoroshiExecutionContext, env)
    NgPlugins.readFrom(gc.plugins.config.select("ng")).slots.find(_.plugin == pluginId)
  }

  /** The table as configured, whether or not its slot is enabled. */
  def installedConfig(using env: Env): CloudApimSecuritySuiteGlobalPresetConfig =
    installedSlot
      .map(slot => CloudApimSecuritySuiteGlobalPresetConfig.format.reads(slot.config.raw))
      .flatMap(_.asOpt)
      .getOrElse(CloudApimSecuritySuiteGlobalPresetConfig.default)

  /**
   * What identifies a route for the decision cache.
   *
   * Not `cacheableId`: a route composition hands out one `NgRoute` per frontend, all carrying the
   * service's id, and they differ in exactly the two places that matter here — their own plugins,
   * which decide whether the table stands down, and their frontend, which a selector may read.
   * Otoroshi already computes the per-frontend discriminator and puts it in the metadata; it simply
   * does not read it back anywhere.
   */
  def cacheKeyOf(route: NgRoute): String =
    route.metadata.getOrElse("otoroshi-core-cacheable-route-id", route.id)

  private def configOf(hash: Int, raw: JsValue): CloudApimSecuritySuiteGlobalPresetConfig =
    configs.get(
      hash,
      _ =>
        CloudApimSecuritySuiteGlobalPresetConfig.format
          .reads(raw)
          .getOrElse(CloudApimSecuritySuiteGlobalPresetConfig.default)
    )
}
