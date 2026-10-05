package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.body.{BodyPrefix, BodyReader, ResponseBody}
import com.cloud.apim.otoroshi.extensions.waf.entities.CloudApimWafConfig
import com.cloud.apim.otoroshi.extensions.waf.security.{ClientIdentity, ThreatBus, ThreatSignal}
import com.cloud.apim.otoroshi.extensions.waf.rules.{SharedWafEngine, WafExchange}
import com.cloud.apim.seclang.impl.utils.StatusCodes
import com.cloud.apim.seclang.model.{Disposition, EngineResult, MatchEvent, RequestContext}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.gateway.Errors
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.*
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import play.api.libs.json.*
import play.api.Logger
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object CloudApimWafKeys {
  val SecLangEngineKey = TypedKey[ContextualCloudApimWafConfig]("otoroshi.next.plugins.SecLangEngine")
  // one exchange is evaluated twice when response inspection is on; the learning window's headline
  // numbers are per request, so the second half must not charge them again
  val LearningCountedKey = TypedKey[Boolean]("cloud-apim.waf.learning.counted")
  // the WAF this route asks for cannot run, and waf.fail-open says not to let the request through
  val UnavailableKey = TypedKey[String]("cloud-apim.waf.unavailable")
}

case class ContextualCloudApimWafConfig(engine: WafExchange, config: CloudApimWafConfig) {
  def close(): Unit = ()
}

/** What a WAF plugin can do with the config its route points at. */
sealed trait WafResolution

object WafResolution {

  /** Nothing to run: the config is switched off, or `waf.fail-open` lets an unavailable WAF through. */
  case object Off extends WafResolution

  /** The config, and the engine its rules compile to. */
  final case class Ready(ext: CloudApimWafExtension, config: CloudApimWafConfig, engine: SharedWafEngine) extends WafResolution

  /** The WAF the route asks for cannot run, and the request is refused rather than let through uninspected. */
  final case class Unavailable(reason: String) extends WafResolution
}

object CloudApimWafPlugins {

  private val logger   = Logger("cloud-apim-waf")
  private val reported = new scala.collection.concurrent.TrieMap[String, Unit]()

  // once per situation rather than per request: a route with traffic would flood the logs
  private def reportOnce(key: String)(message: => String): Unit =
    if (reported.putIfAbsent(key, ()).isEmpty) logger.warn(message)

  /**
   * `waf.fail-open`. Read from the configuration when the extension is not there, since that is one
   * of the cases it decides.
   */
  def failOpen(using env: Env): Boolean =
    env.adminExtensions.extension[CloudApimWafExtension] match {
      case Some(ext) => ext.failOpen
      case None      =>
        env.configuration
          .getOptional[Boolean](s"otoroshi.admin-extensions.configurations.${CloudApimWafExtension.extensionId.cleanup}.waf.fail-open")
          .getOrElse(false)
    }

  def resolve(ref: String)(using env: Env): WafResolution =
    resolve(env.adminExtensions.extension[CloudApimWafExtension], failOpen, ref)

  /**
   * The engine for a route's WAF config, or what to do without one.
   *
   * A route can ask for a WAF that cannot run: the extension is not enabled, the config it names
   * does not exist, or its rules do not compile. The request is then refused, unless `waf.fail-open`
   * lets it through uninspected. A config in monitoring mode is never refused for it: it would not
   * have refused anything had it run. A config switched off is not a failure at all.
   */
  def resolve(extension: Option[CloudApimWafExtension], failOpen: Boolean, ref: String): WafResolution = {
    def unavailable(key: String, why: String): WafResolution = {
      reportOnce(key) {
        if (failOpen) s"$why: its requests go through uninspected (waf.fail-open is on)"
        else s"$why: its requests are refused with a 503 (waf.fail-open is off)"
      }
      if (failOpen) WafResolution.Off else WafResolution.Unavailable(why)
    }
    extension match {
      case None      => unavailable("extension", "a route uses the Cloud APIM WAF but the extension is not enabled")
      case Some(ext) =>
        ext.states.config(ref) match {
          case None                            => unavailable(s"ref:$ref", s"a route uses the waf config '$ref', which does not exist")
          case Some(config) if !config.enabled => WafResolution.Off
          case Some(config)                    =>
            ext.engineFor(config) match {
              case Right(engine)                             => WafResolution.Ready(ext, config, engine)
              // reported by the engine cache already, once per change of the rules
              case Left(_) if failOpen || !config.block      => WafResolution.Off
              case Left(reason)                              => WafResolution.Unavailable(s"waf config '${config.name}' does not compile: $reason")
            }
        }
    }
  }
}

