package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.otoroshi.extensions.waf.entities.{AsnDatabase, CrowdSecBouncer, ThreatFeed}
import play.api.libs.json.*
import play.api.libs.typedmap.TypedKey

import scala.collection.concurrent.TrieMap

/** A single reason why an address was flagged, kept with enough context to be explainable. */
final case class ReputationHit(
    kind: String,
    sourceId: String,
    sourceName: String,
    tag: String,
    weight: Int,
    blocking: Boolean,
    detail: Option[String] = None
) {
  def json: JsValue = Json.obj(
    "kind"        -> kind,
    "source_id"   -> sourceId,
    "source_name" -> sourceName,
    "tag"         -> tag,
    "weight"      -> weight,
    "blocking"    -> blocking,
    "detail"      -> detail
  )
}

/**
 * The outcome of a lookup.
 *
 * `score` is capped additive rather than a maximum, so three weak signals can add up to something
 * an operator wants to act on. `blocked` stays separate from the score because a feed can be
 * authoritative enough to block on its own regardless of the total.
 */
final case class ReputationVerdict(ip: String, hits: List[ReputationHit]) {
  lazy val score: Int         = math.min(100, hits.map(_.weight).sum)
  lazy val blocking: Boolean  = hits.exists(_.blocking)
  lazy val tags: Seq[String]  = hits.map(_.tag).distinct
  def isEmpty: Boolean        = hits.isEmpty
  def nonEmpty: Boolean       = hits.nonEmpty
  def json: JsValue = Json.obj(
    "ip"       -> ip,
    "score"    -> score,
    "blocking" -> blocking,
    "tags"     -> tags,
    "hits"     -> JsArray(hits.map(_.json))
  )
}

object ReputationVerdict {
  def empty(ip: String): ReputationVerdict = ReputationVerdict(ip, List.empty)
}

object ReputationKeys {
  /**
   * Published for every evaluated request, whether or not anything matched.
   *
   * Deliberately a plain value in `attrs`: when a shared threat-score bus lands, this becomes one
   * contributor to it instead of a private channel, and nothing downstream has to change shape.
   */
  val VerdictKey: TypedKey[ReputationVerdict] = TypedKey[ReputationVerdict]("cloud-apim.reputation.Verdict")
}

/** An immutable, atomically swapped view of one feed's content. */
final case class FeedSnapshot(
    feedId: String,
    ranges: IpRangeSet,
    entries: Int,
    rejected: Int,
    fetchedAt: Long,
    etag: Option[String],
    lastModified: Option[String],
    notModified: Boolean,
    error: Option[String]
) {
  def json: JsValue = Json.obj(
    "feed_id"       -> feedId,
    "entries"       -> entries,
    "ranges"        -> ranges.size,
    "rejected"      -> rejected,
    "fetched_at"    -> fetchedAt,
    "etag"          -> etag,
    "last_modified" -> lastModified,
    "not_modified"  -> notModified,
    "error"         -> error
  )
}

object FeedSnapshot {
  def failed(feedId: String, error: String, previous: Option[FeedSnapshot]): FeedSnapshot = {
    previous match {
      // a failed refresh must never empty a feed — keep serving the last good content and surface the error
      case Some(prev) => prev.copy(error = Some(error), fetchedAt = System.currentTimeMillis())
      case None       =>
        FeedSnapshot(feedId, IpRangeSet.empty, 0, 0, System.currentTimeMillis(), None, None, false, Some(error))
    }
  }
}

final case class CrowdSecDecision(
    id: Long,
    origin: String,
    typ: String,
    scope: String,
    value: String,
    duration: String,
    scenario: String
) {
  def json: JsValue = Json.obj(
    "id"       -> id,
    "origin"   -> origin,
    "type"     -> typ,
    "scope"    -> scope,
    "value"    -> value,
    "duration" -> duration,
    "scenario" -> scenario
  )
}

/**
 * Node-local mirror of the decisions held by one CrowdSec LAPI.
 *
 * CrowdSec's stream endpoint hands out deltas, so this holds the accumulated state and rebuilds
 * the range index only when a range-scoped decision actually changes — the common delta touches
 * single addresses and costs nothing.
 */
final class CrowdSecStore {

  private val exact  = new TrieMap[String, CrowdSecDecision]()
  private val ranges = new TrieMap[String, CrowdSecDecision]()

  @volatile private var rangeSet: IpRangeSet = IpRangeSet.empty
  @volatile var lastSync: Long               = 0L
  @volatile var lastError: Option[String]    = None
  @volatile var initialized: Boolean         = false

  def size: Int = exact.size + ranges.size

  def applyDelta(added: Seq[CrowdSecDecision], deleted: Seq[CrowdSecDecision]): Unit = {
    var rangesTouched = false
    deleted.foreach { decision =>
      if (decision.scope.equalsIgnoreCase("range")) {
        if (ranges.remove(decision.value).isDefined) rangesTouched = true
      } else {
        exact.remove(decision.value)
      }
    }
    added.foreach { decision =>
      if (decision.scope.equalsIgnoreCase("range")) {
        ranges.put(decision.value, decision)
        rangesTouched = true
      } else {
        exact.put(decision.value, decision)
      }
    }
    if (rangesTouched) {
      rangeSet = IpRangeSet.build(ranges.keys).set
    }
    lastSync = System.currentTimeMillis()
  }

  def reset(): Unit = {
    exact.clear()
    ranges.clear()
    rangeSet = IpRangeSet.empty
    initialized = false
  }

  def lookup(ip: String): Option[CrowdSecDecision] = {
    exact.get(ip).orElse {
      // the range index answers first; the scan only runs on an actual hit, and range decisions are few
      if (rangeSet.contains(ip)) ranges.values.find(d => IpRangeSet.build(Seq(d.value)).set.contains(ip))
      else None
    }
  }

