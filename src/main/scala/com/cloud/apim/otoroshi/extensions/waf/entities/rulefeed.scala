package com.cloud.apim.otoroshi.extensions.waf.entities

import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.*
import play.api.libs.json.*

import scala.util.{Failure, Success, Try}

/**
 * A source of rules this gateway installs itself (WAF-2, WAF-3).
 *
 * The feed serves a signed bundle of packs, curated rule packs or virtual patches for one
 * vulnerability. Each version is checked before anything is installed: its signature against
 * `trusted_keys`, every pack compiled by this gateway's engine and run against its own tests. A
 * version that passes waits `promotion_delay_seconds`, then each of its packs becomes a managed
 * WAF ruleset, which a WAF config references like any other.
 *
 * `url` is `https://`, or `file:` for a feed copied onto the machine. `headers` carry what a private
 * feed asks for, a licence key for instance. `packs` names the packs to install, empty meaning all.
 */
final case class RuleFeed(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    url: String = "",
    headers: Map[String, String] = Map.empty,
    trustedKeys: Seq[String] = Seq.empty,
    allowUnsigned: Boolean = false,
    packs: Seq[String] = Seq.empty,
    refreshIntervalSeconds: Long = 3600L,
    timeoutMillis: Long = 30000L,
    promotionDelaySeconds: Long = 0L
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = RuleFeed.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def usable: Boolean = enabled && url.trim.nonEmpty

  def installs(packId: String): Boolean = packs.isEmpty || packs.contains(packId)
}

object RuleFeed {

  val format: Format[RuleFeed] = new Format[RuleFeed] {
    override def writes(o: RuleFeed): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                       -> o.id,
      "name"                     -> o.name,
      "description"              -> o.description,
      "metadata"                 -> o.metadata,
      "tags"                     -> JsArray(o.tags.map(JsString.apply)),
      "enabled"                  -> o.enabled,
      "url"                      -> o.url,
      "headers"                  -> o.headers,
      "trusted_keys"             -> o.trustedKeys,
      "allow_unsigned"           -> o.allowUnsigned,
      "packs"                    -> o.packs,
      "refresh_interval_seconds" -> o.refreshIntervalSeconds,
      "timeout_millis"           -> o.timeoutMillis,
      "promotion_delay_seconds"  -> o.promotionDelaySeconds
    )

    override def reads(json: JsValue): JsResult[RuleFeed] = Try {
      RuleFeed(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        url = json.select("url").asOpt[String].map(_.trim).getOrElse(""),
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        trustedKeys = json.select("trusted_keys").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty),
        allowUnsigned = json.select("allow_unsigned").asOpt[Boolean].getOrElse(false),
        packs = json.select("packs").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty),
        refreshIntervalSeconds = json.select("refresh_interval_seconds").asOpt[Long].filter(_ >= 60L).getOrElse(3600L),
        timeoutMillis = json.select("timeout_millis").asOpt[Long].filter(_ > 0L).getOrElse(30000L),
        promotionDelaySeconds = json.select("promotion_delay_seconds").asOpt[Long].filter(_ >= 0L).getOrElse(0L)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): RuleFeed = RuleFeed(
    id = IdGenerator.namedId("rule-feed", env),
    name = "Rule feed",
    description = "Signed rule packs and virtual patches, checked before they are installed"
  )

  def resource(env: Env, datastores: WafExtensionDatastores, states: WafExtensionState): Resource = {
    Resource(
      "RuleFeed",
      "rule-feeds",
      "rule-feed",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[RuleFeed](
        format = RuleFeed.format,
        clazz = classOf[RuleFeed],
        keyf = id => datastores.ruleFeedDatastore.key(id),
        extractIdf = c => datastores.ruleFeedDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => RuleFeed.template(env).json,
        canRead = true, canCreate = true, canUpdate = true, canDelete = true, canBulk = true,
        stateAll = () => states.allRuleFeeds(),
        stateOne = id => states.ruleFeed(id),
        stateUpdate = values => states.updateRuleFeeds(values)
      )
    )
  }
}

trait RuleFeedDatastore extends BasicStore[RuleFeed]

class KvRuleFeedDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends RuleFeedDatastore
    with RedisLikeStore[RuleFeed] {
  override def fmt: Format[RuleFeed]              = RuleFeed.format
  override def redisLike(using env: Env): RedisLike = redisCli
  override def key(id: String): String            = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:rulefeeds:$id"
  override def extractId(value: RuleFeed): String = value.id
}
