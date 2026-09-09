package com.cloud.apim.otoroshi.extensions.waf.security

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** What the response engine decided to do. Also the `event.action` of the emitted event. */
sealed trait ThreatAction {
  def name: String
  def denies: Boolean = false
}

object ThreatAction {
  case object Allow  extends ThreatAction { val name = "allow"  }
  case object Log    extends ThreatAction { val name = "log"    }
  case object Tarpit    extends ThreatAction { val name = "tarpit" }
  case object Challenge extends ThreatAction { val name = "challenge" }
  case object Deny   extends ThreatAction { val name = "deny";  override def denies = true }
  case object Ban    extends ThreatAction { val name = "ban";   override def denies = true }

  val all: Seq[ThreatAction] = Seq(Allow, Log, Challenge, Tarpit, Deny, Ban)

  def parse(raw: String): Option[ThreatAction] =
    all.find(_.name.equalsIgnoreCase(raw.trim))
}

final case class ThreatDecision(
    action: ThreatAction,
    score: Int,
    tier: Option[Int],
    dryRun: Boolean,
    reason: String
) {
  /** In dry-run nothing is enforced, so the decision is recorded and the request proceeds. */
  def enforced: Boolean = !dryRun && action.denies
  def json: JsValue = Json.obj(
    "action"   -> action.name,
    "score"    -> score,
    "tier"     -> tier,
    "dry_run"  -> dryRun,
    "enforced" -> enforced,
    "reason"   -> reason
  )
}

/**
 * One normalised security event, in an ECS-shaped envelope.
 *
 * Every module emits this shape rather than its own, so a SIEM needs one parser and one set of
 * dashboards regardless of which detector fired. The extension's older per-module events are still
 * emitted alongside it, so nothing downstream breaks — this one is additive.
 */
final case class CloudApimSecurityEvent(
    category: String,
    action: String,
    outcome: String,
    severity: Int,
    identity: ClientIdentity,
    score: Int,
    tags: Seq[String],
    signals: JsValue,
    routeId: Option[String],
    routeName: Option[String],
    node: String,
    incidentId: Option[String],
    incidentCount: Int,
    message: String,
    extra: JsObject = Json.obj()
) extends AnalyticEvent {

  override def `@service`: String            = routeName.getOrElse("--")
  override def `@serviceId`: String          = routeId.getOrElse("--")
  def `@id`: String                          = IdGenerator.uuid
  def `@timestamp`: DateTime                 = timestamp
  def `@type`: String                        = "CloudApimSecurityEvent"
  override def fromOrigin: Option[String]    = Some(identity.ip)
  override def fromUserAgent: Option[String] = None

  private val timestamp = DateTime.now()

  override def toJson(using _env: Env): JsValue = {
    // resolved once here rather than threaded through every call site. without these the analytics
    // row has no tenant, api or group, and the console's own filters return nothing on this table
    val route = routeId.flatMap(id => _env.proxyState.route(id))
    Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
    "@type"      -> `@type`,
    "@product"   -> "otoroshi",
    "@service"   -> `@service`,
    "@serviceId" -> `@serviceId`,
    "event"      -> Json.obj(
      "kind"     -> "alert",
      "module"   -> "cloud-apim.security-suite",
      "dataset"  -> s"cloud-apim.$category",
      "category" -> category,
      "action"   -> action,
      "outcome"  -> outcome,
      "severity" -> severity
    ),
    "source"     -> Json.obj(
      "ip"     -> identity.ip,
      "apikey" -> identity.apikey,
      "user"   -> identity.user
    ),
    "threat"     -> Json.obj(
      "score"   -> score,
      "tags"    -> tags,
      "signals" -> signals
    ),
    "otoroshi"   -> Json.obj(
      "route_id"   -> routeId,
      "route_name" -> routeName,
      "node"       -> node,
      // resolved here rather than carried through every call site: without them an analytics row
      // has no tenant, and a multi-tenant console cannot filter what it cannot see
      "tenant"     -> route.map(_.location.tenant.value).getOrElse("default"),
      "teams"      -> JsArray(route.map(_.location.teams.map(t => JsString(t.value))).getOrElse(Seq.empty)),
      "api_id"     -> route.flatMap(_.apiRef).map(_.id),
      "groups"     -> JsArray(route.map(_.groups.map(JsString.apply)).getOrElse(Seq.empty))
    ),
    "incident"   -> Json.obj(
      "id"    -> incidentId,
      "count" -> incidentCount
    ),
    "message"    -> message
    ) ++ extra
  }
}

