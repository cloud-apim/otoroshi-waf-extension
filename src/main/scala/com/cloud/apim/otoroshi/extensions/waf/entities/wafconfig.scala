package com.cloud.apim.otoroshi.extensions.waf.entities

import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import com.cloud.apim.otoroshi.extensions.waf.body.MediaType
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.*
import play.api.libs.json.*

import scala.util.{Failure, Success, Try}

case class CloudApimWafConfig(
   location: EntityLocation = EntityLocation.default,
   id: String,
   name: String,
   description: String = "",
   tags: Seq[String] = Seq.empty,
   metadata: Map[String, String] = Map.empty,
   enabled: Boolean = true,
   block: Boolean = true,
   inspectInputBody: Boolean = true,
   inspectOutputBody: Boolean = true,
   inputBodyLimit: Option[Long] = None,
   outputBodyLimit: Option[Long] = None,
   outputBodyMimetypes: Seq[String] = Seq.empty,
   rulesets: Seq[String] = Seq.empty,
   rules: Seq[String] = Seq.empty,
   oversizeBodyAction: String = CloudApimWafConfig.OversizeInspectPrefix,
   crs: CrsSettings = CrsSettings.empty,
   // PRO-3: what a compressed request body may expand to. None is the default, zero or less is off
   decompressedInputBodyLimit: Option[Long] = None,
   maxInputCompressionRatio: Option[Long] = None,
   undecodableBodyAction: String = CloudApimWafConfig.UndecodableReject,
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = CloudApimWafConfig.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  /**
   * How many bytes of the body may be held in memory at once.
   *
   * An unset limit used to mean "read all of it", which made one large upload enough to matter.
   * It now means the built-in cap: inspection stays bounded whatever the caller sends, and raising
   * it is a decision someone takes on purpose.
   */
  def effectiveInputBodyLimit: Long  = inputBodyLimit.getOrElse(CloudApimWafConfig.defaultBodyLimit)
  def effectiveOutputBodyLimit: Long = outputBodyLimit.getOrElse(CloudApimWafConfig.defaultBodyLimit)

  /**
   * What a compressed request body is held to, judged on what it decompresses to.
   *
   * A few kilobytes of gzip can stand for gigabytes: the backend decompresses them, the gateway
   * forwarded them, and one request is a denial of service. The defaults refuse a body that expands
   * past 64 MiB, or more than a hundredfold once it is past a mebibyte.
   */
  def decompressionLimits: com.cloud.apim.otoroshi.extensions.waf.body.DecompressionLimits =
    com.cloud.apim.otoroshi.extensions.waf.body.DecompressionLimits(
      maxSize = decompressedInputBodyLimit.getOrElse(CloudApimWafConfig.defaultDecompressedBodyLimit),
      maxRatio = maxInputCompressionRatio.getOrElse(CloudApimWafConfig.defaultMaxCompressionRatio)
    )

  /** A body in an encoding the WAF cannot read is a body no rule can see: refused by default. */
  def rejectsUndecodableBody: Boolean =
    !undecodableBodyAction.trim.equalsIgnoreCase(CloudApimWafConfig.UndecodableInspectRaw)

  /** A body past the limit cannot be cleared by inspection, only accepted unseen or refused. */
  def rejectsOversizeBody: Boolean =
    oversizeBodyAction.trim.equalsIgnoreCase(CloudApimWafConfig.OversizeReject)

  /**
   * Whether a response of this content type is inspected.
   *
   * An empty list means every type. A non-empty one is matched on the media type alone, so the
   * `; charset=utf-8` that almost every real header carries no longer defeats the comparison.
   */
  def inspectsContentType(contentType: Option[String]): Boolean =
    // `forall`, not `exists`: a response with no content type at all is still evaluated, as before
    outputBodyMimetypes.isEmpty || contentType.forall(ct => MediaType.matchesAny(outputBodyMimetypes, ct))
}

object CloudApimWafConfig {

  val OversizeInspectPrefix: String = "inspect_prefix"
  val OversizeReject: String        = "reject"
  val oversizeActions: Seq[String]  = Seq(OversizeInspectPrefix, OversizeReject)

  val UndecodableReject: String        = "reject"
  val UndecodableInspectRaw: String    = "inspect_raw"
  val undecodableActions: Seq[String]  = Seq(UndecodableReject, UndecodableInspectRaw)

  val defaultDecompressedBodyLimit: Long = 64L * 1024L * 1024L
  val defaultMaxCompressionRatio: Long   = 100L

  /**
   * The cap that applies when no limit is configured: 2 MiB.
   *
   * ModSecurity ships a far larger default, but it runs in a web server handling one request per
   * worker. A gateway multiplies whatever this is by its in-flight concurrency, which is the number
   * that actually decides whether a node survives.
   */
  val defaultBodyLimit: Long = 2L * 1024L * 1024L

