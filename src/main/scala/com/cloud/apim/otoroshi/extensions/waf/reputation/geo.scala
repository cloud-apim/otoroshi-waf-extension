package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.otoroshi.extensions.waf.entities.GeoDatabase
import com.maxmind.db.{CHMCache, Reader}
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import play.api.libs.json.*

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.net.{InetAddress, URI}
import java.nio.file.{Files, Path}
import java.time.{YearMonth, ZoneOffset}
import java.util.zip.GZIPInputStream
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Try

/** Where an address is, as far as the database knows. The free databases mostly know the country. */
final case class GeoLocation(
    countryCode: Option[String] = None,
    countryName: Option[String] = None,
    continentCode: Option[String] = None,
    continentName: Option[String] = None,
    regionCode: Option[String] = None,
    regionName: Option[String] = None,
    city: Option[String] = None,
    postalCode: Option[String] = None,
    latitude: Option[Double] = None,
    longitude: Option[Double] = None
) {

  def isEmpty: Boolean = countryCode.isEmpty && continentCode.isEmpty && city.isEmpty && latitude.isEmpty

  /** ModSecurity's `GEO` collection, as `@geoLookup` fills it. */
  def seclang: Map[String, String] = Seq(
    "COUNTRY_CODE"      -> countryCode,
    "COUNTRY_CODE3"     -> countryCode.flatMap(GeoLocation.iso3),
    "COUNTRY_NAME"      -> countryName,
    "COUNTRY_CONTINENT" -> continentCode,
    "REGION"            -> regionCode.orElse(regionName),
    "CITY"              -> city,
    "POSTAL_CODE"       -> postalCode,
    "LATITUDE"          -> latitude.map(_.toString),
    "LONGITUDE"         -> longitude.map(_.toString)
  ).collect { case (k, Some(v)) if v.nonEmpty => k -> v }.toMap

  def json: JsValue = Json.obj(
    "country"        -> countryCode,
    "country_name"   -> countryName,
    "continent"      -> continentCode,
    "continent_name" -> continentName,
    "region"         -> regionCode,
    "region_name"    -> regionName,
    "city"           -> city,
    "postal_code"    -> postalCode,
    "latitude"       -> latitude,
    "longitude"      -> longitude
  )
}

object GeoLocation {

  type Record = java.util.Map[String, AnyRef]

  def iso3(countryCode: String): Option[String] =
    Try(new java.util.Locale.Builder().setRegion(countryCode).build().getISO3Country).toOption.filter(_.nonEmpty)

  private def obj(value: Any): Option[Record] = value match {
    case m: java.util.Map[?, ?] => Some(m.asInstanceOf[Record])
    case _                      => None
  }

  private def str(value: Any): Option[String] = value match {
    case s: String if s.trim.nonEmpty => Some(s.trim)
    case _                            => None
  }

  private def num(value: Any): Option[Double] = value match {
    case n: java.lang.Number => Some(n.doubleValue())
    case _                   => None
  }

  private def at(record: Record, path: String*): Option[AnyRef] =
    path.foldLeft(Option[AnyRef](record))((current, key) => current.flatMap(obj).flatMap(m => Option(m.get(key))))

  /**
   * Reads both shapes found in the wild.
   *
   * MaxMind and DB-IP nest objects with localised names (`country.iso_code`, `city.names.en`);
   * IPinfo and IP66 are flat (`country_code`, `country`). A field one shape lacks is just absent.
   */
  def fromRecord(record: Record): GeoLocation = {
    val subdivision = at(record, "subdivisions") match {
      case Some(list: java.util.List[?]) if !list.isEmpty => obj(list.get(0))
      case _                                               => None
    }
    GeoLocation(
      countryCode = at(record, "country", "iso_code").flatMap(str).orElse(at(record, "country_code").flatMap(str)).map(_.toUpperCase),
      countryName = at(record, "country", "names", "en").flatMap(str).orElse(at(record, "country").flatMap(str)),
      continentCode = at(record, "continent", "code").flatMap(str).orElse(at(record, "continent_code").flatMap(str)),
      continentName = at(record, "continent", "names", "en").flatMap(str).orElse(at(record, "continent").flatMap(str)),
      regionCode = subdivision.flatMap(s => at(s, "iso_code")).flatMap(str),
      regionName = subdivision.flatMap(s => at(s, "names", "en")).flatMap(str),
      city = at(record, "city", "names", "en").flatMap(str).orElse(at(record, "city").flatMap(str)),
      postalCode = at(record, "postal", "code").flatMap(str),
      latitude = at(record, "location", "latitude").flatMap(num).orElse(at(record, "latitude").flatMap(num)),
      longitude = at(record, "location", "longitude").flatMap(num).orElse(at(record, "longitude").flatMap(num))
    )
  }

