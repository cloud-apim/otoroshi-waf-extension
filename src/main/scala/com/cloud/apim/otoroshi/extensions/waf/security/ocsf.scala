package com.cloud.apim.otoroshi.extensions.waf.security

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import play.api.libs.json.*

/**
 * The fabric's decisions and alerts in OCSF, for Security Lake, Sentinel and the other SIEMs that
 * read it (OPS-6).
 *
 * Both are Detection Findings (class 2004): a decision is a detector finding something about a
 * request, an alert is the fabric finding something about an attacker. The ECS-shaped
 * `CloudApimSecurityEvent` stays what the console reads; this is an additional stream, emitted only
 * when `security.events.ocsf` is on, as its own event type for an exporter to route.
 */
object Ocsf {

  val version: String = "1.3.0"

  private val product = Json.obj("name" -> "Cloud APIM Threat Protection", "vendor_name" -> "Cloud APIM")

  /** The fabric's 1 to 4 on OCSF's scale, where 1 is informational and 5 critical. */
  def severityId(severity: Int): Int = severity match {
    case 4 => 5
    case 3 => 4
    case 2 => 3
    case _ => 2
  }

  def severityName(id: Int): String = id match {
    case 5 => "Critical"
    case 4 => "High"
    case 3 => "Medium"
    case _ => "Low"
  }

  private def finding(uid: String, time: Long, severity: Int) = Json.obj(
    "class_uid"     -> 2004,
    "class_name"    -> "Detection Finding",
    "category_uid"  -> 2,
    "category_name" -> "Findings",
    "activity_id"   -> 1,
    "activity_name" -> "Create",
    "type_uid"      -> 200401,
    "type_name"     -> "Detection Finding: Create",
    "time"          -> time,
    "severity_id"   -> severityId(severity),
    "severity"      -> severityName(severityId(severity)),
    "status_id"     -> 1,
    "status"        -> "New"
  )

  private def route(routeId: Option[String], routeName: Option[String]): JsValue =
    JsArray((routeId.orElse(routeName)).toSeq.map(_ => Json.obj("uid" -> routeId, "name" -> routeName, "type" -> "otoroshi route")))

  /** One decision of the fabric: what a detector found and what was done about it. */
  def decision(
      uid: String,
      time: Long,
      category: String,
      action: String,
      enforced: Boolean,
      severity: Int,
      identity: ClientIdentity,
      score: Int,
      tags: Seq[String],
      signals: JsValue,
      routeId: Option[String],
      routeName: Option[String],
      incidentId: Option[String],
      message: String,
      node: String
  ): JsObject = {
    // what was done, in OCSF's two vocabularies: a mask changed the response, a denial stopped it
    val (actionId, actionName, dispositionId, dispositionName) =
      if (!enforced) (3, "Observed", 15, "Detected")
      else if (action == "mask") (4, "Modified", 11, "Corrected")
      else (2, "Denied", 2, "Blocked")
    finding(uid, time, severity) ++ Json.obj(
      "action_id"      -> actionId,
      "action"         -> actionName,
      "disposition_id" -> dispositionId,
      "disposition"    -> dispositionName,
      "message"        -> message,
      "risk_score"     -> score,
      "metadata"       -> Json.obj(
        "version"         -> version,
        "uid"             -> uid,
        "product"         -> (product ++ Json.obj("feature" -> Json.obj("name" -> category))),
        "labels"          -> tags,
        "correlation_uid" -> incidentId
      ),
      "finding_info"   -> Json.obj(
        "uid"      -> uid,
        "title"    -> message,
        "types"    -> Json.arr(category),
        "analytic" -> Json.obj("name" -> category, "type_id" -> 1, "type" -> "Rule")
      ),
      "src_endpoint"   -> Json.obj("ip" -> identity.ip),
      "actor"          -> Json.obj("user" -> Json.obj("name" -> identity.user, "credential_uid" -> identity.apikey)),
      "resources"      -> route(routeId, routeName),
      "unmapped"       -> Json.obj("threat" -> Json.obj("action" -> action, "signals" -> signals), "otoroshi" -> Json.obj("node" -> node))
    )
  }

  /** One alert: the fabric telling someone about an attacker, a ban or a burst. */
  def alert(a: SecurityAlert): JsObject =
    finding(a.id, a.at, a.severity) ++ Json.obj(
      "action_id"      -> 0,
      "action"         -> "Unknown",
      "disposition_id" -> 19,
      "disposition"    -> "Alert",
      "message"        -> a.summary,
      "metadata"       -> Json.obj(
        "version"         -> version,
        "uid"             -> a.id,
        "product"         -> (product ++ Json.obj("feature" -> Json.obj("name" -> "alerting"))),
        "correlation_uid" -> a.key
      ),
      "finding_info"   -> Json.obj(
        "uid"      -> a.id,
        "title"    -> a.title,
        "desc"     -> a.summary,
        "types"    -> Json.arr(a.trigger),
        "analytic" -> Json.obj("uid" -> a.ruleId, "name" -> a.ruleName, "type_id" -> 1, "type" -> "Rule"),
        "src_url"  -> a.link
      ),
      "src_endpoint"   -> Json.obj("ip" -> a.identity.filter(_.kind == IdentityRef.Ip).map(_.value)),
      "resources"      -> route(a.routeId, a.routeName),
      "unmapped"       -> Json.obj("alert" -> a.json)
    )
}

/** An OCSF finding, with the envelope Otoroshi's exporters filter on. */
final case class CloudApimSecurityOcsfEvent(ocsf: JsObject, routeId: Option[String], routeName: Option[String], ip: Option[String])
    extends AnalyticEvent {
  override def `@service`: String            = routeName.getOrElse("--")
  override def `@serviceId`: String          = routeId.getOrElse("--")
  val `@id`: String                          = (ocsf \ "metadata" \ "uid").asOpt[String].getOrElse(otoroshi.security.IdGenerator.uuid)
  val `@timestamp`: DateTime                 = new DateTime((ocsf \ "time").asOpt[Long].getOrElse(System.currentTimeMillis()))
  def `@type`: String                        = "CloudApimSecurityOcsf"
  override def fromOrigin: Option[String]    = ip
  override def fromUserAgent: Option[String] = None

  override def toJson(using env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> "otoroshi",
    "@service"   -> `@service`,
    "@serviceId" -> `@serviceId`,
    "@env"       -> env.env
  ) ++ ocsf
}
