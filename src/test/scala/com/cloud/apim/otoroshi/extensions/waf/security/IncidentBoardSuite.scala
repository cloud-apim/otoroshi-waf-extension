package com.cloud.apim.otoroshi.extensions.waf.security

import play.api.Logger
import play.api.libs.json.{JsArray, Json}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class AllowlistSuite extends munit.FunSuite {

  private given ExecutionContext        = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val ip = IdentityRef("ip", "1.2.3.4")

  private def fixture(): (AllowlistStore, BanStore) = {
    val shared    = new InMemorySharedStateStore()
    val allowlist = new AllowlistStore("allow", shared, Logger("test"))
    val bans      = new BanStore("bans", shared, "node-1", Logger("test"), allowlist.check(_: IdentityRef))
    (allowlist, bans)
  }

  test("an allowlisted identity cannot be banned, and the refusal says why") {
    val (allowlist, bans) = fixture()
    await(allowlist.allow(ip, "partner integration suite", "ops@example.com"))
    val outcome = await(bans.ban(ip, 1.hour, "scanner"))
    outcome match {
      case BanOutcome.Allowlisted(entry) => assertEquals(entry.reason, "partner integration suite")
      case other                         => fail(s"expected a refusal, got $other")
    }
    assertEquals(bans.check(ip), None, "nothing may be written when the ban is refused")
  }

  test("the refusal holds however the ban is asked for") {
    // the whole reason the check lives in BanStore rather than at each call site
    val (allowlist, bans) = fixture()
    await(allowlist.allow(ip, "monitoring probe", "ops@example.com"))
    val counter = new Fail2BanCounter("f2b", new InMemorySharedStateStore(), bans, Logger("test"))
    val outcome = await(counter.fail("scope", ip, 1.minute, 1, 1.hour, "401s"))
    assertEquals(outcome.banned, false)
    assertEquals(outcome.allowlisted.map(_.reason), Some("monitoring probe"))
  }

  test("a bounded entry stops protecting once it lapses") {
    val (allowlist, bans) = fixture()
    await(allowlist.allow(ip, "while we tune", "ops@example.com", Some(System.currentTimeMillis() + 30L)))
    assert(await(bans.ban(ip, 1.hour, "scanner")).entry.isEmpty)
    Thread.sleep(60L)
    assert(await(bans.ban(ip, 1.hour, "scanner")).entry.isDefined, "an expired entry protects nobody")
  }

  test("a permanent entry says so, a bounded one carries its end") {
    val (allowlist, _) = fixture()
    val forever        = await(allowlist.allow(ip, "office", "ops@example.com"))
    val until          = await(allowlist.allow(IdentityRef("ip", "9.9.9.9"), "for now", "ops@example.com", Some(1L)))
    assert(forever.permanent)
    assert(!until.permanent)
  }

  test("entries reach the other nodes on their next refresh") {
    val shared = new InMemorySharedStateStore()
    val nodeA  = new AllowlistStore("allow", shared, Logger("test"))
    val nodeB  = new AllowlistStore("allow", shared, Logger("test"))
    await(nodeA.allow(ip, "partner", "ops@example.com"))
    assertEquals(nodeB.check(ip), None, "propagation is by refresh, not instant")
    assertEquals(await(nodeB.refresh()), 1)
    assertEquals(nodeB.check(ip).map(_.reason), Some("partner"))
  }

  test("refresh prunes what has lapsed") {
    val shared = new InMemorySharedStateStore()
    val store  = new AllowlistStore("allow", shared, Logger("test"))
    await(store.allow(ip, "gone", "ops@example.com", Some(System.currentTimeMillis() - 1L)))
    await(store.allow(IdentityRef("ip", "9.9.9.9"), "stays", "ops@example.com"))
    assertEquals(await(store.refresh()), 1)
    assertEquals(store.all.map(_.ref.value), Seq("9.9.9.9"))
  }

  test("any of a caller's identities can carry the entry") {
    val (allowlist, _) = fixture()
    await(allowlist.allow(IdentityRef("apikey", "k1"), "partner", "ops@example.com"))
    assert(allowlist.check(ClientIdentity(ip = "9.9.9.9", apikey = Some("k1"))).isDefined)
    assertEquals(allowlist.check(ClientIdentity(ip = "9.9.9.9")), None)
  }
}

class BanLifecycleSuite extends munit.FunSuite {