  /**
   * An address literal, never a name: `InetAddress.getByName` would resolve a host name, and the
   * value comes from the request.
   */
  def address(ip: String): Option[InetAddress] = {
    val trimmed = ip.trim
    IpParser.parseV4(trimmed) match {
      case Some(v4) =>
        Some(InetAddress.getByAddress(Array((v4 >> 24).toByte, (v4 >> 16).toByte, (v4 >> 8).toByte, v4.toByte)))
      case None     =>
        IpParser.parseV6(trimmed).map { v6 =>
          val raw   = v6.toByteArray.takeRight(16)
          val bytes = Array.fill[Byte](16 - raw.length)(0) ++ raw
          InetAddress.getByAddress(bytes)
        }
    }
  }
}

/**
 * One generation of a database: a memory-mapped reader over a file this node downloaded.
 *
 * Swapped atomically; the generation it replaces is closed later, so a lookup already holding it
 * finishes on a reader that is still open.
 */
final case class GeoSnapshot(
    reader: Option[Reader],
    file: Option[Path],
    url: Option[String],
    etag: Option[String],
    lastModified: Option[String],
    databaseType: Option[String],
    buildTime: Option[Long],
    ipVersion: Option[Int],
    sizeBytes: Long,
    loadedAt: Long,
    fetchedAt: Long,
    error: Option[String]
) {

  def loaded: Boolean = reader.isDefined

  def lookup(ip: String): Option[GeoLocation] = reader.flatMap { db =>
    GeoLocation.address(ip).flatMap { address =>
      // a reader closed under our feet throws, and that is a miss rather than a failure
      Try(Option(db.get(address, classOf[java.util.Map[?, ?]]))).toOption.flatten
        .map(record => GeoLocation.fromRecord(record.asInstanceOf[GeoLocation.Record]))
        .filterNot(_.isEmpty)
    }
  }

  def json: JsValue = Json.obj(
    "loaded"        -> loaded,
    "url"           -> url.map(GeoDatabase.redact),
    "database_type" -> databaseType,
    "build_time"    -> buildTime,
    "ip_version"    -> ipVersion,
    "size_bytes"    -> sizeBytes,
    "loaded_at"     -> (if (loaded) Some(loadedAt) else None),
    "fetched_at"    -> fetchedAt,
    "error"         -> error
  )
}

object GeoSnapshot {
  def failed(error: String, previous: Option[GeoSnapshot], now: Long): GeoSnapshot = previous match {
    // a failed refresh must never blind the lookups: keep serving the generation we have
    case Some(prev) => prev.copy(error = Some(error), fetchedAt = now)
    case None       => GeoSnapshot(None, None, None, None, None, None, None, None, 0L, 0L, now, Some(error))
  }
}

/** Gets the `.mmdb` out of whatever the provider ships, without any process or shell. */
object GeoArchive {

  private val TarMagicOffset = 257

  /**
   * Raw, gzipped (DB-IP) or a gzipped tar (MaxMind), told apart by their content and not by a url
   * that may say nothing. The output is capped: a small archive that inflates without end is a
   * decompression bomb, not a database.
   */
  def extract(source: Path, target: Path, maxBytes: Long): Unit = {
    val raw = new BufferedInputStream(Files.newInputStream(source))
    try {
      val inflated = if (isGzip(raw)) new BufferedInputStream(new GZIPInputStream(raw)) else raw
      if (isTar(inflated)) extractTar(inflated, target, maxBytes)
      else copy(inflated, target, maxBytes)
    } finally raw.close()
  }

