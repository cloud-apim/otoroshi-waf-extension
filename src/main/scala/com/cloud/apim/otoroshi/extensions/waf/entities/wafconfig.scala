package com.cloud.apim.otoroshi.extensions.waf.entities

import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions._
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf._
import play.api.libs.json._

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
   outputBodyMimetype: Seq[String] = Seq.empty,
   rules: Seq[String] = Seq.empty,
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = CloudApimWafConfig.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object CloudApimWafConfig {
  val format = new Format[CloudApimWafConfig] {
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
      "output_body_mimetypes" -> o.outputBodyMimetype,
      "rules" -> o.rules,
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
        inputBodyLimit = json.select("input_body_limit").asOpt[Long],
        outputBodyLimit = json.select("output_body_limit").asOpt[Long],
        outputBodyMimetype = json.select("output_body_mimetypes").asOpt[Seq[String]].getOrElse(Seq.empty),
        rules = json.select("rules").asOpt[Seq[String]].getOrElse(Seq.empty),
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
        tmpl = (v, p, ctx) => {
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
  override def fmt: Format[CloudApimWafConfig]      = CloudApimWafConfig.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:wafconfigs:$id"
  override def extractId(value: CloudApimWafConfig): String    = value.id
}
