package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.reputation.{IpRangeSet, ReputationKeys, ReputationVerdict}
import com.cloud.apim.otoroshi.extensions.waf.security.{ClientIdentity, ThreatBus, ThreatSignal}
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.gateway.Errors
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.*
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import play.api.libs.json.*
import play.api.mvc.{RequestHeader, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

final case class CloudApimIpReputationConfig(
    feeds: Seq[String] = Seq.empty,
    crowdsec: Seq[String] = Seq.empty,
    mode: String = "block",
    scoreThreshold: Int = 0,
    passthrough: Seq[String] = Seq.empty,
    status: Int = 403,
    reportToCrowdsec: Boolean = false
) extends NgPluginConfig {

  override def json: JsValue = CloudApimIpReputationConfig.format.writes(this)

  /** Built once per cached config, never per request. */
  lazy val passthroughSet: IpRangeSet = IpRangeSet.build(passthrough).set

  def blocking: Boolean = mode.trim.equalsIgnoreCase("block")

  /**
   * A feed marked `block` is authoritative on its own; the score threshold is the way to act on an
   * accumulation of weaker signals. Either one is enough.
   */
  def shouldBlock(verdict: ReputationVerdict): Boolean =
    blocking && (verdict.blocking || (scoreThreshold > 0 && verdict.score >= scoreThreshold))
}

object CloudApimIpReputationConfig {

  val default: CloudApimIpReputationConfig = CloudApimIpReputationConfig()

  val format: Format[CloudApimIpReputationConfig] = new Format[CloudApimIpReputationConfig] {
    override def writes(o: CloudApimIpReputationConfig): JsValue = Json.obj(
      "feeds"              -> o.feeds,
      "crowdsec"           -> o.crowdsec,
      "mode"               -> o.mode,
      "score_threshold"    -> o.scoreThreshold,
      "passthrough"        -> o.passthrough,
      "status"             -> o.status,
      "report_to_crowdsec" -> o.reportToCrowdsec
    )
    override def reads(json: JsValue): JsResult[CloudApimIpReputationConfig] = Try {
      CloudApimIpReputationConfig(
        feeds = json.select("feeds").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty),
        crowdsec = json.select("crowdsec").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty),
        mode = json.select("mode").asOpt[String].getOrElse("block"),
        scoreThreshold = json.select("score_threshold").asOpt[Int].getOrElse(0),
        passthrough = json.select("passthrough").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty),
        status = json.select("status").asOpt[Int].getOrElse(403),
        reportToCrowdsec = json.select("report_to_crowdsec").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "mode",
    "score_threshold",
    "status",
    "feeds",
    "crowdsec",
    "report_to_crowdsec",
    "passthrough"
  )

  val configSchema: JsObject = Json.obj(
    "mode"               -> Json.obj(
      "type"  -> "select",
      "label" -> "Mode",
      "help"  -> "'monitor' scores and reports without ever denying a request",
      "props" -> Json.obj(
        "options" -> Json.arr(
          Json.obj("label" -> "Block", "value"   -> "block"),
          Json.obj("label" -> "Monitor", "value" -> "monitor")
        )
      )
    ),
    "score_threshold"    -> Json.obj(
      "type"  -> "number",
      "label" -> "Score threshold",
      "props" -> Json.obj(
        "help" -> "Deny when the accumulated score reaches this value. 0 means only feeds set to 'block' can deny."
      )
    ),
    "status"             -> Json.obj(
      "type"  -> "number",
      "label" -> "Denied status",
      "props" -> Json.obj("help" -> "HTTP status returned when a request is denied")
    ),
    "feeds"              -> Json.obj(
      "type"  -> "array",
      "label" -> "Threat feeds",
      "props" -> Json.obj("help" -> "Threat feed ids to consult. Leave empty to use every enabled feed.")
    ),
    "crowdsec"           -> Json.obj(
      "type"  -> "array",
      "label" -> "CrowdSec bouncers",
      "props" -> Json.obj("help" -> "CrowdSec bouncer ids to consult. Leave empty to use every enabled bouncer.")
    ),
    "report_to_crowdsec" -> Json.obj(
      "type"  -> "bool",
      "label" -> "Report detections to CrowdSec",
      "props" -> Json.obj("help" -> "Push denied requests back to the CrowdSec LAPI as alerts")
    ),
    "passthrough"        -> Json.obj(
      "type"  -> "array",
      "label" -> "Never scored",
      "props" -> Json.obj("help" -> "Addresses or CIDR blocks that bypass reputation entirely, e.g. your own probes")
    )
  )
}