  private def peek(in: BufferedInputStream, length: Int): Array[Byte] = {
    in.mark(length)
    val bytes = in.readNBytes(length)
    in.reset()
    bytes
  }

  private def isGzip(in: BufferedInputStream): Boolean = {
    val head = peek(in, 2)
    head.length == 2 && (head(0) & 0xff) == 0x1f && (head(1) & 0xff) == 0x8b
  }

  private def isTar(in: BufferedInputStream): Boolean = {
    val head = peek(in, TarMagicOffset + 5)
    head.length == TarMagicOffset + 5 && new String(head, TarMagicOffset, 5, "US-ASCII") == "ustar"
  }

  private def extractTar(in: InputStream, target: Path, maxBytes: Long): Unit = {
    val tar   = new TarArchiveInputStream(in)
    var entry = tar.getNextEntry
    while (entry != null && !(entry.isFile && entry.getName.endsWith(".mmdb"))) entry = tar.getNextEntry
    if (entry == null) throw new IllegalStateException("the archive holds no .mmdb file")
    copy(tar, target, maxBytes)
  }

  private def copy(in: InputStream, target: Path, maxBytes: Long): Unit = {
    val out: OutputStream = Files.newOutputStream(target)
    try {
      val buffer = new Array[Byte](64 * 1024)
      var total  = 0L
      var read   = in.read(buffer)
      while (read >= 0) {
        total += read
        if (total > maxBytes) throw new IllegalStateException(s"the database is larger than ${maxBytes / (1024 * 1024)} MB once extracted")
        out.write(buffer, 0, read)
        read = in.read(buffer)
      }
    } finally out.close()
  }
}

/**
 * Downloads, unpacks, checks and swaps in the geolocation databases.
 *
 * Nothing here runs on the request path, nothing here forks a process, and no failure can leave a
 * database stuck: every attempt ends in a snapshot, the last good generation keeps serving, and an
 * attempt that failed is retried after `retryInterval` rather than after a whole refresh interval.
 */
