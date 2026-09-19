package com.cloud.apim.otoroshi.extensions.waf.analytics

import otoroshi.env.Env
import otoroshi.next.models.{NgPluginInstance, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*

/**
 * Where a route's protection comes from.
 *
 * A route can be protected by its own slots, or by a rule of the global preset table, and the two
 * are not read the same way: the first is on the entity, the second is a decision taken per request
 * from a table living on the global configuration. Reporting only the first — which is what this
 * page did before the table existed — reports a fleet governed centrally as entirely unprotected.
 */
final case class RouteGovernance(
    workspaceId: Option[String] = None,
    workspaceName: Option[String] = None,
    preset: Option[JsObject] = None,
    selfManaged: Boolean = false,
    alsoMatched: Seq[String] = Seq.empty
)

object RouteGovernance {
  val none: RouteGovernance = RouteGovernance()
}

/** What one route is actually protected by, as opposed to what someone believes it is. */
final case class RoutePosture(
    routeId: String,
    routeName: String,
    viaPreset: Boolean,
    governance: RouteGovernance,
    waf: Option[String],
    wafBlocking: Boolean,
    reputation: Option[String],
    bots: Boolean,
    fail2ban: Option[Boolean],
    gate: Boolean,
    response: Boolean,
    policy: Option[String],
    policyDryRun: Boolean
) {

  /** Anything at all, on this route. */
  def covered: Boolean = waf.isDefined || reputation.isDefined || bots || fail2ban.isDefined || gate || response

  /**
   * Whether a decision on this route can actually stop anything.
   *
   * A route can be covered and enforce nothing — the whole suite in dry run — and that distinction
   * is the reason this view exists. "Protected" without it means "configured".
   */
  def enforcing: Boolean =
    (waf.isDefined && wafBlocking) || (response && !policyDryRun) || reputation.contains("block")

  /** `route` when the protection is on the entity, `workspace` when the table decides, else `none`. */
  def source: String =
    if (governance.selfManaged || (covered && governance.workspaceId.isEmpty)) "route"
    else if (governance.workspaceId.isDefined) "workspace"
    else "none"

  def json: JsValue = Json.obj(
    "route_id"        -> routeId,
    "route_name"      -> routeName,
    "via_preset"      -> viaPreset,
    "source"          -> source,
    "workspace"       -> governance.workspaceId,
    "workspace_name"  -> governance.workspaceName,
    "self_managed"    -> governance.selfManaged,
    "also_matched"    -> governance.alsoMatched,
    "waf"             -> waf,
    "waf_blocking"    -> wafBlocking,
    "reputation"      -> reputation,
    "bots"            -> bots,
    "fail2ban"        -> fail2ban.map(dry => if (dry) "dry run" else "armed"),
    "gate"            -> gate,
    "response"        -> response,
    "policy"          -> policy,
    "policy_dry_run"  -> policyDryRun,
    "covered"         -> covered,
    "enforcing"       -> enforcing
  )
}

/**
 * Which routes are protected, in which mode.
 *
 * It reads live state — the router's routes, the global preset table and the suite's own entities —
 * and touches no analytics at all: it answers a question about configuration, not about traffic, so
 * it works before a single event has been emitted and without the user-analytics exporter being set
 * up.
 *
 * The row that earns the page is the one with nothing on it. Nobody goes looking for a route they
 * forgot to protect.
 */
object PostureReport {

  private val wafId        = NgPluginHelper.pluginId[CloudApimWaf]
  private val reputationId = NgPluginHelper.pluginId[CloudApimIpReputation]
  private val botsId       = NgPluginHelper.pluginId[CloudApimBotGuard]
  private val fail2banId   = NgPluginHelper.pluginId[CloudApimFail2Ban]
  private val gateId       = NgPluginHelper.pluginId[CloudApimThreatGate]
  private val responseId   = NgPluginHelper.pluginId[CloudApimThreatResponse]
  private val presetId     = NgPluginHelper.pluginId[CloudApimSecuritySuitePreset]

  def of(route: NgRoute, governance: RouteGovernance = RouteGovernance.none)(using env: Env): RoutePosture = {
    val ext   = env.adminExtensions.extension[CloudApimWafExtension]
    val slots = route.plugins.slots.filter(_.enabled)
    def slot(id: String): Option[NgPluginInstance] = slots.find(_.plugin == id)

    // A preset expands at request time, so its coverage is read from its flags rather than by running
    // the expansion — the answer has to be available with no request in flight. The route's own
    // preset comes first: when the table does not stand down, both apply, and a section is covered as
    // soon as either turns it on.
    val presets      = slot(presetId).map(_.config.raw).toSeq ++ governance.preset.toSeq
    val presetOn     = (field: String, default: Boolean) =>
      presets.exists(c => (c \ field).asOpt[Boolean].getOrElse(default))
    val presetRef    = (field: String) =>
      presets.flatMap(c => (c \ field).asOpt[String]).map(_.trim).find(_.nonEmpty)
    val presetWafRef = presetRef("waf_config")

    val wafRef = slot(wafId)
      .flatMap(i => (i.config.raw \ "ref").asOpt[String])
      .orElse(presetWafRef.filter(_ => presetOn("waf", true)))
      .filter(_.trim.nonEmpty)
    val wafCfg = wafRef.flatMap(r => ext.flatMap(_.states.config(r)))

    val reputationMode = slot(reputationId)
      .map(i => (i.config.raw \ "mode").asOpt[String].getOrElse("block"))
      .orElse(
        Option.when(presets.nonEmpty && presetOn("reputation", true))(
          presets.flatMap(c => (c \ "reputation_mode").asOpt[String]).headOption.getOrElse("block")
        )
      )

    val policyRef = slot(gateId)
      .orElse(slot(responseId))
      .flatMap(i => (i.config.raw \ "policy").asOpt[String])
      .orElse(presetRef("threat_policy"))
      .filter(_.trim.nonEmpty)
    val policy    = policyRef.flatMap(r => ext.flatMap(_.security.policy(r)))

    RoutePosture(
      routeId = route.id,
      routeName = route.name,
      viaPreset = presets.nonEmpty,
      governance = governance,
      waf = wafCfg.map(_.name),
      wafBlocking = wafCfg.exists(_.block),
      reputation = reputationMode,
      bots = slot(botsId).isDefined || (presets.nonEmpty && presetOn("bots", true)),
      fail2ban = slot(fail2banId)
        .map(i => (i.config.raw \ "dry_run").asOpt[Boolean].getOrElse(true))
        .orElse(
          Option.when(presets.nonEmpty && presetOn("fail2ban", false))(
            presets.flatMap(c => (c \ "fail2ban_dry_run").asOpt[Boolean]).headOption.getOrElse(true)
          )
        ),
      gate = slot(gateId).isDefined || (presets.nonEmpty && presetOn("gate", true)),
      response = slot(responseId).isDefined || (presets.nonEmpty && presetOn("response", true)),
      policy = policy.map(_.name).orElse(policyRef),
      // no policy at all means the built-in one, which is dry run by definition
      policyDryRun = policy.forall(_.dryRun)
    )
  }

  /**
   * The global preset table, resolved against every route of the router.
   *
   * Selectors are evaluated with no request in flight, which is exactly what they are given at
   * expansion time for everything reading the route. A selector reading the request cannot be
   * answered here — `Table.dynamic` says so rather than letting the page claim a coverage it guessed.
   */
  final case class Table(
      installed: Boolean,
      enabled: Boolean,
      config: CloudApimSecuritySuiteGlobalPresetConfig,
      governanceOf: Map[String, RouteGovernance]
  ) {
    def dynamic: Boolean = !config.routeOnly
    def rules: Seq[CloudApimSecuritySuiteGlobalRule] = config.rules
  }

  def table(routes: Seq[NgRoute])(using env: Env): Table = {
    val slot     = CloudApimSecuritySuiteGlobalPreset.installedSlot
    val config   = CloudApimSecuritySuiteGlobalPreset.installedConfig
    val live     = slot.exists(_.enabled)
    val resolved =
      if (!live) Map.empty[String, RouteGovernance]
      else
        routes.map { route =>
          // the expression language reads the route off the attributes, the same place the engine
          // puts it before a preset is expanded
          val attrs   = TypedMap.empty.put(otoroshi.next.plugins.Keys.RouteKey -> route)
          val resolve = (expr: String) => expr.evaluateEl(attrs)
          val down    = config.standsDownOn(route)
          val matched = if (down) Seq.empty else config.matching(route, resolve)
          val winner  = matched.headOption
          route.id -> RouteGovernance(
            workspaceId = winner.filterNot(_.skip).map(_.id),
            workspaceName = winner.filterNot(_.skip).map(r => Option(r.name).filter(_.nonEmpty).getOrElse(r.id)),
            preset = winner.filterNot(_.skip).map(_.preset.json.asObject),
            selfManaged = down,
            alsoMatched = matched.drop(1).map(_.id)
          )
        }.toMap
    Table(installed = slot.isDefined, enabled = live, config = config, governanceOf = resolved)
  }

  def all(using env: Env): Seq[RoutePosture] = {
    val routes = env.proxyState.allRoutes()
    val tbl    = table(routes)
    routes.map(r => of(r, tbl.governanceOf.getOrElse(r.id, RouteGovernance.none))).sortBy(p => (p.covered, p.routeName))
  }

  def json(using env: Env): JsValue = {
    val routes   = env.proxyState.allRoutes()
    val tbl      = table(routes)
    val postures = routes
      .map(r => of(r, tbl.governanceOf.getOrElse(r.id, RouteGovernance.none)))
      .sortBy(p => (p.covered, p.routeName))
    Json.obj(
      "routes"     -> JsArray(postures.map(_.json)),
      "summary"    -> Json.obj(
        "total"     -> postures.size,
        "covered"   -> postures.count(_.covered),
        "enforcing" -> postures.count(_.enforcing),
        "uncovered" -> postures.count(!_.covered)
      ),
      "governance" -> Json.obj(
        "installed"    -> tbl.installed,
        "enabled"      -> tbl.enabled,
        "rules"        -> tbl.rules.size,
        "dynamic"      -> tbl.dynamic,
        "governed"     -> postures.count(_.governance.workspaceId.isDefined),
        "self_managed" -> postures.count(_.governance.selfManaged)
      )
    )
  }
}
