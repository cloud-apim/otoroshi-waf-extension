package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.otoroshi.extensions.waf.entities.GeoDatabase
import com.cloud.apim.seclang.model.{Disposition, NoLogSecLangIntegration, RequestContext}
import com.cloud.apim.seclang.scaladsl.SecLang
import com.maxmind.db.{CHMCache, Reader}
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream}

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}
import java.time.{YearMonth, ZoneOffset}
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** The MaxMind test databases (Apache 2.0 / MIT), from github.com/maxmind/MaxMind-DB. */
object GeoFixtures {
  def bytes(name: String): Array[Byte] = getClass.getResourceAsStream(s"/geo/$name").readAllBytes()
  val city: Array[Byte]                = bytes("GeoLite2-City-Test.mmdb")
  val country: Array[Byte]             = bytes("GeoLite2-Country-Test.mmdb")

  def gzip(raw: Array[Byte]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val gz  = new GZIPOutputStream(out)
    gz.write(raw)
    gz.close()
    out.toByteArray
  }

  /** The layout MaxMind ships: a dated directory holding the database and its licence. */
  def tarGz(files: (String, Array[Byte])*): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val tar = new TarArchiveOutputStream(new GZIPOutputStream(out))
    files.foreach { case (name, content) =>
      val entry = new TarArchiveEntry(name)
      entry.setSize(content.length.toLong)
      tar.putArchiveEntry(entry)
      tar.write(content)
      tar.closeArchiveEntry()
    }
    tar.close()
    out.toByteArray
  }

  def snapshotOf(raw: Array[Byte]): GeoSnapshot = {
    val file   = Files.createTempFile("geo-fixture-", ".mmdb")
    Files.write(file, raw)
    val reader = new Reader(file.toFile, new CHMCache())
    GeoSnapshot(Some(reader), Some(file), None, None, None, None, None, None, raw.length.toLong, 0L, 0L, None)
  }
}

class GeoLocationSuite extends munit.FunSuite {

  private lazy val city    = GeoFixtures.snapshotOf(GeoFixtures.city)
  private lazy val country = GeoFixtures.snapshotOf(GeoFixtures.country)

  test("a city database gives the whole location") {
    val loc = city.lookup("81.2.69.142").get
    assertEquals(loc.countryCode, Some("GB"))
    assertEquals(loc.countryName, Some("United Kingdom"))
    assertEquals(loc.continentCode, Some("EU"))
    assertEquals(loc.regionCode, Some("ENG"))
    assertEquals(loc.city, Some("London"))
    assertEquals(loc.latitude, Some(51.5142))
    assertEquals(loc.longitude, Some(-0.0931))
  }

  test("a country database gives the country, and nothing it does not know") {
    val loc = country.lookup("81.2.69.142").get
    assertEquals(loc.countryCode, Some("GB"))
    assertEquals(loc.city, None)
    assertEquals(loc.latitude, None)
  }

  test("ipv6 addresses are located too") {
    assertEquals(city.lookup("2001:218::1").flatMap(_.countryCode), Some("JP"))
  }

  test("an address the database does not hold is a miss") {
    assertEquals(city.lookup("10.0.0.1"), None)
  }

  test("only address literals are looked up, never names") {
    assertEquals(GeoLocation.address("localhost"), None)
    assertEquals(GeoLocation.address("example.com"), None)
    assertEquals(GeoLocation.address("not an ip"), None)
    assertEquals(GeoLocation.address(" 81.2.69.142 ").map(_.getHostAddress), Some("81.2.69.142"))
    assertEquals(city.lookup("example.com"), None)
  }

  test("a closed reader is a miss, not a failure") {
    val snapshot = GeoFixtures.snapshotOf(GeoFixtures.country)
    snapshot.reader.foreach(_.close())
    assertEquals(snapshot.lookup("81.2.69.142"), None)
  }

