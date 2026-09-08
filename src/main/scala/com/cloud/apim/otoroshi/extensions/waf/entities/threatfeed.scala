package com.cloud.apim.otoroshi.extensions.waf.entities

import com.cloud.apim.otoroshi.extensions.waf.reputation.ThreatFeedCatalog
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
 * A remote list of addresses or netblocks, refreshed on a schedule and matched off the request
 * path against an in-memory range set.
 *
 * `headers` goes through Otoroshi's secret filling, so an API key belongs in a vault reference
 * (`${vault://…}`) rather than in the entity itself.
 */
final case class ThreatFeed(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    url: String = "",
    method: String = "GET",
    headers: Map[String, String] = Map.empty,
    followRedirects: Boolean = true,
    format: String = "cidr_lines",
    options: JsObject = Json.obj(),
    refreshIntervalSeconds: Long = 3600L,
    timeoutMillis: Long = 30000L,
    maxEntries: Int = 2000000,
    weight: Int = 50,
    action: String = "monitor",
    tag: String = "",
    catalogRef: Option[String] = None
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = ThreatFeed.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def refreshInterval: FiniteDuration = refreshIntervalSeconds.max(30L).seconds
  def timeout: FiniteDuration         = timeoutMillis.max(1000L).millis
  def blocking: Boolean               = action.trim.equalsIgnoreCase("block")
  def effectiveTag: String            = if (tag.trim.isEmpty) s"feed:$id" else tag.trim
  def usable: Boolean                 = enabled && url.trim.nonEmpty
}

object ThreatFeed {

  val actions: Seq[String] = Seq("block", "monitor")

  val format: Format[ThreatFeed] = new Format[ThreatFeed] {
    override def writes(o: ThreatFeed): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                       -> o.id,
      "name"                     -> o.name,
      "description"              -> o.description,
      "metadata"                 -> o.metadata,
      "tags"                     -> JsArray(o.tags.map(JsString.apply)),
      "enabled"                  -> o.enabled,
      "url"                      -> o.url,
      "method"                   -> o.method,
      "headers"                  -> o.headers,
      "follow_redirects"         -> o.followRedirects,
      "format"                   -> o.format,
      "options"                  -> o.options,
      "refresh_interval_seconds" -> o.refreshIntervalSeconds,
      "timeout_millis"           -> o.timeoutMillis,
      "max_entries"              -> o.maxEntries,
      "weight"                   -> o.weight,
      "action"                   -> o.action,
      "tag"                      -> o.tag,
      "catalog_ref"              -> o.catalogRef
    )

    override def reads(json: JsValue): JsResult[ThreatFeed] = Try {
      ThreatFeed(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        url = json.select("url").asOpt[String].getOrElse(""),
        method = json.select("method").asOpt[String].getOrElse("GET"),
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        followRedirects = json.select("follow_redirects").asOpt[Boolean].getOrElse(true),
        format = json.select("format").asOpt[String].getOrElse("cidr_lines"),
        options = json.select("options").asOpt[JsObject].getOrElse(Json.obj()),
        refreshIntervalSeconds = json.select("refresh_interval_seconds").asOpt[Long].getOrElse(3600L),
        timeoutMillis = json.select("timeout_millis").asOpt[Long].getOrElse(30000L),
        maxEntries = json.select("max_entries").asOpt[Int].getOrElse(2000000),
        weight = json.select("weight").asOpt[Int].getOrElse(50),
        action = json.select("action").asOpt[String].getOrElse("monitor"),
        tag = json.select("tag").asOpt[String].getOrElse(""),
        catalogRef = json.select("catalog_ref").asOpt[String].filter(_.trim.nonEmpty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): ThreatFeed = ThreatFeed(
    id = IdGenerator.namedId("threat-feed", env),
    name = "Threat feed",
    description = "A threat intelligence feed"
  )

  /** Seeds an editable feed from a catalog entry — the catalog never becomes runtime state. */
  def fromCatalog(entryId: String, env: Env): Option[ThreatFeed] = ThreatFeedCatalog.find(entryId).map { entry =>
    ThreatFeed(
      id = IdGenerator.namedId("threat-feed", env),
      name = entry.name,
      description = entry.description,
      tags = Seq("catalog", entry.category),
      enabled = !entry.manualUrl && !entry.requiresAuth,
      url = entry.url,
      format = entry.format,
      options = entry.options,
      refreshIntervalSeconds = entry.refreshIntervalSeconds,
      weight = entry.weight,
      action = entry.action,
      tag = entry.tag,
      catalogRef = Some(entry.id)
    )
  }

  def resource(env: Env, datastores: ReputationDatastores, states: ReputationState): Resource = {
    Resource(
      "ThreatFeed",
      "threat-feeds",
      "threat-feed",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ThreatFeed](
        format = ThreatFeed.format,
        clazz = classOf[ThreatFeed],
        keyf = id => datastores.threatFeedDatastore.key(id),
        extractIdf = c => datastores.threatFeedDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => ThreatFeed.template(env).json,
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allThreatFeeds(),
        stateOne = id => states.threatFeed(id),
        stateUpdate = values => states.updateThreatFeeds(values)
      )
    )
  }
}

trait ThreatFeedDatastore extends BasicStore[ThreatFeed]

class KvThreatFeedDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends ThreatFeedDatastore
    with RedisLikeStore[ThreatFeed] {
  override def fmt: Format[ThreatFeed]              = ThreatFeed.format
  override def redisLike(using env: Env): RedisLike = redisCli
  override def key(id: String): String              = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:threatfeeds:$id"
  override def extractId(value: ThreatFeed): String = value.id
}
