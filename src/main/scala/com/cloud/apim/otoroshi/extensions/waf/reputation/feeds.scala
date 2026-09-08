package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.otoroshi.extensions.waf.entities.ThreatFeed
import otoroshi.utils.syntax.implicits.*
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

/**
 * Fetches a feed and turns it into a snapshot.
 *
 * Two rules hold everywhere in here, because a threat feed is an external dependency on the
 * protection path: a refresh never blocks a request, and a failed refresh never empties a feed —
 * the previous snapshot keeps serving and the error is surfaced instead of silently disabling
 * protection.
 */
class FeedRefresher(http: ReputationHttpClient, registry: ReputationRegistry, logger: Logger)(using
    ec: ExecutionContext
) {

  def refresh(feed: ThreatFeed): Future[FeedSnapshot] = {
    val previous = registry.snapshot(feed.id)
    if (!feed.usable) {
      val snapshot = FeedSnapshot.failed(feed.id, "feed is disabled or has no url", previous)
      registry.putSnapshot(snapshot)
      snapshot.vfuture
    } else {
      // conditional request: most public lists change slowly, and 304 keeps both sides cheap
      val conditional = Seq(
        previous.flatMap(_.etag).map(v => "If-None-Match"     -> v),
        previous.flatMap(_.lastModified).map(v => "If-Modified-Since" -> v)
      ).flatten
      val headers     = (feed.headers.toSeq ++ conditional).filter(_._2.trim.nonEmpty)

      http
        .call(HttpCall(feed.method, feed.url, headers, None, feed.timeout, feed.followRedirects))
        .map { response =>
          val snapshot = response.status match {
            case 304                     =>
              previous match {
                case Some(prev) => prev.copy(fetchedAt = System.currentTimeMillis(), notModified = true, error = None)
                case None       => FeedSnapshot.failed(feed.id, "got 304 without a cached snapshot", None)
              }
            case status if status >= 400 =>
              FeedSnapshot.failed(feed.id, s"feed responded with $status: ${response.body.take(200)}", previous)
            case _                       =>
              FeedParser.parse(feed.format, feed.options, response.body) match {
                case Left(error)    => FeedSnapshot.failed(feed.id, error, previous)
                case Right(entries) =>
                  val capped = if (entries.size > feed.maxEntries) entries.take(feed.maxEntries) else entries
                  val built  = IpRangeSet.build(capped)
                  if (built.accepted == 0 && capped.nonEmpty) {
                    FeedSnapshot.failed(
                      feed.id,
                      s"parsed ${capped.size} lines but none was a valid address or cidr — wrong format?",
                      previous
                    )
                  } else {
                    FeedSnapshot(
                      feedId = feed.id,
                      ranges = built.set,
                      entries = built.accepted,
                      rejected = built.rejected,
                      fetchedAt = System.currentTimeMillis(),
                      etag = response.header("ETag"),
                      lastModified = response.header("Last-Modified"),
                      notModified = false,
                      error = None
                    )
                  }
              }
          }
          registry.putSnapshot(snapshot)
          snapshot.error match {
            case Some(error) => logger.warn(s"threat feed '${feed.name}' (${feed.id}) refresh failed: $error")
            case None        =>
              if (!snapshot.notModified) {
                logger.info(
                  s"threat feed '${feed.name}' (${feed.id}) refreshed: ${snapshot.entries} entries, " +
                  s"${snapshot.ranges.size} merged ranges, ${snapshot.rejected} rejected"
                )
              }
          }
          snapshot
        }
        .andThen { case Failure(err) =>
          val snapshot = FeedSnapshot.failed(feed.id, s"fetch failed: ${err.getMessage}", previous)
          registry.putSnapshot(snapshot)
          logger.warn(s"threat feed '${feed.name}' (${feed.id}) fetch failed", err)
        }
        .recover { case err: Throwable =>
          FeedSnapshot.failed(feed.id, s"fetch failed: ${err.getMessage}", previous)
        }
    }
  }

  def isDue(feed: ThreatFeed, now: Long): Boolean = {
    registry.snapshot(feed.id) match {
      case None       => true
      case Some(snap) => now - snap.fetchedAt >= feed.refreshInterval.toMillis
    }
  }
}
