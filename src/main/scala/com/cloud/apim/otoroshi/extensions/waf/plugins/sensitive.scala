package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.body.{BodyEncoding, BodyReader, MediaType, ResponseBody}
import com.cloud.apim.otoroshi.extensions.waf.dlp.*
import com.cloud.apim.otoroshi.extensions.waf.security.{ThreatAction, ThreatDecision}
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * What the sensitive data guard looks for, and what it does about it (DLP-1, DLP-2).
 *
 * Every detector has an action, `off`, `log`, `mask` or `block`, and a default when `detectors`
 * does not name it. `monitor` turns every action into a report, for a rollout that wants to see
 * what would be masked before anything is.
 *
 * `body_limit` is how much of the response is read before it is sent on, to decide a `block`: past
 * it, the status is already on its way. Masking is not bounded by it, the whole body is read.
 */
final case class CloudApimSensitiveDataConfig(
    mode: String = "enforce",
    detectors: Map[String, String] = Map.empty,
    emailThreshold: Int = 50,
    bodyLimit: Long = 256L * 1024L,
    contentTypes: Seq[String] = Seq.empty
) extends NgPluginConfig {
  override def json: JsValue = CloudApimSensitiveDataConfig.format.writes(this)

  def enforces: Boolean = !mode.trim.equalsIgnoreCase("monitor")

  /** What a detector does, a `mask` asked of a detector with nothing to mask being a `log`. */
  def actionOf(detector: Detector): DlpAction =
    detectors
      .get(detector.id)
      .flatMap(DlpAction.parse)
      .map(a => if (a == DlpAction.Mask && detector.volume) DlpAction.Log else a)
      .getOrElse(detector.defaultAction)

  def rules: Seq[(Detector, DlpAction)] = Detectors.all.map(d => d -> actionOf(d))

  /** Whether a response of this type is read: every textual type by default, and untyped ones. */
  def inspects(contentType: Option[String]): Boolean = contentType.map(MediaType.of) match {
    case None                              => true
    case Some(mt) if contentTypes.nonEmpty => MediaType.matchesAny(contentTypes, mt)
    case Some(mt)                          => MediaType.textual(mt)
  }
}

object CloudApimSensitiveDataConfig {

  val default: CloudApimSensitiveDataConfig = CloudApimSensitiveDataConfig()

