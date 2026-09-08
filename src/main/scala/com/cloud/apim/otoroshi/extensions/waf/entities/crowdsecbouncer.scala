package com.cloud.apim.otoroshi.extensions.waf.entities

import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import com.cloud.apim.otoroshi.extensions.waf.reputation.*
import play.api.libs.json.*

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * A CrowdSec Local API connection, used in both directions.
 *
 * Pull: the bouncer streams decisions from the LAPI and keeps them in a node-local set.
 * Push: detections made here are reported back as alerts, which turns Otoroshi into a CrowdSec
 * detector rather than only an enforcement point.
 *
 * The two directions use different credentials, because CrowdSec models them as different roles:
 * a bouncer API key reads decisions, a machine login writes alerts.
 */
final case class CrowdSecBouncer(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    lapiUrl: String = "http://127.0.0.1:8080",
    apiKey: String = "",
    pollIntervalSeconds: Long = 10L,
    timeoutMillis: Long = 10000L,
    scopes: Seq[String] = Seq("ip", "range"),
    originsFilter: Seq[String] = Seq.empty,
    weight: Int = 90,
    action: String = "block",
    tag: String = "crowdsec",
    pushEnabled: Boolean = false,
    pushMachineId: String = "",
    pushPassword: String = "",
    pushScenario: String = "cloud-apim/otoroshi-reputation",
    pushIntervalSeconds: Long = 10L,
    pushMaxBatch: Int = 50,
    pushWithDecision: Boolean = false,
    pushDecisionDuration: String = "4h",
    pushWafDetections: Boolean = false,
    pushWafMonitored: Boolean = false
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = CrowdSecBouncer.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def pollInterval: FiniteDuration = pollIntervalSeconds.max(2L).seconds
  def pushInterval: FiniteDuration = pushIntervalSeconds.max(2L).seconds
  def timeout: FiniteDuration      = timeoutMillis.max(1000L).millis
  def blocking: Boolean            = action.trim.equalsIgnoreCase("block")
  def effectiveTag: String         = if (tag.trim.isEmpty) "crowdsec" else tag.trim
  def baseUrl: String              = lapiUrl.trim.stripSuffix("/")
  def pullUsable: Boolean          = enabled && baseUrl.nonEmpty && apiKey.trim.nonEmpty
  def pushUsable: Boolean          = enabled && pushEnabled && baseUrl.nonEmpty && pushMachineId.trim.nonEmpty
  def relaysWafDetections: Boolean = pushUsable && pushWafDetections
  def acceptsScope(scope: String): Boolean = scopes.isEmpty || scopes.exists(_.equalsIgnoreCase(scope))
  def acceptsOrigin(origin: String): Boolean = originsFilter.isEmpty || originsFilter.exists(_.equalsIgnoreCase(origin))
}

object CrowdSecBouncer {

  val format: Format[CrowdSecBouncer] = new Format[CrowdSecBouncer] {
    override def writes(o: CrowdSecBouncer): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                     -> o.id,
      "name"                   -> o.name,
      "description"            -> o.description,
      "metadata"               -> o.metadata,
      "tags"                   -> JsArray(o.tags.map(JsString.apply)),
      "enabled"                -> o.enabled,
      "lapi_url"               -> o.lapiUrl,
      "api_key"                -> o.apiKey,
      "poll_interval_seconds"  -> o.pollIntervalSeconds,
      "timeout_millis"         -> o.timeoutMillis,
      "scopes"                 -> o.scopes,
      "origins_filter"         -> o.originsFilter,
      "weight"                 -> o.weight,
      "action"                 -> o.action,
      "tag"                    -> o.tag,
      "push_enabled"           -> o.pushEnabled,
      "push_machine_id"        -> o.pushMachineId,
      "push_password"          -> o.pushPassword,
      "push_scenario"          -> o.pushScenario,
      "push_interval_seconds"  -> o.pushIntervalSeconds,
      "push_max_batch"         -> o.pushMaxBatch,
      "push_with_decision"     -> o.pushWithDecision,
      "push_decision_duration" -> o.pushDecisionDuration,
      "push_waf_detections"    -> o.pushWafDetections,
      "push_waf_monitored"     -> o.pushWafMonitored
    )

    override def reads(json: JsValue): JsResult[CrowdSecBouncer] = Try {
      CrowdSecBouncer(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        lapiUrl = json.select("lapi_url").asOpt[String].getOrElse("http://127.0.0.1:8080"),
        apiKey = json.select("api_key").asOpt[String].getOrElse(""),
        pollIntervalSeconds = json.select("poll_interval_seconds").asOpt[Long].getOrElse(10L),
        timeoutMillis = json.select("timeout_millis").asOpt[Long].getOrElse(10000L),
        scopes = json.select("scopes").asOpt[Seq[String]].getOrElse(Seq("ip", "range")),
        originsFilter = json.select("origins_filter").asOpt[Seq[String]].getOrElse(Seq.empty),
        weight = json.select("weight").asOpt[Int].getOrElse(90),
        action = json.select("action").asOpt[String].getOrElse("block"),
        tag = json.select("tag").asOpt[String].getOrElse("crowdsec"),
        pushEnabled = json.select("push_enabled").asOpt[Boolean].getOrElse(false),
        pushMachineId = json.select("push_machine_id").asOpt[String].getOrElse(""),
        pushPassword = json.select("push_password").asOpt[String].getOrElse(""),
        pushScenario = json.select("push_scenario").asOpt[String].getOrElse("cloud-apim/otoroshi-reputation"),
        pushIntervalSeconds = json.select("push_interval_seconds").asOpt[Long].getOrElse(10L),
        pushMaxBatch = json.select("push_max_batch").asOpt[Int].getOrElse(50),
        pushWithDecision = json.select("push_with_decision").asOpt[Boolean].getOrElse(false),
        pushDecisionDuration = json.select("push_decision_duration").asOpt[String].getOrElse("4h"),
        pushWafDetections = json.select("push_waf_detections").asOpt[Boolean].getOrElse(false),
        pushWafMonitored = json.select("push_waf_monitored").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): CrowdSecBouncer = CrowdSecBouncer(
    id = IdGenerator.namedId("crowdsec-bouncer", env),
    name = "CrowdSec bouncer",
    description = "A CrowdSec Local API connection"
  )

  def resource(env: Env, datastores: ReputationDatastores, states: ReputationState): Resource = {
    Resource(
      "CrowdSecBouncer",
      "crowdsec-bouncers",
      "crowdsec-bouncer",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[CrowdSecBouncer](
        format = CrowdSecBouncer.format,
        clazz = classOf[CrowdSecBouncer],
        keyf = id => datastores.crowdSecBouncerDatastore.key(id),
        extractIdf = c => datastores.crowdSecBouncerDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => CrowdSecBouncer.template(env).json,
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allCrowdSecBouncers(),
        stateOne = id => states.crowdSecBouncer(id),
        stateUpdate = values => states.updateCrowdSecBouncers(values)
      )
    )
  }
}

trait CrowdSecBouncerDatastore extends BasicStore[CrowdSecBouncer]

class KvCrowdSecBouncerDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends CrowdSecBouncerDatastore
    with RedisLikeStore[CrowdSecBouncer] {
  override def fmt: Format[CrowdSecBouncer]              = CrowdSecBouncer.format
  override def redisLike(using env: Env): RedisLike      = redisCli
  override def key(id: String): String                   = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:crowdsecbouncers:$id"
  override def extractId(value: CrowdSecBouncer): String = value.id
}
