package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.body.{BodyReader, MediaType, ResponseBody}
import com.cloud.apim.otoroshi.extensions.waf.leakage.{Leak, LeakageDetector}
import com.cloud.apim.otoroshi.extensions.waf.security.{ThreatAction, ThreatDecision}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * How the error leakage guard reads and answers (DLP-3).
 *
 * `mask` replaces a response that leaks with a neutral error, `monitor` only reports it. Only the
 * first `body_limit` bytes are read, decompressed when the response is compressed: an error page
 * says what it has to say early, and a leak past the limit is not seen.
 */
final case class CloudApimErrorLeakageConfig(
    mode: String = "mask",
    bodyLimit: Long = 256L * 1024L,
    paranoiaLevel: Int = 1,
    contentTypes: Seq[String] = Seq.empty
) extends NgPluginConfig {
  override def json: JsValue = CloudApimErrorLeakageConfig.format.writes(this)

  def masks: Boolean = !mode.trim.equalsIgnoreCase("monitor")

  /**
   * Whether a response of this type is read.
   *
   * By default every textual type, which is where an error page, a JSON error or a log line ends up.
   * A response without a type is read too: what it is cannot be told, and leaks rarely say.
   */
  def inspects(contentType: Option[String]): Boolean = contentType.map(MediaType.of) match {
    case None                               => true
    case Some(mt) if contentTypes.nonEmpty  => MediaType.matchesAny(contentTypes, mt)
    case Some(mt)                           => MediaType.textual(mt)
  }
}

object CloudApimErrorLeakageConfig {

  val default: CloudApimErrorLeakageConfig = CloudApimErrorLeakageConfig()

