package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.otoroshi.extensions.waf.entities.{AsnCategory, AsnDatabase}

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class IpRangeMapSuite extends munit.FunSuite {

  private def build(entries: (String, String, String)*) = IpRangeMap.build(entries.toVector)

  test("returns the value of the range an address falls in") {
    val m = build(("10.0.0.0", "10.0.0.255", "a"), ("192.168.0.0", "192.168.255.255", "b")).map
    assertEquals(m.get("10.0.0.7"), Some("a"))
    assertEquals(m.get("10.0.0.0"), Some("a"))
    assertEquals(m.get("10.0.0.255"), Some("a"))
    assertEquals(m.get("192.168.42.42"), Some("b"))
    assertEquals(m.get("10.0.1.0"), None)
  }

  test("an empty map answers nothing and does not blow up") {
    assertEquals(IpRangeMap.empty[String].get("1.2.3.4"), None)
    assertEquals(IpRangeMap.empty[String].get("not-an-ip"), None)
    assert(IpRangeMap.empty[String].isEmpty)
  }

  test("overlapping ranges are rejected rather than answered wrongly") {
    val built = build(("10.0.0.0", "10.0.0.255", "a"), ("10.0.0.128", "10.0.1.0", "b"))
    assertEquals(built.accepted, 1)
    assertEquals(built.rejected, 1)
    assertEquals(built.map.get("10.0.0.200"), Some("a"), "the first range seen wins")
  }

  test("ranges are found whatever order they were given in") {
    val m = build(("192.168.0.0", "192.168.0.255", "b"), ("10.0.0.0", "10.0.0.255", "a")).map
    assertEquals(m.get("10.0.0.1"), Some("a"))
    assertEquals(m.get("192.168.0.1"), Some("b"))
  }

  test("ipv6 ranges work and stay separate from ipv4") {
    val m = build(("2001:db8::", "2001:db8::ffff", "v6"), ("10.0.0.0", "10.0.0.255", "v4")).map
    assertEquals(m.get("2001:db8::42"), Some("v6"))
    assertEquals(m.get("2001:db9::1"), None)
    assertEquals(m.get("10.0.0.1"), Some("v4"))
  }

  test("a reversed or malformed range is rejected") {
    val built = build(("10.0.0.255", "10.0.0.0", "backwards"), ("nonsense", "10.0.0.5", "junk"))
    assertEquals(built.accepted, 0)
    assertEquals(built.rejected, 2)
  }

  test("identical values are interned rather than duplicated") {
    val shared = "AS16509"
    val built  = build(("10.0.0.0", "10.0.0.255", shared), ("11.0.0.0", "11.0.0.255", shared))
    assert(built.map.get("10.0.0.1").get eq built.map.get("11.0.0.1").get)
  }

  test("finds a needle in a large table") {
    val entries = (0 until 20000).map(i => (s"10.${i / 256}.${i % 256}.0", s"10.${i / 256}.${i % 256}.255", s"v$i"))
    val m       = IpRangeMap.build(entries).map
    assertEquals(m.get("10.0.0.1"), Some("v0"))
    assertEquals(m.get("10.39.15.9"), Some("v9999"))
    assertEquals(m.get("11.0.0.1"), None)
  }
}

class AsnParserSuite extends munit.FunSuite {

  private val sample =
    """1.0.0.0	1.0.0.255	13335	US	CLOUDFLARENET
      |1.0.1.0	1.0.3.255	0	None	Not routed
      |1.44.96.0	1.44.96.255	16509	US	AMAZON-02""".stripMargin

  test("parses the published tsv shape") {
    val rows = AsnParser.parse("iptoasn_tsv", sample).toOption.get
    assertEquals(rows.size, 2, "the 'not routed' row is dropped, not rejected")
    assertEquals(rows.head, ("1.0.0.0", "1.0.0.255", AsnRecord(13335, "US", "CLOUDFLARENET")))
    assertEquals(rows(1)._3.org, "AMAZON-02")
  }

  test("short rows, comments and blanks are skipped") {
    val body = "# a comment\n\n1.0.0.0\t1.0.0.255\n1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET"
    assertEquals(AsnParser.parse("iptoasn_tsv", body).toOption.get.size, 1)
  }

  test("a table with no routed row is an error, not an empty table") {
    assert(AsnParser.parse("iptoasn_tsv", "1.0.1.0\t1.0.3.255\t0\tNone\tNot routed").isLeft)
    assert(AsnParser.parse("iptoasn_tsv", "").isLeft)
  }

  test("an unknown format is refused") {
    assert(AsnParser.parse("maxmind", sample).isLeft)
  }

  test("gzipped payloads round-trip") {
    val out = new ByteArrayOutputStream()
    val gz  = new GZIPOutputStream(out)
    gz.write(sample.getBytes("UTF-8"))
    gz.close()
    assertEquals(AsnParser.decode(out.toByteArray, gzip = true), Right(sample))
    assertEquals(AsnParser.decode(sample.getBytes("UTF-8"), gzip = false), Right(sample))
  }

  test("a payload that is not gzip is reported, not silently mangled") {
    assert(AsnParser.decode("plain text".getBytes("UTF-8"), gzip = true).isLeft)
  }
}

class AsnClassificationSuite extends munit.FunSuite {

  private val db = AsnDatabase(id = "db", name = "db")

  private def classify(asn: Int, org: String) = db.classify(AsnRecord(asn, "US", org))

  test("cloudflare is a cdn, not hosting — the category order is what guarantees this") {
    val m = classify(13335, "CLOUDFLARENET")
    assertEquals(m.category, Some("cdn"), "'CLOUDFLARENET' contains 'cloud' and must not fall into hosting")
    assertEquals(m.weight, 0)
  }

  test("M247 is a vpn host, not generic hosting") {
    assertEquals(classify(9009, "M247").category, Some("vpn"))
    assertEquals(classify(9009, "M247").weight, 30)
  }

  test("the big clouds are hosting, by number and by name") {
    assertEquals(classify(16509, "AMAZON-02").category, Some("hosting"))
    assertEquals(classify(24940, "HETZNER-AS").category, Some("hosting"))
    assertEquals(classify(999999, "Some Random Hosting Ltd").category, Some("hosting"))
    assertEquals(classify(16509, "AMAZON-02").weight, 15, "hosting is a weak signal on purpose")
  }

  test("an ordinary network gets no category and no weight") {
    val m = classify(3215, "Orange S.A.")
    assertEquals(m.category, None)
    assertEquals(m.weight, 0)
    assertEquals(m.tag, "asn:3215")
  }

  test("nothing ships as blocking — hosting is a signal, never a verdict") {
    assert(db.categories.forall(!_.blocking), "a shipped category that blocks would take out server-to-server traffic")
  }

  test("an explicit asn matches even when the description says nothing useful") {
    assertEquals(classify(16509, "").category, Some("hosting"))
  }

  test("categories are editable and a custom one can block") {
    val custom = db.copy(categories = Seq(AsnCategory("tor-exit", 60, "block", asns = Seq(1234))))
    val m      = custom.classify(AsnRecord(1234, "US", "whatever"))
    assertEquals(m.category, Some("tor-exit"))
    assert(m.blocking)
    assertEquals(m.tag, "asn:tor-exit")
  }

  test("the entity round-trips, defaults included") {
    val back = AsnDatabase.format.reads(AsnDatabase.format.writes(db)).get
    assertEquals(back.categories.map(_.name), Seq("cdn", "vpn", "hosting"))
    assertEquals(back.url, "https://iptoasn.com/data/ip2asn-v4.tsv.gz")
    assertEquals(back.gzip, true)
  }
}
