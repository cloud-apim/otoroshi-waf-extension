package com.cloud.apim.otoroshi.extensions.waf.reputation

import play.api.libs.json.Json

class IpParserSuite extends munit.FunSuite {

  test("parses ipv4 literals") {
    assertEquals(IpParser.parseV4("0.0.0.0"), Some(0L))
    assertEquals(IpParser.parseV4("1.2.3.4"), Some(16909060L))
    assertEquals(IpParser.parseV4("255.255.255.255"), Some(4294967295L))
  }

  test("rejects malformed ipv4") {
    assertEquals(IpParser.parseV4("1.2.3"), None)
    assertEquals(IpParser.parseV4("1.2.3.256"), None)
    assertEquals(IpParser.parseV4("1.2.3.4.5"), None)
    assertEquals(IpParser.parseV4("a.b.c.d"), None)
    assertEquals(IpParser.parseV4(""), None)
  }

  test("parses ipv6 literals, compressed and full") {
    assertEquals(IpParser.parseV6("::"), Some(BigInt(0)))
    assertEquals(IpParser.parseV6("::1"), Some(BigInt(1)))
    assertEquals(IpParser.parseV6("2001:db8:0:0:0:0:0:1"), IpParser.parseV6("2001:db8::1"))
    assertEquals(IpParser.parseV6("fe80::1%eth0"), IpParser.parseV6("fe80::1"))
  }

  test("parses an ipv6 with an embedded ipv4") {
    assertEquals(IpParser.parseV6("::ffff:1.2.3.4"), Some(BigInt("281470698652420")))
  }

  test("rejects malformed ipv6, and never treats input as a hostname") {
    assertEquals(IpParser.parseV6("1.2.3.4"), None)
    assertEquals(IpParser.parseV6("2001:db8::1::2"), None)
    assertEquals(IpParser.parseV6("2001:db8:::1"), None)
    assertEquals(IpParser.parseV6("gggg::1"), None)
    assertEquals(IpParser.parseV6("evil.example.com"), None)
    assertEquals(IpParser.parseV6("2001:db8:1"), None)
  }

  test("expands cidr blocks into inclusive ranges") {
    assertEquals(IpParser.parseEntry("10.0.0.0/24"), Some(Left((167772160L, 167772415L))))
    assertEquals(IpParser.parseEntry("10.0.0.7/32"), Some(Left((167772167L, 167772167L))))
    assertEquals(IpParser.parseEntry("0.0.0.0/0"), Some(Left((0L, 4294967295L))))
    assertEquals(IpParser.parseEntry("10.0.0.5"), Some(Left((167772165L, 167772165L))))
  }

  test("expands ipv6 cidr blocks") {
    val parsed = IpParser.parseEntry("2001:db8::/126")
    assert(parsed.exists(_.isRight))
    val Some(Right((start, end))) = parsed: @unchecked
    assertEquals(end - start, BigInt(3))
  }

  test("rejects out of range prefixes") {
    assertEquals(IpParser.parseEntry("10.0.0.0/33"), None)
    assertEquals(IpParser.parseEntry("2001:db8::/129"), None)
  }
}

class IpRangeSetSuite extends munit.FunSuite {

  test("matches addresses inside and outside a block") {
    val set = IpRangeSet.build(Seq("10.0.0.0/24", "192.168.1.5")).set
    assert(set.contains("10.0.0.0"))
    assert(set.contains("10.0.0.128"))
    assert(set.contains("10.0.0.255"))
    assert(!set.contains("10.0.1.0"))
    assert(!set.contains("9.255.255.255"))
    assert(set.contains("192.168.1.5"))
    assert(!set.contains("192.168.1.6"))
  }

  test("an empty set matches nothing and does not blow up") {
    assert(IpRangeSet.empty.isEmpty)
    assert(!IpRangeSet.empty.contains("1.2.3.4"))
    assert(!IpRangeSet.empty.contains("::1"))
    assert(!IpRangeSet.empty.contains("not-an-ip"))
  }

  test("merges overlapping and adjacent blocks") {
    val built = IpRangeSet.build(Seq("10.0.0.0/24", "10.0.1.0/24", "10.0.0.128/25"))
    assertEquals(built.accepted, 3)
    assertEquals(built.set.size, 1)
    assert(built.set.contains("10.0.1.255"))
    assert(!built.set.contains("10.0.2.0"))
  }

