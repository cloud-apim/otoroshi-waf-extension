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
   rules: Seq[String] = Seq.empty,
   oversizeBodyAction: String = CloudApimWafConfig.OversizeInspectPrefix,
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
      "rules" -> o.rules,
      "oversize_body_action" -> o.oversizeBodyAction,
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
        rules = json.select("rules").asOpt[Seq[String]].getOrElse(Seq.empty),
        // reads with a default, so a config written before the cap existed keeps working untouched
        oversizeBodyAction = json
          .select("oversize_body_action")
          .asOpt[String]
          .map(_.trim.toLowerCase)
          .filter(CloudApimWafConfig.oversizeActions.contains)
          .getOrElse(CloudApimWafConfig.OversizeInspectPrefix),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
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
