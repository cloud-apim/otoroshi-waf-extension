package com.cloud.apim.otoroshi.extensions.waf.security

import com.cloud.apim.otoroshi.extensions.waf.entities.{AlertChannel, AlertRule}
import play.api.Logger
import play.api.libs.json.*

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/**
 * OPS-6 without a network: what each trigger fires on, that an attack is one message and not
 * thousands, that two nodes send it once, what each channel receives, and the OCSF shape.
 */
class AlertSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global

  private final class Capture extends AlertSender {
    val sent = new ConcurrentLinkedQueue[(String, JsValue)]()
    override def post(url: String, headers: Map[String, String], body: JsValue, timeout: FiniteDuration): Future[AlertDelivery] = {
      sent.add(url -> body)
      Future.successful(AlertDelivery(200, "ok"))
    }
  }

  private def rule(trigger: String, f: AlertRule => AlertRule = identity): AlertRule =
    f(AlertRule(id = s"alert-rule_$trigger", name = s"$trigger rule", trigger = trigger, channel = AlertChannel("webhook", "https://hooks.example.com/x")))

  private final class Fixture(rules: Seq[AlertRule], store: SharedStateStore = new InMemorySharedStateStore(), node: String = "node-1") {
    val sender   = new Capture()
    val emitted  = new ConcurrentLinkedQueue[SecurityAlert]()
    val engine   = new AlertEngine("test", store, () => rules, sender, node, () => Some("https://otoroshi.example.com/bo"), a => { emitted.add(a); () }, Logger("test"), () => true)
    val incidents = new IncidentCorrelator(() => 30.minutes)

    def decide(ip: String, score: Int, enforced: Boolean = true, category: String = "threat", route: String = "route_1"): Unit = {
      val incident = incidents.record(IdentityRef(IdentityRef.Ip, ip), category, score, Seq.empty, if (enforced) "deny" else "log", s"$category $score", enforced, Some(route), Some(s"name of $route"))
      engine.decision(incident, category, enforced, Some(route), Some(s"name of $route"))
    }

    // the engine schedules its work: give it the moment it needs
    def settle(): Unit = Thread.sleep(150)
    def sent: Seq[(String, JsValue)] = { settle(); sender.sent.asScala.toSeq }
  }

  // ---------------------------------------------------------------- triggers

  test("an incident alerts once it reaches the score, and an attack is one message") {
    val f = new Fixture(Seq(rule("incident", _.copy(minScore = 70))))
    f.decide("203.0.113.7", 40)
    assertEquals(f.sent.size, 0, "below the score")
    (1 to 50).foreach(_ => f.decide("203.0.113.7", 90))
    assertEquals(f.sent.size, 1, "fifty decisions of one attack")
    assertEquals((f.sent.head._2 \ "trigger").as[String], "incident")
    assertEquals((f.sent.head._2 \ "identity" \ "value").as[String], "203.0.113.7")
    f.decide("198.51.100.1", 95)
    assertEquals(f.sent.size, 2, "another attacker is another alert")
  }

  test("an incident made only of observations does not alert unless asked to") {
    val strict  = new Fixture(Seq(rule("incident")))
    strict.decide("203.0.113.7", 90, enforced = false)
    assertEquals(strict.sent.size, 0)
    val lenient = new Fixture(Seq(rule("incident", _.copy(enforcedOnly = false))))
    lenient.decide("203.0.113.7", 90, enforced = false)
    assertEquals(lenient.sent.size, 1)
  }

  test("categories, routes and decision counts narrow what a rule covers") {
    val f = new Fixture(
      Seq(rule("incident", _.copy(categories = Seq("honeypot"), routes = Seq("name of route_2"), minCount = 3)))
    )
    f.decide("203.0.113.7", 90, category = "threat", route = "route_2")
    f.decide("203.0.113.8", 90, category = "honeypot", route = "route_1")
    assertEquals(f.sent.size, 0)
    (1 to 3).foreach(_ => f.decide("203.0.113.9", 90, category = "honeypot", route = "route_2"))
    assertEquals(f.sent.size, 1)
  }

  test("a ban alerts, whoever issued it") {
    val f   = new Fixture(Seq(rule("ban")))
    val ban = BanEntry(IdentityRef(IdentityRef.Ip, "203.0.113.7"), "ledger crossed 100", Seq("ledger"), 100, 0L, 3600000L, "node-1")
    f.engine.banned(ban)
    f.engine.banned(ban)
    assertEquals(f.sent.size, 1)
    val alert = f.emitted.asScala.head
    assertEquals(alert.severity, 4)
    assertEquals(alert.title, "ip 203.0.113.7 banned")
  }

  test("a burst alerts exactly when the threshold is crossed") {
    val f = new Fixture(Seq(rule("burst", _.copy(burstThreshold = 10, burstWindowSeconds = 60))))
    (1 to 9).foreach(i => f.decide(s"10.0.0.$i", 50))
    assertEquals(f.sent.size, 0)
    (10 to 40).foreach(i => f.decide(s"10.0.0.$i", 50))
    assertEquals(f.sent.size, 1)
    assert((f.sent.head._2 \ "title").as[String].startsWith("10 enforced decisions on name of route_1"), f.sent.head._2.toString)
  }

  test("two nodes seeing the same attacker send one message") {
    val shared = new InMemorySharedStateStore()
    val a      = new Fixture(Seq(rule("incident")), shared, "node-a")
    val b      = new Fixture(Seq(rule("incident")), shared, "node-b")
    a.decide("203.0.113.7", 90)
    b.decide("203.0.113.7", 90)
    a.decide("203.0.113.7", 90)
    assertEquals(a.sent.size + b.sent.size, 1)
  }

  test("a disabled rule, or the event channel, sends nothing over the network") {
    val off = new Fixture(Seq(rule("incident", _.copy(enabled = false))))
    off.decide("203.0.113.7", 90)
    assertEquals(off.sent.size, 0)
    assertEquals(off.emitted.size, 0)
    val event = new Fixture(Seq(rule("incident", _.copy(channel = AlertChannel("event")))))
    event.decide("203.0.113.7", 90)
    assertEquals(event.sent.size, 0)
    assertEquals(event.emitted.size, 1, "the alert is still emitted for the exporters")
  }

  // ---------------------------------------------------------------- payloads

  private val sample = SecurityAlert(
    id = "a1", at = 0L, ruleId = "r1", ruleName = "Enforced attacks", trigger = "incident", key = "incident:ip:203.0.113.7",
    severity = 3, title = "Incident on ip 203.0.113.7: score 90", summary = "waf block on /login", identity = Some(IdentityRef("ip", "203.0.113.7")),
    routeId = Some("route_1"), routeName = Some("Public API"), facts = Seq("Score" -> "90", "Decisions" -> "12, 12 enforced"),
    details = Json.obj(), node = "node-1", link = Some("https://otoroshi.example.com/bo")
  )

  test("Slack gets a message with a fallback text and blocks") {
    val s = AlertPayloads.slack(sample)
    assertEquals((s \ "text").as[String], "[high] Incident on ip 203.0.113.7: score 90")
    val types = (s \ "blocks").as[Seq[JsObject]].map(b => (b \ "type").as[String])
    assertEquals(types, Seq("header", "section", "section", "context"))
    assert(Json.stringify(s).contains("<https://otoroshi.example.com/bo|open the console>"))
  }

  test("Teams gets an Adaptive Card") {
    val t       = AlertPayloads.teams(sample)
    val content = (t \ "attachments" \ 0 \ "content").as[JsObject]
    assertEquals((t \ "attachments" \ 0 \ "contentType").as[String], "application/vnd.microsoft.card.adaptive")
    assertEquals((content \ "type").as[String], "AdaptiveCard")
    assertEquals((content \ "actions" \ 0 \ "url").as[String], "https://otoroshi.example.com/bo")
  }

  test("PagerDuty gets an Events API v2 trigger, deduplicated on the alert key") {
    val p = AlertPayloads.pagerduty(sample, "R0UT1NGK3Y")
    assertEquals((p \ "routing_key").as[String], "R0UT1NGK3Y")
    assertEquals((p \ "event_action").as[String], "trigger")
    assertEquals((p \ "dedup_key").as[String], "r1:incident:ip:203.0.113.7")
    assertEquals((p \ "payload" \ "severity").as[String], "error")
    assertEquals(AlertPayloads.pagerduty(sample.copy(severity = 4), "k").\("payload").\("severity").as[String], "critical")
    assertEquals(AlertChannel("pagerduty").endpoint, "https://events.pagerduty.com/v2/enqueue")
  }

  test("a test alert goes through the channel as it is") {
    val f        = new Fixture(Seq.empty)
    val r        = rule("incident", _.copy(channel = AlertChannel("slack", "https://hooks.slack.com/services/x")))
    val delivery = Await.result(f.engine.send(r, f.engine.testAlert(r)), 5.seconds)
    assert(delivery.ok)
    assertEquals(f.sender.sent.asScala.head._1, "https://hooks.slack.com/services/x")
    assertEquals(Await.result(f.engine.send(r.copy(channel = AlertChannel("webhook", "")), f.engine.testAlert(r)), 5.seconds).status, 0)
  }

  test("an alert rule survives a round trip, and unknown values fall back") {
    val r = rule("burst", _.copy(categories = Seq("waf"), routes = Seq("r"), channel = AlertChannel("teams", "https://x", headers = Map("a" -> "b"))))
    assertEquals(AlertRule.format.reads(r.json).get, r)
    val odd = AlertRule.format.reads(Json.obj("id" -> "x", "name" -> "x", "trigger" -> "nope", "channel" -> Json.obj("kind" -> "fax"))).get
    assertEquals(odd.trigger, "incident")
    assertEquals(odd.channel.kind, "slack")
  }

  // ---------------------------------------------------------------- OCSF

  test("a decision is an OCSF Detection Finding, with what was done in both vocabularies") {
    def ocsf(action: String, enforced: Boolean, severity: Int) = Ocsf.decision(
      "e1", 1000L, "waf", action, enforced, severity, ClientIdentity("203.0.113.7", apikey = Some("key_1")), 90, Seq("waf:942100"),
      JsArray(), Some("route_1"), Some("Public API"), Some("inc_1"), "SQL injection", "node-1"
    )
    val blocked = ocsf("deny", enforced = true, 4)
    assertEquals((blocked \ "class_uid").as[Int], 2004)
    assertEquals((blocked \ "type_uid").as[Int], 200401)
    assertEquals((blocked \ "severity_id").as[Int], 5)
    assertEquals(((blocked \ "action_id").as[Int], (blocked \ "disposition_id").as[Int]), (2, 2))
    assertEquals((blocked \ "src_endpoint" \ "ip").as[String], "203.0.113.7")
    assertEquals((blocked \ "actor" \ "user" \ "credential_uid").as[String], "key_1")
    assertEquals((blocked \ "metadata" \ "correlation_uid").as[String], "inc_1")
    val observed = ocsf("log", enforced = false, 1)
    assertEquals(((observed \ "action_id").as[Int], (observed \ "disposition_id").as[Int], (observed \ "severity_id").as[Int]), (3, 15, 2))
    val masked = ocsf("mask", enforced = true, 2)
    assertEquals(((masked \ "action_id").as[Int], (masked \ "disposition_id").as[Int]), (4, 11))
  }

  test("an alert is an OCSF Detection Finding too") {
    val o = Ocsf.alert(sample)
    assertEquals((o \ "class_uid").as[Int], 2004)
    assertEquals((o \ "disposition_id").as[Int], 19)
    assertEquals((o \ "finding_info" \ "title").as[String], sample.title)
    assertEquals((o \ "finding_info" \ "analytic" \ "uid").as[String], "r1")
    assertEquals((o \ "severity_id").as[Int], 4)
  }
}
