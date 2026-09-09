package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.security.*
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.IpFiltering
import otoroshi.next.plugins.api.*
import otoroshi.utils.RegexPool
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** An inclusive status range, written `403` or `500-599`. */
final case class StatusRange(from: Int, to: Int) {
  def contains(status: Int): Boolean = status >= from && status <= to
  def str: String                    = if (from == to) from.toString else s"$from-$to"
}

object StatusRange {
  def parse(raw: String): Option[StatusRange] = Try {
    val t = raw.trim
    if (t.contains("-")) {
      val Array(a, b) = t.split("-", 2)
      StatusRange(a.trim.toInt, b.trim.toInt)
    } else {
      val v = t.toInt
      StatusRange(v, v)
    }
  }.toOption.filter(r => r.from <= r.to)
}

/** A path rule: `allow` puts the path in scope for counting, `block` takes it out. */
final case class UrlRule(pattern: String = ".*", mode: String = "allow") {
  def matches(pathAndQuery: String): Boolean = RegexPool.regex(pattern).matches(pathAndQuery)
  def json: JsValue                          = Json.obj("pattern" -> pattern, "mode" -> mode)
}

object UrlRule {
  def read(json: JsValue): UrlRule = UrlRule(
    json.select("pattern").asOpt[String].getOrElse(".*"),
    json.select("mode").asOpt[String].getOrElse("allow")
  )
}

final case class CloudApimFail2BanConfig(
    counterKey: String = "${route.id}-${req.ip}",
    banScope: String = "auto",
    detectTime: FiniteDuration = 10.minutes,
    banTime: FiniteDuration = 1.hour,
    maxRetry: Int = 5,
    statusCodes: Seq[StatusRange] = CloudApimFail2BanConfig.defaultStatuses,
    urlRules: Seq[UrlRule] = Seq.empty,
    ignored: Seq[String] = Seq.empty,
    dryRun: Boolean = true,
    fabricWeight: Int = 3
) extends NgPluginConfig {

  override def json: JsValue = CloudApimFail2BanConfig.format.writes(this)

  def isFailure(status: Int): Boolean = statusCodes.exists(_.contains(status))

  def isInScope(pathAndQuery: String): Boolean =
    if (urlRules.isEmpty) true
    else
      urlRules.find(_.matches(pathAndQuery)) match {
        case Some(rule) if rule.mode.equalsIgnoreCase("block") => false
        case _                                                 => true
      }

  /**
   * Same three notations Otoroshi's own plugin accepts, so an existing list can be pasted over.
   *
   * With one repair: every released Otoroshi hands the whole `Cidr(10.0.0.0/8)` string to
   * `Cidr.fromString`, which does not know about the wrapper and returns `None` — so the notation
   * parses to a filter that matches nothing. The prefix is stripped here. The same fix has been
   * made upstream, so this only differs from an Otoroshi new enough to carry it.
   */
  def isIgnored(value: String): Boolean = ignored.exists {
    // note that the bare form is a wildcard, not a regex — `RegexPool.apply` escapes `.` and
    // expands `*`, which is what makes `*-probe` the right way to write it
    case ip if ip.startsWith("Ip(") && ip.endsWith(")")         => RegexPool(ip.substring(3).init).matches(value)
    case cidr if cidr.startsWith("Cidr(") && cidr.endsWith(")") => IpFiltering.cidr(cidr.substring(5).init).contains(value)
    case raw                                                    => RegexPool(raw).matches(value)
  }

  def refOf(identity: ClientIdentity): IdentityRef =
    if (banScope.trim.equalsIgnoreCase("ip")) IdentityRef(IdentityRef.Ip, identity.ip)
    else identity.refs.headOption.getOrElse(IdentityRef(IdentityRef.Ip, identity.ip))
}

object CloudApimFail2BanConfig {

  /**
   * Deliberately narrower than Otoroshi's `400, 401, 403-499, 500-599`.
   *
   * `404` is ordinary traffic on any site with a broken link, and `5xx` is the gateway's own fault:
   * counting either means a bad deploy of yours bans your own users, at the exact moment you can
   * least afford it. What is left is the set that says a caller is trying credentials or ignoring
   * a refusal.
   */
  val defaultStatuses: Seq[StatusRange] =
    Seq(StatusRange(401, 401), StatusRange(403, 403), StatusRange(407, 407), StatusRange(429, 429))

  val default: CloudApimFail2BanConfig = CloudApimFail2BanConfig()

