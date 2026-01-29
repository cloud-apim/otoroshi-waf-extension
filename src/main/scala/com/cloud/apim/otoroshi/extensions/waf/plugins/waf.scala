package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.waf.entities.CloudApimWafConfig
import com.cloud.apim.seclang.impl.engine.SecLangEngine
import com.cloud.apim.seclang.impl.utils.StatusCodes
import com.cloud.apim.seclang.model.{Disposition, EngineResult, MatchEvent, RequestContext}
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.gateway.Errors
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc
import play.api.mvc.{RequestHeader, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object CloudApimWafKeys {
  val SecLangEngineKey = TypedKey[ContextualCloudApimWafConfig]("otoroshi.next.plugins.SecLangEngine")
}

case class ContextualCloudApimWafConfig(engine: SecLangEngine, config: CloudApimWafConfig) {
  def close(): Unit = ()
}

case class CloudApimWafConfigRef(ref: String) extends NgPluginConfig {
  override def json: JsValue = CloudApimWafConfigRef.format.writes(this)
}

object CloudApimWafConfigRef {
  val format = new Format[CloudApimWafConfigRef] {
    override def writes(o: CloudApimWafConfigRef): JsValue             = Json.obj("ref" -> o.ref)
    override def reads(json: JsValue): JsResult[CloudApimWafConfigRef] = Try {
      CloudApimWafConfigRef(
        ref = json.select("ref").asString
      )
    } match {
      case Success(e) => JsSuccess(e)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

object RequestContextBuilder {
  def request(req: RequestHeader, request: NgPluginHttpRequest, body: Option[ByteString])(implicit env: Env): RequestContext = {
    val conn = req.headers.get("Remote-Address").getOrElse("0.0.0.0:0")
    val connParts = conn.split(":")
    RequestContext(
      method = request.method.toUpperCase,
      uri = req.uri,
      headers = com.cloud.apim.seclang.model.Headers(req.headers.toMap.mapValues(_.toList)),
      cookies = req.cookies.map(c => (c.name, c.value)).groupBy(_._1).mapValues(_.map(_._2)).mapValues(_.toList),
      query = req.queryString.mapValues(_.toList),
      body = body.map(b => com.cloud.apim.seclang.model.ByteString(b.utf8String)),
      status = None,
      statusTxt = None,
      startTime = System.currentTimeMillis(),
      remoteAddr = connParts.headOption.getOrElse("0.0.0.0"),
      remotePort = connParts.lastOption.map(_.toInt).getOrElse(0),
      protocol = req.version.toLowerCase,
      secure = req.theSecured
    )
  }
  def response(req: RequestHeader, response: NgPluginHttpResponse, body: Option[ByteString])(implicit env: Env): RequestContext = {
    val conn = req.headers.get("Remote-Address").getOrElse("0.0.0.0:0")
    val connParts = conn.split(":")
    RequestContext(
      method = req.method.toUpperCase,
      uri = req.theUri.toString(),
      headers = com.cloud.apim.seclang.model.Headers(response.headers.mapValues(v => List(v))),
      cookies = req.cookies.map(c => (c.name, c.value)).groupBy(_._1).mapValues(_.map(_._2)).mapValues(_.toList),
      query = req.queryString.mapValues(_.toList),
      body = body.map(b => com.cloud.apim.seclang.model.ByteString(b.utf8String)),
      status = Some(response.status),
      statusTxt = StatusCodes.get(response.status),
      startTime = System.currentTimeMillis(),
      remoteAddr = connParts.headOption.getOrElse("0.0.0.0"),
      remotePort = connParts.lastOption.map(_.toInt).getOrElse(0),
      protocol = req.version.toLowerCase,
      secure = req.theSecured
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
      "label" -> s"WAF Config.",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/waf-configs",
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

  def report(result: EngineResult, req: JsObject, route: NgRoute, blocking: Boolean)(implicit env: Env): Unit = {
    val b = result.disposition match {
      case Disposition.Continue => None
      case bl: Disposition.Block => Some(bl)
    }
    CloudApimWafTrailEvent(b, result.events, req, Some(route), blocking).toAnalytics()
  }

  override def beforeRequest(
    ctx: NgBeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = ctx.cachedConfig(internalName)(CloudApimWafConfigRef.format).getOrElse(CloudApimWafConfigRef("none"))
    val ext = env.adminExtensions.extension[CloudApimWafExtension].get
    ext.states.config(config.ref).filter(_.enabled).foreach { wafConfig =>
      val engine = ext.factory.engine(wafConfig.rules.toList)
      ctx.attrs.put(CloudApimWafKeys.SecLangEngineKey -> ContextualCloudApimWafConfig(engine, wafConfig))
    }
    ().vfuture
  }

  override def afterRequest(
    ctx: NgAfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ctx.attrs.get(CloudApimWafKeys.SecLangEngineKey).foreach(_.close())
    ().vfuture
  }

  override def transformRequest(
    ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    ctx.attrs.get(CloudApimWafKeys.SecLangEngineKey) match {
      case Some(ContextualCloudApimWafConfig(engine, config)) => {
        val hasBody = ctx.request.theHasBody
        if (hasBody && config.inspectInputBody) {
          ctx.otoroshiRequest.body
            .runFold(ByteString.empty)(_ ++ _)
            .flatMap { bytes =>
              val res = env.metrics.withTimer("cloud_apim.plugins.waf.evaluation.request") {
                val req = RequestContextBuilder.request(ctx.request, ctx.otoroshiRequest, config.inputBodyLimit.map(l => bytes.take(l.toInt)).orElse(Some(bytes)))
                engine.evaluate(req, List(1, 2, 5))
              }
              res.disposition match {
                case Disposition.Continue if res.events.nonEmpty =>
                  report(res, Json.obj("request" -> ctx.otoroshiRequest.json), ctx.route, config.block)
                  ctx.otoroshiRequest.copy(body = bytes.chunks(32 * 1024)).rightf
                case Disposition.Continue =>
                  ctx.otoroshiRequest.copy(body = bytes.chunks(32 * 1024)).rightf
                case Disposition.Block(status, _, _) if config.block =>
                  report(res, Json.obj("request" -> ctx.otoroshiRequest.json), ctx.route, config.block)
                  triggerFail2Ban(ctx.attrs, status)
                  Errors
                    .craftResponseResult(
                      message = "",
                      status = Results.Status(status),
                      req = ctx.request,
                      maybeDescriptor = None,
                      maybeCauseId = None,
                      duration = ctx.report.getDurationNow(),
                      overhead = ctx.report.getOverheadInNow(),
                      attrs = ctx.attrs,
                      maybeRoute = ctx.route.some,
                      emptyBody = true,
                    ).map(r => Left(r)) // BLOCKING HERE !!!!
                  //Results.Status(status)("").leftf
                case Disposition.Block(_, _, _) if !config.block => {
                  report(res, Json.obj("request" -> ctx.otoroshiRequest.json), ctx.route, config.block)
                  ctx.otoroshiRequest.copy(body = bytes.chunks(32 * 1024)).rightf
                }
              }
            }
        } else {
          val res = env.metrics.withTimer("cloud_apim.plugins.waf.evaluation.request") {
            val req = RequestContextBuilder.request(ctx.request, ctx.otoroshiRequest, None)
            engine.evaluate(req, List(1, 2, 5))
          }
          res.disposition match {
            case Disposition.Continue if res.events.nonEmpty =>
              report(res, Json.obj("request" -> ctx.otoroshiRequest.json), ctx.route, config.block)
              ctx.otoroshiRequest.rightf
            case Disposition.Continue =>
              ctx.otoroshiRequest.rightf
            case Disposition.Block(status, _, _) if config.block =>
              report(res, Json.obj("request" -> ctx.otoroshiRequest.json), ctx.route, config.block)
              triggerFail2Ban(ctx.attrs, status)
              Errors
                .craftResponseResult(
                  message = "",
                  status = Results.Status(status),
                  req = ctx.request,
                  maybeDescriptor = None,
                  maybeCauseId = None,
                  duration = ctx.report.getDurationNow(),
                  overhead = ctx.report.getOverheadInNow(),
                  attrs = ctx.attrs,
                  maybeRoute = ctx.route.some,
                  emptyBody = true,
                ).map(r => Left(r)) // BLOCKING HERE !!!!
              //Results.Status(status)("").leftf
            case Disposition.Block(_, _, _) if !config.block => {
              report(res, Json.obj("request" -> ctx.otoroshiRequest.json), ctx.route, config.block)
              ctx.otoroshiRequest.rightf
            }
          }
        }
      }
      case None => ctx.otoroshiRequest.rightf
    }
  }

  override def transformResponse(
    ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpResponse]] = {
    ctx.attrs.get(CloudApimWafKeys.SecLangEngineKey) match {
      case Some(ContextualCloudApimWafConfig(_, config)) if ctx.otoroshiResponse.contentType.nonEmpty && config.outputBodyMimetypes.nonEmpty && !config.outputBodyMimetypes.contains(ctx.otoroshiResponse.contentType.get) => ctx.otoroshiResponse.rightf
      case Some(ContextualCloudApimWafConfig(engine, config)) => {
        val hasBody = ctx.request.theHasBody
        if (hasBody && config.inspectOutputBody) {
          ctx.otoroshiResponse.body
            .runFold(ByteString.empty)(_ ++ _)
            .flatMap { bytes =>
              val res = env.metrics.withTimer("cloud_apim.plugins.waf.evaluation.response") {
                val req = RequestContextBuilder.response(ctx.request, ctx.otoroshiResponse, config.outputBodyLimit.map(l => bytes.take(l.toInt)).orElse(Some(bytes)))
                engine.evaluate(req, List(3, 4, 5))
              }
              res.disposition match {
                case Disposition.Continue if res.events.nonEmpty =>
                  report(res, Json.obj("response" -> ctx.otoroshiResponse.json), ctx.route, config.block)
                  ctx.otoroshiResponse.copy(body = bytes.chunks(32 * 1024)).rightf
                case Disposition.Continue =>
                  ctx.otoroshiResponse.copy(body = bytes.chunks(32 * 1024)).rightf
                case Disposition.Block(status, _, _) if config.block =>
                  report(res, Json.obj("response" -> ctx.otoroshiResponse.json), ctx.route, config.block)
                  triggerFail2Ban(ctx.attrs, status)
                  Errors
                    .craftResponseResult(
                      message = "",
                      status = Results.Status(status),
                      req = ctx.request,
                      maybeDescriptor = None,
                      maybeCauseId = None,
                      duration = ctx.report.getDurationNow(),
                      overhead = ctx.report.getOverheadInNow(),
                      attrs = ctx.attrs,
                      maybeRoute = ctx.route.some,
                      emptyBody = true,
                    ).map(r => Left(r)) // BLOCKING HERE !!!!
                  // Results.Status(status)("").leftf
                case Disposition.Block(_, _, _) if !config.block => {
                  report(res, Json.obj("response" -> ctx.otoroshiResponse.json), ctx.route, config.block)
                  ctx.otoroshiResponse.copy(body = bytes.chunks(32 * 1024)).rightf
                }
              }
            }
        } else {
          val res = env.metrics.withTimer("cloud_apim.plugins.waf.evaluation.response") {
            val req = RequestContextBuilder.response(ctx.request, ctx.otoroshiResponse, None)
            engine.evaluate(req, List(3, 4, 5))
          }
          res.disposition match {
            case Disposition.Continue if res.events.nonEmpty =>
              report(res, Json.obj("response" -> ctx.otoroshiResponse.json), ctx.route, config.block)
              ctx.otoroshiResponse.rightf
            case Disposition.Continue =>
              ctx.otoroshiResponse.rightf
            case Disposition.Block(status, _, _) if config.block =>
              report(res, Json.obj("response" -> ctx.otoroshiResponse.json), ctx.route, config.block)
              triggerFail2Ban(ctx.attrs, status)
              Errors
                .craftResponseResult(
                  message = "",
                  status = Results.Status(status),
                  req = ctx.request,
                  maybeDescriptor = None,
                  maybeCauseId = None,
                  duration = ctx.report.getDurationNow(),
                  overhead = ctx.report.getOverheadInNow(),
                  attrs = ctx.attrs,
                  maybeRoute = ctx.route.some,
                  emptyBody = true,
                ).map(r => Left(r)) // BLOCKING HERE !!!!
              // Results.Status(status)("").leftf
            case Disposition.Block(_, _, _) if !config.block => {
              report(res, Json.obj("response" -> ctx.otoroshiResponse.json), ctx.route, config.block)
              ctx.otoroshiResponse.rightf
            }
          }
        }
      }
      case None => ctx.otoroshiResponse.rightf
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

  def report(result: EngineResult, req: JsObject, blocking: Boolean)(implicit env: Env): Unit = {
    val b = result.disposition match {
      case Disposition.Continue => None
      case bl: Disposition.Block => Some(bl)
    }
    CloudApimWafTrailEvent(b, result.events, req, None, blocking).toAnalytics()
  }

  override def access(
                       ctx: NgIncomingRequestValidatorContext
                     )(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.config.select("ref").asOpt[String] match {
      case None      => NgAccess.NgAllowed.vfuture
      case Some(ref) => {
        val ext = env.adminExtensions.extension[CloudApimWafExtension].get
        ext.states.config(ref).filter(_.enabled) match {
          case None => NgAccess.NgAllowed.vfuture
          case Some(wafConfig) => {
            val engine = ext.factory.engine(wafConfig.rules.toList)
            val req = RequestContextBuilder.request(ctx.request, NgPluginHttpRequest.fromRequest(ctx.request), None)
            val res = engine.evaluate(req, List(1, 2, 5))
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
        }
      }
  }
}

case class CloudApimWafTrailEvent(
  block: Option[Disposition.Block],
  events: List[MatchEvent],
  request: JsObject,
  route: Option[NgRoute],
  blocking: Boolean,
) extends AnalyticEvent {

  override def `@service`: String            = "--"
  override def `@serviceId`: String          = "--"
  def `@id`: String                          = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime   = timestamp
  def `@type`: String                        = "CloudApimWafTrailEvent"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  private val timestamp = DateTime.now()

  override def toJson(implicit env: Env): JsValue = {
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"      -> "CloudApimWafTrailEvent",
      "@product"   -> "otoroshi",
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> "prod",
      "blocking"   -> blocking,
      "events"     -> JsArray(events.map(e => e.json)),
      "block"      -> block.map(_.json).getOrElse(JsNull).asValue,
      "route"      -> route.map(_.json).getOrElse(JsNull).asValue,
    ) ++ request
  }
}
