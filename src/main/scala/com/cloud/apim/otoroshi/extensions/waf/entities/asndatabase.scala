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

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * One class of network, and what a caller from it is worth on the threat score.
 *
 * Matching is: an explicit AS number first, then a substring of the organisation name. Order
 * between categories matters and is the list order — `CLOUDFLARENET` contains "cloud", so the CDN
 * category has to be considered before hosting.
 */
final case class AsnCategory(
    name: String,
    weight: Int = 0,
    action: String = "monitor",
    tag: String = "",
    orgContains: Seq[String] = Seq.empty,
    asns: Seq[Int] = Seq.empty
) {
  def blocking: Boolean    = action.trim.equalsIgnoreCase("block")
  def effectiveTag: String = if (tag.trim.isEmpty) s"asn:$name" else tag.trim
  def matches(record: AsnRecord): Boolean =
    asns.contains(record.asn) || orgContains.exists(p => p.trim.nonEmpty && record.lowerOrg.contains(p.trim.toLowerCase))
  def json: JsValue = Json.obj(
    "name"         -> name,
    "weight"       -> weight,
    "action"       -> action,
    "tag"          -> tag,
    "org_contains" -> orgContains,
    "asns"         -> asns
  )
}

object AsnCategory {
  def read(json: JsValue): AsnCategory = AsnCategory(
    name = (json \ "name").asOpt[String].getOrElse("unknown"),
    weight = (json \ "weight").asOpt[Int].getOrElse(0),
    action = (json \ "action").asOpt[String].getOrElse("monitor"),
    tag = (json \ "tag").asOpt[String].getOrElse(""),
    orgContains = (json \ "org_contains").asOpt[Seq[String]].getOrElse(Seq.empty),
    asns = (json \ "asns").asOpt[Seq[Int]].getOrElse(Seq.empty)
  )

  /**
   * Shipped defaults. Every AS number here was checked against the live table rather than recalled.
   *
   * Weights are deliberately low and every action is `monitor`: hosting is a *signal*, not a
   * verdict. Every legitimate server-to-server integration you have also comes from a hosting ASN.
   */
  val defaults: Seq[AsnCategory] = Seq(
    AsnCategory(
      name = "cdn",
      weight = 0,
      action = "monitor",
      // first on purpose: CLOUDFLARENET would otherwise fall into hosting on the word "cloud"
      orgContains = Seq("cloudflare", "fastly", "akamai", "edgecast", "stackpath", "bunny", "cdn77", "keycdn", "cachefly", "imperva", "incapsula"),
      asns = Seq(13335, 54113, 20940, 212238)
    ),
    AsnCategory(
      name = "vpn",
      weight = 30,
      action = "monitor",
      // before hosting: M247 and friends are hosting companies whose address space is mostly vpn exits
      orgContains = Seq("nordvpn", "expressvpn", "mullvad", "private internet access", "cyberghost", "surfshark", "protonvpn", "ipvanish", "m247", "datacamp", "vpn"),
      asns = Seq(9009)
    ),
    AsnCategory(
      name = "hosting",
      weight = 15,
      action = "monitor",
      orgContains = Seq("amazon", "google", "microsoft", "azure", "ovh", "hetzner", "digitalocean", "linode", "vultr",
        "contabo", "leaseweb", "scaleway", "online sas", "oracle", "alibaba", "tencent", "ionos", "godaddy",
        "hostinger", "data center", "datacenter", "hosting", "colocation", "dedicated server", "cloud", "vps"),
      asns = Seq(16509, 14618, 15169, 396982, 8075, 16276, 24940, 14061, 20473, 63949, 12876)
    )
  )
}

/**
 * The address-to-network table, and the rules that turn a network into a signal.
 *
 * There is normally one of these per deployment. It is an entity rather than configuration so the
 * classification rules can be edited without a restart — which matters, because they are the part
 * that will need adjusting for your own traffic.
 */
