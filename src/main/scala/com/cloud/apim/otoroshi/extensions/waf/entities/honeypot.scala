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
import play.api.mvc.RequestHeader

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * A value that should never come back.
 *
 * Planted somewhere a scraper would pick it up — a fake apikey in a config file, a record id that
 * exists in no database. Presenting it is not suspicious behaviour, it is proof: there is no way to
 * possess this value except by taking it.
 */
final case class CanaryToken(value: String, description: String = "", where: String = "any") {
  def json: JsValue = Json.obj("value" -> value, "description" -> description, "where" -> where)
  def present(request: RequestHeader): Boolean =
    presentIn(request.uri, request.path, request.rawQueryString, request.headers.toSimpleMap.values.toSeq)

  /** The pure half, so the matching rules can be tested without a request. */
  def presentIn(uri: String, path: String, query: String, headerValues: Seq[String]): Boolean = {
    if (value.trim.isEmpty) false
    else
      where.trim.toLowerCase match {
        case "header" => headerValues.exists(_.contains(value))
        case "query"  => query.contains(value)
        case "path"   => path.contains(value)
        case _        => uri.contains(value) || headerValues.exists(_.contains(value))
      }
  }
}

object CanaryToken {
  def read(json: JsValue): CanaryToken = CanaryToken(
    value = (json \ "value").asOpt[String].getOrElse(""),
    description = (json \ "description").asOpt[String].getOrElse(""),
    where = (json \ "where").asOpt[String].getOrElse("any")
  )
}

/**
 * Paths nobody legitimate asks for, and values nobody legitimate holds.
 *
 * The cheapest detection there is, and the one with almost no false positives by construction: a
 * request for `/.env` is not ambiguous. It runs **before routing**, because these paths match no
 * route — a route-level plugin would never see them.
 */
final case class HoneypotPolicy(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    paths: Seq[String] = HoneypotPolicy.defaultPaths,
    weight: Int = 100,
    action: String = "deny",
    banForSeconds: Long = 86400L,
    status: Int = 404,
    canaries: Seq[CanaryToken] = Seq.empty
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = HoneypotPolicy.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def banFor: FiniteDuration = banForSeconds.max(1L).seconds
  def bans: Boolean          = action.trim.equalsIgnoreCase("ban")
  def denies: Boolean        = bans || action.trim.equalsIgnoreCase("deny")

  /** Prefix match when the pattern ends in `*`, exact otherwise. Case-insensitive. */
  def matchingPath(path: String): Option[String] = {
    val p = path.toLowerCase
    paths.map(_.trim).filter(_.nonEmpty).find { pattern =>
      val pat = pattern.toLowerCase
      if (pat.endsWith("*")) p.startsWith(pat.dropRight(1)) else p == pat
    }
  }

  def matchingCanary(request: RequestHeader): Option[CanaryToken] = canaries.find(_.present(request))
}

object HoneypotPolicy {

  /**
   * What automated scanning actually asks for.
   *
   * Deliberately short and unambiguous: every one of these is a request no browser and no API
   * client of yours will ever make. A longer list would trade the property that makes this useful —
   * a false positive rate of essentially zero — for coverage that the WAF already provides.
   */
  val defaultPaths: Seq[String] = Seq(
    "/.env",
    "/.env.local",
    "/.git/config",
    "/.git/HEAD",
    "/.aws/credentials",
    "/wp-login.php",
    "/wp-admin*",
    "/xmlrpc.php",
    "/phpmyadmin*",
    "/phpinfo.php",
    "/actuator/env",
    "/actuator/heapdump",
    "/_ignition/execute-solution",
    "/vendor/phpunit*",
    "/solr/admin*",
    "/config.json.bak"
  )

  val format: Format[HoneypotPolicy] = new Format[HoneypotPolicy] {
    override def writes(o: HoneypotPolicy): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"              -> o.id,
      "name"            -> o.name,
      "description"     -> o.description,
      "metadata"        -> o.metadata,
      "tags"            -> JsArray(o.tags.map(JsString.apply)),
      "enabled"         -> o.enabled,
      "paths"           -> o.paths,
      "weight"          -> o.weight,
      "action"          -> o.action,
      "ban_for_seconds" -> o.banForSeconds,
      "status"          -> o.status,
      "canaries"        -> JsArray(o.canaries.map(_.json))
    )

    override def reads(json: JsValue): JsResult[HoneypotPolicy] = Try {
      HoneypotPolicy(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        paths = json.select("paths").asOpt[Seq[String]].getOrElse(defaultPaths),
        weight = json.select("weight").asOpt[Int].getOrElse(100),
        action = json.select("action").asOpt[String].getOrElse("deny"),
        banForSeconds = json.select("ban_for_seconds").asOpt[Long].getOrElse(86400L),
        status = json.select("status").asOpt[Int].getOrElse(404),
        canaries = json.select("canaries").asOpt[JsArray].map(_.value.toSeq.map(CanaryToken.read)).getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): HoneypotPolicy = HoneypotPolicy(
    id = IdGenerator.namedId("honeypot-policy", env),
    name = "Honeypot policy",
    description = "Paths nobody legitimate asks for, and values nobody legitimate holds"
  )

  def resource(env: Env, datastores: SecurityDatastores, states: SecurityState): Resource = {
    Resource(
      "HoneypotPolicy",
      "honeypot-policies",
      "honeypot-policy",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[HoneypotPolicy](
        format = HoneypotPolicy.format,
        clazz = classOf[HoneypotPolicy],
        keyf = id => datastores.honeypotPolicyDatastore.key(id),
        extractIdf = c => datastores.honeypotPolicyDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => HoneypotPolicy.template(env).json,
        canRead = true, canCreate = true, canUpdate = true, canDelete = true, canBulk = true,
        stateAll = () => states.allHoneypotPolicies(),
        stateOne = id => states.honeypotPolicy(id),
        stateUpdate = values => states.updateHoneypotPolicies(values)
      )
    )
  }
}

trait HoneypotPolicyDatastore extends BasicStore[HoneypotPolicy]

class KvHoneypotPolicyDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends HoneypotPolicyDatastore
    with RedisLikeStore[HoneypotPolicy] {
  override def fmt: Format[HoneypotPolicy]              = HoneypotPolicy.format
  override def redisLike(using env: Env): RedisLike     = redisCli
  override def key(id: String): String                  = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:honeypotpolicies:$id"
  override def extractId(value: HoneypotPolicy): String = value.id
}
