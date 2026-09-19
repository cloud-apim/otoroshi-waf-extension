package com.cloud.apim.otoroshi.extensions.waf.security

import otoroshi.utils.TypedMap
import play.api.Logger
import play.api.libs.json.{JsArray, Json}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class ClientIdentitySuite extends munit.FunSuite {

  test("refs are ordered most specific first") {
    val id = ClientIdentity(ip = "1.2.3.4", apikey = Some("k1"), user = Some("u@x.io"))
    assertEquals(id.refs.map(_.kind), Seq("apikey", "user", "ip"))
    assertEquals(id.primary, IdentityRef("apikey", "k1"))
  }

  test("an anonymous caller is identified by address alone") {
    val id = ClientIdentity(ip = "1.2.3.4")
    assertEquals(id.refs, Seq(IdentityRef("ip", "1.2.3.4")))
    assertEquals(id.primary.key, "ip:1.2.3.4")
  }

  test("refs round-trip through their key form") {
    assertEquals(IdentityRef.parse("apikey:abc"), Some(IdentityRef("apikey", "abc")))
    assertEquals(IdentityRef.parse("ip:2001:db8::1"), Some(IdentityRef("ip", "2001:db8::1")))
    assertEquals(IdentityRef.parse("nonsense"), None)
    assertEquals(IdentityRef.parse(""), None)
  }
}

class ThreatScoreSuite extends munit.FunSuite {

  private val id = ClientIdentity(ip = "1.2.3.4")
  private def sig(weight: Int, tag: String = "t", source: String = "s", confidence: Double = 1.0) =
    ThreatSignal(source = source, kind = "k", weight = weight, tag = tag, confidence = confidence)

  test("signals accumulate and cap at 100") {
    assertEquals(ThreatScore(id, List(sig(30), sig(40))).score, 70)
    assertEquals(ThreatScore(id, List(sig(80), sig(80))).score, 100)
    assertEquals(ThreatScore.empty(id).score, 0)
  }

  test("confidence scales a signal down instead of rounding it up to a certainty") {
    assertEquals(sig(80, confidence = 0.5).effectiveWeight, 40)
    assertEquals(sig(80, confidence = 0.0).effectiveWeight, 0)
    assertEquals(ThreatScore(id, List(sig(80, confidence = 0.25))).score, 20)
  }

  test("confidence is clamped, a detector cannot inflate its own weight") {
    assertEquals(sig(50, confidence = 3.0).effectiveWeight, 50)
    assertEquals(sig(50, confidence = -1.0).effectiveWeight, 0)
  }

  test("tags and sources are deduplicated for reporting") {
    val score = ThreatScore(id, List(sig(10, "a", "s1"), sig(10, "a", "s1"), sig(10, "b", "s2")))
    assertEquals(score.tags, Seq("a", "b"))
    assertEquals(score.sources, Seq("s1", "s2"))
  }
}

class ThreatBusSuite extends munit.FunSuite {

  private val anonymous = ClientIdentity(ip = "1.2.3.4")
  private def sig(weight: Int) = ThreatSignal("s", "k", weight, "t")

  test("start creates an accumulator and is idempotent") {
    val attrs = TypedMap.empty
    ThreatBus.start(attrs, anonymous)
    ThreatBus.start(attrs, anonymous)
    assertEquals(ThreatBus.scoreOf(attrs), 0)
    assert(ThreatBus.current(attrs).isDefined)
  }

  test("contributions from several detectors add up") {
    val attrs = TypedMap.empty
    ThreatBus.contribute(attrs, anonymous, sig(20))
    ThreatBus.contribute(attrs, anonymous, sig(35))
    assertEquals(ThreatBus.scoreOf(attrs), 55)
    assertEquals(ThreatBus.current(attrs).get.signals.size, 2)
  }

  test("contributing without an explicit start still works") {
    val attrs = TypedMap.empty
    ThreatBus.contribute(attrs, anonymous, sig(10))
    assertEquals(ThreatBus.scoreOf(attrs), 10)
  }