final case class AsnDatabase(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    url: String = "https://iptoasn.com/data/ip2asn-v4.tsv.gz",
    gzip: Boolean = true,
    format: String = "iptoasn_tsv",
    refreshIntervalSeconds: Long = 86400L,
    timeoutMillis: Long = 120000L,
    maxEntries: Int = 1500000,
    categories: Seq[AsnCategory] = AsnCategory.defaults
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = AsnDatabase.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def refreshInterval: FiniteDuration = refreshIntervalSeconds.max(300L).seconds
  def timeout: FiniteDuration         = timeoutMillis.max(1000L).millis
  def usable: Boolean                 = enabled && url.trim.nonEmpty

  /** First category that matches wins, which is why the list is ordered. */
  def classify(record: AsnRecord): AsnMatch = {
    categories.find(_.matches(record)) match {
      case Some(category) =>
        AsnMatch(record, Some(category.name), category.weight, category.effectiveTag, category.blocking)
      case None           =>
        AsnMatch(record, None, 0, s"asn:${record.asn}", blocking = false)
    }
  }
}

object AsnDatabase {

  val format: Format[AsnDatabase] = new Format[AsnDatabase] {
    override def writes(o: AsnDatabase): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                       -> o.id,
      "name"                     -> o.name,
      "description"              -> o.description,
      "metadata"                 -> o.metadata,
      "tags"                     -> JsArray(o.tags.map(JsString.apply)),
      "enabled"                  -> o.enabled,
      "url"                      -> o.url,
      "gzip"                     -> o.gzip,
      "format"                   -> o.format,
      "refresh_interval_seconds" -> o.refreshIntervalSeconds,
      "timeout_millis"           -> o.timeoutMillis,
      "max_entries"              -> o.maxEntries,
      "categories"               -> JsArray(o.categories.map(_.json))
    )

    override def reads(json: JsValue): JsResult[AsnDatabase] = Try {
      AsnDatabase(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        url = json.select("url").asOpt[String].getOrElse("https://iptoasn.com/data/ip2asn-v4.tsv.gz"),
        gzip = json.select("gzip").asOpt[Boolean].getOrElse(true),
        format = json.select("format").asOpt[String].getOrElse("iptoasn_tsv"),
        refreshIntervalSeconds = json.select("refresh_interval_seconds").asOpt[Long].getOrElse(86400L),
        timeoutMillis = json.select("timeout_millis").asOpt[Long].getOrElse(120000L),
        maxEntries = json.select("max_entries").asOpt[Int].getOrElse(1500000),
        categories = json
          .select("categories")
          .asOpt[JsArray]
          .map(_.value.toSeq.map(AsnCategory.read))
          .getOrElse(AsnCategory.defaults)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): AsnDatabase = AsnDatabase(
    id = IdGenerator.namedId("asn-database", env),
    name = "ASN database",
    description = "Resolves callers to their network, and classifies that network"
  )

  def resource(env: Env, datastores: ReputationDatastores, states: ReputationState): Resource = {
    Resource(
      "AsnDatabase",
      "asn-databases",
      "asn-database",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[AsnDatabase](
        format = AsnDatabase.format,
        clazz = classOf[AsnDatabase],
        keyf = id => datastores.asnDatabaseDatastore.key(id),
        extractIdf = c => datastores.asnDatabaseDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => AsnDatabase.template(env).json,
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allAsnDatabases(),
        stateOne = id => states.asnDatabase(id),
        stateUpdate = values => states.updateAsnDatabases(values)
      )
    )
  }
}

trait AsnDatabaseDatastore extends BasicStore[AsnDatabase]

class KvAsnDatabaseDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends AsnDatabaseDatastore
    with RedisLikeStore[AsnDatabase] {
  override def fmt: Format[AsnDatabase]              = AsnDatabase.format
  override def redisLike(using env: Env): RedisLike  = redisCli
  override def key(id: String): String               = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:asndatabases:$id"
  override def extractId(value: AsnDatabase): String = value.id
}