object CloudApimSecuritySuite {
  /** The single plugin-picker category every module of the suite declares. */
  val category: NgPluginCategory = NgPluginCategory.Custom("Security Suite")
}

private[plugins] object ReputationSupport {

  def module(using env: Env): Option[com.cloud.apim.otoroshi.extensions.waf.reputation.ReputationModule] =
    env.adminExtensions.extension[CloudApimWafExtension].map(_.reputation)

  def evaluate(
      config: CloudApimIpReputationConfig,
      request: RequestHeader
  )(using env: Env): Option[(String, ReputationVerdict)] = {
    module.map { mod =>
      val ip = request.theIpAddress
      if (config.passthroughSet.nonEmpty && config.passthroughSet.contains(ip)) {
        (ip, ReputationVerdict.empty(ip))
      } else {
        (ip, mod.lookup(ip, config.feeds, config.crowdsec))
      }
    }
  }

  /**
   * Publishes each hit as a signal on the shared bus.
   *
   * The plugin still reaches its own verdict — that behaviour is unchanged and routes relying on it
   * keep working — but from now on it also *contributes*, so the threat response engine can weigh
   * reputation against everything else instead of reputation deciding alone.
   */
  def contribute(
      attrs: otoroshi.utils.TypedMap,
      request: play.api.mvc.RequestHeader,
      verdict: ReputationVerdict
  )(using env: Env): Unit = {
    if (verdict.nonEmpty) {
      val identity = ClientIdentity.from(request, attrs)
      ThreatBus.contributeAll(
        attrs,
        identity,
        verdict.hits.map { hit =>
          ThreatSignal(
            source = s"reputation.${hit.kind}",
            kind = "reputation",
            weight = hit.weight,
            tag = hit.tag,
            detail = hit.detail
          )
        }
      )
    }
  }

  def report(
      config: CloudApimIpReputationConfig,
      verdict: ReputationVerdict,
      blocked: Boolean,
      route: Option[NgRoute],
      request: JsObject
  )(using env: Env): Unit = {
    CloudApimWafReputationEvent(verdict, blocked, config.mode, route, request).toAnalytics()
    if (blocked && config.reportToCrowdsec) {
      module.foreach { mod =>
        val reason = verdict.hits.map(h => h.tag).distinct.mkString(", ")
        mod.reportToCrowdSec(
          verdict.ip,
          s"denied by otoroshi ip reputation (score ${verdict.score}${if (reason.isEmpty) "" else s", $reason"})",
          config.crowdsec
        )
      }
    }
  }
}

/**
 * Route-level IP reputation.
 *
 * Runs before anything reads the body, because the cheapest request to handle is the one refused
 * on the strength of who sent it.
 */