  test("learning the apikey later keeps the signals already contributed") {
    val attrs = TypedMap.empty
    ThreatBus.contribute(attrs, anonymous, sig(40))
    val richer = ClientIdentity(ip = "1.2.3.4", apikey = Some("k1"))
    ThreatBus.start(attrs, richer)
    assertEquals(ThreatBus.scoreOf(attrs), 40, "signals must survive an identity refresh")
    assertEquals(ThreatBus.identity(attrs).get.primary.key, "apikey:k1")
  }

  test("an untouched request has no score") {
    assertEquals(ThreatBus.scoreOf(TypedMap.empty), 0)
    assertEquals(ThreatBus.current(TypedMap.empty), None)
  }
}

class ThreatPolicySuite extends munit.FunSuite {

  private val policy = ThreatPolicy(id = "p", name = "p", dryRun = false)

  test("the highest matching tier wins, whatever the order they are listed in") {
    val shuffled = policy.copy(tiers =
      Seq(
        ThreatTier(90, "ban"),
        ThreatTier(40, "log"),
        ThreatTier(70, "tarpit")
      )
    )
    assertEquals(shuffled.tierFor(95).map(_._2.action), Some("ban"))
    assertEquals(shuffled.tierFor(75).map(_._2.action), Some("tarpit"))
    assertEquals(shuffled.tierFor(40).map(_._2.action), Some("log"))
  }

  test("a score below every tier matches nothing") {
    assertEquals(policy.tierFor(10), None)
    assertEquals(policy.tierFor(39), None)
    assertEquals(policy.tierFor(40).map(_._2.action), Some("log"))
  }

  test("exemptions bypass scoring entirely") {
    val exempt = policy.copy(exemptions = Seq("10.0.0.0/8", "203.0.113.9"))
    assert(exempt.isExempt("10.1.2.3"))
    assert(exempt.isExempt("203.0.113.9"))
    assert(!exempt.isExempt("203.0.113.10"))
    assert(!policy.isExempt("10.1.2.3"), "an empty exemption list exempts nobody")
  }

  test("auto ban targets the most specific identity, an explicit kind targets that one") {
    val id = ClientIdentity(ip = "1.2.3.4", apikey = Some("k1"))
    assertEquals(policy.banRef(id).map(_.key), Some("apikey:k1"))
    assertEquals(policy.copy(banIdentity = "ip").banRef(id).map(_.key), Some("ip:1.2.3.4"))
    // asking for a kind the caller does not have falls back rather than failing to ban at all
    assertEquals(policy.copy(banIdentity = "fingerprint").banRef(id).map(_.key), Some("apikey:k1"))
  }

  test("a new policy is dry-run, so nothing is enforced by accident") {
    val fresh = ThreatPolicy.format.reads(Json.obj("id" -> "x", "name" -> "x")).get
    assert(fresh.dryRun)
    assertEquals(fresh.tiers, ThreatTier.default)
  }

  test("tiers round-trip through json") {
    val original = policy.copy(tiers = Seq(ThreatTier(55, "tarpit", 1234L, 60L, 429)))
    val back     = ThreatPolicy.format.reads(ThreatPolicy.format.writes(original)).get
    assertEquals(back.tiers, original.tiers)
    assertEquals(back.tiers.head.resolvedAction, ThreatAction.Tarpit)
  }

  test("an unknown action degrades to log rather than to deny") {
    assertEquals(ThreatTier(10, "obliterate").resolvedAction, ThreatAction.Log)
  }
}

/**
 * The policy — not the rule engine — decides what a WAF verdict is worth to the score.
 *
 * These pin the fix for a real gap: a WAF that reached a *block* used to contribute a flat 50, which
 * a default deny tier of 90 never reached, so a payload the engine itself judged block-worthy only
 * ever got logged. The policy now owns two weights and an optional decisive flag.
 */