  test("counts junk lines instead of failing on them") {
    val built = IpRangeSet.build(Seq("10.0.0.1", "nonsense", "", "999.1.1.1"))
    assertEquals(built.accepted, 1)
    assertEquals(built.rejected, 3)
  }

  test("keeps v4 and v6 apart") {
    val set = IpRangeSet.build(Seq("2001:db8::/32")).set
    assert(set.contains("2001:db8::1"))
    assert(!set.contains("2001:db9::1"))
    assert(!set.contains("10.0.0.1"))
  }

  test("finds a needle in a large sorted set") {
    val blocks = (0 until 5000).map(i => s"10.${i / 256}.${i % 256}.0/24")
    val set    = IpRangeSet.build(blocks).set
    assert(set.contains("10.0.0.1"))
    assert(set.contains("10.19.135.200"))
    assert(!set.contains("11.0.0.1"))
  }
}

class FeedParserSuite extends munit.FunSuite {

  test("reads one entry per line and drops both comment styles") {
    val body =
      """# a firehol style comment
        |10.0.0.0/8
        |
        |192.168.0.0/16 ; SBL123 spamhaus style
        |  172.16.0.1
        |""".stripMargin
    assertEquals(
      FeedParser.parse("cidr_lines", Json.obj(), body),
      Right(Vector("10.0.0.0/8", "192.168.0.0/16", "172.16.0.1"))
    )
  }

  test("reads a csv column") {
    val body = "cidr,country\n10.0.0.0/8,FR\n192.168.0.0/16,DE\n"
    assertEquals(
      FeedParser.parse("csv", Json.obj("column" -> 0, "skip_header" -> true), body),
      Right(Vector("10.0.0.0/8", "192.168.0.0/16"))
    )
  }

  test("reads a json array of strings and of objects") {
    assertEquals(
      FeedParser.parse("json_array", Json.obj(), """["1.2.3.4","5.6.7.8"]"""),
      Right(Vector("1.2.3.4", "5.6.7.8"))
    )
    assertEquals(
      FeedParser.parse("json_array", Json.obj("field" -> "ip"), """[{"ip":"1.2.3.4"},{"ip":"5.6.7.8"}]"""),
      Right(Vector("1.2.3.4", "5.6.7.8"))
    )
  }

  test("walks a json path, the aws and gcp shape") {
    val aws = """{"prefixes":[{"ip_prefix":"3.2.34.0/26"},{"ip_prefix":"3.5.140.0/22"}]}"""
    assertEquals(
      FeedParser.parse("json_path", Json.obj("path" -> "prefixes[].ip_prefix"), aws),
      Right(Vector("3.2.34.0/26", "3.5.140.0/22"))
    )
  }

  test("walks a json path ending in an array of scalars, the azure shape") {
    val azure = """{"values":[{"properties":{"addressPrefixes":["13.64.0.0/11","13.104.0.0/14"]}}]}"""
    assertEquals(
      FeedParser.parse("json_path", Json.obj("path" -> "values[].properties.addressPrefixes[]"), azure),
      Right(Vector("13.64.0.0/11", "13.104.0.0/14"))
    )
  }

  test("reads misp attributes, keeping only ip ones") {
    val misp =
      """{"response":[{"Event":{"Attribute":[
        |{"type":"ip-dst","value":"1.2.3.4"},
        |{"type":"domain","value":"evil.example.com"},
        |{"type":"ip-src|port","value":"5.6.7.8|443"}
        |]}}]}""".stripMargin
    assertEquals(FeedParser.parse("misp", Json.obj(), misp), Right(Vector("1.2.3.4", "5.6.7.8")))
  }

  test("reports a broken payload instead of returning an empty feed") {
    assert(FeedParser.parse("json_array", Json.obj(), "<html>nope</html>").isLeft)
    assert(FeedParser.parse("json_array", Json.obj(), """{"not":"an array"}""").isLeft)
    assert(FeedParser.parse("json_path", Json.obj(), """{"a":1}""").isLeft)
    assert(FeedParser.parse("nope", Json.obj(), "").isLeft)
  }
}

class ThreatFeedCatalogSuite extends munit.FunSuite {