/**
 * A run of events from one identity, collapsed into a single object.
 *
 * The value is in what it removes: an attack produces thousands of matches and one incident, so
 * alerting on incidents pages someone once instead of nine thousand times.
 */
final case class Incident(
    id: String,
    ref: IdentityRef,
    firstSeen: Long,
    lastSeen: Long,
    count: Int,
    maxScore: Int,
    categories: Set[String],
    tags: Set[String],
    actions: Set[String],
    lastMessage: String
) {
  def json: JsValue = Json.obj(
    "id"           -> id,
    "ref"          -> ref.json,
    "key"          -> ref.key,
    "first_seen"   -> firstSeen,
    "last_seen"    -> lastSeen,
    "count"        -> count,
    "max_score"    -> maxScore,
    "categories"   -> categories.toSeq.sorted,
    "tags"         -> tags.toSeq.sorted,
    "actions"      -> actions.toSeq.sorted,
    "last_message" -> lastMessage
  )
}

/**
 * Windowed correlation, node-local.
 *
 * Incidents live in memory on the node that observed them: they are an operational view, not
 * durable state, and every node sees the traffic it handles. A cluster-wide view is the job of the
 * SIEM the normalised events are exported to.
 */
class IncidentCorrelator(window: () => FiniteDuration, maxIncidents: Int = 2000) {

  private val incidents = new TrieMap[String, Incident]()

  def record(
      ref: IdentityRef,
      category: String,
      score: Int,
      tags: Seq[String],
      action: String,
      message: String
  ): Incident = {
    val now      = System.currentTimeMillis()
    val cutoff   = now - window().toMillis
    val existing = incidents.get(ref.key).filter(_.lastSeen >= cutoff)
    val next = existing match {
      case Some(inc) =>
        inc.copy(
          lastSeen = now,
          count = inc.count + 1,
          maxScore = math.max(inc.maxScore, score),
          categories = inc.categories + category,
          tags = inc.tags ++ tags,
          actions = inc.actions + action,
          lastMessage = message
        )
      case None      =>
        Incident(
          id = IdGenerator.uuid,
          ref = ref,
          firstSeen = now,
          lastSeen = now,
          count = 1,
          maxScore = score,
          categories = Set(category),
          tags = tags.toSet,
          actions = Set(action),
          lastMessage = message
        )
    }
    incidents.put(ref.key, next)
    if (incidents.size > maxIncidents) evict(now)
    next
  }

  /** Drops everything outside the window, and trims the oldest if still over the cap. */
  def evict(now: Long = System.currentTimeMillis()): Int = {
    val cutoff = now - window().toMillis
    incidents.filter(_._2.lastSeen < cutoff).keys.foreach(incidents.remove)
    if (incidents.size > maxIncidents) {
      incidents.values.toSeq.sortBy(_.lastSeen).take(incidents.size - maxIncidents).foreach(i => incidents.remove(i.ref.key))
    }
    incidents.size
  }

  def all: Seq[Incident] = {
    val cutoff = System.currentTimeMillis() - window().toMillis
    incidents.values.filter(_.lastSeen >= cutoff).toSeq.sortBy(-_.lastSeen)
  }

  def get(id: String): Option[Incident] = incidents.values.find(_.id == id)

  def forget(key: String): Unit = { incidents.remove(key); () }

  def size: Int = incidents.size
}

object IncidentCorrelator {
  val defaultWindow: FiniteDuration = 30.minutes
}
