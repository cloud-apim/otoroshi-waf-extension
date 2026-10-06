package com.cloud.apim.otoroshi.extensions.waf.entities

import com.cloud.apim.otoroshi.extensions.waf.security.{SecurityDatastores, SecurityState}
import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.util.{Failure, Success, Try}

/**
 * An API's OpenAPI contract, what its requests are checked against (API-1).
 *
 * `spec` is the document itself, JSON or YAML, OpenAPI 3.0 or 3.1. It is stored, never fetched on
 * a schedule: what is enforced changes when someone changes it here, not when a file elsewhere
 * does. `base_path` is what the contract's paths are relative to; empty, it is the path of the
 * contract's first server.
 */
final case class ApiContract(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    spec: String = "",
    basePath: String = ""
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = ApiContract.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object ApiContract {

  /** Where a route names its own contract, for a preset laid over many routes. */
  val RouteMetadataKey: String = "cloud-apim-api-contract"

  val format: Format[ApiContract] = new Format[ApiContract] {
    override def writes(o: ApiContract): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "enabled"     -> o.enabled,
      "spec"        -> o.spec,
      "base_path"   -> o.basePath
    )

    override def reads(json: JsValue): JsResult[ApiContract] = Try {
      ApiContract(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        // a document pasted as JSON rather than as text is still a document
        spec = json.select("spec").asOpt[String].orElse(json.select("spec").asOpt[JsObject].map(Json.prettyPrint)).getOrElse(""),
        basePath = json.select("base_path").asOpt[String].map(_.trim).getOrElse("")
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  val example: String =
    """openapi: 3.0.3
      |info:
      |  title: My API
      |  version: "1.0"
      |paths:
      |  /items/{id}:
      |    get:
      |      parameters:
      |        - name: id
      |          in: path
      |          required: true
      |          schema:
      |            type: integer
      |      responses:
      |        "200":
      |          description: The item
      |""".stripMargin

  def template(env: Env): ApiContract = ApiContract(
    id = IdGenerator.namedId("api-contract", env),
    name = "My API",
    description = "The OpenAPI contract of an API",
    spec = example
  )

  def resource(env: Env, datastores: SecurityDatastores, states: SecurityState): Resource = {
    Resource(
      "ApiContract",
      "api-contracts",
      "api-contract",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ApiContract](
        format = ApiContract.format,
        clazz = classOf[ApiContract],
        keyf = id => datastores.apiContractDatastore.key(id),
        extractIdf = c => datastores.apiContractDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => ApiContract.template(env).json,
        canRead = true, canCreate = true, canUpdate = true, canDelete = true, canBulk = true,
        stateAll = () => states.allApiContracts(),
        stateOne = id => states.apiContract(id),
        stateUpdate = values => states.updateApiContracts(values)
      )
    )
  }
}

trait ApiContractDatastore extends BasicStore[ApiContract]

class KvApiContractDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends ApiContractDatastore
    with RedisLikeStore[ApiContract] {
  override def fmt: Format[ApiContract]              = ApiContract.format
  override def redisLike(using env: Env): RedisLike  = redisCli
  override def key(id: String): String               = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:apicontracts:$id"
  override def extractId(value: ApiContract): String = value.id
}