class CloudApimIpReputation extends NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Security Suite - IP reputation"
  override def description: Option[String]                 =
    "Scores the caller against threat intelligence feeds and CrowdSec decisions before the request is processed".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimIpReputationConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimIpReputationConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimIpReputationConfig.configSchema.some

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(CloudApimIpReputationConfig.format)
      .getOrElse(CloudApimIpReputationConfig.default)
    ReputationSupport.evaluate(config, ctx.request) match {
      case None                  => NgAccess.NgAllowed.vfuture
      case Some((_, verdict))    =>
        ctx.attrs.put(ReputationKeys.VerdictKey -> verdict)
        ReputationSupport.contribute(ctx.attrs, ctx.request, verdict)
        if (verdict.isEmpty) {
          NgAccess.NgAllowed.vfuture
        } else {
          val blocked = config.shouldBlock(verdict)
          ReputationSupport.report(config, verdict, blocked, ctx.route.some, Json.obj("request" -> JsonHelpers.requestToJson(ctx.request, ctx.attrs)))
          if (!blocked) {
            NgAccess.NgAllowed.vfuture
          } else {
            Errors
              .craftResponseResult(
                message = "",
                status = Results.Status(config.status),
                req = ctx.request,
                maybeDescriptor = None,
                maybeCauseId = None,
                duration = ctx.report.getDurationNow(),
                overhead = ctx.report.getOverheadInNow(),
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some,
                emptyBody = true
              )
              .map(result => NgAccess.NgDenied(result))
          }
        }
    }
  }
}

/**
 * Gateway-wide IP reputation, evaluated before routing.
 *
 * Cheaper than the route-level plugin and applies to traffic that never matches a route — which is
 * most of what a scanner sends.
 */
class IncomingRequestValidatorCloudApimIpReputation extends NgIncomingRequestValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Security Suite - IP reputation (Incoming Request Validator)"
  override def description: Option[String]                 =
    "Global IP reputation check, evaluated before routing".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimIpReputationConfig.default.some

  // deliberately NOT noJsForm: incoming request validators are read only from
  // globalConfig.plugins.config.incoming_request_validators and never from route.plugins, so
  // offering this one in the route designer would let someone add a plugin that silently never
  // runs. Otoroshi's own incoming request validators leave noJsForm at its default for the same
  // reason. The schema below is still published on /api/plugins/all for tooling.
  override def configFlow: Seq[String]        = CloudApimIpReputationConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimIpReputationConfig.configSchema.some

  override def access(ctx: NgIncomingRequestValidatorContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = CloudApimIpReputationConfig.format.reads(ctx.config).asOpt.getOrElse(CloudApimIpReputationConfig.default)
    ReputationSupport.evaluate(config, ctx.request) match {
      case None               => NgAccess.NgAllowed.vfuture
      case Some((_, verdict)) =>
        ctx.attrs.put(ReputationKeys.VerdictKey -> verdict)
        ReputationSupport.contribute(ctx.attrs, ctx.request, verdict)
        if (verdict.isEmpty) {
          NgAccess.NgAllowed.vfuture
        } else {
          val blocked = config.shouldBlock(verdict)
          ReputationSupport.report(config, verdict, blocked, None, Json.obj("request" -> JsonHelpers.requestToJson(ctx.request, ctx.attrs)))
          if (blocked) NgAccess.NgDenied(Results.Status(config.status)("")).vfuture
          else NgAccess.NgAllowed.vfuture
        }
    }
  }
}

final case class CloudApimWafReputationEvent(
    verdict: ReputationVerdict,
    blocked: Boolean,
    mode: String,
    route: Option[NgRoute],
    request: JsObject
) extends AnalyticEvent {

  override def `@service`: String            = "--"
  override def `@serviceId`: String          = "--"
  def `@id`: String                          = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime   = timestamp
  def `@type`: String                        = "CloudApimWafReputationEvent"
  override def fromOrigin: Option[String]    = Some(verdict.ip)
  override def fromUserAgent: Option[String] = None

  private val timestamp = DateTime.now()

  override def toJson(using _env: Env): JsValue = {
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"      -> "CloudApimWafReputationEvent",
      "@product"   -> "otoroshi",
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "blocked"    -> blocked,
      "mode"       -> mode,
      "verdict"    -> verdict.json,
      "route"      -> route.map(_.json).getOrElse(JsNull).asValue
    ) ++ request
  }
}