  test("every catalog entry is coherent") {
    val entries = ThreatFeedCatalog.entries
    assert(entries.nonEmpty)
    assertEquals(entries.map(_.id).distinct.size, entries.size, "catalog ids must be unique")
    entries.foreach { entry =>
      assert(FeedParser.formats.contains(entry.format), s"${entry.id} uses an unknown format")
      assert(Seq("block", "monitor").contains(entry.action), s"${entry.id} has an unknown action")
      assert(entry.weight >= 0 && entry.weight <= 100, s"${entry.id} weight out of range")
      assert(entry.tag.trim.nonEmpty, s"${entry.id} has no tag")
      assert(entry.licence.trim.nonEmpty, s"${entry.id} has no licence note")
      assert(entry.refreshIntervalSeconds >= 60, s"${entry.id} refreshes too aggressively")
      assert(entry.url.nonEmpty || entry.manualUrl, s"${entry.id} has no url and is not marked manual")
      if (entry.format == "json_path") {
        assert((entry.options \ "path").asOpt[String].exists(_.nonEmpty), s"${entry.id} needs a json path")
      }
    }
  }

  test("a source needing credentials or a manual url is never created enabled by accident") {
    ThreatFeedCatalog.entries.filter(e => e.requiresAuth || e.manualUrl).foreach { entry =>
      assert(entry.requiresAuth == false || entry.authHint.isDefined, s"${entry.id} should explain what credential it needs")
    }
  }
}

class ReputationVerdictSuite extends munit.FunSuite {

  private def hit(weight: Int, blocking: Boolean, tag: String = "t") =
    ReputationHit("feed", "id", "name", tag, weight, blocking)

  test("scores accumulate and cap at 100") {
    assertEquals(ReputationVerdict("1.2.3.4", List(hit(30, false), hit(40, false))).score, 70)
    assertEquals(ReputationVerdict("1.2.3.4", List(hit(80, false), hit(80, false))).score, 100)
    assertEquals(ReputationVerdict.empty("1.2.3.4").score, 0)
  }

  test("one blocking source is enough, whatever the score") {
    assert(ReputationVerdict("1.2.3.4", List(hit(1, true))).blocking)
    assert(!ReputationVerdict("1.2.3.4", List(hit(99, false))).blocking)
  }

  test("tags are deduplicated for reporting") {
    val verdict = ReputationVerdict("1.2.3.4", List(hit(10, false, "feed:a"), hit(10, false, "feed:a"), hit(10, false, "feed:b")))
    assertEquals(verdict.tags.toSet, Set("feed:a", "feed:b"))
  }
}

class CrowdSecStoreSuite extends munit.FunSuite {

  private def decision(value: String, scope: String = "Ip") =
    CrowdSecDecision(1L, "crowdsec", "ban", scope, value, "4h", "test/scenario")

  test("applies stream deltas") {
    val store = new CrowdSecStore()
    store.applyDelta(Seq(decision("1.2.3.4")), Seq.empty)
    assert(store.lookup("1.2.3.4").isDefined)
    assert(store.lookup("1.2.3.5").isEmpty)
    store.applyDelta(Seq.empty, Seq(decision("1.2.3.4")))
    assert(store.lookup("1.2.3.4").isEmpty)
  }

  test("honours range scoped decisions") {
    val store = new CrowdSecStore()
    store.applyDelta(Seq(decision("10.0.0.0/24", "Range")), Seq.empty)
    assert(store.lookup("10.0.0.42").isDefined)
    assert(store.lookup("10.0.1.42").isEmpty)
    store.applyDelta(Seq.empty, Seq(decision("10.0.0.0/24", "Range")))
    assert(store.lookup("10.0.0.42").isEmpty)
  }

  test("reset clears everything for a fresh startup sync") {
    val store = new CrowdSecStore()
    store.applyDelta(Seq(decision("1.2.3.4"), decision("10.0.0.0/24", "Range")), Seq.empty)
    assertEquals(store.size, 2)
    store.reset()
    assertEquals(store.size, 0)
    assert(store.lookup("10.0.0.42").isEmpty)
    assert(!store.initialized)
  }
}

class WafDetectionRelaySuite extends munit.FunSuite {

  private def event(request: play.api.libs.json.JsObject, block: Boolean, blocking: Boolean) = Json.obj(
    "blocking" -> blocking,
    "events"   -> Json.arr(Json.obj("rule_id" -> 942100, "msg" -> "SQL Injection Attack Detected", "phase" -> 2)),
    "block"    -> (if (block) Json.obj("status" -> 403, "msg" -> "SQL Injection Attack Detected") else play.api.libs.json.JsNull),
    "request"  -> request
  )

  test("reads the address the incoming request validator serialises") {
    val evt = event(Json.obj("remote" -> "203.0.113.7", "headers" -> Json.obj()), block = true, blocking = true)
    assertEquals(WafDetectionRelay.clientIp(evt), Some("203.0.113.7"))
  }

