package com.cloud.apim.otoroshi.extensions.waf.security

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

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
 * One thing that happened inside an incident, kept verbatim.
 *
 * The counters answer *how much*; this answers *what*, which is the question an operator actually
 * has in front of the console. Bounded per incident — see [[IncidentCorrelator.maxTimeline]] —
 * because an attack produces thousands of these and the last twenty tell the same story as the last
 * twenty thousand.
 */
final case class IncidentEvent(
    at: Long,
    category: String,
    action: String,
    score: Int,
    enforced: Boolean,
    routeId: Option[String],
    routeName: Option[String],
    message: String
) {
  def json: JsValue = Json.obj(
    "at"         -> at,
    "category"   -> category,
    "action"     -> action,
    "score"      -> score,
    "enforced"   -> enforced,
    "route_id"   -> routeId,
    "route_name" -> routeName,
    "message"    -> message
  )
}

object IncidentEvent {
  def read(json: JsValue): Option[IncidentEvent] = Try {
    IncidentEvent(
      at = (json \ "at").asOpt[Long].getOrElse(0L),
      category = (json \ "category").asOpt[String].getOrElse("unknown"),
      action = (json \ "action").asOpt[String].getOrElse("log"),
      score = (json \ "score").asOpt[Int].getOrElse(0),
      enforced = (json \ "enforced").asOpt[Boolean].getOrElse(false),
      routeId = (json \ "route_id").asOpt[String],
      routeName = (json \ "route_name").asOpt[String],
      message = (json \ "message").asOpt[String].getOrElse("")
    )
  }.toOption
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
    lastMessage: String,
    /** Newest first, bounded. */
    timeline: Seq[IncidentEvent] = Seq.empty,
    /** Which node observed it. Only meaningful once published — see [[IncidentBoard]]. */
    node: Option[String] = None,
    /** How many of the collapsed events were actually enforced rather than only recorded. */
    enforcedCount: Int = 0,
    routes: Set[String] = Set.empty
) {
  def json: JsValue = Json.obj(
    "id"             -> id,
    "ref"            -> ref.json,
    "key"            -> ref.key,
    "first_seen"     -> firstSeen,
    "last_seen"      -> lastSeen,
    "count"          -> count,
    "enforced_count" -> enforcedCount,
    "max_score"      -> maxScore,
    "categories"     -> categories.toSeq.sorted,
    "tags"           -> tags.toSeq.sorted,
    "actions"        -> actions.toSeq.sorted,
    "routes"         -> routes.toSeq.sorted,
    "last_message"   -> lastMessage,
    "node"           -> node,
    "timeline"       -> JsArray(timeline.map(_.json))
  )
}

object Incident {
  def read(json: JsValue): Option[Incident] = Try {
    Incident(
      id = (json \ "id").as[String],
      ref = IdentityRef((json \ "ref" \ "kind").as[String], (json \ "ref" \ "value").as[String]),
      firstSeen = (json \ "first_seen").asOpt[Long].getOrElse(0L),
      lastSeen = (json \ "last_seen").asOpt[Long].getOrElse(0L),
      count = (json \ "count").asOpt[Int].getOrElse(0),
      maxScore = (json \ "max_score").asOpt[Int].getOrElse(0),
      categories = (json \ "categories").asOpt[Seq[String]].getOrElse(Seq.empty).toSet,
      tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty).toSet,
      actions = (json \ "actions").asOpt[Seq[String]].getOrElse(Seq.empty).toSet,
      lastMessage = (json \ "last_message").asOpt[String].getOrElse(""),
      timeline = (json \ "timeline").asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty).flatMap(IncidentEvent.read),
      node = (json \ "node").asOpt[String],
      enforcedCount = (json \ "enforced_count").asOpt[Int].getOrElse(0),
      routes = (json \ "routes").asOpt[Seq[String]].getOrElse(Seq.empty).toSet
    )
  }.toOption
}

/**
 * Windowed correlation, node-local.
 *
 * Correlation happens here, on the node that served the traffic, because it is fed from the request
 * path and has to cost nothing. It is **not** where the operator reads incidents from: that is
 * [[IncidentBoard]], which merges every node's published view. Keeping the two apart is what lets
 * this stay a `TrieMap` with no I/O in it at all.
 */
class IncidentCorrelator(window: () => FiniteDuration, maxIncidents: Int = 2000, node: Option[String] = None) {

  private val incidents = new TrieMap[String, Incident]()

  def record(
      ref: IdentityRef,
      category: String,
      score: Int,
      tags: Seq[String],
      action: String,
      message: String,
      enforced: Boolean = false,
      routeId: Option[String] = None,
      routeName: Option[String] = None
  ): Incident = {
    val now      = System.currentTimeMillis()
    val cutoff   = now - window().toMillis
    val existing = incidents.get(ref.key).filter(_.lastSeen >= cutoff)
    val event    = IncidentEvent(now, category, action, score, enforced, routeId, routeName, message)
    val route    = routeName.orElse(routeId).toSet
    val next = existing match {
      case Some(inc) =>
        inc.copy(
          lastSeen = now,
          count = inc.count + 1,
          enforcedCount = inc.enforcedCount + (if (enforced) 1 else 0),
          maxScore = math.max(inc.maxScore, score),
          categories = inc.categories + category,
          tags = inc.tags ++ tags,
          actions = inc.actions + action,
          routes = inc.routes ++ route,
          lastMessage = message,
          timeline = (event +: inc.timeline).take(IncidentCorrelator.maxTimeline)
        )
      case None      =>
        Incident(
          id = IdGenerator.uuid,
          ref = ref,
          firstSeen = now,
          lastSeen = now,
          count = 1,
          enforcedCount = if (enforced) 1 else 0,
          maxScore = score,
          categories = Set(category),
          tags = tags.toSet,
          actions = Set(action),
          routes = route,
          lastMessage = message,
          timeline = Seq(event),
          node = node
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

  /** By identity rather than by incident id — the key everything outside this class uses. */
  def byKey(key: String): Option[Incident] = incidents.get(key)

  def forget(key: String): Unit = { incidents.remove(key); () }

  def size: Int = incidents.size
}

object IncidentCorrelator {
  val defaultWindow: FiniteDuration = 30.minutes

  /**
   * How many events an incident keeps verbatim.
   *
   * Small on purpose. This is a triage view, not a forensic record — the full stream is in the
   * analytics table, and an incident that needs more than twenty lines to understand is one you
   * open the dashboard for.
   */
  val maxTimeline: Int = 20
}