  test("the flat shape of IPinfo and IP66 is read as well") {
    val record: GeoLocation.Record = Map[String, AnyRef](
      "country_code"   -> "fr",
      "country"        -> "France",
      "continent_code" -> "EU",
      "continent"      -> "Europe",
      "asn"            -> "AS16276"
    ).asJava
    val loc = GeoLocation.fromRecord(record)
    assertEquals(loc.countryCode, Some("FR"))
    assertEquals(loc.countryName, Some("France"))
    assertEquals(loc.continentName, Some("Europe"))
  }

  test("the GEO collection uses ModSecurity's names") {
    val geo = city.lookup("81.2.69.142").get.seclang
    assertEquals(geo("COUNTRY_CODE"), "GB")
    assertEquals(geo("COUNTRY_CODE3"), "GBR")
    assertEquals(geo("COUNTRY_CONTINENT"), "EU")
    assertEquals(geo("REGION"), "ENG")
    assertEquals(geo("CITY"), "London")
    assertEquals(geo("LATITUDE"), "51.5142")
    assert(!country.lookup("81.2.69.142").get.seclang.contains("CITY"), "a field the database lacks is absent, not empty")
  }

  test("@geoLookup and GEO work end to end against a real database") {
    val rules =
      """
        |SecRule REMOTE_ADDR "@geoLookup" "id:1,phase:1,deny,status:403,msg:'blocked from %{GEO.COUNTRY_CODE}',chain"
        |    SecRule GEO:COUNTRY_CODE "@within GB SE" "t:none"
        |SecRuleEngine On
        |""".stripMargin
    val integration = new NoLogSecLangIntegration() {
      override def geoLookup(address: String): Option[Map[String, String]] = city.lookup(address).map(_.seclang)
    }
    val program = SecLang.compile(SecLang.parse(rules).fold(err => throw err.throwable, identity))
    val engine  = SecLang.engine(program, integration = integration)
    def from(ip: String) = engine.evaluate(RequestContext(method = "GET", uri = "/", remoteAddr = ip)).disposition
    assertEquals(from("81.2.69.142"), Disposition.Block(403, Some("blocked from GB"), Some(1)))
    assertEquals(from("216.160.83.56"), Disposition.Continue)
    assertEquals(from("10.0.0.1"), Disposition.Continue)
  }
}

class GeoArchiveSuite extends munit.FunSuite {

  private def extract(raw: Array[Byte], maxBytes: Long = 10L * 1024 * 1024): Path = {
    val source = Files.createTempFile("geo-archive-", ".bin")
    val target = Files.createTempFile("geo-archive-", ".mmdb")
    Files.write(source, raw)
    GeoArchive.extract(source, target, maxBytes)
    target
  }

  test("a raw database is copied as it is") {
    assertEquals(Files.readAllBytes(extract(GeoFixtures.country)).toSeq, GeoFixtures.country.toSeq)
  }

  test("a gzipped database, as DB-IP ships it, is inflated") {
    assertEquals(Files.readAllBytes(extract(GeoFixtures.gzip(GeoFixtures.country))).toSeq, GeoFixtures.country.toSeq)
  }

  test("a tar.gz, as MaxMind ships it, gives up its .mmdb whatever the directory") {
    val archive = GeoFixtures.tarGz(
      "GeoLite2-Country_20261003/COPYRIGHT.txt"             -> "Copyright".getBytes,
      "GeoLite2-Country_20261003/GeoLite2-Country.mmdb"     -> GeoFixtures.country,
      "GeoLite2-Country_20261003/LICENSE.txt"               -> "License".getBytes
    )
    assertEquals(Files.readAllBytes(extract(archive)).toSeq, GeoFixtures.country.toSeq)
  }

  test("an archive without a database is an error") {
    intercept[IllegalStateException](extract(GeoFixtures.tarGz("README.txt" -> "nothing here".getBytes)))
  }

  test("a body that inflates past the limit is refused") {
    val bomb = GeoFixtures.gzip(new Array[Byte](4 * 1024 * 1024))
    assert(bomb.length < 100 * 1024)
    intercept[IllegalStateException](extract(bomb, maxBytes = 1024 * 1024))
  }
}

class GeoRefresherSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global

  private class FakeHttp(answer: HttpCall => HttpResult) extends ReputationHttpClient {
    val calls = new CopyOnWriteArrayList[HttpCall]()
    override def call(request: HttpCall): Future[HttpResult] = {
      calls.add(request)
      Future(answer(request))
    }
  }

  private def ok(body: Array[Byte], headers: (String, String)*) = HttpResult(200, body, headers.map { case (k, v) => k -> Seq(v) }.toMap)
  private def status(code: Int, headers: (String, String)*)     = HttpResult(code, Array.emptyByteArray, headers.map { case (k, v) => k -> Seq(v) }.toMap)

  private def setup(answer: HttpCall => HttpResult) = {
    val http     = new FakeHttp(answer)
    val registry = new ReputationRegistry()
    val retired  = new CopyOnWriteArrayList[GeoSnapshot]()
    val dir      = Files.createTempDirectory("geo-refresher-")
    val refresher = new GeoRefresher(http, registry, () => dir, s => { retired.add(s); () }, play.api.Logger("geo-test"))
    (http, registry, retired, refresher)
  }

  private def await[T](f: Future[T]): T = Await.result(f, 30.seconds)

  private val db = GeoDatabase(id = "geo-1", name = "test", url = "https://geo.example.com/db.mmdb.gz")

  test("a database is downloaded, unpacked and served") {
    val (_, registry, _, refresher) = setup(_ => ok(GeoFixtures.gzip(GeoFixtures.city), "ETag" -> "\"v1\""))
    val snapshot = await(refresher.refresh(db))
    assertEquals(snapshot.error, None)
    assert(snapshot.loaded)
    assertEquals(snapshot.databaseType, Some("GeoLite2-City"))
    assertEquals(registry.geoSnapshot("geo-1").flatMap(_.lookup("81.2.69.142")).flatMap(_.countryCode), Some("GB"))
  }

  test("an unchanged database is not downloaded again, and keeps its reader") {
    val (http, registry, retired, refresher) = setup { call =>
      if (call.headers.contains("If-None-Match" -> "\"v1\"")) status(304)
      else ok(GeoFixtures.gzip(GeoFixtures.city), "ETag" -> "\"v1\"")
    }
    val first  = await(refresher.refresh(db))
    val second = await(refresher.refresh(db))
    assert(second.reader.get eq first.reader.get)
    assertEquals(http.calls.size, 2)
    assertEquals(retired.size, 0)
    assertEquals(registry.geoSnapshot("geo-1").flatMap(_.lookup("81.2.69.142")).flatMap(_.countryCode), Some("GB"))
  }

  test("a newer database replaces the old one, which is retired rather than closed at once") {
    var version = 0
    val (_, _, retired, refresher) = setup { _ =>
      version += 1
      ok(GeoFixtures.gzip(if (version == 1) GeoFixtures.country else GeoFixtures.city), "ETag" -> s"\"v$version\"")
    }
    val first  = await(refresher.refresh(db))
    val second = await(refresher.refresh(db))
    assertEquals(second.databaseType, Some("GeoLite2-City"))
    assertEquals(retired.asScala.toList.map(_.databaseType), List(first.databaseType))
    assertEquals(first.lookup("81.2.69.142").flatMap(_.countryCode), Some("GB"), "the retired generation still answers")
  }

  test("a failed refresh keeps serving the last good database, and is retried soon") {
    var fail = false
    val (_, registry, retired, refresher) = setup(_ => if (fail) status(503) else ok(GeoFixtures.gzip(GeoFixtures.city)))
    await(refresher.refresh(db))
    fail = true
    val failed = await(refresher.refresh(db))
    assert(failed.error.exists(_.contains("503")))
    assert(failed.loaded)
    assertEquals(retired.size, 0)
    assertEquals(registry.geoSnapshot("geo-1").flatMap(_.lookup("81.2.69.142")).flatMap(_.countryCode), Some("GB"))
    val now = failed.fetchedAt
    assert(!refresher.isDue(db, now + 60 * 1000), "not hammered")
    assert(refresher.isDue(db, now + 6 * 60 * 1000), "but not left alone for a day")
  }

  test("a body that is not a database is refused, and the previous one kept") {
    var garbage = false
    val (_, registry, _, refresher) = setup(_ => ok(if (garbage) "<html>maintenance</html>".getBytes else GeoFixtures.gzip(GeoFixtures.city)))
    await(refresher.refresh(db))
    garbage = true
    val snapshot = await(refresher.refresh(db))
    assert(snapshot.error.isDefined)
    assertEquals(registry.geoSnapshot("geo-1").flatMap(_.lookup("81.2.69.142")).flatMap(_.countryCode), Some("GB"))
  }

  test("a first download that fails leaves an error to show, not a stuck state") {
    val (_, _, _, refresher) = setup(_ => throw new java.net.ConnectException("connection refused"))
    val snapshot = await(refresher.refresh(db))
    assert(!snapshot.loaded)
    assert(snapshot.error.exists(_.contains("connection refused")))
    assert(refresher.isDue(db, snapshot.fetchedAt + 6 * 60 * 1000))
  }

  test("a monthly url falls back to the previous month while the current one is not published") {
    val month    = YearMonth.now(ZoneOffset.UTC)
    val previous = month.minusMonths(1)
    val expected = f"https://download.example.com/dbip-country-lite-${previous.getYear}%04d-${previous.getMonthValue}%02d.mmdb.gz"
    val (http, _, _, refresher) = setup(call => if (call.url == expected) ok(GeoFixtures.gzip(GeoFixtures.country)) else status(404))
    val snapshot = await(refresher.refresh(db.copy(url = "https://download.example.com/dbip-country-lite-{yyyy}-{MM}.mmdb.gz")))
    assertEquals(snapshot.error, None)
    assertEquals(snapshot.url, Some(expected))
    assertEquals(http.calls.size, 2)
  }

  test("credentials follow a redirect on the same host, never to another one") {
    val (http, _, _, refresher) = setup { call =>
      call.url match {
        case "https://download.maxmind.com/geoip/databases/GeoLite2-City/download?suffix=tar.gz" =>
          status(302, "Location" -> "/geoip/databases/GeoLite2-City/latest")
        case "https://download.maxmind.com/geoip/databases/GeoLite2-City/latest"                 =>
          status(302, "Location" -> "https://storage.example.com/presigned?signature=abc")
        case _                                                                                    =>
          ok(GeoFixtures.tarGz("GeoLite2-City_20261003/GeoLite2-City.mmdb" -> GeoFixtures.city))
      }
    }
    val maxmind = db.copy(
      url = "https://download.maxmind.com/geoip/databases/GeoLite2-City/download?suffix=tar.gz",
      username = Some("123456"),
      password = Some("license-key")
    )
    val snapshot = await(refresher.refresh(maxmind))
    assertEquals(snapshot.error, None)
    val calls = http.calls.asScala.toList
    assertEquals(calls.map(_.url.takeWhile(_ != '?')), List(
      "https://download.maxmind.com/geoip/databases/GeoLite2-City/download",
      "https://download.maxmind.com/geoip/databases/GeoLite2-City/latest",
      "https://storage.example.com/presigned"
    ))
    assert(calls(1).headers.exists(_._1 == "Authorization"), "same host keeps the credentials")
    assert(!calls(2).headers.exists(_._1 == "Authorization"), "another host never sees them")
    // the url kept is the one configured, which is what the next conditional request is about
    assertEquals((snapshot.json \ "url").asOpt[String], Some("https://download.maxmind.com/geoip/databases/GeoLite2-City/download?…"))
  }

  test("a disabled database is not fetched") {
    val (http, _, _, refresher) = setup(_ => ok(GeoFixtures.gzip(GeoFixtures.city)))
    val snapshot = await(refresher.refresh(db.copy(enabled = false)))
    assert(!snapshot.loaded)
    assertEquals(http.calls.size, 0)
  }
}