  val format: Format[CloudApimSensitiveDataConfig] = new Format[CloudApimSensitiveDataConfig] {
    override def writes(o: CloudApimSensitiveDataConfig): JsValue = Json.obj(
      "mode"            -> o.mode,
      "detectors"       -> o.detectors,
      "email_threshold" -> o.emailThreshold,
      "body_limit"      -> o.bodyLimit,
      "content_types"   -> o.contentTypes
    )
    override def reads(json: JsValue): JsResult[CloudApimSensitiveDataConfig] = Try {
      CloudApimSensitiveDataConfig(
        mode = modeOf(json.select("mode").asOpt[String]),
        detectors = detectorsOf(json.select("detectors").asOpt[JsObject]),
        emailThreshold = json.select("email_threshold").asOpt[Int].filter(_ > 0).getOrElse(50),
        bodyLimit = json.select("body_limit").asOpt[Long].filter(_ > 0L).getOrElse(256L * 1024L),
        contentTypes = json.select("content_types").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  def modeOf(raw: Option[String]): String =
    raw.map(_.trim.toLowerCase).filter(Set("enforce", "monitor")).getOrElse("enforce")

  /** The detectors a config names, keeping only known ids and known actions. */
  def detectorsOf(raw: Option[JsObject]): Map[String, String] =
    raw.toSeq.flatMap(_.value.toSeq).collect {
      case (id, JsString(action)) if Detectors.byId(id).isDefined && DlpAction.parse(action).isDefined => id -> action.trim.toLowerCase
    }.toMap

  val configFlow: Seq[String] = Seq("mode", "detectors", "email_threshold", "body_limit", "content_types")

  val configSchema: JsObject = Json.obj(
    "mode"            -> Json.obj(
      "type"  -> "select",
      "label" -> "Mode",
      "props" -> Json.obj(
        "help"    -> "'monitor' reports what every detector finds and changes nothing",
        "options" -> Json.arr(
          Json.obj("label" -> "Enforce", "value" -> "enforce"),
          Json.obj("label" -> "Monitor", "value" -> "monitor")
        )
      )
    ),
    "detectors"       -> Json.obj(
      "type"  -> "object",
      "label" -> "Detectors",
      "props" -> Json.obj(
        "help" -> s"A detector id and its action: off, log, mask or block. Ids: ${Detectors.all.map(_.id).mkString(", ")}. A detector not named keeps its default"
      )
    ),
    "email_threshold" -> Json.obj(
      "type"  -> "number",
      "label" -> "Email threshold",
      "props" -> Json.obj("help" -> "Distinct email addresses in one response past which it counts as a bulk export")
    ),
    "body_limit"      -> Json.obj(
      "type"  -> "number",
      "label" -> "Block decision limit",
      "props" -> Json.obj(
        "help"   -> "Bytes read before the response is sent on, to decide a block. Past them a block cuts the response instead. Masking always reads the whole body",
        "suffix" -> "bytes"
      )
    ),
    "content_types"   -> Json.obj(
      "type"  -> "array",
      "label" -> "Content types",
      "props" -> Json.obj("help" -> "The response types read. Empty means every textual type but server-sent events", "placeholder" -> "application/json")
    )
  )
}

/**
 * Keeps regulated and secret values from leaving in responses (DLP-1, DLP-2).
 *
 * Card numbers, IBANs, national identifiers, private keys, cloud and service credentials, JWTs and
 * email addresses in bulk are found in the response as it streams by, each one checked the way its
 * issuer would before it counts. What a detector finds is reported, masked in place so the response
 * stays valid JSON, XML or HTML and keeps its length, or refuses the whole response.
 */
class CloudApimSensitiveDataGuard extends NgRequestTransformer {

  private val logger = Logger("cloud-apim-waf-sensitive-data")

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Transformations, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Threat Protection - Sensitive data guard"
  override def description: Option[String]                 =
    "Masks card numbers, IBANs, national identifiers and secrets in responses, or refuses the response".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimSensitiveDataConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimSensitiveDataConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimSensitiveDataConfig.configSchema.some

  override def isTransformRequestAsync: Boolean  = false
  override def isTransformResponseAsync: Boolean = true
  override def transformsRequest: Boolean        = false
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config   = ctx.cachedConfig(internalName)(CloudApimSensitiveDataConfig.format).getOrElse(CloudApimSensitiveDataConfig.default)
    val response = ctx.otoroshiResponse
    val rules    = config.rules.filter(_._2 != DlpAction.Off)
    if (rules.isEmpty || !ResponseBody.hasBody(ctx.request.method, response.status, response.contentLength) || !config.inspects(response.contentType)) {
      response.rightf
    } else {
      BodyEncoding.of(response.headers) match {
        case BodyEncoding.Undecodable(codings) =>
          // zstd and the like cannot be read here, so they go through as they are
          logger.debug(s"response in $codings on route ${ctx.route.id} not scanned for sensitive data")
          response.rightf
        case encoding                          =>
          val coding   = encoding match {
            case BodyEncoding.Decodable(c) => Some(c)
            case _                         => None
          }
          val blockers = rules.filter(_._2 == DlpAction.Block)
          if (config.enforces && blockers.nonEmpty) {
            BodyReader.prefix(response.body, config.bodyLimit).map { prefix =>
              val (head, _) = RequestBodies.readResponse(prefix, response.headers)
              val verdict   = Try {
                val scanner = new SensitiveScanner(blockers, rewrite = false, config.emailThreshold)
                scanner.scanAll(head)
                scanner
              }
              verdict match {
                case Success(scanner) if scanner.blocked.isDefined =>
                  prefix.drain()
                  report(ctx, config, scanner.hits, blocked = true, masked = 0)
                  Right(CloudApimErrorLeakageGuard.neutral(response, ctx.snowflake))
                case Success(_)                                    => Right(stream(ctx, config, rules, coding, response.copy(body = prefix.resume)))
                case Failure(e)                                    =>
                  logger.error("could not look for sensitive data in a response, it goes through as it is", e)
                  Right(response.copy(body = prefix.resume))
              }
            }
          } else Future.successful(Right(stream(ctx, config, rules, coding, response)))
      }
    }
  }

  /**
   * The response with its body scanned on the way out.
   *
   * In enforcement, anything a detector masks or blocks makes the body rewritten: held back by the
   * scanner's window and emitted decoded. Otherwise the original bytes go through untouched and are
   * only read.
   */
  private def stream(
      ctx: NgTransformerResponseContext,
      config: CloudApimSensitiveDataConfig,
      rules: Seq[(Detector, DlpAction)],
      coding: Option[String],
      response: NgPluginHttpResponse
  )(using env: Env): NgPluginHttpResponse = {
    val rewrite = config.enforces && rules.exists(r => r._2 == DlpAction.Mask || r._2 == DlpAction.Block)
    val scanner = new SensitiveScanner(rules, rewrite, config.emailThreshold)
    val flow    = SensitiveDataFlow(coding, scanner, rewrite, config.enforces) { () =>
      Try {
        val hits = scanner.hits
        if (hits.nonEmpty) report(ctx, config, hits, blocked = config.enforces && scanner.blocked.isDefined, masked = scanner.masked)
      }.failed.foreach(e => logger.error("could not report the sensitive data found in a response", e))
    }
    val headers =
      if (!rewrite) response.headers
      else
        response.headers.filterNot { case (name, _) =>
          val lower = name.toLowerCase
          CloudApimSensitiveDataGuard.describesTheBytes(lower) || (coding.isDefined && CloudApimSensitiveDataGuard.describesTheEncoding(lower))
        }
    response.copy(headers = headers, body = response.body.via(flow))
  }

  private def report(ctx: NgTransformerResponseContext, config: CloudApimSensitiveDataConfig, hits: Seq[DlpHit], blocked: Boolean, masked: Int)(using
      env: Env
  ): Unit =
    ThreatSupport.module.foreach { mod =>
      val status   = ctx.otoroshiResponse.status
      val found    = hits.map(h => s"${h.detector.label} × ${h.count}").mkString(", ")
      val outcome  =
        if (!config.enforces) "reported, monitor mode"
        else if (blocked) "response refused"
        else if (masked > 0) s"$masked masked"
        else "reported"
      val action   =
        if (blocked) ThreatAction.Deny
        else if (masked > 0) ThreatAction.Mask
        else ThreatAction.Log
      val decision = ThreatDecision(action = action, score = 50, tier = None, dryRun = !config.enforces, reason = s"$found in a $status response")
      mod.record(
        category = "sensitive_data",
        identity = ThreatSupport.identityOf(ctx.request, ctx.attrs),
        decision = decision,
        tags = hits.map(h => s"dlp:${h.detector.id}"),
        signals = JsArray(hits.map(h => h.json.as[JsObject] ++ Json.obj("status" -> status, "reference" -> ctx.snowflake))),
        routeId = ctx.route.id.some,
        routeName = ctx.route.name.some,
        message = s"$found: $outcome"
      )
    }
}

object CloudApimSensitiveDataGuard {

  // a checksum of the original bytes no longer describes the masked ones
  val describesTheBytes: Set[String] = Set("content-md5", "digest", "content-digest", "repr-digest")

  // a decoded body is no longer the compressed representation these headers describe
  val describesTheEncoding: Set[String] = Set("content-encoding", "content-length", "etag")
}
