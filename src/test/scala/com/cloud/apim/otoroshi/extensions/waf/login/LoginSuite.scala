package com.cloud.apim.otoroshi.extensions.waf.login

import com.cloud.apim.otoroshi.extensions.waf.security.InMemorySharedStateStore
import org.apache.pekko.util.ByteString
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimLoginGuardConfig
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * BEH-3 without a gateway: where credentials are found, what the counters remember and never
 * store, which patterns become signals and how they escalate, and the k-anonymity check.
 */
class LoginSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global

  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  private def extract(body: String, ct: String, auth: Option[String] = None) =
    Credentials.extract(ByteString(body), Some(ct), auth, Credentials.defaultUsernameFields ++ Seq("credentials.login"), Credentials.defaultPasswordFields)

  // ---------------------------------------------------------------- credentials

  test("credentials are found in JSON, nested JSON, a form and a Basic header") {
    assertEquals(extract("""{"email":"jane@example.com","password":"s3cret"}""", "application/json"), Some(Credentials("jane@example.com", Some("s3cret"))))
    assertEquals(extract("""{"credentials":{"login":"jane"}}""", "application/json; charset=utf-8").map(_.username), Some("jane"))
    assertEquals(extract("username=jane%40example.com&password=a%26b", "application/x-www-form-urlencoded"), Some(Credentials("jane@example.com", Some("a&b"))))
    val basic = "Basic " + Base64.getEncoder.encodeToString("jane:pw".getBytes(StandardCharsets.UTF_8))
    assertEquals(extract("", "text/plain", Some(basic)), Some(Credentials("jane", Some("pw"))))
    assertEquals(extract("""{"q":"search"}""", "application/json"), None)
  }

  test("an account is shown masked, enough to recognise it and not to collect it") {
    assertEquals(Credentials.mask("jane.doe@example.com"), "j***@example.com")
    assertEquals(Credentials.mask("administrator"), "a***r")
    assertEquals(Credentials.mask("jo"), "***")
  }

  test("login paths are exact or prefixes, for the configured methods only") {
    val cfg = CloudApimLoginGuardConfig(loginPaths = Seq("/login", "/api/auth/*"))
    assert(cfg.isLogin("POST", "/login") && cfg.isLogin("post", "/api/auth/token"))
    assert(!cfg.isLogin("GET", "/login") && !cfg.isLogin("POST", "/login/help") && !cfg.isLogin("POST", "/api/users"))
    assert(CloudApimLoginGuardConfig().isLogin("POST", "/anything"))
  }

  // ---------------------------------------------------------------- patterns

  test("each pattern needs its threshold, and weighs more past twice it") {
    val t = LoginThresholds()
    assertEquals(t.signals(LoginState(19, 1, 0, 0)), Seq.empty)
    assertEquals(t.signals(LoginState(20, 1, 0, 0)).map(s => (s._1, s._2)), Seq(("credential_stuffing", 50)))
    assertEquals(t.signals(LoginState(40, 1, 0, 0)).map(s => (s._1, s._2)), Seq(("credential_stuffing", 70)))
    assertEquals(t.signals(LoginState(10, 10, 0, 0)).map(_._1), Seq("password_spraying"))
    assertEquals(t.signals(LoginState(0, 0, 10, 4)), Seq.empty, "one account failing from a few sources is a user, not an attack")
    assertEquals(t.signals(LoginState(0, 0, 10, 5)).map(_._1), Seq("account_under_attack"))
    assertEquals(LoginThresholds(sourceFailures = 0).signals(LoginState(500, 1, 0, 0)), Seq.empty, "0 turns a pattern off")
  }

  // ---------------------------------------------------------------- counters

  test("failures are counted per source and per account, and accounts are never stored as typed") {
    val store    = new InMemorySharedStateStore()
    val counters = new LoginCounters("t", store, "a-secret")
    val window   = 60000L
    val jane     = counters.account("shop", "Jane@Example.com")
    assertEquals(jane, counters.account("shop", " jane@example.com "), "case and spaces do not make another account")
    assertNotEquals(jane, counters.account("other-realm", "jane@example.com"))
    (1 to 3).foreach(i => await(counters.failed("shop", "10.0.0.1", counters.account("shop", s"user$i"), window)))
    await(counters.failed("shop", "10.0.0.2", jane, window))
    await(counters.failed("shop", "10.0.0.3", jane, window))
    assertEquals(await(counters.state("shop", "10.0.0.1", jane, window)), LoginState(3, 3, 2, 2))
    val keys = await(store.keys("t:*"))
    assert(keys.nonEmpty && !keys.exists(_.contains("jane")), s"$keys")
  }

  test("an observation is reported once per window") {
    val counters = new LoginCounters("t", new InMemorySharedStateStore(), "s")
    assertEquals(await(counters.first("shop", "credential_stuffing:10.0.0.1", 60000L)), true)
    assertEquals(await(counters.first("shop", "credential_stuffing:10.0.0.1", 60000L)), false)
    assertEquals(await(counters.first("shop", "credential_stuffing:10.0.0.2", 60000L)), true)
  }

  // ---------------------------------------------------------------- breached passwords

  private def sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)).map("%02X".format(_)).mkString

  test("a breached password is found from its range, only the prefix leaves, and ranges are cached") {
    val calls    = new AtomicInteger(0)
    val asked    = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val hash     = sha1("password123")
    val padded   = sha1("never-breached-xyz")
    val checker  = new BreachedPasswords(prefix => {
      calls.incrementAndGet()
      asked.add(prefix)
      Future.successful(Some(s"${hash.drop(5)}:2389787\r\n${padded.drop(5)}:0\r\nAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:3"))
    })
    assertEquals(await(checker.breached("password123")), Some(true))
    assertEquals(asked.peek(), hash.take(5))
    assertEquals(await(checker.breached("password123")), Some(true))
    assertEquals(calls.get(), 1, "the range is cached")
    val unknown = new BreachedPasswords(_ => Future.successful(None))
    assertEquals(await(unknown.breached("x")), None, "no answer is no verdict")
  }

  test("a padding line of the range is not a breach") {
    val padded  = sha1("never-breached-xyz")
    val checker = new BreachedPasswords(_ => Future.successful(Some(s"${padded.drop(5)}:0")))
    assertEquals(await(checker.breached("never-breached-xyz")), Some(false))
  }

  test("a config survives a round trip") {
    val cfg = CloudApimLoginGuardConfig(
      loginPaths = Seq("/login"),
      failureMarker = Some("Invalid credentials"),
      realm = Some("shop"),
      thresholds = LoginThresholds(sourceFailures = 5, accountSources = 2),
      breachedPasswords = true
    )
    assertEquals(CloudApimLoginGuardConfig.format.reads(cfg.json).get, cfg)
    assertEquals(CloudApimLoginGuardConfig.format.reads(Json.obj()).get, CloudApimLoginGuardConfig.default)
    assertEquals(CloudApimLoginGuardConfig.configFlow.filterNot(CloudApimLoginGuardConfig.configSchema.keys.contains), Seq.empty[String])
  }
}