  val format: Format[CloudApimErrorLeakageConfig] = new Format[CloudApimErrorLeakageConfig] {
    override def writes(o: CloudApimErrorLeakageConfig): JsValue = Json.obj(
      "mode"           -> o.mode,
      "body_limit"     -> o.bodyLimit,
      "paranoia_level" -> o.paranoiaLevel,
      "content_types"  -> o.contentTypes
    )
    override def reads(json: JsValue): JsResult[CloudApimErrorLeakageConfig] = Try {
      CloudApimErrorLeakageConfig(
        mode = json.select("mode").asOpt[String].map(_.trim.toLowerCase).filter(Set("mask", "monitor")).getOrElse("mask"),
        bodyLimit = json.select("body_limit").asOpt[Long].filter(_ > 0L).getOrElse(256L * 1024L),
        paranoiaLevel = json.select("paranoia_level").asOpt[Int].getOrElse(1).max(1).min(4),
        contentTypes = json.select("content_types").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq("mode", "body_limit", "paranoia_level", "content_types")

  val configSchema: JsObject = Json.obj(
    "mode"           -> Json.obj(
      "type"  -> "select",
      "label" -> "Mode",
      "props" -> Json.obj(
        "help"    -> "'mask' replaces a response that leaks with a neutral error; 'monitor' only reports it",
        "options" -> Json.arr(
          Json.obj("label" -> "Mask", "value"    -> "mask"),
          Json.obj("label" -> "Monitor", "value" -> "monitor")
        )
      )
    ),
    "body_limit"     -> Json.obj(
      "type"  -> "number",
      "label" -> "Body limit",
      "props" -> Json.obj("help" -> "Bytes of the response read, decompressed. A leak past them is not seen", "suffix" -> "bytes")
    ),
    "paranoia_level" -> Json.obj(
      "type"  -> "number",
      "label" -> "Paranoia level",
      "props" -> Json.obj("help" -> "Which of the Core Rule Set leakage rules run, from 1 to 4. Higher catches more and objects more often")
    ),
    "content_types"  -> Json.obj(
      "type"  -> "array",
      "label" -> "Content types",
      "props" -> Json.obj("help" -> "The response types read. Empty means every textual type", "placeholder" -> "text/html")
    )
  )
}

/**
 * Keeps errors from telling the caller what is behind the gateway (DLP-3).
 *
 * A stack trace names the framework, its version and often the file layout; a SQL error names the
 * database and the query that failed. The guard finds them in responses, with the Core Rule Set's
 * leakage signatures and a few of its own, and replaces the response with a neutral error carrying
 * the request's reference, so whoever needs the original can still find it in the gateway's logs.
 */
class CloudApimErrorLeakageGuard extends NgRequestTransformer {

  private val logger = Logger("cloud-apim-waf-leakage")

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Transformations, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Threat Protection - Error leakage guard"
  override def description: Option[String]                 =
    "Replaces stack traces, SQL errors and debug pages in responses with a neutral error".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimErrorLeakageConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimErrorLeakageConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimErrorLeakageConfig.configSchema.some

  override def isTransformRequestAsync: Boolean  = false
  override def isTransformResponseAsync: Boolean = true
  override def transformsRequest: Boolean        = false
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config   = ctx.cachedConfig(internalName)(CloudApimErrorLeakageConfig.format).getOrElse(CloudApimErrorLeakageConfig.default)
    val response = ctx.otoroshiResponse
    if (!ResponseBody.hasBody(ctx.request.method, response.status, response.contentLength) || !config.inspects(response.contentType)) {
      response.rightf
    } else {
      BodyReader.prefix(response.body, config.bodyLimit).map { prefix =>
        val (bytes, _) = RequestBodies.readResponse(prefix, response.headers)
        val context    = RequestContextBuilder.response(ctx.request, response, Some(bytes), Some(ctx.route))
        // a guard that fails must not take the response with it
        Try(LeakageDetector.detect(context, config.paranoiaLevel)) match {
          case Failure(e)          =>
            logger.error("could not look for leaks in a response, it goes through as it is", e)
            Right(response.copy(body = prefix.resume))
          case Success(None)       => Right(response.copy(body = prefix.resume))
          case Success(Some(leak)) =>
            report(ctx, config, leak)
            if (config.masks) {
              prefix.drain()
              Right(CloudApimErrorLeakageGuard.neutral(response, ctx.snowflake))
            } else Right(response.copy(body = prefix.resume))
        }
      }
    }
  }

  private def report(ctx: NgTransformerResponseContext, config: CloudApimErrorLeakageConfig, leak: Leak)(using env: Env): Unit =
    ThreatSupport.module.foreach { mod =>
      val decision = ThreatDecision(
        action = if (config.masks) ThreatAction.Mask else ThreatAction.Log,
        score = 50,
        tier = None,
        dryRun = !config.masks,
        reason = s"${leak.message} in a ${ctx.otoroshiResponse.status} response"
      )
      mod.record(
        category = "leakage",
        identity = ThreatSupport.identityOf(ctx.request, ctx.attrs),
        decision = decision,
        tags = Seq(s"leakage:${leak.family}"),
        signals = Json.arr(leak.json.as[JsObject] ++ Json.obj("status" -> ctx.otoroshiResponse.status, "reference" -> ctx.snowflake)),
        routeId = ctx.route.id.some,
        routeName = ctx.route.name.some,
        message = s"${leak.message} (rule ${leak.ruleId}) ${if (config.masks) "masked" else "let through"}"
      )
    }
}

object CloudApimErrorLeakageGuard {

  // what described the original body describes nothing now
  private val dropped = Set("content-length", "content-encoding", "content-type", "content-md5", "etag", "last-modified", "transfer-encoding")

  /**
   * The neutral error a leaking response becomes.
   *
   * An error keeps its status, a success that leaks becomes a 500: it failed, it only did not say so.
   * The body takes the response's own shape, JSON for JSON and HTML for HTML, so a client that parses
   * errors still can, and it carries the request's reference, which is what support asks for.
   */
  def neutral(response: NgPluginHttpResponse, reference: String): NgPluginHttpResponse = {
    val status        = if (response.status >= 400) response.status else 500
    val (kind, body)  = response.contentType.map(MediaType.of) match {
      case Some(mt) if mt.endsWith("json") =>
        ("application/json", Json.stringify(Json.obj("error" -> "internal_error", "message" -> "Something went wrong.", "reference" -> reference)))
      case Some(mt) if mt.contains("html") =>
        ("text/html; charset=utf-8", s"<!doctype html><html><head><title>Error</title></head><body><p>Something went wrong.</p><p>Reference: $reference</p></body></html>")
      case _                               =>
        ("text/plain; charset=utf-8", s"Something went wrong. Reference: $reference")
    }
    val bytes         = ByteString(body)
    response.copy(
      status = status,
      headers = response.headers.filterNot { case (name, _) => dropped.contains(name.toLowerCase) } ++
        Map("Content-Type" -> kind, "Content-Length" -> bytes.size.toString),
      body = Source.single(bytes)
    )
  }
}