  private given ExecutionContext        = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)
  private def store()                   = new BanStore("bans", new InMemorySharedStateStore(), "node-1", Logger("test"))

  private val ip = IdentityRef("ip", "1.2.3.4")

  test("extending measures from the current end, not from now") {
    val bans = store()
    val issued = await(bans.ban(ip, 1.hour, "scanner")).entry.get
    val extended = await(bans.extend(ip, 1.hour, "ops@example.com")).get
    val added = extended.until - issued.until
    assert(
      math.abs(added - 1.hour.toMillis) < 2000L,
      s"an hour added to a ban with an hour left must leave two hours, not one — added ${added}ms"
    )
    assertEquals(extended.lastAction, Some("extended"))
    assertEquals(extended.lastActionBy, Some("ops@example.com"))
  }

  test("there is nothing to extend once a ban has lapsed") {
    val bans = store()
    await(bans.ban(ip, 1.milli, "scanner"))
    Thread.sleep(30L)
    assertEquals(
      await(bans.extend(ip, 1.hour, "ops@example.com")),
      None,
      "extending a lapsed ban must not quietly mint a fresh one"
    )
  }

  test("an operator's ban records the operator rather than the node") {
    val bans = store()
    val entry = await(bans.ban(ip, 1.hour, "manual", issuedBy = Some("ops@example.com"))).entry.get
    assertEquals(entry.issuedBy, "ops@example.com")
  }

  test("the evidence survives a round trip through the shared state") {
    val shared = new InMemorySharedStateStore()
    val nodeA  = new BanStore("bans", shared, "a", Logger("test"))
    val nodeB  = new BanStore("bans", shared, "b", Logger("test"))
    val events = Seq(
      IncidentEvent(1000L, "waf", "deny", 90, true, Some("route_1"), Some("public-api"), "sqli in ARGS:q"),
      IncidentEvent(900L, "reputation", "log", 30, false, None, None, "listed on firehol1")
    )
    await(nodeA.ban(ip, 1.hour, "accumulated", timeline = events, signals = JsArray(Seq(Json.obj("source" -> "waf")))))
    await(nodeB.refresh())
    val seen = nodeB.check(ip).get
    assertEquals(seen.timeline.map(_.message), events.map(_.message))
    assertEquals(seen.timeline.head.routeName, Some("public-api"))
    assertEquals((seen.signals \\ "source").map(_.as[String]), Seq("waf"))
  }
}

class IncidentTimelineSuite extends munit.FunSuite {

  private def correlator() = new IncidentCorrelator(() => 30.minutes, node = Some("node-1"))
  private val ip           = IdentityRef("ip", "1.2.3.4")

  test("the timeline keeps the newest events and is bounded") {
    val c = correlator()
    (1 to IncidentCorrelator.maxTimeline + 10).foreach(i => c.record(ip, "waf", i, Seq("waf:match"), "log", s"event $i"))
    val incident = c.byKey(ip.key).get
    assertEquals(incident.count, IncidentCorrelator.maxTimeline + 10, "every event still counts")
    assertEquals(incident.timeline.size, IncidentCorrelator.maxTimeline, "only the last few are kept verbatim")
    assertEquals(incident.timeline.head.message, s"event ${IncidentCorrelator.maxTimeline + 10}")
  }

  test("enforcement is counted apart from observation") {
    val c = correlator()
    c.record(ip, "waf", 50, Seq.empty, "log", "observed", enforced = false)
    c.record(ip, "waf", 90, Seq.empty, "deny", "blocked", enforced = true)
    val incident = c.byKey(ip.key).get
    assertEquals(incident.count, 2)
    assertEquals(incident.enforcedCount, 1, "a dry-run rollout only ever asks this question")
  }

  test("the routes a caller touched are collected") {
    val c = correlator()
    c.record(ip, "waf", 50, Seq.empty, "log", "a", routeId = Some("r1"), routeName = Some("public-api"))
    c.record(ip, "waf", 50, Seq.empty, "log", "b", routeId = Some("r2"), routeName = Some("partner-api"))
    assertEquals(c.byKey(ip.key).get.routes, Set("public-api", "partner-api"))
  }

  test("an incident round-trips through json") {
    val c = correlator()
    c.record(ip, "waf", 90, Seq("waf:match"), "deny", "sqli", enforced = true, routeName = Some("public-api"))
    val original = c.byKey(ip.key).get
    val restored = Incident.read(original.json).get
    assertEquals(restored.count, original.count)
    assertEquals(restored.timeline.map(_.message), original.timeline.map(_.message))
    assertEquals(restored.node, Some("node-1"))
    assertEquals(restored.routes, original.routes)
  }
}

class IncidentBoardSuite extends munit.FunSuite {

  private given ExecutionContext        = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val ip = IdentityRef("ip", "1.2.3.4")

  private class Node(shared: SharedStateStore, name: String) {
    val allowlist  = new AllowlistStore(s"allow", shared, Logger("test"))
    val bans       = new BanStore("bans", shared, name, Logger("test"), allowlist.check(_: IdentityRef))
    val correlator = new IncidentCorrelator(() => 30.minutes, node = Some(name))
    val board      = new IncidentBoard("px", shared, name, correlator, bans, allowlist, Logger("test"))
  }

  test("two nodes' view of the same caller is one incident, with both counts") {
    val shared = new InMemorySharedStateStore()
    val a      = new Node(shared, "a")
    val b      = new Node(shared, "b")
    (1 to 3).foreach(i => a.correlator.record(ip, "waf", 50, Seq("waf:match"), "log", s"a$i"))
    (1 to 2).foreach(i => b.correlator.record(ip, "reputation", 80, Seq("rep"), "deny", s"b$i", enforced = true))
    await(a.board.publish())
    await(b.board.publish())
    val views = await(a.board.all())
    assertEquals(views.size, 1, "one caller is one incident, however many nodes saw them")
    val view = views.head
    assertEquals(view.incident.count, 5)
    assertEquals(view.incident.enforcedCount, 2)
    assertEquals(view.incident.categories, Set("waf", "reputation"))
    assertEquals(view.nodes, Set("a", "b"))
    assertEquals(view.incident.timeline.size, 5, "the merged timeline is the only cluster-wide ordering")
  }

