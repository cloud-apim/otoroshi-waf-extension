package com.cloud.apim.otoroshi.extensions.waf.security

import com.cloud.apim.otoroshi.extensions.waf.reputation.IpRangeSet
import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

/** One rung of the escalation: at this score and above, do this. */
final case class ThreatTier(
    minScore: Int,
    action: String,
    tarpitMillis: Long = 3000L,
    banForSeconds: Long = 3600L,
    status: Int = 403
) {
  def resolvedAction: ThreatAction = ThreatAction.parse(action).getOrElse(ThreatAction.Log)
  def tarpit: FiniteDuration       = tarpitMillis.max(0L).millis
  def banFor: FiniteDuration       = banForSeconds.max(1L).seconds
  def json: JsValue = Json.obj(
    "min_score"       -> minScore,
    "action"          -> action,
    "tarpit_millis"   -> tarpitMillis,
    "ban_for_seconds" -> banForSeconds,
    "status"          -> status
  )
}

object ThreatTier {
  def read(json: JsValue): ThreatTier = ThreatTier(
    minScore = (json \ "min_score").asOpt[Int].getOrElse(0),
    action = (json \ "action").asOpt[String].getOrElse("log"),
    tarpitMillis = (json \ "tarpit_millis").asOpt[Long].getOrElse(3000L),
    banForSeconds = (json \ "ban_for_seconds").asOpt[Long].getOrElse(3600L),
    status = (json \ "status").asOpt[Int].getOrElse(403)
  )

  /** Log early, slow down in the middle, ban only at the top. */
  val default: Seq[ThreatTier] = Seq(
    ThreatTier(minScore = 40, action = "log"),
    ThreatTier(minScore = 70, action = "tarpit", tarpitMillis = 3000L),
    ThreatTier(minScore = 90, action = "ban", banForSeconds = 3600L, status = 403)
  )
}

/**
 * How an accumulated threat score turns into an action.
 *
 * `dryRun` defaults to **true**: a freshly created policy records what it would have done and
 * enforces nothing. Arming a scoring system you have not yet measured is the fastest way to break
 * production, so the entity refuses to do it by default.
 */
final case class ThreatPolicy(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    dryRun: Boolean = true,
    tiers: Seq[ThreatTier] = ThreatTier.default,
    exemptions: Seq[String] = Seq.empty,
    banIdentity: String = "auto",
    wafBlockWeight: Int = 50
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = ThreatPolicy.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  lazy val exemptionSet: IpRangeSet = IpRangeSet.build(exemptions).set

  /** Highest matching rung wins, so tiers can be listed in any order. */
  def tierFor(score: Int): Option[(Int, ThreatTier)] =
    tiers.zipWithIndex.filter(_._1.minScore <= score).sortBy(-_._1.minScore).headOption.map { case (t, i) => (i, t) }

  def isExempt(ip: String): Boolean = exemptionSet.nonEmpty && exemptionSet.contains(ip)

  def banRef(identity: ClientIdentity): Option[IdentityRef] = banIdentity.trim.toLowerCase match {
    case "auto" | "" => identity.refs.headOption
    case kind        => identity.refs.find(_.kind == kind).orElse(identity.refs.headOption)
  }
}

object ThreatPolicy {

  val format: Format[ThreatPolicy] = new Format[ThreatPolicy] {
    override def writes(o: ThreatPolicy): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "enabled"          -> o.enabled,
      "dry_run"          -> o.dryRun,
      "tiers"            -> JsArray(o.tiers.map(_.json)),
      "exemptions"       -> o.exemptions,
      "ban_identity"     -> o.banIdentity,
      "waf_block_weight" -> o.wafBlockWeight
    )

    override def reads(json: JsValue): JsResult[ThreatPolicy] = Try {
      ThreatPolicy(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        dryRun = json.select("dry_run").asOpt[Boolean].getOrElse(true),
        tiers = json.select("tiers").asOpt[JsArray].map(_.value.toSeq.map(ThreatTier.read)).getOrElse(ThreatTier.default),
        exemptions = json.select("exemptions").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty),
        banIdentity = json.select("ban_identity").asOpt[String].getOrElse("auto"),
        wafBlockWeight = json.select("waf_block_weight").asOpt[Int].getOrElse(50)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): ThreatPolicy = ThreatPolicy(
    id = IdGenerator.namedId("threat-policy", env),
    name = "Threat policy",
    description = "How an accumulated threat score turns into an action"
  )

  def resource(env: Env, datastores: SecurityDatastores, states: SecurityState): Resource = {
    Resource(
      "ThreatPolicy",
      "threat-policies",
      "threat-policy",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ThreatPolicy](
        format = ThreatPolicy.format,
        clazz = classOf[ThreatPolicy],
        keyf = id => datastores.threatPolicyDatastore.key(id),
        extractIdf = c => datastores.threatPolicyDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => ThreatPolicy.template(env).json,
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allThreatPolicies(),
        stateOne = id => states.threatPolicy(id),
        stateUpdate = values => states.updateThreatPolicies(values)
      )
    )
  }
}

trait ThreatPolicyDatastore extends BasicStore[ThreatPolicy]

class KvThreatPolicyDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends ThreatPolicyDatastore
    with RedisLikeStore[ThreatPolicy] {
  override def fmt: Format[ThreatPolicy]              = ThreatPolicy.format
  override def redisLike(using env: Env): RedisLike   = redisCli
  override def key(id: String): String                = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:threatpolicies:$id"
  override def extractId(value: ThreatPolicy): String = value.id
}
