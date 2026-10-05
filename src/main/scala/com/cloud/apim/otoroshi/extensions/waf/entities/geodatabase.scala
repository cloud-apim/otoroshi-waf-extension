package com.cloud.apim.otoroshi.extensions.waf.entities

import com.cloud.apim.otoroshi.extensions.waf.reputation.*
import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.time.YearMonth
import java.util.Base64
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * Where addresses are, from a MaxMind DB file (`.mmdb`) this node downloads and memory-maps.
 *
 * Any database in that format works: DB-IP lite (the default, free and keyless), MaxMind GeoLite2
 * or GeoIP2 (an account id and a license key, as basic auth), IPinfo lite, IP66. The file can be
 * served raw, gzipped or in a tar.gz, which is how those providers publish it.
 *
 * It backs `@geoLookup` and the `GEO` collection of the WAF, and the geolocation the consoles show.
 */
final case class GeoDatabase(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    url: String = GeoDatabase.dbIpCountryLite,
    username: Option[String] = None,
    password: Option[String] = None,
    headers: Map[String, String] = Map.empty,
    refreshIntervalSeconds: Long = 86400L,
    timeoutMillis: Long = 300000L,
    maxSizeMb: Long = 512L,
    attribution: String = "IP Geolocation by DB-IP",
    attributionUrl: String = "https://db-ip.com"
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = GeoDatabase.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def refreshInterval: FiniteDuration = refreshIntervalSeconds.max(300L).seconds
  def timeout: FiniteDuration         = timeoutMillis.max(1000L).millis
  def maxBytes: Long                  = maxSizeMb.max(1L) * 1024L * 1024L
  def usable: Boolean                 = enabled && url.trim.nonEmpty

  /** Basic auth is how MaxMind takes an account id and a license key. */
  def requestHeaders: Seq[(String, String)] = {
    val auth = username.filter(_.trim.nonEmpty).map { user =>
      val token = Base64.getEncoder.encodeToString(s"${user.trim}:${password.getOrElse("")}".getBytes(StandardCharsets.UTF_8))
      "Authorization" -> s"Basic $token"
    }
    headers.toSeq ++ auth.toSeq
  }

  /** The urls to try, in order: a monthly file may not be published yet on the first of the month. */
  def candidates(now: YearMonth): List[String] = GeoDatabase.candidates(url.trim, now)
}

object GeoDatabase {

  /**
   * DB-IP publishes one file a month and never updates it in place, so the url carries the month.
   * `{yyyy}` and `{MM}` are filled with the current month, then with the previous one.
   */
  val dbIpCountryLite: String = "https://download.db-ip.com/free/dbip-country-lite-{yyyy}-{MM}.mmdb.gz"

  def candidates(url: String, now: YearMonth): List[String] = {
    if (!url.contains("{yyyy}") && !url.contains("{MM}")) List(url)
    else
      List(now, now.minusMonths(1)).map { month =>
        url.replace("{yyyy}", f"${month.getYear}%04d").replace("{MM}", f"${month.getMonthValue}%02d")
      }
  }

  /** A url as it may be shown or logged: some providers put the token in the query string. */
  def redact(url: String): String = {
    val idx = url.indexOf('?')
    if (idx < 0) url else s"${url.substring(0, idx)}?…"
  }

  val format: Format[GeoDatabase] = new Format[GeoDatabase] {
    override def writes(o: GeoDatabase): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                       -> o.id,
      "name"                     -> o.name,
      "description"              -> o.description,
      "metadata"                 -> o.metadata,
      "tags"                     -> JsArray(o.tags.map(JsString.apply)),
      "enabled"                  -> o.enabled,
      "url"                      -> o.url,
      "username"                 -> o.username,
      "password"                 -> o.password,
      "headers"                  -> o.headers,
      "refresh_interval_seconds" -> o.refreshIntervalSeconds,
      "timeout_millis"           -> o.timeoutMillis,
      "max_size_mb"              -> o.maxSizeMb,
      "attribution"              -> o.attribution,
      "attribution_url"          -> o.attributionUrl
    )

    override def reads(json: JsValue): JsResult[GeoDatabase] = Try {
      GeoDatabase(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        url = json.select("url").asOpt[String].getOrElse(dbIpCountryLite),
        username = json.select("username").asOpt[String].filter(_.trim.nonEmpty),
        password = json.select("password").asOpt[String].filter(_.nonEmpty),
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        refreshIntervalSeconds = json.select("refresh_interval_seconds").asOpt[Long].getOrElse(86400L),
        timeoutMillis = json.select("timeout_millis").asOpt[Long].getOrElse(300000L),
        maxSizeMb = json.select("max_size_mb").asOpt[Long].getOrElse(512L),
        attribution = json.select("attribution").asOpt[String].getOrElse(""),
        attributionUrl = json.select("attribution_url").asOpt[String].getOrElse("")
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): GeoDatabase = GeoDatabase(
    id = IdGenerator.namedId("geo-database", env),
    name = "Geolocation database",
    description = "DB-IP lite, country level. Free, monthly, and it asks for attribution"
  )

  def resource(env: Env, datastores: ReputationDatastores, states: ReputationState): Resource = {
    Resource(
      "GeoDatabase",
      "geo-databases",
      "geo-database",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[GeoDatabase](
        format = GeoDatabase.format,
        clazz = classOf[GeoDatabase],
        keyf = id => datastores.geoDatabaseDatastore.key(id),
        extractIdf = c => datastores.geoDatabaseDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => GeoDatabase.template(env).json,
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allGeoDatabases(),
        stateOne = id => states.geoDatabase(id),
        stateUpdate = values => states.updateGeoDatabases(values)
      )
    )
  }
}

trait GeoDatabaseDatastore extends BasicStore[GeoDatabase]

class KvGeoDatabaseDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends GeoDatabaseDatastore
    with RedisLikeStore[GeoDatabase] {
  override def fmt: Format[GeoDatabase]              = GeoDatabase.format
  override def redisLike(using env: Env): RedisLike  = redisCli
  override def key(id: String): String               = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:geodatabases:$id"
  override def extractId(value: GeoDatabase): String = value.id
}
