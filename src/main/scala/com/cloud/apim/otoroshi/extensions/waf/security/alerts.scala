package com.cloud.apim.otoroshi.extensions.waf.security

import com.cloud.apim.otoroshi.extensions.waf.entities.AlertRule
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.security.IdGenerator
import play.api.Logger
import play.api.libs.json.*
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** One alert, as it is sent (OPS-6). */
final case class SecurityAlert(
    id: String,
    at: Long,
    ruleId: String,
    ruleName: String,
    trigger: String,
    key: String,
    severity: Int,
    title: String,
    summary: String,
    identity: Option[IdentityRef],
    routeId: Option[String],
    routeName: Option[String],
    facts: Seq[(String, String)],
    details: JsValue,
    node: String,
    link: Option[String],
    test: Boolean = false
) {

  def severityName: String = severity match {
    case 4 => "critical"
    case 3 => "high"
    case 2 => "medium"
    case _ => "low"
  }

  def json: JsValue = Json.obj(
    "id"         -> id,
    "at"         -> at,
    "rule"       -> Json.obj("id" -> ruleId, "name" -> ruleName),
    "trigger"    -> trigger,
    "key"        -> key,
    "severity"   -> severity,
    "level"      -> severityName,
    "title"      -> title,
    "summary"    -> summary,
    "identity"   -> identity.map(_.json),
    "route_id"   -> routeId,
    "route_name" -> routeName,
    "facts"      -> JsObject(facts.map { case (k, v) => k -> JsString(v) }),
    "details"    -> details,
    "node"       -> node,
    "link"       -> link,
    "test"       -> test
  )
}

/**
 * The alert as an analytics event, so a data exporter can route it anywhere Otoroshi reaches: a
 * mailer, Splunk, a Kafka topic. Emitted once per alert, cluster-wide, whatever the channel.
 */
final case class CloudApimSecurityAlertEvent(alert: SecurityAlert) extends AnalyticEvent {
  override def `@service`: String            = alert.routeName.getOrElse("--")
  override def `@serviceId`: String          = alert.routeId.getOrElse("--")
  val `@id`: String                          = alert.id
  val `@timestamp`: DateTime                 = new DateTime(alert.at)
  def `@type`: String                        = "CloudApimSecurityAlert"
  override def fromOrigin: Option[String]    = alert.identity.filter(_.kind == IdentityRef.Ip).map(_.value)
  override def fromUserAgent: Option[String] = None

  override def toJson(using env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> "otoroshi",
    "@service"   -> `@service`,
    "@serviceId" -> `@serviceId`,
    "@env"       -> env.env,
    "event"      -> Json.obj(
      "kind"     -> "alert",
      "module"   -> "cloud-apim.security-suite",
      "dataset"  -> "cloud-apim.alert",
      "category" -> alert.trigger,
      "severity" -> alert.severity
    ),
    "alert"      -> alert.json
  )
}

/**
 * What each channel expects to receive.
 *
 * Kept apart from the sending so the payloads can be checked without a network: they are the part
 * that is easy to get subtly wrong, a field Slack ignores or a severity PagerDuty refuses.
 */
object AlertPayloads {

  def slack(a: SecurityAlert): JsValue = {
    val fields = a.facts.take(10).map { case (k, v) => Json.obj("type" -> "mrkdwn", "text" -> s"*$k*\n$v") }
    Json.obj(
      // what a notification shows when the blocks cannot be displayed
      "text"   -> s"[${a.severityName}] ${a.title}",
      "blocks" -> JsArray(
        Seq(
          Json.obj("type" -> "header", "text" -> Json.obj("type" -> "plain_text", "text" -> a.title.take(150))),
          Json.obj("type" -> "section", "text" -> Json.obj("type" -> "mrkdwn", "text" -> a.summary.take(2900)))
        ) ++ Option.when(fields.nonEmpty)(Json.obj("type" -> "section", "fields" -> JsArray(fields))).toSeq ++ Seq(
          Json.obj(
            "type"     -> "context",
            "elements" -> Json.arr(
              Json.obj(
                "type" -> "mrkdwn",
                "text" -> (s"${a.severityName} · rule *${a.ruleName}* · node ${a.node}" + a.link.fold("")(l => s" · <$l|open the console>"))
              )
            )
          )
        )
      )
    )
  }