  test("falls back to the Remote-Address header the transformer carries, and drops the port") {
    val evt = event(Json.obj("headers" -> Json.obj("Remote-Address" -> "203.0.113.7:56136")), block = true, blocking = true)
    assertEquals(WafDetectionRelay.clientIp(evt), Some("203.0.113.7"))
  }

  test("falls back to the first X-Forwarded-For hop") {
    val evt = event(Json.obj("headers" -> Json.obj("x-forwarded-for" -> "203.0.113.7, 10.0.0.1")), block = true, blocking = true)
    assertEquals(WafDetectionRelay.clientIp(evt), Some("203.0.113.7"))
  }

  test("keeps an ipv6 address intact") {
    val evt = event(Json.obj("remote" -> "2001:db8::1", "headers" -> Json.obj()), block = true, blocking = true)
    assertEquals(WafDetectionRelay.clientIp(evt), Some("2001:db8::1"))
  }

  test("gives up rather than guessing when there is no address") {
    assertEquals(WafDetectionRelay.clientIp(event(Json.obj("headers" -> Json.obj()), block = true, blocking = true)), None)
    assertEquals(WafDetectionRelay.clientIp(Json.obj()), None)
    val garbage = event(Json.obj("remote" -> "not-an-address", "headers" -> Json.obj()), block = true, blocking = true)
    assertEquals(WafDetectionRelay.clientIp(garbage), None)
  }

  test("builds a message naming the rules that fired") {
    val evt = event(Json.obj("remote" -> "203.0.113.7", "headers" -> Json.obj()), block = true, blocking = true)
    val msg = WafDetectionRelay.message(evt, enforced = true)
    assert(msg.contains("blocked"), msg)
    assert(msg.contains("942100"), msg)
    assert(msg.contains("SQL Injection Attack Detected"), msg)
    assert(WafDetectionRelay.message(evt, enforced = false).contains("detected"))
  }
}

class SnapshotRollbackSuite extends munit.FunSuite {

  private def snapshot(feedId: String, entries: Seq[String], error: Option[String] = None, notModified: Boolean = false) = {
    val built = IpRangeSet.build(entries)
    FeedSnapshot(feedId, built.set, built.accepted, built.rejected, System.currentTimeMillis(), None, None, notModified, error)
  }

  test("keeps one generation back and restores it") {
    val registry = new ReputationRegistry()
    registry.putSnapshot(snapshot("f", Seq("10.0.0.0/24")))
    assertEquals(registry.previousSnapshot("f"), None)

    registry.putSnapshot(snapshot("f", Seq("192.168.0.0/16")))
    assert(registry.snapshot("f").exists(_.ranges.contains("192.168.1.1")))
    assert(registry.previousSnapshot("f").exists(_.ranges.contains("10.0.0.1")))

    val restored = registry.rollbackSnapshot("f")
    assert(restored.isDefined)
    assert(registry.snapshot("f").exists(_.ranges.contains("10.0.0.1")))
    assertEquals(registry.previousSnapshot("f"), None, "a rollback point is consumed once used")
  }

  test("a failed refresh does not consume the rollback point") {
    val registry = new ReputationRegistry()
    registry.putSnapshot(snapshot("f", Seq("10.0.0.0/24")))
    registry.putSnapshot(snapshot("f", Seq("192.168.0.0/16")))
    registry.putSnapshot(snapshot("f", Seq("192.168.0.0/16"), error = Some("boom")))
    assert(registry.previousSnapshot("f").exists(_.ranges.contains("10.0.0.1")))
  }

  test("a 304 does not create a rollback point") {
    val registry = new ReputationRegistry()
    registry.putSnapshot(snapshot("f", Seq("10.0.0.0/24")))
    registry.putSnapshot(snapshot("f", Seq("10.0.0.0/24"), notModified = true))
    assertEquals(registry.previousSnapshot("f"), None)
  }

  test("nothing to roll back to is not an error") {
    assertEquals(new ReputationRegistry().rollbackSnapshot("f"), None)
  }

  test("dropping a feed drops both its generations") {
    val registry = new ReputationRegistry()
    registry.putSnapshot(snapshot("f", Seq("10.0.0.0/24")))
    registry.putSnapshot(snapshot("f", Seq("192.168.0.0/16")))
    registry.retainSnapshots(Set.empty)
    assertEquals(registry.snapshot("f"), None)
    assertEquals(registry.previousSnapshot("f"), None)
  }
}
