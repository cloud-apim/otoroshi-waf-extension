package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.api.*
import com.cloud.apim.otoroshi.extensions.waf.body.{BodyPrefix, BodyReader, ResponseBody}
import com.cloud.apim.otoroshi.extensions.waf.entities.ApiContract
import com.cloud.apim.otoroshi.extensions.waf.security.*
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Result, Results}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * What the contract plugin checks a route against, and what it does about it (API-1).
 *
 * `contract` names an API contract; empty, the route's own metadata does, under
 * `cloud-apim-api-contract`, which is how one preset lays different contracts over different
 * routes. `monitor` reports what does not match the contract; `enforce` refuses it.
 */
final case class CloudApimApiContractConfig(
    contract: Option[String] = None,
    mode: String = "monitor",
    exposeErrors: Boolean = false,
    contribute: Boolean = false,
    rejectUnknownQueryParams: Boolean = false,
    maxBodyBytes: Long = 1024L * 1024L,
    validateResponses: Boolean = false,
    unknownPathWeight: Int = 20,
    violationWeight: Int = 30,
    // API-2, API-3: count what the contract sees, and how often to compare a payload with its shape
    inventory: Boolean = true,
    driftSamplingSeconds: Long = 60L
) extends NgPluginConfig {
  override def json: JsValue = CloudApimApiContractConfig.format.writes(this)
  def enforces: Boolean      = mode == "enforce"
}

object CloudApimApiContractConfig {

  val default: CloudApimApiContractConfig = CloudApimApiContractConfig()