case class CloudApimWafConfigRef(
    ref: String,
    contribute: Boolean = true,
    blockWeight: Int = 50,
    matchWeight: Int = 20
) extends NgPluginConfig {
  override def json: JsValue = CloudApimWafConfigRef.format.writes(this)
}

object CloudApimWafConfigRef {
  val format: Format[CloudApimWafConfigRef] = new Format[CloudApimWafConfigRef] {
    override def writes(o: CloudApimWafConfigRef): JsValue             = Json.obj(
      "ref"          -> o.ref,
      "contribute"   -> o.contribute,
      "block_weight" -> o.blockWeight,
      "match_weight" -> o.matchWeight
    )
    // every new field reads with a default, so a route configured before the fabric existed keeps
    // working untouched and starts contributing without anyone editing it
    override def reads(json: JsValue): JsResult[CloudApimWafConfigRef] = Try {
      CloudApimWafConfigRef(
        ref = json.select("ref").asString,
        contribute = json.select("contribute").asOpt[Boolean].getOrElse(true),
        blockWeight = json.select("block_weight").asOpt[Int].getOrElse(50),
        matchWeight = json.select("match_weight").asOpt[Int].getOrElse(20)
      )
    } match {
      case Success(e) => JsSuccess(e)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

/**
 * Publishes what the rule engine saw onto the shared threat score.
 *
 * Strictly additive: it is called with the engine result **before** the disposition is acted on,
 * and it never looks at, changes or short-circuits that decision. The WAF remains the sole
 * authority on whether it blocks — this only tells the rest of the fabric what it found.
 *
 * The valuable case is the one the WAF itself does nothing about: a match in monitoring mode
 * contributes a signal, so the fabric can weigh it against everything else without the WAF having
 * to start blocking.
 */
object CloudApimWafFabric {

  /** Pure: what the engine result says, with no gateway or environment involved. */
  def signalFor(result: EngineResult, config: CloudApimWafConfigRef): Option[ThreatSignal] = {
    if (!config.contribute || result.events.isEmpty) None
    else {
      val blocked = result.disposition match {
        case _: Disposition.Block => true
        case _                    => false
      }
      val rules = result.events.flatMap(_.ruleId).distinct.take(5)
      Some(
        ThreatSignal(
          source = "waf.seclang",
          kind = "payload",
          weight = if (blocked) config.blockWeight else config.matchWeight,
          tag = if (blocked) ThreatSignal.WafBlocked else ThreatSignal.WafMatch,
          detail = Some(
            (if (blocked) "the ruleset reached a block decision" else "rules matched without blocking") +
            (if (rules.isEmpty) "" else s" (rules ${rules.mkString(", ")})")
          )
        )
      )
    }
  }

  def contribute(
      attrs: otoroshi.utils.TypedMap,
      request: RequestHeader,
      result: EngineResult,
      config: CloudApimWafConfigRef
  )(using env: Env): Unit = {
    signalFor(result, config).foreach { signal =>
      ThreatBus.contribute(attrs, ClientIdentity.from(request, attrs), signal)
    }
  }
}

/**
 * `REMOTE_ADDR` is the client as Otoroshi resolves it (trusted proxies, forwarded headers), not the
 * peer of the connection: behind a load balancer the peer is the load balancer for every request,
 * which is useless to a rule and worse to a geolocation.
 */
object RequestContextBuilder {

  /**
   * Where the route rides along: the engine calls the integration back with the request context and
   * nothing else, and an audit event that cannot say which route it came from is hard to use.
   * Rules cannot read these, a rule only names the variables SecLang defines.
   */
  val RouteIdVariable   = "otoroshi_route_id"
  val RouteNameVariable = "otoroshi_route_name"

  private def routeVariables(route: Option[NgRoute]): Map[String, String] =
    route.map(r => Map(RouteIdVariable -> r.id, RouteNameVariable -> r.name)).getOrElse(Map.empty)

  // the peer's port: the last segment, so that an ipv6 peer does not break it
  private def remotePort(req: RequestHeader): Int = {
    val conn = req.headers.get("Remote-Address").getOrElse("")
    conn.substring(conn.lastIndexOf(':') + 1).toIntOption.getOrElse(0)
  }

  def request(req: RequestHeader, request: NgPluginHttpRequest, body: Option[ByteString], route: Option[NgRoute] = None)(using env: Env): RequestContext = {
    RequestContext(
      method = request.method.toUpperCase,
      uri = req.uri,
      headers = com.cloud.apim.seclang.model.Headers(req.headers.toMap.view.mapValues(_.toList).toMap),
      cookies = req.cookies.groupBy(_.name).view.mapValues(_.map(_.value).toList).toMap,
      query = req.queryString.view.mapValues(_.toList).toMap,
      body = body.map(b => com.cloud.apim.seclang.model.ByteString(b.utf8String)),
      status = None,
      statusTxt = None,
      startTime = System.currentTimeMillis(),
      remoteAddr = req.theIpAddress,
      remotePort = remotePort(req),
      protocol = req.version.toLowerCase,
      secure = req.theSecured,
      variables = routeVariables(route)
    )
  }
  def response(req: RequestHeader, response: NgPluginHttpResponse, body: Option[ByteString], route: Option[NgRoute] = None)(using env: Env): RequestContext = {
    RequestContext(
      method = req.method.toUpperCase,
      uri = req.theUri.toString(),
      headers = com.cloud.apim.seclang.model.Headers(response.headers.view.mapValues(List(_)).toMap),
      cookies = req.cookies.groupBy(_.name).view.mapValues(_.map(_.value).toList).toMap,
      query = req.queryString.view.mapValues(_.toList).toMap,
      body = body.map(b => com.cloud.apim.seclang.model.ByteString(b.utf8String)),
      status = Some(response.status),
      statusTxt = StatusCodes.get(response.status),
      startTime = System.currentTimeMillis(),
      remoteAddr = req.theIpAddress,
      remotePort = remotePort(req),
      protocol = req.version.toLowerCase,
      secure = req.theSecured,
      variables = routeVariables(route)
    )
  }
}

class CloudApimWaf extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess, NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Custom("WAF"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM WAF"
  override def description: Option[String]                 = "Cloud APIM WAF".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimWafConfigRef("none").some

  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def usesCallbacks: Boolean            = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  override def noJsForm: Boolean = true

  override def configFlow: Seq[String] = Seq("ref")

  override def configSchema: Option[JsObject] = Some(Json.obj(
    "ref" -> Json.obj(
      "type" -> "select",
      "label" -> "WAF Config.",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/waf-configs",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))

  def triggerFail2Ban(attrs: TypedMap, status: Int): Unit = {
    attrs.update(otoroshi.next.plugins.Fail2BanPlugin.Fail2BanTriggerStatusKey)(_ => status)
  }

  def report(result: EngineResult, req: JsObject, route: NgRoute, blocking: Boolean, truncated: Boolean = false)(using env: Env): Unit = {
    val b = result.disposition match {
      case Disposition.Continue => None
      case bl: Disposition.Block => Some(bl)
    }
    CloudApimWafTrailEvent(b, result.events, req, Some(route), blocking, truncated).toAnalytics()
  }

  /**
   * Keeps the match as a tuning candidate, alongside reporting it.
   *
   * Only what an exclusion can be written against is retained — see [[TuningStore.recordAll]] — so
   * the anomaly-score and correlation rules that fire on every match do not fill the list with
   * entries nobody can act on. Bounded and in memory: this is a source of examples, not a ledger,
   * and the ledger is the analytics table.
   */
  private def recordForTuning(
    res: EngineResult,
    config: CloudApimWafConfig,
    route: NgRoute,
    request: RequestHeader,
    attrs: TypedMap,
    blocked: Boolean
  )(using env: Env): Unit = {
    env.adminExtensions.extension[CloudApimWafExtension].foreach { ext =>
      ext.tuning.store.recordAll(
        events = res.events,
        configRef = config.id,
        routeId = Some(route.id),
        routeName = Some(route.name),
        method = request.method,
        path = request.path,
        blocked = blocked
      )
      val first = attrs.get(CloudApimWafKeys.LearningCountedKey).isEmpty
      if (first) attrs.put(CloudApimWafKeys.LearningCountedKey -> true)
      ext.learning.observe(
        configRef = config.id,
        events = res.events,
        routeId = Some(route.id),
        routeName = Some(route.name),
        method = request.method,
        path = request.path,
        // in monitoring the engine reaches a deny and nothing is denied — which is precisely the
        // request that breaks the day this configuration is armed
        wouldBlock = res.disposition match {
          case _: com.cloud.apim.seclang.model.Disposition.Block => true
          case _                                                 => false
        },
        countRun = first
      )
    }
  }

  /**
   * Turns one engine verdict into one outcome, for both directions.
   *
   * It exists because the four copies of this `match` that used to be inlined are exactly what let
   * the request path and the response path drift apart. `forward` and `drain` are by-name on
   * purpose: a body may be resumed **or** thrown away, never both.
   */
  private def act[A](
    res: EngineResult,
    config: CloudApimWafConfig,
    route: NgRoute,
    request: RequestHeader,
    attrs: TypedMap,
    payload: JsObject,
    truncated: Boolean,
    forward: () => A,
    drain: () => Unit,
    deny: Int => Future[Result]
  )(using env: Env, ec: ExecutionContext): Future[Either[Result, A]] = {
    res.disposition match {
      case Disposition.Continue if res.events.nonEmpty =>
        report(res, payload, route, config.block, truncated)
        recordForTuning(res, config, route, request, attrs, blocked = false)
        Right(forward()).vfuture
      case Disposition.Continue                        =>
        Right(forward()).vfuture
      case Disposition.Block(status, _, _) if config.block =>
        report(res, payload, route, config.block, truncated)
        recordForTuning(res, config, route, request, attrs, blocked = true)
        triggerFail2Ban(attrs, status)
        drain()
        deny(status).map(Left.apply)
      case Disposition.Block(_, _, _)                  =>
        report(res, payload, route, config.block, truncated)
        // it did not block, but it is exactly what would break once this config is armed, which is
        // the case a tuning session exists to work through
        recordForTuning(res, config, route, request, attrs, blocked = false)
        Right(forward()).vfuture
    }
  }

  private def craftBlock(
    status: Int,
    request: RequestHeader,
    attrs: TypedMap,
    route: NgRoute,
    duration: Long,
    overhead: Long
  )(using env: Env, ec: ExecutionContext): Future[Result] =
    Errors.craftResponseResult(
      message = "",
      status = Results.Status(status),
      req = request,
      maybeDescriptor = None,
      maybeCauseId = None,
      duration = duration,
      overhead = overhead,
      attrs = attrs,
      maybeRoute = route.some,
      emptyBody = true,
    )

  override def beforeRequest(
    ctx: NgBeforeRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = ctx.cachedConfig(internalName)(CloudApimWafConfigRef.format).getOrElse(CloudApimWafConfigRef("none"))
    // the rules the config composes to, not only the ones written inside it, compiled once per change
    CloudApimWafPlugins.resolve(config.ref) match {
      case WafResolution.Off                            => ().vfuture
      // this callback cannot answer: the request transformer refuses it
      case WafResolution.Unavailable(reason)            =>
        ctx.attrs.put(CloudApimWafKeys.UnavailableKey -> reason)
        ().vfuture
      case WafResolution.Ready(ext, wafConfig, engine) =>
        // every request the configuration looks at, matched or not: the denominator of every rate a
        // learning report quotes
        ext.learning.observeRequest(wafConfig.id)
        ctx.attrs.put(CloudApimWafKeys.SecLangEngineKey -> ContextualCloudApimWafConfig(engine.exchange(), wafConfig))
        // the engine asks @rbl synchronously: give the blocklists a short head start, never more
        ext.reputation.rbl.warm(ctx.request.theIpAddress, ext.rblZones(wafConfig))
    }
  }

  override def afterRequest(
    ctx: NgAfterRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ctx.attrs.get(CloudApimWafKeys.SecLangEngineKey).foreach(_.close())
    ().vfuture
  }

  override def transformRequest(
    ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val ref = ctx.cachedConfig(internalName)(CloudApimWafConfigRef.format).getOrElse(CloudApimWafConfigRef("none"))
    ctx.attrs.get(CloudApimWafKeys.SecLangEngineKey) match {
      case None if ctx.attrs.get(CloudApimWafKeys.UnavailableKey).isDefined =>
        craftBlock(503, ctx.request, ctx.attrs, ctx.route, ctx.report.getDurationNow(), ctx.report.getOverheadInNow()).map(Left.apply)
      case None                                              => ctx.otoroshiRequest.rightf
      case Some(ContextualCloudApimWafConfig(engine, config)) => {
        val payload = Json.obj("request" -> ctx.otoroshiRequest.json)
        def deny(status: Int) = craftBlock(
          status, ctx.request, ctx.attrs, ctx.route, ctx.report.getDurationNow(), ctx.report.getOverheadInNow()
        )
        if (config.inspectInputBody && ctx.request.theHasBody) {
          // bounded: at most the configured limit is ever held, whatever the caller decides to send
          BodyReader.prefix(ctx.otoroshiRequest.body, config.effectiveInputBodyLimit).flatMap { prefix =>
            if (prefix.truncated && config.rejectsOversizeBody) {
              // nothing inspected it, and the policy says an uninspected body does not go through
              prefix.drain()
              triggerFail2Ban(ctx.attrs, 413)
              CloudApimWafTrailEvent(None, List.empty, payload, Some(ctx.route), config.block, truncated = true, oversizeRejected = true).toAnalytics()
              deny(413).map(Left.apply)
            } else {
              val res = env.metrics.withTimer("cloud_apim.plugins.waf.evaluation.request") {
                engine.evaluate(RequestContextBuilder.request(ctx.request, ctx.otoroshiRequest, Some(prefix.bytes), Some(ctx.route)), List(1, 2, 5))
              }
              CloudApimWafFabric.contribute(ctx.attrs, ctx.request, res, ref)
              act(
                res, config, ctx.route, ctx.request, ctx.attrs, payload, prefix.truncated,
                forward = () => ctx.otoroshiRequest.copy(body = prefix.resume),
                drain = () => prefix.drain(),
                deny = deny
              )
            }
          }
        } else {
          val res = env.metrics.withTimer("cloud_apim.plugins.waf.evaluation.request") {
            engine.evaluate(RequestContextBuilder.request(ctx.request, ctx.otoroshiRequest, None, Some(ctx.route)), List(1, 2, 5))
          }
          CloudApimWafFabric.contribute(ctx.attrs, ctx.request, res, ref)
          act(
            res, config, ctx.route, ctx.request, ctx.attrs, payload, truncated = false,
            forward = () => ctx.otoroshiRequest,
            drain = () => (),
            deny = deny
          )
        }
      }
    }
  }

  override def transformResponse(
    ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val ref = ctx.cachedConfig(internalName)(CloudApimWafConfigRef.format).getOrElse(CloudApimWafConfigRef("none"))
    ctx.attrs.get(CloudApimWafKeys.SecLangEngineKey) match {
      case None                                                                                            => ctx.otoroshiResponse.rightf
      // the media type alone, so the `; charset=utf-8` that almost every real header carries stops
      // turning an allowlist into a filter that excludes everything
      case Some(ContextualCloudApimWafConfig(_, config)) if !config.inspectsContentType(ctx.otoroshiResponse.contentType) => ctx.otoroshiResponse.rightf
      case Some(ContextualCloudApimWafConfig(engine, config))                                              => {
        val payload = Json.obj("response" -> ctx.otoroshiResponse.json)
        def deny(status: Int) = craftBlock(
          status, ctx.request, ctx.attrs, ctx.route, ctx.report.getDurationNow(), ctx.report.getOverheadInNow()
        )
        // the *response* decides whether there is a response body — asking the request whether it
        // had one meant every plain GET skipped inspection entirely
        val hasBody = ResponseBody.hasBody(ctx.request.method, ctx.otoroshiResponse.status, ctx.otoroshiResponse.contentLength)
        if (config.inspectOutputBody && hasBody) {
          BodyReader.prefix(ctx.otoroshiResponse.body, config.effectiveOutputBodyLimit).flatMap { prefix =>
            if (prefix.truncated && config.rejectsOversizeBody) {
              prefix.drain()
              triggerFail2Ban(ctx.attrs, 502)
              CloudApimWafTrailEvent(None, List.empty, payload, Some(ctx.route), config.block, truncated = true, oversizeRejected = true).toAnalytics()
              deny(502).map(Left.apply)
            } else {
              val res = env.metrics.withTimer("cloud_apim.plugins.waf.evaluation.response") {
                engine.evaluate(RequestContextBuilder.response(ctx.request, ctx.otoroshiResponse, Some(prefix.bytes), Some(ctx.route)), List(3, 4, 5))
              }
              CloudApimWafFabric.contribute(ctx.attrs, ctx.request, res, ref)
              act(
                res, config, ctx.route, ctx.request, ctx.attrs, payload, prefix.truncated,
                forward = () => ctx.otoroshiResponse.copy(body = prefix.resume),
                drain = () => prefix.drain(),
                deny = deny
              )
            }
          }
        } else {
          val res = env.metrics.withTimer("cloud_apim.plugins.waf.evaluation.response") {
            engine.evaluate(RequestContextBuilder.response(ctx.request, ctx.otoroshiResponse, None, Some(ctx.route)), List(3, 4, 5))
          }
          CloudApimWafFabric.contribute(ctx.attrs, ctx.request, res, ref)
          act(
            res, config, ctx.route, ctx.request, ctx.attrs, payload, truncated = false,
            forward = () => ctx.otoroshiResponse,
            drain = () => (),
            deny = deny
          )
        }
      }
    }
  }
}

class IncomingRequestValidatorCloudApimWaf extends NgIncomingRequestValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM WAF - Incoming Request Validator"
  override def description: Option[String]                 = "Cloud APIM WAF - Incoming Request Validator plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimWafConfigRef("none").some

  // it runs before routing, so there is no route to name
  def report(result: EngineResult, req: JsObject, blocking: Boolean)(using env: Env): Unit = {
    val b = result.disposition match {
      case Disposition.Continue => None
      case bl: Disposition.Block => Some(bl)
    }
    CloudApimWafTrailEvent(b, result.events, req, None, blocking).toAnalytics()
  }

  override def access(
    ctx: NgIncomingRequestValidatorContext
  )(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.config.select("ref").asOpt[String] match {
      case None      => NgAccess.NgAllowed.vfuture
      case Some(ref) => {
        // the rules the config composes to, compiled once per change of them
        CloudApimWafPlugins.resolve(ref) match {
          case WafResolution.Off                            => NgAccess.NgAllowed.vfuture
          case WafResolution.Unavailable(_)                 => NgAccess.NgDenied(Results.ServiceUnavailable("")).vfuture
          case WafResolution.Ready(ext, wafConfig, shared) =>
            // the engine asks @rbl synchronously: give the blocklists a short head start, never more,
            // and no async hop at all for the configs that name no blocklist
            val zones = ext.rblZones(wafConfig)
            if (zones.isEmpty) validate(ctx, shared.exchange(), ref)
            else ext.reputation.rbl.warm(ctx.request.theIpAddress, zones).flatMap(_ => validate(ctx, shared.exchange(), ref))
        }
      }
    }
  }

  private def validate(
    ctx: NgIncomingRequestValidatorContext,
    engine: WafExchange,
    ref: String
  )(using env: Env): Future[NgAccess] = {
    val req = RequestContextBuilder.request(ctx.request, NgPluginHttpRequest.fromRequest(ctx.request), None)
    val res = engine.evaluate(req, List(1, 2, 5))
    CloudApimWafFabric.contribute(
      ctx.attrs,
      ctx.request,
      res,
      CloudApimWafConfigRef.format.reads(ctx.config).asOpt.getOrElse(CloudApimWafConfigRef(ref))
    )
    res.disposition match {
      case Disposition.Continue if res.events.nonEmpty =>
        report(res, Json.obj("request" -> JsonHelpers.requestToJson(ctx.request, ctx.attrs)), true)
        NgAccess.NgAllowed.vfuture
      case Disposition.Continue =>
        NgAccess.NgAllowed.vfuture
      case Disposition.Block(_, _, _) =>
        report(res, Json.obj("request" -> JsonHelpers.requestToJson(ctx.request, ctx.attrs)), true)
        NgAccess.NgDenied(Results.Forbidden("")).vfuture
    }
  }
}

case class CloudApimWafTrailEvent(
  block: Option[Disposition.Block],
  events: List[MatchEvent],
  request: JsObject,
  route: Option[NgRoute],
  blocking: Boolean,
  truncated: Boolean = false,
  oversizeRejected: Boolean = false,
) extends AnalyticEvent {

  override def `@service`: String            = route.map(_.name).getOrElse("--")
  override def `@serviceId`: String          = route.map(_.id).getOrElse("--")
  def `@id`: String                          = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime   = timestamp
  def `@type`: String                        = "CloudApimWafTrailEvent"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  private val timestamp = DateTime.now()

  override def toJson(using _env: Env): JsValue = {
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"      -> "CloudApimWafTrailEvent",
      "@product"   -> "otoroshi",
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> _env.env,
      "blocking"   -> blocking,
      "events"     -> JsArray(events.map(e => e.json)),
      "block"      -> block.map(_.json).getOrElse(JsNull).asValue,
      "route"      -> route.map(_.json).getOrElse(JsNull).asValue,
      // a body longer than the limit was only inspected up to the cut, so a clean verdict on this
      // event proves less than a clean verdict on a whole one — say so rather than imply otherwise
      "truncated"  -> truncated,
      "oversize_rejected" -> oversizeRejected,
    ) ++ request
  }
}