  def teams(a: SecurityAlert): JsValue = Json.obj(
    "type"        -> "message",
    "attachments" -> Json.arr(
      Json.obj(
        "contentType" -> "application/vnd.microsoft.card.adaptive",
        "content"     -> Json.obj(
          "$schema" -> "http://adaptivecards.io/schemas/adaptive-card.json",
          "type"    -> "AdaptiveCard",
          "version" -> "1.4",
          "body"    -> Json.arr(
            Json.obj("type" -> "TextBlock", "size" -> "Medium", "weight" -> "Bolder", "text" -> a.title, "wrap" -> true),
            Json.obj("type" -> "TextBlock", "text" -> a.summary, "wrap" -> true),
            Json.obj(
              "type"  -> "FactSet",
              "facts" -> JsArray((("Severity", a.severityName) +: a.facts :+ ("Rule", a.ruleName)).map { case (k, v) =>
                Json.obj("title" -> k, "value" -> v)
              })
            )
          ),
          "actions" -> JsArray(a.link.toSeq.map(l => Json.obj("type" -> "Action.OpenUrl", "title" -> "Open the console", "url" -> l)))
        )
      )
    )
  )

  /** PagerDuty's Events API v2: the dedup key makes a repeated alert update one incident there too. */
  def pagerduty(a: SecurityAlert, routingKey: String): JsValue = Json.obj(
    "routing_key"  -> routingKey,
    "event_action" -> "trigger",
    "dedup_key"    -> s"${a.ruleId}:${a.key}".take(255),
    "payload"      -> Json.obj(
      "summary"        -> a.title.take(1024),
      "source"         -> s"otoroshi ${a.node}",
      "severity"       -> (a.severity match {
        case 4 => "critical"
        case 3 => "error"
        case 2 => "warning"
        case _ => "info"
      }),
      "component"      -> a.routeName.getOrElse("otoroshi"),
      "group"          -> "cloud-apim-threat-protection",
      "class"          -> a.trigger,
      "custom_details" -> a.json
    ),
    "links"        -> JsArray(a.link.toSeq.map(l => Json.obj("href" -> l, "text" -> "Open the console")))
  )

  def of(rule: AlertRule, a: SecurityAlert): Option[JsValue] = rule.channel.kind match {
    case "slack"     => Some(slack(a))
    case "teams"     => Some(teams(a))
    case "pagerduty" => Some(pagerduty(a, rule.channel.routingKey))
    case "webhook"   => Some(a.json)
    case _           => None
  }
}

/** What one delivery came back with. `0` when nothing was sent. */
final case class AlertDelivery(status: Int, body: String) {
  def ok: Boolean   = status >= 200 && status < 300
  def json: JsValue = Json.obj("status" -> status, "ok" -> ok, "body" -> body.take(500))
}

trait AlertSender {
  def post(url: String, headers: Map[String, String], body: JsValue, timeout: FiniteDuration): Future[AlertDelivery]
}

final class WsAlertSender(env: Env) extends AlertSender {
  override def post(url: String, headers: Map[String, String], body: JsValue, timeout: FiniteDuration): Future[AlertDelivery] = {
    given ExecutionContext = env.otoroshiExecutionContext
    env.Ws
      .url(url)
      .withRequestTimeout(timeout)
      .withHttpHeaders((headers + ("Content-Type" -> "application/json")).toSeq*)
      .post(body)
      .map(res => AlertDelivery(res.status, res.body))
  }
}

/**
 * Turns decisions and bans into alerts, once per key and per cooldown, for the whole cluster.
 *
 * Every node evaluates what it sees; the shared store decides which node sends. The first `incrBy`
 * on a key wins and sets its expiry, so two nodes that see the same attacker at the same moment send
 * one message, not two. A node also remembers locally what it already let cool down, so an attack in
 * progress does not cost a round trip to the store on every decision.
 *
 * Nothing here runs on the request: a decision only schedules work, whose failures are logged.
 */