class ThreatPolicyWafWeightingSuite extends munit.FunSuite {

  private val id = ClientIdentity(ip = "1.2.3.4")
  private def score(signals: ThreatSignal*) = ThreatScore(id, signals.toList)
  // the engine always emits at 50/20; the point is that the policy re-weighs it, so the emitted
  // weight here is deliberately not the number the assertions expect
  private def waf(tag: String, confidence: Double = 1.0) =
    ThreatSignal(source = "waf.seclang", kind = "payload", weight = 50, tag = tag, confidence = confidence)
  private def other(weight: Int, tag: String = "reputation:x") =
    ThreatSignal(source = "ip.reputation", kind = "reputation", weight = weight, tag = tag)

  private val policy = ThreatPolicy(id = "p", name = "p", dryRun = false)

  test("a new policy weighs a WAF block at 90 and a WAF match at 45") {
    assertEquals(policy.wafBlockWeight, 90)
    assertEquals(policy.wafMatchWeight, 45)
    assert(!policy.wafBlockDecisive)
  }

  test("the policy re-weights the WAF verdicts by tag, not by the emitted weight") {
    assertEquals(policy.effectiveScore(score(waf(ThreatSignal.WafBlocked))), 90)
    assertEquals(policy.effectiveScore(score(waf(ThreatSignal.WafMatch))), 45)
  }

  test("a lone WAF block reaches ban, a lone WAF match only logs") {
    assertEquals(policy.tierFor(policy.effectiveScore(score(waf(ThreatSignal.WafBlocked)))).map(_._2.action), Some("ban"))
    assertEquals(policy.tierFor(policy.effectiveScore(score(waf(ThreatSignal.WafMatch)))).map(_._2.action), Some("log"))
  }

  test("a WAF match plus a corroborating detector escalates — the point of a moderate match") {
    // 45 + 50 = 95 -> ban ; 45 + 30 = 75 -> tarpit
    assertEquals(policy.tierFor(policy.effectiveScore(score(waf(ThreatSignal.WafMatch), other(50)))).map(_._2.action), Some("ban"))
    assertEquals(policy.tierFor(policy.effectiveScore(score(waf(ThreatSignal.WafMatch), other(30)))).map(_._2.action), Some("tarpit"))
  }

  test("non-WAF signals keep the weight they were published with") {
    assertEquals(policy.effectiveScore(score(other(30), other(25, "asn:hosting"))), 55)
  }

  test("the WAF weight is scaled by the signal's confidence, like any other") {
    assertEquals(policy.effectiveScore(score(waf(ThreatSignal.WafBlocked, confidence = 0.5))), 45)
  }

  test("a decisive WAF block reaches the top tier whatever the arithmetic") {
    val decisive = policy.copy(wafBlockWeight = 10, wafBlockDecisive = true)
    assertEquals(decisive.effectiveScore(score(waf(ThreatSignal.WafBlocked))), 100, "even weighed at 10, a block is taken at the ceiling")
    assertEquals(decisive.tierFor(decisive.effectiveScore(score(waf(ThreatSignal.WafBlocked)))).map(_._2.action), Some("ban"))
    assert(decisive.isDecisiveBlock(score(waf(ThreatSignal.WafBlocked))))
  }

  test("decisive only fires on a block, a lone match is still weighed normally") {
    val decisive = policy.copy(wafBlockDecisive = true)
    assert(!decisive.isDecisiveBlock(score(waf(ThreatSignal.WafMatch))))
    assertEquals(decisive.effectiveScore(score(waf(ThreatSignal.WafMatch))), 45)
  }

  test("without decisive, a block under the ban tier does not ban on its own") {
    val soft = policy.copy(wafBlockWeight = 50) // the pre-fabric default, still respected when stored
    assertEquals(soft.effectiveScore(score(waf(ThreatSignal.WafBlocked))), 50)
    assertEquals(soft.tierFor(50).map(_._2.action), Some("log"))
  }