class GeoRefresher(
    http: ReputationHttpClient,
    registry: ReputationRegistry,
    workDir: () => Path,
    retire: GeoSnapshot => Unit,
    logger: play.api.Logger,
    retryIntervalMillis: Long = 5 * 60 * 1000L,
    maxRedirects: Int = 5
)(using ec: ExecutionContext) {

  def isDue(db: GeoDatabase, now: Long): Boolean = registry.geoSnapshot(db.id) match {
    case None       => true
    case Some(snap) =>
      val wait = if (snap.error.isDefined) math.min(retryIntervalMillis, db.refreshInterval.toMillis) else db.refreshInterval.toMillis
      now - snap.fetchedAt >= wait
  }

  def refresh(db: GeoDatabase): Future[GeoSnapshot] = {
    val previous = registry.geoSnapshot(db.id)
    val result   =
      if (!db.usable) Future.successful(GeoSnapshot.failed("database is disabled or has no url", previous, now()))
      // delegated: a work directory that cannot be created throws before any future exists
      else Future.delegate(attempt(db, db.candidates(YearMonth.now(ZoneOffset.UTC)), previous))
    result
      .recover { case err: Throwable => GeoSnapshot.failed(s"refresh failed: ${err.getMessage}", previous, now()) }
      .map { snapshot =>
        registry.putGeoSnapshot(db.id, snapshot)
        // the generation that was serving until now, if this refresh replaced it
        previous.filter(p => p.loaded && !snapshot.reader.exists(r => p.reader.exists(_ eq r))).foreach(retire)
        snapshot.error match {
          case Some(error) => logger.warn(s"geolocation database '${db.name}' refresh failed: $error")
          case None if previous.exists(p => snapshot.reader.exists(r => p.reader.exists(_ eq r))) => ()
          case None        =>
            logger.info(
              s"geolocation database '${db.name}' loaded: ${snapshot.databaseType.getOrElse("unknown type")}, " +
              s"${snapshot.sizeBytes / 1024} KB, from ${snapshot.url.map(GeoDatabase.redact).getOrElse("--")}"
            )
        }
        snapshot
      }
  }

  private def now(): Long = System.currentTimeMillis()

  private def attempt(db: GeoDatabase, urls: List[String], previous: Option[GeoSnapshot]): Future[GeoSnapshot] = urls match {
    case Nil         => Future.successful(GeoSnapshot.failed("no url to fetch", previous, now()))
    case url :: rest =>
      // only ask "has it changed" about the file we actually hold
      val current     = previous.filter(p => p.loaded && p.url.contains(url))
      val conditional = current.toSeq.flatMap(p => p.etag.map("If-None-Match" -> _).toSeq ++ p.lastModified.map("If-Modified-Since" -> _).toSeq)
      val dir         = Files.createDirectories(workDir())
      val download    = Files.createTempFile(dir, "download-", ".part")
      fetch(url, db.requestHeaders, conditional, db, download, maxRedirects)
        .flatMap { response =>
          response.status match {
            case 304 if current.isDefined =>
              Files.deleteIfExists(download)
              Future.successful(current.get.copy(fetchedAt = now(), error = None))
            case 404 if rest.nonEmpty     =>
              // this month's file is not published yet: the previous month's is the latest
              Files.deleteIfExists(download)
              attempt(db, rest, previous)
            case _ if response.isSuccess  =>
              Future(blocking(install(db, url, response, download)))
            case status                   =>
              Files.deleteIfExists(download)
              Future.successful(GeoSnapshot.failed(s"${GeoDatabase.redact(url)} responded with $status", previous, now()))
          }
        }
        .andThen { case _ => Files.deleteIfExists(download) }
  }

  /**
   * Redirects are followed here rather than by the client, because credentials must not be: MaxMind
   * answers with a presigned storage url, which refuses a request that also carries basic auth.
   */
  private def fetch(
      url: String,
      auth: Seq[(String, String)],
      conditional: Seq[(String, String)],
      db: GeoDatabase,
      target: Path,
      hops: Int
  ): Future[HttpDownload] = {
    http
      .download(HttpCall("GET", url, auth ++ conditional, None, db.timeout, followRedirects = false), target, db.maxBytes)
      .flatMap { response =>
        response.header("Location").filter(_ => response.isRedirect) match {
          case Some(location) if hops > 0 =>
            val next     = URI.create(url).resolve(location).toString
            val sameHost = Try(URI.create(next).getHost == URI.create(url).getHost).getOrElse(false)
            fetch(next, if (sameHost) auth else Seq.empty, conditional, db, target, hops - 1)
          case Some(_)                    => Future.failed(new IllegalStateException("too many redirects"))
          case None                       => Future.successful(response)
        }
      }
  }

  private def install(db: GeoDatabase, url: String, response: HttpDownload, download: Path): GeoSnapshot = {
    val dir    = Files.createDirectories(workDir())
    val target = Files.createTempFile(dir, s"${db.id.replaceAll("[^a-zA-Z0-9_-]", "_")}-", ".mmdb")
    try {
      GeoArchive.extract(download, target, db.maxBytes)
      // opening it is the check: a truncated file or an html error page served as 200 fails here
      val reader   = new Reader(target.toFile, new CHMCache())
      val metadata = reader.getMetadata
      if (metadata.nodeCount() <= 0) {
        reader.close()
        throw new IllegalStateException("the database is empty")
      }
      GeoSnapshot(
        reader = Some(reader),
        file = Some(target),
        url = Some(url),
        etag = response.header("ETag"),
        lastModified = response.header("Last-Modified"),
        databaseType = Option(metadata.databaseType()),
        buildTime = Option(metadata.buildTime()).map(_.toEpochMilli),
        ipVersion = Some(metadata.ipVersion()),
        sizeBytes = Files.size(target),
        loadedAt = now(),
        fetchedAt = now(),
        error = None
      )
    } catch {
      case err: Throwable =>
        Files.deleteIfExists(target)
        throw err
    }
  }
}