  val format: Format[CloudApimWafConfig] = new Format[CloudApimWafConfig] {
    override def writes(o: CloudApimWafConfig): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "enabled" -> o.enabled,
      "block" -> o.block,
      "inspect_input_body" -> o.inspectInputBody,
      "inspect_output_body" -> o.inspectOutputBody,
      "input_body_limit" -> o.inputBodyLimit,
      "output_body_limit" -> o.outputBodyLimit,
      "output_body_mimetypes" -> o.outputBodyMimetypes,
      "rulesets" -> o.rulesets,
      "rules" -> o.rules,
      "oversize_body_action" -> o.oversizeBodyAction,
      "crs" -> o.crs.json,
      "decompressed_input_body_limit" -> o.decompressedInputBodyLimit,
      "max_input_compression_ratio" -> o.maxInputCompressionRatio,
      "undecodable_body_action" -> o.undecodableBodyAction,
    )
    override def reads(json: JsValue): JsResult[CloudApimWafConfig] = Try {
      CloudApimWafConfig(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        block = json.select("block").asOpt[Boolean].getOrElse(true),
        inspectInputBody = json.select("inspect_input_body").asOpt[Boolean].getOrElse(true),
        inspectOutputBody = json.select("inspect_output_body").asOpt[Boolean].getOrElse(true),
        inputBodyLimit = json.select("input_body_limit").asOpt[Long].filter(_ > 0L),
        outputBodyLimit = json.select("output_body_limit").asOpt[Long].filter(_ > 0L),
        outputBodyMimetypes = json.select("output_body_mimetypes").asOpt[Seq[String]].getOrElse(Seq.empty),
        // absent on every config written before rulesets existed, which is exactly the case that
        // must keep running on its inline rules alone
        rulesets = json.select("rulesets").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty),
        rules = json.select("rules").asOpt[Seq[String]].getOrElse(Seq.empty),
        // reads with a default, so a config written before the cap existed keeps working untouched
        oversizeBodyAction = json
          .select("oversize_body_action")
          .asOpt[String]
          .map(_.trim.toLowerCase)
          .filter(CloudApimWafConfig.oversizeActions.contains)
          .getOrElse(CloudApimWafConfig.OversizeInspectPrefix),
        // absent on every config written before the fields existed, and absence means "say nothing",
        // which is exactly what those configs did
        crs = (json \ "crs").asOpt[JsValue].map(CrsSettings.read).getOrElse(CrsSettings.empty),
        // absent on configs written before PRO-3: the defaults apply, and zero or less turns one off
        decompressedInputBodyLimit = json.select("decompressed_input_body_limit").asOpt[Long],
        maxInputCompressionRatio = json.select("max_input_compression_ratio").asOpt[Long],
        undecodableBodyAction = json
          .select("undecodable_body_action")
          .asOpt[String]
          .map(_.trim.toLowerCase)
          .filter(CloudApimWafConfig.undecodableActions.contains)
          .getOrElse(CloudApimWafConfig.UndecodableReject),
      )
    } match {
      case Failure(ex)                            => JsError(ex.getMessage)
      // refused on the way in rather than sanitised: a paranoia level of 7 is a mistake with a
      // number in it, and quietly storing 1 instead would leave someone certain they raised it.
      // These fields are new, so nothing already stored can be made unreadable by this
      case Success(value) if value.crs.errors.nonEmpty => JsError(value.crs.errors.mkString("; "))
      case Success(value)                         => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: WafExtensionDatastores, states: WafExtensionState): Resource = {
    Resource(
      "WafConfig",
      "waf-configs",
      "waf-config",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[CloudApimWafConfig](
        format = CloudApimWafConfig.format ,
        clazz = classOf[CloudApimWafConfig],
        keyf = id => datastores.wafConfigDatastore.key(id),
        extractIdf = c => datastores.wafConfigDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => {
          CloudApimWafConfig(
            id = IdGenerator.namedId("waf-config", env),
            name = "WAF Config",
            description = "A WAF config",
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allConfigs(),
        stateOne = id => states.config(id),
        stateUpdate = values => states.updateConfigs(values)
      )
    )
  }
}

trait CloudApimWafConfigDatastore extends BasicStore[CloudApimWafConfig]

class KvCloudApimWafConfigDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends CloudApimWafConfigDatastore
    with RedisLikeStore[CloudApimWafConfig] {
  override def fmt: Format[CloudApimWafConfig]              = CloudApimWafConfig.format
  override def redisLike(using env: Env): RedisLike        = redisCli
  override def key(id: String): String                     = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:wafconfigs:$id"
  override def extractId(value: CloudApimWafConfig): String = value.id
}
