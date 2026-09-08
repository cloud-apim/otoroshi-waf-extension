package com.cloud.apim.otoroshi.extensions.waf.challenge

import com.cloud.apim.otoroshi.extensions.waf.entities.{ChallengePresets, ChallengeProvider}
import com.cloud.apim.otoroshi.extensions.waf.reputation.{HttpCall, HttpResult, ReputationHttpClient}
import com.cloud.apim.otoroshi.extensions.waf.security.InMemorySharedStateStore
import play.api.Logger
import play.api.libs.json.Json

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class PowSuite extends munit.FunSuite {

  test("leading zero bits are counted, not guessed") {
    assertEquals(Pow.leadingZeroBits("ffff"), 0)
    assertEquals(Pow.leadingZeroBits("7fff"), 1)
    assertEquals(Pow.leadingZeroBits("0fff"), 4)
    assertEquals(Pow.leadingZeroBits("00ff"), 8)
    assertEquals(Pow.leadingZeroBits("007f"), 9)
    assertEquals(Pow.leadingZeroBits("0000"), 16)
  }

  test("a nonce that meets the difficulty solves, one that does not fails") {
    // brute force a small puzzle, exactly as the browser does
    val challenge = "test-challenge"
    val nonce     = LazyList.from(0).map(_.toString).find(n => Pow.solves(challenge, n, 8)).get
    assert(Pow.solves(challenge, nonce, 8))
    assert(!Pow.solves(challenge, nonce, 32), "the same nonce must not satisfy a harder puzzle")
    assert(!Pow.solves("another-challenge", nonce, 8), "a solution is bound to its own challenge")
  }

  test("clearance survives a round trip") {
    val c = Pow.Clearance(exp = 4102444800L, ip = Some("1.2.3.4"), ua = Some("curl"), score = 60)
    val t = Pow.signClearance(c, "secret")
    assertEquals(Pow.verifyClearance(t, "secret", Some("1.2.3.4"), Some("curl")), Some(c))
  }

  test("a tampered or wrongly signed token is refused") {
    val c = Pow.Clearance(4102444800L, None, None, 0)
    val t = Pow.signClearance(c, "secret")
    assertEquals(Pow.verifyClearance(t, "other-secret", None, None), None)
    assertEquals(Pow.verifyClearance(t.dropRight(1) + "0", "secret", None, None), None)
    assertEquals(Pow.verifyClearance("nonsense", "secret", None, None), None)
    assertEquals(Pow.verifyClearance("", "secret", None, None), None)
  }

  test("an expired token is refused") {
    val c = Pow.Clearance(exp = 1000L, ip = None, ua = None, score = 0)
    assertEquals(Pow.verifyClearance(Pow.signClearance(c, "s"), "s", None, None, now = 2000L), None)
    assert(Pow.verifyClearance(Pow.signClearance(c, "s"), "s", None, None, now = 500L).isDefined)
  }

  test("clearance is bound to the caller, so a solved token cannot be shared around") {
    val c = Pow.Clearance(4102444800L, Some("1.2.3.4"), Some("firefox"), 0)
    val t = Pow.signClearance(c, "s")
    assertEquals(Pow.verifyClearance(t, "s", Some("9.9.9.9"), Some("firefox")), None, "another address")
    assertEquals(Pow.verifyClearance(t, "s", Some("1.2.3.4"), Some("chrome")), None, "another user agent")
    assert(Pow.verifyClearance(t, "s", Some("1.2.3.4"), Some("firefox")).isDefined)
  }

  test("an unbound token travels, which is what turning the binding off means") {
    val t = Pow.signClearance(Pow.Clearance(4102444800L, None, None, 0), "s")
    assert(Pow.verifyClearance(t, "s", Some("anything"), Some("anything")).isDefined)
  }

  test("difficulty scales with the score, and stays inside its bounds") {
    assertEquals(Pow.difficultyFor(0, 18, 24), 18)
    assertEquals(Pow.difficultyFor(100, 18, 24), 24)
    assertEquals(Pow.difficultyFor(50, 18, 24), 21)
    assertEquals(Pow.difficultyFor(-50, 18, 24), 18, "a nonsense score cannot go below the floor")
    assertEquals(Pow.difficultyFor(500, 18, 24), 24, "nor above the ceiling")
    // inverted bounds collapse to the easier of the two: the failure mode of a misconfiguration
    // should be less protection, never an unsolvable puzzle served to your own users
    assertEquals(Pow.difficultyFor(50, 24, 18), 18)
    assertEquals(Pow.difficultyFor(100, 24, 18), 18)
  }
}

class ChallengeServiceSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  private val refusingHttp = new ReputationHttpClient {
    override def call(request: HttpCall): Future[HttpResult] =
      Future.failed(new RuntimeException("no vendor reachable in a unit test"))
  }

  private def service(http: ReputationHttpClient = refusingHttp) =
    new ChallengeService(new InMemorySharedStateStore(), http, "challenges", Logger("test"))

  private val provider = ChallengeProvider(id = "p", name = "p", difficultyFloor = 6, difficultyCeiling = 8)

  private def solve(challenge: String, difficulty: Int): String =
    LazyList.from(0).map(_.toString).find(n => Pow.solves(challenge, n, difficulty)).get

  test("issuing produces a puzzle the browser can act on") {
    val issued = await(service().issue(provider, 0))
    assertEquals((issued.payload \ "kind").as[String], "pow")
    assertEquals((issued.payload \ "difficulty").as[Int], 6)
    assert((issued.payload \ "challenge").as[String].nonEmpty)
  }

  test("difficulty follows the score at issue time") {
    assertEquals((await(service().issue(provider, 100)).payload \ "difficulty").as[Int], 8)
  }

  test("a correct solution is accepted and grants clearance") {
    val svc    = service()
    val issued = await(svc.issue(provider, 0))
    val nonce  = solve(issued.id, 6)
    val result = await(svc.verify(provider, Json.obj("challenge" -> issued.id, "nonce" -> nonce), Some("1.2.3.4"), Some("ua")))
    assert(result.isRight, s"expected clearance, got $result")
    assertEquals(result.toOption.get.ip, Some("1.2.3.4"))
  }

  test("a solution can only be used once — this is why challenges are stored") {
    val svc    = service()
    val issued = await(svc.issue(provider, 0))
    val nonce  = solve(issued.id, 6)
    val body   = Json.obj("challenge" -> issued.id, "nonce" -> nonce)
    assert(await(svc.verify(provider, body, None, None)).isRight)
    val replay = await(svc.verify(provider, body, None, None))
    assert(replay.isLeft, "a replayed solution must be refused")
    assert(replay.left.exists(_.contains("expired")) || replay.left.exists(_.contains("unknown")))
  }

  test("a wrong nonce is refused") {
    val svc    = service()
    val issued = await(svc.issue(provider, 0))
    val result = await(svc.verify(provider, Json.obj("challenge" -> issued.id, "nonce" -> "0"), None, None))
    assert(result.isLeft || Pow.solves(issued.id, "0", 6), "only a genuine solution passes")
  }

  test("an unknown challenge is refused") {
    val result = await(service().verify(provider, Json.obj("challenge" -> "never-issued", "nonce" -> "1"), None, None))
    assert(result.isLeft)
  }

  test("an incomplete submission is refused") {
    assert(await(service().verify(provider, Json.obj(), None, None)).isLeft)
    assert(await(service().verify(provider, Json.obj("challenge" -> "x"), None, None)).isLeft)
  }

  test("a vendor answer that cannot be verified fails closed") {
    val vendor = provider.copy(kind = "vendor", verifyUrl = "https://example.com/verify", secretKey = "k")
    val result = await(service().verify(vendor, Json.obj("token" -> "abc"), None, None))
    assert(result.isLeft, "an unreachable provider is not a passed challenge")
    assert(result.left.exists(_.contains("could not reach")))
  }

  test("a vendor answer the provider accepts grants clearance") {
    val ok = new ReputationHttpClient {
      override def call(request: HttpCall): Future[HttpResult] =
        Future.successful(HttpResult(200, """{"success":true}""".getBytes("UTF-8"), Map.empty))
    }
    val vendor = provider.copy(kind = "vendor", verifyUrl = "https://example.com/verify", secretKey = "k")
    assert(await(service(ok).verify(vendor, Json.obj("token" -> "abc"), None, None)).isRight)
  }

  test("a vendor answer the provider rejects is refused, with the reason") {
    val no = new ReputationHttpClient {
      override def call(request: HttpCall): Future[HttpResult] =
        Future.successful(HttpResult(200, """{"success":false,"error-codes":["invalid-input-response"]}""".getBytes("UTF-8"), Map.empty))
    }
    val vendor = provider.copy(kind = "vendor", verifyUrl = "https://example.com/verify", secretKey = "k")
    val result = await(service(no).verify(vendor, Json.obj("token" -> "abc"), None, None))
    assert(result.left.exists(_.contains("invalid-input-response")))
  }
}

class ChallengeProviderSuite extends munit.FunSuite {

  test("a proof-of-work provider is usable out of the box, a vendor one is not") {
    assert(ChallengeProvider(id = "p", name = "p").usable)
    assert(!ChallengeProvider(id = "p", name = "p", kind = "vendor").usable, "a vendor needs keys and urls first")
  }

  test("presets are created disabled — an unconfigured challenge would lock everyone out") {
    ChallengePresets.entries.foreach { preset =>
      val provider = ChallengePresets.apply(preset, "challenge-provider_test")
      assert(!provider.enabled, s"${preset.id} must not be created enabled")
      assertEquals(provider.kind, "vendor")
      assertEquals(provider.presetRef, Some(preset.id))
    }
  }

  test("every preset says where it is operated from") {
    ChallengePresets.entries.foreach(p => assert(p.origin.trim.nonEmpty, s"${p.id} has no origin"))
    assert(ChallengePresets.find("friendly-captcha").exists(_.origin.contains("Germany")))
    assert(ChallengePresets.find("captcha-eu").exists(_.origin.contains("Austria")))
    assert(ChallengePresets.find("turnstile").exists(_.origin.contains("United States")))
  }

  test("presets with no shipped endpoint say so instead of shipping a guess") {
    val eu = ChallengePresets.find("captcha-eu").get
    assertEquals(eu.verifyUrl, "")
    assert(eu.notes.exists(_.contains("dashboard")), "it must tell the operator where to get them")
  }

  test("the entity round-trips") {
    val p    = ChallengeProvider(id = "p", name = "p", difficultyFloor = 20, bindUa = false, cookieName = "c")
    val back = ChallengeProvider.format.reads(ChallengeProvider.format.writes(p)).get
    assertEquals(back, p)
  }
}