  test("a node's own published snapshot never double-counts its live buffer") {
    val shared = new InMemorySharedStateStore()
    val a      = new Node(shared, "a")
    a.correlator.record(ip, "waf", 50, Seq.empty, "log", "once")
    await(a.board.publish())
    await(a.board.publish())
    assertEquals(await(a.board.all()).head.incident.count, 1)
  }

  test("a node that stopped publishing is dropped rather than shown as current") {
    val shared = new InMemorySharedStateStore()
    val a      = new Node(shared, "a")
    val stale  = Json.obj(
      "at"        -> (System.currentTimeMillis() - IncidentBoard.staleAfter.toMillis - 1000L),
      "incidents" -> JsArray(
        Seq(
          Incident(
            id = "old",
            ref = ip,
            firstSeen = 1L,
            lastSeen = 2L,
            count = 999,
            maxScore = 100,
            categories = Set("waf"),
            tags = Set.empty,
            actions = Set("deny"),
            lastMessage = "from a node that died",
            node = Some("ghost")
          ).json
        )
      )
    )
    await(shared.hset("px:incidents", "ghost-1", Json.stringify(stale)))
    assertEquals(await(a.board.all()), Seq.empty, "a dead node's last words are not the current state")
  }

  test("a resolved caller who comes back reopens") {
    val shared = new InMemorySharedStateStore()
    val a      = new Node(shared, "a")
    a.correlator.record(ip, "waf", 50, Seq.empty, "log", "first")
    await(a.board.setState(ip.key, IncidentState.Resolved, "ops@example.com", Some("false positive")))
    assertEquals(await(a.board.all()).head.effectiveState, IncidentState.Resolved)
    Thread.sleep(10L)
    // the grace absorbs clock skew between the node that resolved and the node that observed
    a.correlator.record(ip.copy(), "waf", 50, Seq.empty, "log", "and again")
    val reopened = a.correlator.byKey(ip.key).get.copy(lastSeen = System.currentTimeMillis() + IncidentBoard.reopenGraceMs + 1000L)
    val view     = IncidentView(reopened, Set("a"), Some(IncidentState(ip.key, IncidentState.Resolved, "ops", 0L, None)), None, None)
    assertEquals(view.effectiveState, "reopened")
  }

  test("acknowledging is not undone by more of the same activity") {
    // you acknowledged it *because* it is ongoing — reopening on every event would be noise
    val view = IncidentView(
      Incident("i", ip, 0L, System.currentTimeMillis(), 10, 50, Set("waf"), Set.empty, Set("log"), "still going"),
      Set("a"),
      Some(IncidentState(ip.key, IncidentState.Acknowledged, "ops", 0L, None)),
      None,
      None
    )
    assertEquals(view.effectiveState, IncidentState.Acknowledged)
  }

  test("moving an incident back to open clears the row rather than storing a third value") {
    val shared = new InMemorySharedStateStore()
    val a      = new Node(shared, "a")
    a.correlator.record(ip, "waf", 50, Seq.empty, "log", "x")
    await(a.board.setState(ip.key, IncidentState.Acknowledged, "ops@example.com", None))
    assert(await(a.board.all()).head.state.isDefined)
    await(a.board.setState(ip.key, IncidentState.Open, "ops@example.com", None))
    val view = await(a.board.all()).head
    assertEquals(view.state, None)
    assertEquals(view.effectiveState, IncidentState.Open)
  }

  test("the worst state sorts first") {
    val ordered = Seq(IncidentState.Resolved, IncidentState.Acknowledged, "reopened", IncidentState.Open)
      .sortBy(IncidentBoard.stateOrder)
    assertEquals(ordered, Seq("reopened", IncidentState.Open, IncidentState.Acknowledged, IncidentState.Resolved))
  }

  test("what the fabric currently holds against a caller travels with the incident") {
    val shared = new InMemorySharedStateStore()
    val a      = new Node(shared, "a")
    a.correlator.record(ip, "waf", 90, Seq.empty, "deny", "sqli", enforced = true)
    await(a.bans.ban(ip, 1.hour, "sqli"))
    val view = await(a.board.all()).head
    assert(view.ban.isDefined, "the console must not make an operator cross-reference two lists")
    assertEquals((view.json \ "banned").as[Boolean], true)
    assertEquals((view.json \ "state").as[String], IncidentState.Open)
  }

  test("only a recognised state can be set") {
    assertEquals(IncidentState.parse("Resolved"), Some(IncidentState.Resolved))
    assertEquals(IncidentState.parse("closed"), None)
    assertEquals(IncidentState.parse(""), None)
  }
}
