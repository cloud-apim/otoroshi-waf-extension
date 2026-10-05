package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.seclang.model.{Disposition, NoLogSecLangIntegration, RequestContext}
import com.cloud.apim.seclang.scaladsl.SecLang

import java.net.{InetAddress, UnknownHostException}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class RblQuerySuite extends munit.FunSuite {

  test("an ipv4 address is reversed in front of the zone") {
    assertEquals(RblQuery.name("1.2.3.4", "zen.spamhaus.org"), Some("4.3.2.1.zen.spamhaus.org"))
    assertEquals(RblQuery.name(" 127.0.0.2 ", "ZEN.Spamhaus.org."), Some("2.0.0.127.zen.spamhaus.org"))
  }

  test("an ipv6 address is reversed nibble by nibble") {
    assertEquals(
      RblQuery.name("2001:db8::1", "zen.spamhaus.org"),
      Some("1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.zen.spamhaus.org")
    )
  }

  test("a host name is never turned into a query, nor is a zone that is not one") {
    assertEquals(RblQuery.name("example.com", "zen.spamhaus.org"), None)
    assertEquals(RblQuery.name("1.2.3.4", ""), None)
    assertEquals(RblQuery.name("1.2.3.4", "zen spamhaus org"), None)
    assertEquals(RblQuery.name("1.2.3.4", "%{tx.zone}"), None)
  }

  test("http:BL puts the access key first, and needs one") {
    assertEquals(RblQuery.name("1.2.3.4", "dnsbl.httpbl.org", Some("abcdefghijkl")), Some("abcdefghijkl.4.3.2.1.dnsbl.httpbl.org"))
    assertEquals(RblQuery.name("1.2.3.4", "dnsbl.httpbl.org", None), None)
    assertEquals(RblQuery.name("2001:db8::1", "dnsbl.httpbl.org", Some("abcdefghijkl")), None)
  }

  test("the zones a ruleset names are found, except the ones only known at request time") {
    val rules = Seq(
      """SecRule REMOTE_ADDR "@rbl zen.spamhaus.org" "id:1,phase:1,deny"""",
      """SecRule REMOTE_ADDR "!@rbl bl.example.org" "id:2,phase:1,deny"""",
      """SecRule REMOTE_ADDR "@rbl %{tx.zone}" "id:3,phase:1,deny"
        |SecRule REMOTE_ADDR "@rbl ZEN.spamhaus.org" "id:4,phase:1,deny"""".stripMargin
    )
    assertEquals(RblResolver.zonesIn(rules), Seq("zen.spamhaus.org", "bl.example.org"))
  }
}

class RblAnswerSuite extends munit.FunSuite {

  private def ip(s: String) = InetAddress.getByAddress(s.split('.').map(_.toInt.toByte))

  test("an answer in 127.0.0.0/8 is a listing, with its codes") {
    assertEquals(RblAnswer.classify(Seq(ip("127.0.0.2"), ip("127.0.0.10"))), RblAnswer.Listed(Seq("127.0.0.2", "127.0.0.10")))
  }

  test("no answer is not a listing") {
    assertEquals(RblAnswer.classify(Seq.empty), RblAnswer.NotListed)
  }

  test("spamhaus error codes are refusals, never listings") {
    RblAnswer.classify(Seq(ip("127.255.255.254"))) match {
      case RblAnswer.Refused(reason) => assert(reason.contains("public resolver"), reason)
      case other                     => fail(s"expected a refusal, got $other")
    }
  }

  test("an answer outside 127.0.0.0/8 is a resolver rewriting misses, not a listing") {
    assert(!RblAnswer.classify(Seq(ip("92.242.132.24"))).listed)
  }
}

class RblResolverSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global

  private def after(d: FiniteDuration, f: () => Future[Unit]): Future[Unit] =
    Future(Thread.sleep(d.toMillis)).flatMap(_ => f())

  private def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  private def addresses(ips: String*) = ips.map(s => InetAddress.getByAddress(s.split('.').map(_.toInt.toByte)))

  // the RFC 5782 test entry, which the resolver asks about to know whether a zone answers at all
  private def isProbe(name: String) = name.startsWith("2.0.0.127.")

  private class Dns(answer: String => Future[Seq[InetAddress]]) {
    val calls  = new AtomicInteger(0)
    val probes = new AtomicInteger(0)
    def resolve(name: String): Future[Seq[InetAddress]] = {
      if (isProbe(name)) probes.incrementAndGet() else calls.incrementAndGet()
      answer(name)
    }
  }

  private def resolver(dns: Dns, settings: RblSettings = RblSettings()) =
    new RblResolver(dns.resolve, settings, after, play.api.Logger("rbl-test"))

  private val listedOnly = new Dns(name =>
    if (name == "4.3.2.1.zen.spamhaus.org") Future.successful(addresses("127.0.0.2"))
    else Future.failed(new UnknownHostException(name))
  )

  test("the engine is never kept waiting: a miss says no and starts the query") {
    val rbl = resolver(listedOnly)
    assert(!rbl.listed("1.2.3.4", "zen.spamhaus.org"))
    await(rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))
    assert(rbl.listed("1.2.3.4", "zen.spamhaus.org"))
    assert(!rbl.listed("5.6.7.8", "zen.spamhaus.org"))
  }

  test("a warm lookup gives a first request its answer") {
    val rbl = resolver(listedOnly)
    await(rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))
    assert(rbl.listed("1.2.3.4", "zen.spamhaus.org"))
  }

  test("a slow blocklist costs a request the wait, not the timeout, and answers the next ones") {
    val release = Promise[Seq[InetAddress]]()
    val dns     = new Dns(_ => release.future)
    val rbl     = resolver(dns, RblSettings(waitFor = 50.millis))
    val start   = System.nanoTime()
    await(rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))
    assert((System.nanoTime() - start).nanos < 2.seconds)
    assert(!rbl.listed("1.2.3.4", "zen.spamhaus.org"), "not known yet is not listed")
    release.success(addresses("127.0.0.2"))
    Thread.sleep(100)
    assert(rbl.listed("1.2.3.4", "zen.spamhaus.org"))
    assertEquals(dns.calls.get(), 1)
  }

  test("answers are cached, listed or not, and concurrent questions share one query") {
    val dns = new Dns(name => Future { Thread.sleep(50); if (name.startsWith("4.3.2.1.")) addresses("127.0.0.2") else throw new UnknownHostException(name) })
    val rbl = resolver(dns)
    await(Future.sequence((1 to 20).map(_ => rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))))
    await(rbl.warm("5.6.7.8", Seq("zen.spamhaus.org")))
    (1 to 10).foreach { _ =>
      assert(rbl.listed("1.2.3.4", "zen.spamhaus.org"))
      assert(!rbl.listed("5.6.7.8", "zen.spamhaus.org"))
    }
    assertEquals(dns.calls.get(), 2)
  }

  test("a timeout is not a listing, and is asked again soon rather than cached for long") {
    val dns = new Dns(_ => Future.failed(new java.util.concurrent.TimeoutException("query timed out")))
    val rbl = resolver(dns, RblSettings(errorTtl = 1.second))
    await(rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))
    assert(!rbl.listed("1.2.3.4", "zen.spamhaus.org"))
    assertEquals(dns.calls.get(), 1)
    Thread.sleep(1200)
    await(rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))
    assertEquals(dns.calls.get(), 2)
    assertEquals((rbl.status \ "unknown").as[Long], 2L)
  }

  test("a refusal is not a listing, and is shown on the status") {
    val dns = new Dns(_ => Future.successful(addresses("127.255.255.254")))
    val rbl = resolver(dns)
    await(rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))
    assert(!rbl.listed("1.2.3.4", "zen.spamhaus.org"))
    assert((rbl.status \ "refusals" \ "zen.spamhaus.org").as[String].contains("public resolver"))
  }

  test("disabled, nothing is ever asked") {
    val dns = new Dns(_ => Future.successful(addresses("127.0.0.2")))
    val rbl = resolver(dns, RblSettings(enabled = false))
    await(rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))
    assert(!rbl.listed("1.2.3.4", "zen.spamhaus.org"))
    assertEquals(dns.calls.get(), 0)
  }

  test("a zone that lists its test entry is healthy") {
    val dns = new Dns(name => if (isProbe(name)) Future.successful(addresses("127.0.0.2")) else Future.failed(new UnknownHostException(name)))
    val rbl = resolver(dns)
    await(rbl.warm("1.2.3.4", Seq("bl.example.org")))
    Thread.sleep(100)
    assertEquals((rbl.status \ "zones" \ "bl.example.org" \ "healthy").as[Boolean], true)
    assertEquals(dns.probes.get(), 1)
  }

  test("a zone that does not list its test entry is reported, since its silence cannot be trusted") {
    // what spamhaus does through some public resolvers: nxdomain to everything
    val dns = new Dns(name => Future.failed(new UnknownHostException(name)))
    val rbl = resolver(dns)
    await(rbl.warm("1.2.3.4", Seq("zen.spamhaus.org")))
    await(rbl.warm("5.6.7.8", Seq("zen.spamhaus.org")))
    Thread.sleep(100)
    assertEquals((rbl.status \ "zones" \ "zen.spamhaus.org" \ "healthy").as[Boolean], false)
    assert((rbl.status \ "zones" \ "zen.spamhaus.org" \ "detail").as[String].contains("test entry"))
    assertEquals(dns.probes.get(), 1, "probed once, not once per caller")
  }

  test("@rbl works end to end through the engine") {
    val rbl = resolver(listedOnly)
    val integration = new NoLogSecLangIntegration() {
      override def rblLookup(address: String, zone: String): Boolean = rbl.listed(address, zone)
    }
    val rules =
      """
        |SecRule REMOTE_ADDR "@rbl zen.spamhaus.org" "id:1,phase:1,deny,status:403,msg:'listed by spamhaus'"
        |SecRuleEngine On
        |""".stripMargin
    val engine = SecLang.engine(SecLang.compile(SecLang.parse(rules).fold(err => throw err.throwable, identity)), integration = integration)
    def from(ip: String) = {
      await(rbl.warm(ip, RblResolver.zonesIn(Seq(rules))))
      engine.evaluate(RequestContext(method = "GET", uri = "/", remoteAddr = ip)).disposition
    }
    assertEquals(from("1.2.3.4"), Disposition.Block(403, Some("listed by spamhaus"), Some(1)))
    assertEquals(from("5.6.7.8"), Disposition.Continue)
  }
}
