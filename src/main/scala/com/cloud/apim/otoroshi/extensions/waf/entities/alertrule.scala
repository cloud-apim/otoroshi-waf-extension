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
 * Where an alert goes (OPS-6).
 *
 * `slack`, `teams` and `pagerduty` each get the payload their incoming endpoint expects: a Slack
 * message, an Adaptive Card, an Events API v2 trigger. `webhook` posts the alert as it is, with
 * `headers`. `event` sends nothing itself: the alert is still emitted as a `CloudApimSecurityAlert`
 * event, for whatever data exporter is routed to it.
 */
final case class AlertChannel(
    kind: String = "slack",
    url: String = "",
    routingKey: String = "",
    headers: Map[String, String] = Map.empty,
    timeoutMillis: Long = 10000L
) {
  def json: JsValue = Json.obj(
    "kind"           -> kind,
    "url"            -> url,
    "routing_key"    -> routingKey,
    "headers"        -> headers,
    "timeout_millis" -> timeoutMillis
  )

  /** PagerDuty has one endpoint for every account: the routing key says which service. */
  def endpoint: String =
    if (url.trim.isEmpty && kind == "pagerduty") "https://events.pagerduty.com/v2/enqueue" else url.trim
}

object AlertChannel {
  val kinds: Seq[String] = Seq("slack", "teams", "pagerduty", "webhook", "event")

  def read(json: JsValue): AlertChannel = AlertChannel(
    kind = (json \ "kind").asOpt[String].map(_.trim.toLowerCase).filter(kinds.contains).getOrElse("slack"),
    url = (json \ "url").asOpt[String].getOrElse(""),
    routingKey = (json \ "routing_key").asOpt[String].getOrElse(""),
    headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty),
    timeoutMillis = (json \ "timeout_millis").asOpt[Long].filter(_ > 0L).getOrElse(10000L)
  )
}

/**
 * When the fabric tells someone (OPS-6).
 *
 * An alert is about something that lasts, never about one request: an attack produces thousands of
 * decisions, and a channel that receives one message per decision is a channel nobody reads. Three
 * triggers:
 *
 *  - `incident`: an identity's incident reaches `min_score` over at least `min_count` decisions;
 *  - `ban`: a ban is issued, whoever issued it;
 *  - `burst`: a route sees `burst_threshold` decisions within `burst_window_seconds`.
 *
 * Each alert has a key (the identity, or the route) and is sent once per key and per
 * `cooldown_seconds`, once for the whole cluster, whichever node saw it first.
 */
final case class AlertRule(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    trigger: String = "incident",
    minScore: Int = 70,
    minCount: Int = 1,
    enforcedOnly: Boolean = true,
    categories: Seq[String] = Seq.empty,
    routes: Seq[String] = Seq.empty,
    burstThreshold: Int = 100,
    burstWindowSeconds: Long = 60L,
    cooldownSeconds: Long = 900L,
    channel: AlertChannel = AlertChannel()
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = AlertRule.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  /** Whether a decision in this category concerns the rule. Empty means every category. */
  def coversCategory(category: String): Boolean = categories.isEmpty || categories.exists(_.equalsIgnoreCase(category))

  /** Whether a route concerns the rule, by id or by name. Empty means every route. */
  def coversRoute(routeId: Option[String], routeName: Option[String]): Boolean =
    routes.isEmpty || routes.exists(r => routeId.contains(r) || routeName.exists(_.equalsIgnoreCase(r)))
}

object AlertRule {

  val triggers: Seq[String] = Seq("incident", "ban", "burst")

  val format: Format[AlertRule] = new Format[AlertRule] {
    override def writes(o: AlertRule): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                   -> o.id,
      "name"                 -> o.name,
      "description"          -> o.description,
      "metadata"             -> o.metadata,
      "tags"                 -> JsArray(o.tags.map(JsString.apply)),
      "enabled"              -> o.enabled,
      "trigger"              -> o.trigger,
      "min_score"            -> o.minScore,
      "min_count"            -> o.minCount,
      "enforced_only"        -> o.enforcedOnly,
      "categories"           -> o.categories,
      "routes"               -> o.routes,
      "burst_threshold"      -> o.burstThreshold,
      "burst_window_seconds" -> o.burstWindowSeconds,
      "cooldown_seconds"     -> o.cooldownSeconds,
      "channel"              -> o.channel.json
    )

    override def reads(json: JsValue): JsResult[AlertRule] = Try {
      AlertRule(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        trigger = json.select("trigger").asOpt[String].map(_.trim.toLowerCase).filter(triggers.contains).getOrElse("incident"),
        minScore = json.select("min_score").asOpt[Int].getOrElse(70),
        minCount = json.select("min_count").asOpt[Int].getOrElse(1).max(1),
        enforcedOnly = json.select("enforced_only").asOpt[Boolean].getOrElse(true),
        categories = json.select("categories").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty),
        routes = json.select("routes").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty),
        burstThreshold = json.select("burst_threshold").asOpt[Int].getOrElse(100).max(1),
        burstWindowSeconds = json.select("burst_window_seconds").asOpt[Long].getOrElse(60L).max(1L),
        cooldownSeconds = json.select("cooldown_seconds").asOpt[Long].getOrElse(900L).max(0L),
        channel = json.select("channel").asOpt[JsObject].map(AlertChannel.read).getOrElse(AlertChannel())
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): AlertRule = AlertRule(
    id = IdGenerator.namedId("alert-rule", env),
    name = "Enforced attacks",
    description = "One message per attacker, when an incident reaches a high score"
  )

  def resource(env: Env, datastores: SecurityDatastores, states: SecurityState): Resource = {
    Resource(
      "AlertRule",
      "alert-rules",
      "alert-rule",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[AlertRule](
        format = AlertRule.format,
        clazz = classOf[AlertRule],
        keyf = id => datastores.alertRuleDatastore.key(id),
        extractIdf = c => datastores.alertRuleDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => AlertRule.template(env).json,
        canRead = true, canCreate = true, canUpdate = true, canDelete = true, canBulk = true,
        stateAll = () => states.allAlertRules(),
        stateOne = id => states.alertRule(id),
        stateUpdate = values => states.updateAlertRules(values)
      )
    )
  }
}

trait AlertRuleDatastore extends BasicStore[AlertRule]

class KvAlertRuleDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends AlertRuleDatastore
    with RedisLikeStore[AlertRule] {
  override def fmt: Format[AlertRule]              = AlertRule.format
  override def redisLike(using env: Env): RedisLike = redisCli
  override def key(id: String): String             = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:alertrules:$id"
  override def extractId(value: AlertRule): String = value.id
}