final class AlertEngine(
    prefix: String,
    store: SharedStateStore,
    rules: () => Seq[AlertRule],
    sender: AlertSender,
    node: String,
    link: () => Option[String],
    emit: SecurityAlert => Unit,
    logger: Logger,
    enabled: () => Boolean
)(using ec: ExecutionContext) {

  // an alert key may not be re-sent more often than this, whatever a rule says
  private val minCooldownMillis = 10000L
  private val cooling           = new TrieMap[String, Long]()

  private def active(trigger: String): Seq[AlertRule] =
    if (!enabled()) Seq.empty else rules().filter(r => r.enabled && r.trigger == trigger)

  private def severityOf(score: Int): Int =
    if (score >= 90) 4 else if (score >= 70) 3 else if (score >= 40) 2 else 1

  /** A decision was recorded, and folded into `incident`. */
  def decision(incident: Incident, category: String, enforced: Boolean, routeId: Option[String], routeName: Option[String]): Unit =
    Try {
      active("incident").foreach { rule =>
        val qualifies =
          incident.maxScore >= rule.minScore && incident.count >= rule.minCount && (!rule.enforcedOnly || incident.enforcedCount > 0) &&
            incident.categories.exists(rule.coversCategory) && rule.coversRoute(routeId, routeName)
        if (qualifies) fire(rule, s"incident:${incident.ref.key}")(incidentAlert(rule, incident))
      }
      if (enforced || active("burst").exists(!_.enforcedOnly))
        active("burst").filter(r => (enforced || !r.enforcedOnly) && r.coversCategory(category) && r.coversRoute(routeId, routeName)).foreach {
          rule => burst(rule, category, routeId, routeName)
        }
    }.failed.foreach(e => logger.error("could not evaluate the alert rules", e))

  /** A ban was issued, by anything: a policy, the ledger, fail2ban, a honeypot, an operator. */
  def banned(ban: BanEntry): Unit =
    Try {
      active("ban").foreach(rule => fire(rule, s"ban:${ban.ref.key}")(banAlert(rule, ban)))
    }.failed.foreach(e => logger.error("could not evaluate the alert rules", e))

  private def burst(rule: AlertRule, category: String, routeId: Option[String], routeName: Option[String]): Unit = {
    val window = rule.burstWindowSeconds * 1000L
    val bucket = System.currentTimeMillis() / window
    val route  = routeId.getOrElse("-")
    val key    = s"$prefix:alerts:burst:${rule.id}:$route:$bucket"
    store
      .incrBy(key, 1L)
      .map { n =>
        if (n == 1L) store.pexpire(key, window * 2)
        // exactly at the threshold: the one decision that crosses it, on whichever node, fires
        if (n == rule.burstThreshold.toLong) fire(rule, s"burst:$route")(burstAlert(rule, n, category, routeId, routeName))
      }
      .recover { case e => logger.warn(s"could not count a burst for alert rule ${rule.id}: ${e.getMessage}") }
  }

  private def fire(rule: AlertRule, key: String)(build: => SecurityAlert): Unit = {
    val now      = System.currentTimeMillis()
    val cooldown = math.max(rule.cooldownSeconds * 1000L, minCooldownMillis)
    val local    = s"${rule.id}:$key"
    if (!cooling.get(local).exists(_ > now)) {
      cooling.put(local, now + cooldown)
      if (cooling.size > 10000) cooling.filterInPlace((_, until) => until > now)
      val shared = s"$prefix:alerts:sent:$local"
      store
        .incrBy(shared, 1L)
        .map { n =>
          if (n == 1L) {
            store.pexpire(shared, cooldown)
            dispatch(rule, build)
          }
        }
        .recover { case e => logger.warn(s"could not deduplicate an alert of rule ${rule.id}, it is not sent: ${e.getMessage}") }
    }
  }

  private def dispatch(rule: AlertRule, alert: SecurityAlert): Unit = {
    Try(emit(alert)).failed.foreach(e => logger.error("could not emit an alert event", e))
    send(rule, alert).foreach { delivery =>
      if (delivery.status != 0 && !delivery.ok)
        logger.warn(s"alert rule '${rule.name}' could not deliver to ${rule.channel.kind}: ${delivery.status} ${delivery.body.take(200)}")
    }
  }

  /** Sends one alert to the rule's channel, as it is, cooldown or not. Used for test alerts too. */
  def send(rule: AlertRule, alert: SecurityAlert): Future[AlertDelivery] =
    AlertPayloads.of(rule, alert) match {
      case None                                   => Future.successful(AlertDelivery(0, "sent as an event only"))
      case Some(_) if rule.channel.endpoint.isEmpty => Future.successful(AlertDelivery(0, "the channel has no url"))
      case Some(body)                             =>
        sender
          .post(rule.channel.endpoint, rule.channel.headers, body, rule.channel.timeoutMillis.millis)
          .recover { case e => AlertDelivery(0, s"could not reach the channel: ${e.getMessage}") }
    }

  // ---------------------------------------------------------------------------------------------
  // what each trigger says
  // ---------------------------------------------------------------------------------------------

  private def base(rule: AlertRule, trigger: String, key: String) = SecurityAlert(
    id = IdGenerator.uuid,
    at = System.currentTimeMillis(),
    ruleId = rule.id,
    ruleName = rule.name,
    trigger = trigger,
    key = key,
    severity = 1,
    title = "",
    summary = "",
    identity = None,
    routeId = None,
    routeName = None,
    facts = Seq.empty,
    details = JsObject.empty,
    node = node,
    link = link()
  )

  def incidentAlert(rule: AlertRule, i: Incident): SecurityAlert =
    base(rule, "incident", s"incident:${i.ref.key}").copy(
      severity = severityOf(i.maxScore),
      title = s"Incident on ${i.ref.kind} ${i.ref.value}: score ${i.maxScore}",
      summary = i.lastMessage,
      identity = Some(i.ref),
      routeId = i.timeline.headOption.flatMap(_.routeId),
      routeName = i.timeline.headOption.flatMap(_.routeName),
      facts = Seq(
        "Identity"   -> s"${i.ref.kind} ${i.ref.value}",
        "Score"      -> i.maxScore.toString,
        "Decisions"  -> s"${i.count}, ${i.enforcedCount} enforced",
        "Categories" -> i.categories.toSeq.sorted.mkString(", "),
        "Routes"     -> (if (i.routes.isEmpty) "-" else i.routes.toSeq.sorted.take(5).mkString(", "))
      ),
      details = i.json
    )

  def banAlert(rule: AlertRule, b: BanEntry): SecurityAlert =
    base(rule, "ban", s"ban:${b.ref.key}").copy(
      severity = math.max(3, severityOf(b.score)),
      title = s"${b.ref.kind} ${b.ref.value} banned",
      summary = b.reason,
      identity = Some(b.ref),
      facts = Seq(
        "Identity"  -> s"${b.ref.kind} ${b.ref.value}",
        "Until"     -> new DateTime(b.until).toString,
        "Score"     -> b.score.toString,
        "Tags"      -> (if (b.tags.isEmpty) "-" else b.tags.take(8).mkString(", ")),
        "Issued by" -> b.issuedBy
      ),
      details = b.json
    )

  def burstAlert(rule: AlertRule, count: Long, category: String, routeId: Option[String], routeName: Option[String]): SecurityAlert = {
    val what = if (rule.enforcedOnly) "enforced decisions" else "decisions"
    val on   = routeName.orElse(routeId).getOrElse("a request before routing")
    base(rule, "burst", s"burst:${routeId.getOrElse("-")}").copy(
      severity = 3,
      title = s"$count $what on $on within ${rule.burstWindowSeconds}s",
      summary = s"The route crossed the burst threshold of rule '${rule.name}'. The decision that crossed it was a $category one.",
      routeId = routeId,
      routeName = routeName,
      facts = Seq("Route" -> on, "Decisions" -> count.toString, "Window" -> s"${rule.burstWindowSeconds}s", "Last category" -> category),
      details = Json.obj("count" -> count, "window_seconds" -> rule.burstWindowSeconds, "category" -> category)
    )
  }

  /** What a channel receives when someone asks to check it. */
  def testAlert(rule: AlertRule): SecurityAlert =
    base(rule, rule.trigger, "test").copy(
      severity = 2,
      title = s"Test alert from rule '${rule.name}'",
      summary = "This channel is wired to Otoroshi's threat protection. Nothing happened: an operator asked to check the channel.",
      facts = Seq("Trigger" -> rule.trigger, "Cooldown" -> s"${rule.cooldownSeconds}s"),
      test = true
    )
}