  test("the two weights and the decisive flag round-trip through json") {
    val original = policy.copy(wafMatchWeight = 33, wafBlockWeight = 88, wafBlockDecisive = true)
    val back     = ThreatPolicy.format.reads(ThreatPolicy.format.writes(original)).get
    assertEquals(back.wafMatchWeight, 33)
    assertEquals(back.wafBlockWeight, 88)
    assert(back.wafBlockDecisive)
  }

  test("a policy stored before the match knob keeps its block weight and defaults the rest") {
    val stored = Json.obj("id" -> "old", "name" -> "old", "waf_block_weight" -> 50)
    val back   = ThreatPolicy.format.reads(stored).get
    assertEquals(back.wafBlockWeight, 50, "a stored weight is never silently changed")
    assertEquals(back.wafMatchWeight, 45, "the new knob reads its default")
    assert(!back.wafBlockDecisive)
  }

  test("the advisory helpers say what a lone verdict reaches, and whether anything denies") {
    assertEquals(policy.loneBlockTier.map(_.action), Some("ban"))
    assertEquals(policy.loneMatchTier.map(_.action), Some("log"))
    assert(policy.hasDenyingTier)
    // a policy whose only tier logs cannot refuse, and even a decisive block still only logs
    val toothless = policy.copy(tiers = Seq(ThreatTier(10, "log")), wafBlockDecisive = true)
    assert(!toothless.hasDenyingTier)
    assertEquals(toothless.loneBlockTier.map(_.action), Some("log"))
  }
}

class ThreatDecisionSuite extends munit.FunSuite {

  test("only a denying action outside dry-run is enforced") {
    assert(ThreatDecision(ThreatAction.Deny, 95, Some(2), dryRun = false, "").enforced)
    assert(ThreatDecision(ThreatAction.Ban, 95, Some(2), dryRun = false, "").enforced)
    assert(!ThreatDecision(ThreatAction.Deny, 95, Some(2), dryRun = true, "").enforced)
    assert(!ThreatDecision(ThreatAction.Log, 95, Some(0), dryRun = false, "").enforced)
    assert(!ThreatDecision(ThreatAction.Tarpit, 95, Some(1), dryRun = false, "").enforced)
  }

  test("actions parse case-insensitively and reject nonsense") {
    assertEquals(ThreatAction.parse("BAN"), Some(ThreatAction.Ban))
    assertEquals(ThreatAction.parse(" tarpit "), Some(ThreatAction.Tarpit))
    assertEquals(ThreatAction.parse("CHALLENGE"), Some(ThreatAction.Challenge))
    assertEquals(ThreatAction.parse("throttle"), None, "throttle has no module behind it yet")
    assertEquals(ThreatAction.parse("obliterate"), None)
  }
}

class BanStoreSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)
  private def store() = new BanStore("bans", new InMemorySharedStateStore(), "node-1", Logger("test"))

  private val alice = ClientIdentity(ip = "1.2.3.4", apikey = Some("k1"))

  test("a ban is enforced on the issuing node immediately, before any refresh") {
    val bans = store()
    assertEquals(bans.check(alice), None)
    await(bans.ban(IdentityRef("ip", "1.2.3.4"), 1.hour, "test"))
    assert(bans.check(alice).isDefined, "the issuing node must not wait for a refresh round trip")
  }

  test("any of the caller's identities can carry the ban") {
    val bans = store()
    await(bans.ban(IdentityRef("apikey", "k1"), 1.hour, "test"))
    assert(bans.check(alice).isDefined)
    assert(bans.check(ClientIdentity(ip = "9.9.9.9", apikey = Some("k1"))).isDefined)
    assert(bans.check(ClientIdentity(ip = "9.9.9.9")).isEmpty)
  }

  test("an expired ban stops matching without waiting for a refresh") {
    val bans = store()
    await(bans.ban(IdentityRef("ip", "1.2.3.4"), 1.milli, "test"))
    Thread.sleep(30L)
    assertEquals(bans.check(alice), None)
  }

  test("unban removes it locally and from the shared state") {
    val bans = store()
    await(bans.ban(IdentityRef("ip", "1.2.3.4"), 1.hour, "test"))
    assert(await(bans.unban(IdentityRef("ip", "1.2.3.4"))))
    assertEquals(bans.check(alice), None)
    assertEquals(await(bans.refresh()), 0, "and it must not come back on the next refresh")
  }

  test("another node picks the ban up on refresh") {
    val shared = new InMemorySharedStateStore()
    val nodeA  = new BanStore("bans", shared, "a", Logger("test"))
    val nodeB  = new BanStore("bans", shared, "b", Logger("test"))
    await(nodeA.ban(IdentityRef("ip", "1.2.3.4"), 1.hour, "test"))
    assertEquals(nodeB.check(alice), None, "not yet — propagation is by refresh, not instant")
    assertEquals(await(nodeB.refresh()), 1)
    assert(nodeB.check(alice).isDefined)
    assertEquals(nodeB.check(alice).get.issuedBy, "a", "the issuing node is recorded")
  }

  test("refresh prunes what has expired") {
    val bans = store()
    await(bans.ban(IdentityRef("ip", "1.1.1.1"), 1.milli, "gone"))
    await(bans.ban(IdentityRef("ip", "2.2.2.2"), 1.hour, "stays"))
    Thread.sleep(30L)
    assertEquals(await(bans.refresh()), 1)
    assertEquals(bans.all.map(_.ref.value), Seq("2.2.2.2"))
  }

  test("a failing shared state never empties the local list") {
    val broken = new InMemorySharedStateStore() {
      override def hgetall(key: String): Future[Map[String, String]] =
        Future.failed(new RuntimeException("redis is down"))
    }
    val bans = new BanStore("bans", broken, "node-1", Logger("test"))
    await(bans.ban(IdentityRef("ip", "1.2.3.4"), 1.hour, "test"))
    assertEquals(await(bans.refresh()), 1, "a failed refresh keeps what we already enforce")
    assert(bans.check(alice).isDefined)
    assert(bans.lastError.isDefined, "and the failure is visible")
  }

  test("unbanAll clears everything") {
    val bans = store()
    await(bans.ban(IdentityRef("ip", "1.1.1.1"), 1.hour, "x"))
    await(bans.ban(IdentityRef("ip", "2.2.2.2"), 1.hour, "x"))
    assertEquals(await(bans.unbanAll()), 2L)
    assertEquals(bans.size, 0)
  }

  test("entries survive a json round trip") {
    val entry = BanEntry(IdentityRef("ip", "1.2.3.4"), "why", Seq("a"), 90, 1L, 2L, "node", JsArray(Seq.empty))
    assertEquals(BanEntry.read(entry.json), Some(entry))
  }
}

class ThreatLedgerSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private def fixture(settings: LedgerSettings) = {
    val shared = new InMemorySharedStateStore()
    val bans   = new BanStore("bans", shared, "node-1", Logger("test"))
    (bans, new ThreatLedger("ledger", shared, bans, () => settings, Logger("test")))
  }

  private val ref = IdentityRef("ip", "1.2.3.4")

  test("weight accumulates across requests") {
    val (_, ledger) = fixture(LedgerSettings(banThreshold = 1000))
    assertEquals(await(ledger.record(ref, 30, "a")), 30L)
    assertEquals(await(ledger.record(ref, 25, "b")), 55L)
    assertEquals(await(ledger.scoreOf(ref)), 55L)
  }

  test("crossing the threshold promotes the caller to a ban") {
    val (bans, ledger) = fixture(LedgerSettings(banThreshold = 100, banDuration = 1.hour))
    await(ledger.record(ref, 60, "first"))
    assertEquals(bans.check(ref), None)
    await(ledger.record(ref, 45, "second"))
    val ban = bans.check(ref)
    assert(ban.isDefined, "105 >= 100 should ban")
    assert(ban.get.reason.contains("accumulated"))
  }

  test("the total is consumed by the ban, so the next hit does not re-ban instantly") {
    val (_, ledger) = fixture(LedgerSettings(banThreshold = 50))
    await(ledger.record(ref, 60, "x"))
    assertEquals(await(ledger.scoreOf(ref)), 0L)
  }

  test("a disabled ledger records nothing") {
    val (bans, ledger) = fixture(LedgerSettings(enabled = false, banThreshold = 1))
    assertEquals(await(ledger.record(ref, 500, "x")), 0L)
    assertEquals(bans.check(ref), None)
  }

  test("zero and negative weights are ignored") {
    val (_, ledger) = fixture(LedgerSettings())
    assertEquals(await(ledger.record(ref, 0, "x")), 0L)
    assertEquals(await(ledger.record(ref, -10, "x")), 0L)
  }

  test("only the most specific identity is charged, never both") {
    val (_, ledger) = fixture(LedgerSettings(banThreshold = 1000))
    val id = ClientIdentity(ip = "1.2.3.4", apikey = Some("k1"))
    await(ledger.recordAll(id, 40, "x", Seq.empty))
    assertEquals(await(ledger.scoreOf(IdentityRef("apikey", "k1"))), 40L)
    assertEquals(await(ledger.scoreOf(IdentityRef("ip", "1.2.3.4"))), 0L, "the shared address must not be charged")
  }

  test("forgetting an identity clears its total") {
    val (_, ledger) = fixture(LedgerSettings(banThreshold = 1000))
    await(ledger.record(ref, 40, "x"))
    await(ledger.forget(ref))
    assertEquals(await(ledger.scoreOf(ref)), 0L)
  }
}

class IncidentCorrelatorSuite extends munit.FunSuite {

  private val ref = IdentityRef("ip", "1.2.3.4")

  test("repeated events from one caller collapse into a single incident") {
    val c = new IncidentCorrelator(() => 30.minutes)
    val a = c.record(ref, "waf", 50, Seq("t1"), "log", "first")
    val b = c.record(ref, "reputation", 80, Seq("t2"), "deny", "second")
    assertEquals(a.id, b.id, "same caller, same window, one incident")
    assertEquals(b.count, 2)
    assertEquals(b.maxScore, 80)
    assertEquals(b.categories, Set("waf", "reputation"))
    assertEquals(b.tags, Set("t1", "t2"))
    assertEquals(b.actions, Set("log", "deny"))
    assertEquals(b.lastMessage, "second")
    assertEquals(c.all.size, 1)
  }

  test("different callers get different incidents") {
    val c = new IncidentCorrelator(() => 30.minutes)
    c.record(ref, "waf", 10, Seq.empty, "log", "a")
    c.record(IdentityRef("ip", "5.6.7.8"), "waf", 10, Seq.empty, "log", "b")
    assertEquals(c.all.size, 2)
  }

  test("a caller returning after the window opens a new incident") {
    val c     = new IncidentCorrelator(() => 1.milli)
    val first = c.record(ref, "waf", 10, Seq.empty, "log", "a")
    Thread.sleep(20L)
    val second = c.record(ref, "waf", 10, Seq.empty, "log", "b")
    assertNotEquals(first.id, second.id)
    assertEquals(second.count, 1)
  }

  test("eviction drops what fell out of the window") {
    val c = new IncidentCorrelator(() => 1.milli)
    c.record(ref, "waf", 10, Seq.empty, "log", "a")
    Thread.sleep(20L)
    assertEquals(c.evict(), 0)
    assertEquals(c.all, Seq.empty)
  }

  test("incidents are addressable by id") {
    val c   = new IncidentCorrelator(() => 30.minutes)
    val inc = c.record(ref, "waf", 10, Seq.empty, "log", "a")
    assertEquals(c.get(inc.id).map(_.ref), Some(ref))
    assertEquals(c.get("nope"), None)
  }
}
