package com.cloud.apim.otoroshi.extensions.waf.reputation

import play.api.libs.json.*

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import scala.util.Try

/** One autonomous system, as published by the routing tables. */
final case class AsnRecord(asn: Int, country: String, org: String) {
  lazy val lowerOrg: String = org.toLowerCase
  def json: JsValue         = Json.obj("asn" -> asn, "country" -> country, "org" -> org)
}

/** An address resolved to its network, and what we make of that network. */
final case class AsnMatch(record: AsnRecord, category: Option[String], weight: Int, tag: String, blocking: Boolean) {
  def json: JsValue = Json.obj(
    "asn"      -> record.asn,
    "country"  -> record.country,
    "org"      -> record.org,
    "category" -> category,
    "weight"   -> weight,
    "tag"      -> tag,
    "blocking" -> blocking
  )
}

final case class AsnSnapshot(
    ranges: IpRangeMap[AsnRecord],
    entries: Int,
    rejected: Int,
    fetchedAt: Long,
    error: Option[String]
) {
  def json: JsValue = Json.obj(
    "entries"    -> entries,
    "rejected"   -> rejected,
    "fetched_at" -> fetchedAt,
    "error"      -> error
  )
}

object AsnSnapshot {
  def empty: AsnSnapshot = AsnSnapshot(IpRangeMap.empty[AsnRecord], 0, 0, 0L, None)
  def failed(error: String, previous: Option[AsnSnapshot]): AsnSnapshot = previous match {
    // a failed refresh must not blind the classifier — keep the table we already have
    case Some(prev) => prev.copy(error = Some(error), fetchedAt = System.currentTimeMillis())
    case None       => AsnSnapshot(IpRangeMap.empty[AsnRecord], 0, 0, System.currentTimeMillis(), Some(error))
  }
}

object AsnParser {

  val formats: Seq[String] = Seq("iptoasn_tsv")

  /** Transparently handles a gzipped payload, which is how these tables are actually published. */
  def decode(bytes: Array[Byte], gzip: Boolean): Either[String, String] = {
    if (!gzip) Right(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
    else
      Try {
        val in  = new GZIPInputStream(new ByteArrayInputStream(bytes))
        val out = in.readAllBytes()
        in.close()
        new String(out, java.nio.charset.StandardCharsets.UTF_8)
      }.toEither.left.map(err => s"could not gunzip the payload: ${err.getMessage}")
  }

  /**
   * `range_start \t range_end \t asn \t country \t description`, with inclusive bounds.
   *
   * Rows with `asn = 0` mean "not routed" and are dropped rather than counted as rejects — they are
   * a normal and large part of the file, not a parse failure.
   */
  def parse(format: String, body: String): Either[String, Vector[(String, String, AsnRecord)]] = {
    format.trim.toLowerCase match {
      case "iptoasn_tsv" | "iptoasn" | "tsv" =>
        val rows = body.linesIterator.flatMap { line =>
          if (line.isEmpty || line.startsWith("#")) None
          else {
            val cols = line.split("\t")
            if (cols.length < 5) None
            else {
              cols(2).trim.toIntOption.filter(_ > 0).map { asn =>
                (cols(0).trim, cols(1).trim, AsnRecord(asn, cols(3).trim, cols(4).trim))
              }
            }
          }
        }.toVector
        if (rows.isEmpty) Left("no routed row found — wrong format, or an empty table?")
        else Right(rows)
      case other                             =>
        Left(s"unknown asn format '$other', expected one of ${formats.mkString(", ")}")
    }
  }
}

/**
 * Fetches and indexes the address-to-network table.
 *
 * Same contract as the threat feed refresher: conditional requests, a failed refresh keeps the last
 * good table, and nothing here ever runs on the request path. The table is large — half a million
 * routed ranges — so the build is done once per refresh and swapped in atomically.
 */
class AsnRefresher(
    http: ReputationHttpClient,
    registry: ReputationRegistry,
    logger: play.api.Logger
)(using ec: scala.concurrent.ExecutionContext) {

  import com.cloud.apim.otoroshi.extensions.waf.entities.AsnDatabase
  import otoroshi.utils.syntax.implicits.*

  def isDue(db: AsnDatabase, now: Long): Boolean = registry.asnSnapshot(db.id) match {
    case None       => true
    case Some(snap) => now - snap.fetchedAt >= db.refreshInterval.toMillis
  }

  def refresh(db: AsnDatabase): scala.concurrent.Future[AsnSnapshot] = {
    val previous = registry.asnSnapshot(db.id)
    if (!db.usable) {
      val snapshot = AsnSnapshot.failed("database is disabled or has no url", previous)
      registry.putAsnSnapshot(db.id, snapshot)
      snapshot.vfuture
    } else {
      http
        .call(HttpCall("GET", db.url, Seq.empty, None, db.timeout))
        .map { response =>
          val snapshot = if (response.status >= 400) {
            AsnSnapshot.failed(s"asn table responded with ${response.status}", previous)
          } else {
            AsnParser.decode(response.bodyBytes, db.gzip).flatMap(AsnParser.parse(db.format, _)) match {
              case Left(error) => AsnSnapshot.failed(error, previous)
              case Right(rows) =>
                val capped = if (rows.size > db.maxEntries) rows.take(db.maxEntries) else rows
                val built  = IpRangeMap.build(capped)
                AsnSnapshot(built.map, built.accepted, built.rejected, System.currentTimeMillis(), None)
            }
          }
          registry.putAsnSnapshot(db.id, snapshot)
          snapshot.error match {
            case Some(error) => logger.warn(s"asn database '${db.name}' refresh failed: $error")
            case None        =>
              logger.info(s"asn database '${db.name}' refreshed: ${snapshot.entries} networks, ${snapshot.rejected} rejected")
          }
          snapshot
        }
        .recover { case err: Throwable =>
          val snapshot = AsnSnapshot.failed(s"fetch failed: ${err.getMessage}", previous)
          registry.putAsnSnapshot(db.id, snapshot)
          logger.warn(s"asn database '${db.name}' fetch failed", err)
          snapshot
        }
    }
  }
}