  private def duration(js: JsValue, name: String, fallback: FiniteDuration): FiniteDuration =
    (js \ name).asOpt[JsValue] match {
      case Some(JsNumber(n)) => n.toLong.millis
      case Some(JsString(s)) =>
        val t = s.trim.toLowerCase
        Try {
          if (t.endsWith("ms")) t.stripSuffix("ms").trim.toLong.millis
          else if (t.endsWith("s")) t.stripSuffix("s").trim.toLong.seconds
          else if (t.endsWith("m")) t.stripSuffix("m").trim.toLong.minutes
          else if (t.endsWith("h")) t.stripSuffix("h").trim.toLong.hours
          else if (t.endsWith("d")) t.stripSuffix("d").trim.toLong.days
          else t.toLong.millis
        }.getOrElse(fallback)
      case _                 => fallback
    }

  val format: Format[CloudApimFail2BanConfig] = new Format[CloudApimFail2BanConfig] {
    override def writes(o: CloudApimFail2BanConfig): JsValue = Json.obj(
      "counter_key"   -> o.counterKey,
      "ban_scope"     -> o.banScope,
      "detect_time"   -> s"${o.detectTime.toSeconds}s",
      "ban_time"      -> s"${o.banTime.toSeconds}s",
      "max_retry"     -> o.maxRetry,
      "status_codes"  -> JsArray(o.statusCodes.map(r => JsString(r.str))),
      "url_rules"     -> JsArray(o.urlRules.map(_.json)),
      "ignored"       -> o.ignored,
      "dry_run"       -> o.dryRun,
      "fabric_weight" -> o.fabricWeight
    )
    override def reads(json: JsValue): JsResult[CloudApimFail2BanConfig] = Try {
      val statuses = json
        .select("status_codes")
        .asOpt[Seq[String]]
        .map(_.flatMap(StatusRange.parse))
        .filter(_.nonEmpty)
        .getOrElse(defaultStatuses)
      CloudApimFail2BanConfig(
        counterKey = json.select("counter_key").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse(default.counterKey),
        banScope = json.select("ban_scope").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse("auto"),
        detectTime = duration(json, "detect_time", default.detectTime),
        banTime = duration(json, "ban_time", default.banTime),
        maxRetry = json.select("max_retry").asOpt[Int].filter(_ > 0).getOrElse(default.maxRetry),
        statusCodes = statuses,
        urlRules = json.select("url_rules").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(UrlRule.read),
        ignored = json.select("ignored").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty),
        dryRun = json.select("dry_run").asOpt[Boolean].getOrElse(true),
        fabricWeight = json.select("fabric_weight").asOpt[Int].map(_.max(0)).getOrElse(default.fabricWeight)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "dry_run",
    "max_retry",
    "detect_time",
    "ban_time",
    "status_codes",
    "counter_key",
    "ban_scope",
    "url_rules",
    "ignored",
    "fabric_weight"
  )

  val configSchema: JsObject = Json.obj(
    "dry_run"       -> Json.obj(
      "type"  -> "bool",
      "label" -> "Dry run",
      "props" -> Json.obj(
        "help" -> "Count and report, but never issue a ban. The ledger is still charged, so the fabric can still act."
      )
    ),
    "max_retry"     -> Json.obj(
      "type"  -> "number",
      "label" -> "Max failures",
      "props" -> Json.obj("help" -> "Failures within the detection window before the caller is banned")
    ),
    "detect_time"   -> Json.obj(
      "type"  -> "string",
      "label" -> "Detection window",
      "props" -> Json.obj("help" -> "e.g. 10m. Sliding: every failure pushes the window out")
    ),
    "ban_time"      -> Json.obj(
      "type"  -> "string",
      "label" -> "Ban duration",
      "props" -> Json.obj("help" -> "e.g. 1h")
    ),
    "status_codes"  -> Json.obj(
      "type"  -> "array",
      "label" -> "Failure statuses",
      "props" -> Json.obj(
        "help" -> "Single codes or inclusive ranges: 401, 403, 429, 500-599. Counting 404 or 5xx will ban your own users."
      )
    ),
    "counter_key"   -> Json.obj(
      "type"  -> "string",
      "label" -> "Counter key",
      "props" -> Json.obj(
        "help" -> "Expression language. What counts as the same offender — ${req.ip} counts across every route, the default counts per route."
      )
    ),
    "ban_scope"     -> Json.obj(
      "type"  -> "select",
      "label" -> "Ban scope",
      "props" -> Json.obj(
        "help"    -> "Who gets banned. 'auto' bans the most specific identity known — apikey, then user, then address",
        "options" -> Json.arr(
          Json.obj("label" -> "Most specific identity", "value" -> "auto"),
          Json.obj("label" -> "Ip address", "value"            -> "ip")
        )
      )
    ),
    "url_rules"     -> Json.obj(
      "type"   -> "array",
      "label"  -> "Path rules",
      "array"  -> true,
      "format" -> "form",
      "schema" -> Json.obj(
        "pattern" -> Json.obj("type" -> "string", "label" -> "Pattern", "placeholder" -> "/api/.*"),
        "mode"    -> Json.obj(
          "type"           -> "select",
          "label"          -> "Mode",
          "possibleValues" -> Json.arr(
            Json.obj("label" -> "Count", "value"      -> "allow"),
            Json.obj("label" -> "Never count", "value" -> "block")
          )
        )
      ),
      "flow"   -> Json.arr("pattern", "mode")
    ),
    "ignored"       -> Json.obj(
      "type"  -> "array",
      "label" -> "Never counted",
      "props" -> Json.obj("help" -> "Counter keys that are never counted. A wildcard (*-probe), Ip(1.2.3.4) or Cidr(10.0.0.0/8)")
    ),
    "fabric_weight" -> Json.obj(
      "type"  -> "number",
      "label" -> "Ledger weight per failure",
      "props" -> Json.obj(
        "help" -> "Charged to the cross-request ledger on every counted failure, so failures compose with the other detectors. 0 disables the contribution."
      )
    )
  )
}

private[plugins] object Fail2BanSupport {

  /** Ours, so this plugin and Otoroshi's own can coexist without eating each other's counts. */
  val AlreadyCountedKey: TypedKey[Boolean] =
    TypedKey[Boolean]("cloud-apim.security.Fail2BanAlreadyCounted")

  def scopeOf(config: CloudApimFail2BanConfig, attrs: TypedMap)(using env: Env): String =
    config.counterKey.evaluateEl(attrs)

  /**
   * Records one failed response, off the response path.
   *
   * Nothing here is awaited by the caller: a counter that is slow, or a redis that is down, must
   * cost the response nothing. What it produces — a ban — is read back from a node-local map.
   */
  def count(
      config: CloudApimFail2BanConfig,
      request: RequestHeader,
      attrs: TypedMap,
      status: Int,
      routeId: Option[String],
      routeName: Option[String]
  )(using env: Env, ec: ExecutionContext): Unit = {
    ThreatSupport.module.foreach { mod =>
      val scope = scopeOf(config, attrs)
      if (!config.isIgnored(scope) && config.isInScope(request.thePath) && config.isFailure(status)) {
        attrs.put(AlreadyCountedKey -> true)
        val identity = ThreatSupport.identityOf(request, attrs)
        val ref      = config.refOf(identity)
        val reason   = s"status $status on ${request.thePath}"
        mod.fail2ban
          .fail(
            scope = scope,
            ref = ref,
            window = config.detectTime,
            maxRetry = config.maxRetry,
            banFor = config.banTime,
            reason = reason,
            tags = Seq(s"fail2ban:$status"),
            enforce = !config.dryRun
          )
          .map { outcome =>
            // every counted failure feeds the fabric, whether or not it bans on its own — this is
            // what lets a slow trickle of 401s add up alongside waf matches and reputation hits
            if (config.fabricWeight > 0) {
              mod.ledger.recordAll(identity, config.fabricWeight, s"fail2ban — $reason", Seq("fail2ban"))
            }
            // one event on the outcome that matters, not one per failed response: the incident
            // correlator is there to collapse repeats, not to be handed nine thousand of them
            if (outcome.banned || (outcome.reached && config.dryRun)) {
              mod.record(
                category = "fail2ban",
                identity = identity,
                decision = ThreatDecision(
                  action = ThreatAction.Ban,
                  score = 100,
                  tier = None,
                  dryRun = config.dryRun,
                  reason = s"${outcome.count} failures within ${config.detectTime.toSeconds}s"
                ),
                tags = Seq("fail2ban", s"fail2ban:$status"),
                signals = Json.obj(
                  "count"     -> outcome.count,
                  "threshold" -> outcome.threshold,
                  "scope"     -> scope,
                  "ref"       -> ref.key
                ),
                routeId = routeId,
                routeName = routeName,
                message =
                  if (outcome.banned) s"banned ${ref.key} after ${outcome.count} failed requests"
                  else s"would have banned ${ref.key} after ${outcome.count} failed requests"
              )
            }
          }
      }
    }
  }
}

/**
 * Bans a caller who keeps failing, across the whole cluster, and tells the fabric about it.
 *
 * A port of Otoroshi's `fail2ban` plugin with two changes that are the reason it exists. The
 * counters and the bans live in the **shared store** rather than in a node-local map, so
 * `max_retry` means the same thing on one node and on twelve, and a ban issued anywhere is enforced
 * everywhere. And every counted failure is **charged to the cross-request ledger**, so a caller
 * probing credentials is not judged in isolation from what the WAF and the reputation layer saw of
 * them.
 *
 * It is the one detector in the suite whose trigger you produce yourself — a bad deploy returning
 * 500s, or a broken client looping on 401, is indistinguishable from an attack from here. That is
 * why the default status set is narrow and why it starts in dry run.
 */
class CloudApimFail2Ban extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep]                =
    Seq(NgStep.ValidateAccess, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] =
    Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true

  override def name: String                                = "Cloud APIM Security Suite - Fail2ban"
  override def description: Option[String]                 =
    "Bans a caller that keeps producing failed responses — cluster-wide, and feeding the threat ledger".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimFail2BanConfig.default.some

  override def usesCallbacks: Boolean      = true
  override def transformsRequest: Boolean  = false
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean    = true

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimFail2BanConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimFail2BanConfig.configSchema.some

  /**
   * Enforces the bans this plugin issued, and only those.
   *
   * The ban list as a whole belongs to the threat gate; when both run, the gate refuses first and
   * this never fires. Standalone — the plugin dropped on a route with no fabric around it — this is
   * what makes the ban mean anything.
   */
  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(CloudApimFail2BanConfig.format).getOrElse(CloudApimFail2BanConfig.default)
    ThreatSupport.module match {
      case None      => NgAccess.NgAllowed.vfuture
      case Some(mod) =>
        val scope = Fail2BanSupport.scopeOf(config, ctx.attrs)
        if (config.isIgnored(scope)) {
          NgAccess.NgAllowed.vfuture
        } else {
          val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
          mod.bans.check(identity).filter(_.tags.contains(Fail2Ban.tag)) match {
            case None        => NgAccess.NgAllowed.vfuture
            case Some(entry) =>
              // already banned: do not count this one too, or the ban extends itself forever
              ctx.attrs.put(Fail2BanSupport.AlreadyCountedKey -> true)
              NgAccess
                .NgDenied(
                  Results.Forbidden(
                    Json.obj(
                      "error"            -> "temporary_ban",
                      "message"          -> "You are temporarily banned due to too many failed requests.",
                      "retry_in_seconds" -> (entry.remainingMs(System.currentTimeMillis()) / 1000L)
                    )
                  )
                )
                .vfuture
          }
        }
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(CloudApimFail2BanConfig.format).getOrElse(CloudApimFail2BanConfig.default)
    Fail2BanSupport.count(
      config, ctx.request, ctx.attrs, ctx.otoroshiResponse.status, ctx.route.id.some, ctx.route.name.some
    )
    Right(ctx.otoroshiResponse).vfuture
  }

  override def transformError(
      ctx: NgTransformerErrorContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    val config = ctx.cachedConfig(internalName)(CloudApimFail2BanConfig.format).getOrElse(CloudApimFail2BanConfig.default)
    Fail2BanSupport.count(
      config, ctx.request, ctx.attrs, ctx.otoroshiResponse.status, ctx.route.id.some, ctx.route.name.some
    )
    ctx.otoroshiResponse.vfuture
  }

  /**
   * Picks up a refusal that never became a response this plugin could see.
   *
   * The WAF already publishes what it blocked through Otoroshi's fail2ban trigger keys, so a
   * request the rule engine denied is counted here without the WAF knowing this plugin exists.
   */
  override def afterRequest(
      ctx: NgAfterRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = ctx.cachedConfig(internalName)(CloudApimFail2BanConfig.format).getOrElse(CloudApimFail2BanConfig.default)
    if (!ctx.attrs.get(Fail2BanSupport.AlreadyCountedKey).contains(true)) {
      val elCtx    = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty[String, String])
      val status   = elCtx
        .get("fail2ban-trigger-status")
        .flatMap(_.toIntOption)
        .orElse(ctx.attrs.get(otoroshi.next.plugins.Fail2BanPlugin.Fail2BanTriggerStatusKey))
      val triggered = elCtx.contains("fail2ban-trigger") ||
        ctx.attrs.get(otoroshi.next.plugins.Fail2BanPlugin.Fail2BanTriggerKey).isDefined
      status.filter(config.isFailure).orElse(Option.when(triggered)(403)).foreach { s =>
        Fail2BanSupport.count(config, ctx.request, ctx.attrs, s, ctx.route.id.some, ctx.route.name.some)
      }
    }
    ().vfuture
  }
}