  val format: Format[CloudApimApiContractConfig] = new Format[CloudApimApiContractConfig] {
    override def writes(o: CloudApimApiContractConfig): JsValue = Json.obj(
      "contract"                    -> o.contract,
      "mode"                        -> o.mode,
      "expose_errors"               -> o.exposeErrors,
      "contribute"                  -> o.contribute,
      "reject_unknown_query_params" -> o.rejectUnknownQueryParams,
      "max_body_bytes"              -> o.maxBodyBytes,
      "validate_responses"          -> o.validateResponses,
      "unknown_path_weight"         -> o.unknownPathWeight,
      "violation_weight"            -> o.violationWeight,
      "inventory"                   -> o.inventory,
      "drift_sampling_seconds"      -> o.driftSamplingSeconds
    )
    override def reads(json: JsValue): JsResult[CloudApimApiContractConfig] = Try {
      val d = CloudApimApiContractConfig.default
      CloudApimApiContractConfig(
        contract = json.select("contract").asOpt[String].map(_.trim).filter(_.nonEmpty),
        mode = json.select("mode").asOpt[String].map(_.trim.toLowerCase).filter(Set("monitor", "enforce")).getOrElse(d.mode),
        exposeErrors = json.select("expose_errors").asOpt[Boolean].getOrElse(d.exposeErrors),
        contribute = json.select("contribute").asOpt[Boolean].getOrElse(d.contribute),
        rejectUnknownQueryParams = json.select("reject_unknown_query_params").asOpt[Boolean].getOrElse(d.rejectUnknownQueryParams),
        maxBodyBytes = json.select("max_body_bytes").asOpt[Long].filter(_ > 0L).getOrElse(d.maxBodyBytes),
        validateResponses = json.select("validate_responses").asOpt[Boolean].getOrElse(d.validateResponses),
        unknownPathWeight = json.select("unknown_path_weight").asOpt[Int].filter(_ >= 0).getOrElse(d.unknownPathWeight),
        violationWeight = json.select("violation_weight").asOpt[Int].filter(_ >= 0).getOrElse(d.violationWeight),
        inventory = json.select("inventory").asOpt[Boolean].getOrElse(d.inventory),
        driftSamplingSeconds = json.select("drift_sampling_seconds").asOpt[Long].filter(_ >= 0L).getOrElse(d.driftSamplingSeconds)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "contract", "mode", "expose_errors", "contribute", "reject_unknown_query_params", "max_body_bytes", "validate_responses",
    "unknown_path_weight", "violation_weight", "inventory", "drift_sampling_seconds"
  )

  private def bool(label: String, help: String)   = Json.obj("type" -> "bool", "label" -> label, "props" -> Json.obj("help" -> help))
  private def number(label: String, help: String) = Json.obj("type" -> "number", "label" -> label, "props" -> Json.obj("help" -> help))

  val configSchema: JsObject = Json.obj(
    "contract"                    -> Json.obj(
      "type"  -> "select",
      "label" -> "API contract",
      "props" -> Json.obj(
        "help"             -> "Empty: the route's metadata names it, under cloud-apim-api-contract",
        "optionsFrom"      -> "/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/api-contracts",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
      )
    ),
    "mode"                        -> Json.obj(
      "type"  -> "select",
      "label" -> "Mode",
      "props" -> Json.obj(
        "help"    -> "Report what does not match the contract, or refuse it",
        "options" -> Json.arr(Json.obj("label" -> "Monitor", "value" -> "monitor"), Json.obj("label" -> "Enforce", "value" -> "enforce"))
      )
    ),
    "expose_errors"               -> bool("Say why", "A refusal lists what did not match. Off, it is the gateway's plain error"),
    "contribute"                  -> bool("Score", "Put what does not match on the threat bus too"),
    "reject_unknown_query_params" -> bool("Reject unknown query parameters", "A query parameter the operation does not declare does not match"),
    "max_body_bytes"              -> number("Body limit", "Bytes of a JSON body read to check it. A larger one does not match"),
    "validate_responses"          -> bool("Check responses", "Report a response whose status, media type or JSON body the contract does not declare. Never refused"),
    "unknown_path_weight"         -> number("Unknown path weight", "What a path or a method outside the contract contributes"),
    "violation_weight"            -> number("Violation weight", "What a parameter or a body that does not match contributes"),
    "inventory"                   -> bool("Inventory", "Count the operations used, the paths outside the contract and how traffic drifts from it, for the API reports"),
    "drift_sampling_seconds"      -> number("Drift sampling", "Seconds between two payloads of an operation compared with their declared shape, on each node. 0 compares none")
  )
}

/** A request matched to its operation, carried to its response and to the object guard. */
final case class ContractTouch(contractId: String, contract: CompiledContract, matched: ContractMatch, inventoryKey: Option[String] = None)

object CloudApimApiContract {
  val TouchKey: TypedKey[ContractTouch] = TypedKey[ContractTouch]("cloud-apim.waf.api.contract")
  // a request outside the contract, counted in the inventory: what the backend answers says whether it exists
  val ShadowKey: TypedKey[String]       = TypedKey[String]("cloud-apim.waf.api.shadow")

  /**
   * Whether a request carries a body, by what it says about it rather than by its method: a `POST`
   * with nothing in it has no body to be missing. Over HTTP/2 a body may come with no length, but
   * then it comes with a type.
   */
  def hasBody(contentLength: Option[String], transferEncoding: Option[String], contentType: Option[String]): Boolean =
    contentLength.flatMap(_.trim.toLongOption) match {
      case Some(length) => length > 0L
      case None         => transferEncoding.exists(_.toLowerCase.contains("chunked")) || contentType.isDefined
    }
}

/**
 * Checks every request against the route's OpenAPI contract (API-1).
 *
 * The path and the method must be an operation of the contract; its parameters must be present
 * when required and match their schemas, read as the types they declare; a body must be there when
 * required, of a declared media type, and a JSON one must match its schema, `additionalProperties`,
 * enums and formats included. Whole classes of attack never reach the backend, because the payload
 * does not match the contract to begin with.
 *
 * In monitor mode, what does not match is reported, once a minute per route, operation and kind of
 * mismatch; in enforce mode it is refused, with the status the mismatch calls for. A contract that
 * does not compile is reported and checks nothing.
 */
class CloudApimApiContract extends NgRequestTransformer {

  private val logger = Logger("cloud-apim-waf-api-contract")

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Threat Protection - API contract"
  override def description: Option[String]                 =
    "Checks every request against the route's OpenAPI contract: paths, methods, parameters and bodies".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimApiContractConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimApiContractConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimApiContractConfig.configSchema.some

  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  // a mismatch is reported once a minute per route, operation and kind; a broken contract once an hour
  private val reported = new TrieMap[String, Long]()

  // API-3: when a payload of an operation was last compared with its shape, on this node
  private val sampled = new TrieMap[String, Long]()

  private def sample(cfg: CloudApimApiContractConfig, key: String, now: Long): Boolean =
    cfg.inventory && cfg.driftSamplingSeconds > 0L && {
      val due = sampled.get(key).forall(_ <= now)
      if (due) {
        sampled.put(key, now + cfg.driftSamplingSeconds * 1000L)
        if (sampled.size > 10000) sampled.filterInPlace((_, until) => until > now)
      }
      due
    }

  /** A payload against its declared shape: what is there that the contract never mentions. */
  private def drift(mod: SecurityModule, routeId: String, touch: ContractTouch, media: ContractMedia, text: String, direction: String, now: Long): Unit =
    for {
      schema <- media.schema
      json   <- Try(Json.parse(text)).toOption
    } ShapeDiff.diff(json, schema.raw, touch.contract.resolve).foreach { finding =>
      mod.apiInventory.drift(routeId, touch.contractId, touch.matched.operation.method, touch.matched.operation.path, finding.copy(where = s"$direction ${finding.where}"), now)
    }

  private def config(ctx: NgCachedConfigContext): CloudApimApiContractConfig =
    ctx.cachedConfig(internalName)(CloudApimApiContractConfig.format).getOrElse(CloudApimApiContractConfig.default)

  private def once(key: String, now: Long, every: Long): Boolean =
    if (reported.get(key).exists(_ > now)) false
    else {
      reported.put(key, now + every)
      if (reported.size > 10000) reported.filterInPlace((_, until) => until > now)
      true
    }

  private def compiled(mod: SecurityModule, cfg: CloudApimApiContractConfig, route: NgRoute): Option[(ApiContract, CompiledContract)] =
    mod.apiContractOf(cfg.contract, route.metadata).flatMap { contract =>
      mod.apiContracts.get(contract) match {
        case Right(c)    => Some((contract, c))
        case Left(error) =>
          if (once(s"broken|${contract.id}", System.currentTimeMillis(), 3600000L))
            logger.warn(s"the API contract '${contract.name}' (${contract.id}) does not compile and checks nothing: $error")
          None
      }
    }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val cfg     = config(ctx)
    val request = ctx.otoroshiRequest
    ThreatSupport.module.flatMap(mod => compiled(mod, cfg, ctx.route).map(c => (mod, c))) match {
      case None                              => request.rightf
      case Some((mod, (contract, compiled))) =>
        val now = System.currentTimeMillis()
        compiled.resolve(ctx.request.method, ctx.request.path) match {
          case Right(None)    => request.rightf
          case Left(violation) =>
            if (cfg.inventory) ctx.attrs.put(CloudApimApiContract.ShadowKey -> mod.apiInventory.shadow(ctx.route.id, ctx.request.method, ctx.request.path, now))
            mismatch(mod, ctx, cfg, compiled, None, Seq(violation), None)
          case Right(Some(m)) =>
            val counted = Option.when(cfg.inventory)(mod.apiInventory.operation(ctx.route.id, contract.id, m.operation.method, m.operation.path, now))
            val touch   = ContractTouch(contract.id, compiled, m, counted)
            ctx.attrs.put(CloudApimApiContract.TouchKey -> touch)
            val headers    = ctx.request.headers.toSimpleMap.map { case (k, v) => k.toLowerCase -> v }
            val parameters = compiled.checkParameters(m, ctx.request.queryString, headers, cfg.rejectUnknownQueryParams)
            compiled.bodyMedia(m, request.contentType, CloudApimApiContract.hasBody(ctx.request.headers.get("Content-Length"), ctx.request.headers.get("Transfer-Encoding"), request.contentType)) match {
              case Left(violation)   => mismatch(mod, ctx, cfg, compiled, Some(m), parameters :+ violation, None)
              case Right(None)       => if (parameters.isEmpty) request.rightf else mismatch(mod, ctx, cfg, compiled, Some(m), parameters, None)
              case Right(Some(media)) =>
                BodyReader.prefix(request.body, cfg.maxBodyBytes).flatMap { prefix =>
                  val (bytes, truncated) = RequestBodies.readResponse(prefix, request.headers)
                  val text               = bytes.utf8String
                  val body               =
                    if (truncated) Seq(Violation("body_too_large", "body", s"over ${cfg.maxBodyBytes} bytes, it cannot be checked"))
                    else compiled.checkBody(media, text)
                  if (parameters.isEmpty && body.isEmpty) {
                    if (!truncated && sample(cfg, s"${ctx.route.id}|${m.operation.method} ${m.operation.path}|request", now))
                      drift(mod, ctx.route.id, touch, media, text, "request", now)
                    Right(request.copy(body = prefix.resume)).vfuture
                  } else mismatch(mod, ctx, cfg, compiled, Some(m), parameters ++ body, Some(prefix))
                }
            }
        }
    }
  }

  /** Reports what does not match, contributes it when asked, and refuses it when enforcing. */
  private def mismatch(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      cfg: CloudApimApiContractConfig,
      compiled: CompiledContract,
      matched: Option[ContractMatch],
      violations: Seq[Violation],
      prefix: Option[BodyPrefix]
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val identity  = ThreatSupport.identityOf(ctx.request, ctx.attrs)
    val first     = violations.head
    val kinds     = violations.map(_.kind).distinct
    val operation = matched.map(m => s"${m.operation.method} ${m.operation.path}").getOrElse(s"${ctx.request.method} ${ctx.request.path.takeWhile(_ != '?')}")
    if (cfg.contribute) kinds.foreach { kind =>
      val weight = if (kind == "unknown_path" || kind == "method_not_allowed") cfg.unknownPathWeight else cfg.violationWeight
      ThreatBus.contribute(
        ctx.attrs,
        identity,
        ThreatSignal(source = "api", kind = kind, weight = weight, tag = s"api:$kind", detail = Some(s"$operation: ${violations.find(_.kind == kind).map(_.detail).getOrElse("")}"))
      )
    }
    val now = System.currentTimeMillis()
    if (once(s"${ctx.route.id}|$operation|${kinds.mkString(",")}", now, 60000L))
      mod.record(
        category = "api",
        identity = identity,
        decision = ThreatDecision(
          action = if (cfg.enforces) ThreatAction.Deny else ThreatAction.Log,
          score = 0,
          tier = None,
          dryRun = !cfg.enforces,
          reason = s"${first.kind.replace('_', ' ')}: ${first.where}, ${first.detail}"
        ),
        tags = kinds.map(k => s"api:$k"),
        signals = JsArray(violations.map(_.json)),
        routeId = ctx.route.id.some,
        routeName = ctx.route.name.some,
        message = s"$operation does not match the contract: ${first.kind.replace('_', ' ')}, ${first.where}, ${first.detail}" +
          (if (violations.size > 1) s", and ${violations.size - 1} more" else "")
      )
    if (!cfg.enforces) Right(prefix.fold(ctx.otoroshiRequest)(p => ctx.otoroshiRequest.copy(body = p.resume))).vfuture
    else {
      // refused here: the inventory counts it as such, the backend never answered it
      ctx.attrs.get(CloudApimApiContract.ShadowKey).orElse(ctx.attrs.get(CloudApimApiContract.TouchKey).flatMap(_.inventoryKey)).foreach(mod.apiInventory.status(_, 0))
      prefix.foreach(_.drain())
      val status = first.status
      val allow  = if (status == 405) Seq("Allow" -> compiled.allowed(ctx.request.path).mkString(", ")) else Seq.empty
      if (cfg.exposeErrors)
        Left(
          Results
            .Status(status)(Json.obj("error" -> "the request does not match the API contract", "violations" -> JsArray(violations.map(_.json))))
            .withHeaders(allow*)
        ).vfuture
      else ThreatSupport.denyT(status, ctx).map(r => Left(r.withHeaders(allow*)))
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val response = ctx.otoroshiResponse
    val cfg      = config(ctx)
    val now      = System.currentTimeMillis()
    ThreatSupport.module.foreach(mod => ctx.attrs.get(CloudApimApiContract.ShadowKey).foreach(mod.apiInventory.status(_, response.status)))
    (ThreatSupport.module, ctx.attrs.get(CloudApimApiContract.TouchKey)) match {
      case (Some(mod), Some(touch)) if cfg.validateResponses || cfg.inventory =>
        touch.inventoryKey.foreach(mod.apiInventory.status(_, response.status))
        val op          = touch.matched.operation
        val headers     = response.headers
        val contentType = headers.collectFirst { case (k, v) if k.equalsIgnoreCase("Content-Type") => v }
        val length      = headers.collectFirst { case (k, v) if k.equalsIgnoreCase("Content-Length") => v }.flatMap(_.trim.toLongOption)
        val hasBody     = ResponseBody.hasBody(ctx.request.method, response.status, length)
        touch.contract.responseMedia(touch.matched, response.status, contentType, hasBody) match {
          case Left(violation)                                                   =>
            if (cfg.validateResponses) reportResponse(mod, ctx, touch, Seq(violation))
            if (cfg.inventory)
              mod.apiInventory.drift(ctx.route.id, touch.contractId, op.method, op.path, DriftFinding(violation.kind, s"response ${response.status}", violation.detail), now)
            response.rightf
          case Right(None)                                                       => response.rightf
          case Right(Some(media))                                                =>
            val compare = sample(cfg, s"${ctx.route.id}|${op.method} ${op.path}|response ${response.status}", now)
            if (!cfg.validateResponses && !compare) response.rightf
            else
              BodyReader.prefix(response.body, cfg.maxBodyBytes).map { prefix =>
                val (bytes, truncated) = RequestBodies.readResponse(prefix, headers)
                if (!truncated) {
                  val text = bytes.utf8String
                  if (cfg.validateResponses) {
                    val violations = touch.contract.checkResponseBody(touch.matched, response.status, media, text)
                    if (violations.nonEmpty) reportResponse(mod, ctx, touch, violations)
                  }
                  if (compare) drift(mod, ctx.route.id, touch, media, text, s"response ${response.status}", now)
                }
                Right(response.copy(body = prefix.resume))
              }
        }
      case _                                                                     => response.rightf
    }
  }

  /** A response the contract does not declare: reported, never refused, once a minute per operation. */
  private def reportResponse(mod: SecurityModule, ctx: NgTransformerResponseContext, touch: ContractTouch, violations: Seq[Violation])(using env: Env): Unit = {
    val operation = s"${touch.matched.operation.method} ${touch.matched.operation.path}"
    val kinds     = violations.map(_.kind).distinct
    val first     = violations.head
    if (once(s"${ctx.route.id}|$operation|response|${kinds.mkString(",")}", System.currentTimeMillis(), 60000L))
      mod.record(
        category = "api",
        identity = ThreatSupport.identityOf(ctx.request, ctx.attrs),
        decision = ThreatDecision(action = ThreatAction.Log, score = 0, tier = None, dryRun = true, reason = s"${first.kind.replace('_', ' ')}: ${first.detail}"),
        tags = kinds.map(k => s"api:$k"),
        signals = JsArray(violations.map(_.json)),
        routeId = ctx.route.id.some,
        routeName = ctx.route.name.some,
        message = s"the response of $operation does not match the contract: ${first.kind.replace('_', ' ')}, ${first.detail}"
      )
  }
}