  def status: JsValue = Json.obj(
    "decisions"   -> size,
    "exact"       -> exact.size,
    "ranges"      -> ranges.size,
    "last_sync"   -> lastSync,
    "initialized" -> initialized,
    "last_error"  -> lastError
  )
}

/** Holds every runtime index. Written by the refresher, read by the plugins. */
final class ReputationRegistry {

  private val snapshots = new TrieMap[String, FeedSnapshot]()
  private val asnSnapshots = new TrieMap[String, AsnSnapshot]()
  private val previous  = new TrieMap[String, FeedSnapshot]()
  private val bouncers  = new TrieMap[String, CrowdSecStore]()

  def snapshot(feedId: String): Option[FeedSnapshot]         = snapshots.get(feedId)
  def previousSnapshot(feedId: String): Option[FeedSnapshot] = previous.get(feedId)
  def allSnapshots: Seq[FeedSnapshot]                        = snapshots.values.toSeq

  /**
   * Keeps one generation back so a refresh that succeeded but brought garbage can be undone.
   *
   * Only a clean replacement of clean content creates a rollback point: a failed refresh already
   * keeps serving the previous content, and promoting it would throw away the good generation.
   */
  def putSnapshot(snapshot: FeedSnapshot): Unit = {
    snapshots.get(snapshot.feedId).foreach { current =>
      if (current.error.isEmpty && snapshot.error.isEmpty && !snapshot.notModified) {
        previous.put(snapshot.feedId, current)
      }
    }
    snapshots.put(snapshot.feedId, snapshot)
  }

  def rollbackSnapshot(feedId: String): Option[FeedSnapshot] = {
    previous.remove(feedId).map { restored =>
      snapshots.put(feedId, restored)
      restored
    }
  }

  def removeSnapshot(feedId: String): Unit = {
    snapshots.remove(feedId)
    previous.remove(feedId)
  }

  def retainSnapshots(ids: Set[String]): Unit = {
    snapshots.keySet.diff(ids).foreach(snapshots.remove)
    previous.keySet.diff(ids).foreach(previous.remove)
  }

  def asnSnapshot(id: String): Option[AsnSnapshot]       = asnSnapshots.get(id)
  def putAsnSnapshot(id: String, snap: AsnSnapshot): Unit = { asnSnapshots.put(id, snap); () }
  def allAsnSnapshots: Map[String, AsnSnapshot]           = asnSnapshots.readOnlySnapshot().toMap
  def retainAsnSnapshots(ids: Set[String]): Unit          = asnSnapshots.keySet.diff(ids).foreach(asnSnapshots.remove)

  def crowdSecStore(bouncerId: String): CrowdSecStore  = bouncers.getOrElseUpdate(bouncerId, new CrowdSecStore())
  def crowdSecStoreOpt(id: String): Option[CrowdSecStore] = bouncers.get(id)
  def allCrowdSecStores: Map[String, CrowdSecStore]    = bouncers.readOnlySnapshot().toMap
  def retainCrowdSecStores(ids: Set[String]): Unit     = bouncers.keySet.diff(ids).foreach(bouncers.remove)

  def lookup(
      ip: String,
      feeds: Seq[ThreatFeed],
      crowdsec: Seq[CrowdSecBouncer],
      asnDatabases: Seq[AsnDatabase] = Seq.empty
  ): ReputationVerdict = {
    if (ip.isEmpty) ReputationVerdict.empty(ip)
    else {
      val feedHits = feeds.iterator.flatMap { feed =>
        snapshots.get(feed.id).filter(_.ranges.contains(ip)).map { snap =>
          ReputationHit(
            kind = "feed",
            sourceId = feed.id,
            sourceName = feed.name,
            tag = feed.effectiveTag,
            weight = feed.weight,
            blocking = feed.blocking,
            detail = Some(s"${snap.ranges.size} ranges, refreshed ${snap.fetchedAt}")
          )
        }
      }
      val crowdSecHits = crowdsec.iterator.flatMap { bouncer =>
        bouncers.get(bouncer.id).flatMap(_.lookup(ip)).filter(d => bouncer.acceptsOrigin(d.origin)).map { decision =>
          ReputationHit(
            kind = "crowdsec",
            sourceId = bouncer.id,
            sourceName = bouncer.name,
            tag = bouncer.effectiveTag,
            weight = bouncer.weight,
            blocking = bouncer.blocking,
            detail = Some(s"${decision.typ} from ${decision.origin} — ${decision.scenario}")
          )
        }
      }
      // the network the caller sits on: a signal, deliberately never a verdict on its own
      val asnHits = asnDatabases.iterator.flatMap { db =>
        asnSnapshots.get(db.id).flatMap(_.ranges.get(ip)).map(db.classify).filter(m => m.weight > 0 || m.blocking).map { m =>
          ReputationHit(
            kind = "asn",
            sourceId = db.id,
            sourceName = db.name,
            tag = m.tag,
            weight = m.weight,
            blocking = m.blocking,
            detail = Some(s"AS${m.record.asn} ${m.record.org}${m.category.map(c => s" — $c").getOrElse("")}")
          )
        }
      }
      ReputationVerdict(ip, (feedHits ++ crowdSecHits ++ asnHits).toList)
    }
  }

  def status: JsValue = Json.obj(
    "feeds"    -> JsArray(allSnapshots.map(_.json)),
    "crowdsec" -> JsObject(allCrowdSecStores.view.mapValues(_.status).toMap),
    "asn"      -> JsObject(allAsnSnapshots.view.mapValues(_.json).toMap)
  )
}
