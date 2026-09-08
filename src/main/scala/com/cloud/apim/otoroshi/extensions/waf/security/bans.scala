package com.cloud.apim.otoroshi.extensions.waf.security

import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

final case class BanEntry(
    ref: IdentityRef,
    reason: String,
    tags: Seq[String],
    score: Int,
    issuedAt: Long,
    until: Long,
    issuedBy: String,
    signals: JsValue = JsArray(Seq.empty)
) {
  def expired(now: Long): Boolean = until <= now
  def remainingMs(now: Long): Long = math.max(0L, until - now)
  def json: JsValue = Json.obj(
    "ref"       -> ref.json,
    "key"       -> ref.key,
    "reason"    -> reason,
    "tags"      -> tags,
    "score"     -> score,
    "issued_at" -> issuedAt,
    "until"     -> until,
    "issued_by" -> issuedBy,
    "signals"   -> signals
  )
}

object BanEntry {
  def read(json: JsValue): Option[BanEntry] = Try {
    BanEntry(
      ref = IdentityRef(
        (json \ "ref" \ "kind").as[String],
        (json \ "ref" \ "value").as[String]
      ),
      reason = (json \ "reason").asOpt[String].getOrElse(""),
      tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty),
      score = (json \ "score").asOpt[Int].getOrElse(0),
      issuedAt = (json \ "issued_at").asOpt[Long].getOrElse(0L),
      until = (json \ "until").asOpt[Long].getOrElse(0L),
      issuedBy = (json \ "issued_by").asOpt[String].getOrElse("unknown"),
      signals = (json \ "signals").asOpt[JsValue].getOrElse(JsArray(Seq.empty))
    )
  }.toOption
}

/**
 * A cluster-wide ban registry.
 *
 * Two properties matter more than anything else here.
 *
 * **Reads never touch the datastore.** `check` answers from a node-local map, because it sits on
 * the request path and the whole point of a ban is to be cheaper than the inspection it replaces.
 * The map is rebuilt off the request path by [[refresh]].
 *
 * **Writes are immediately visible on the issuing node.** A node that bans an address enforces it
 * on the very next request; other nodes pick it up within one refresh interval. Otoroshi's
 * datastore abstraction has no pub/sub, so that bounded staleness is the honest trade — a few
 * seconds of lag on propagation, in exchange for zero I/O per request.
 */
class BanStore(hashKey: String, store: SharedStateStore, nodeId: String, logger: Logger)(using
    ec: ExecutionContext
) {

  private val cache = new TrieMap[String, BanEntry]()

  @volatile var lastRefresh: Long         = 0L
  @volatile var lastError: Option[String] = None

  // the caller passes one hash key rather than a key per ban: refreshing is a single HGETALL
  // instead of a KEYS scan followed by N GETs, which is the difference between usable and unusable
  // on a real redis

  // -----------------------------------------------------------------------------------------------
  // request path — local only, no i/o
  // -----------------------------------------------------------------------------------------------

  /** The ban that applies to this caller, if any. */
  def check(identity: ClientIdentity): Option[BanEntry] = {
    val now = System.currentTimeMillis()
    identity.refs.iterator.flatMap(ref => cache.get(ref.key)).find(!_.expired(now))
  }

  def check(ref: IdentityRef): Option[BanEntry] =
    cache.get(ref.key).filterNot(_.expired(System.currentTimeMillis()))

  def size: Int = cache.size

  def all: Seq[BanEntry] = {
    val now = System.currentTimeMillis()
    cache.values.filterNot(_.expired(now)).toSeq.sortBy(-_.issuedAt)
  }

  // -----------------------------------------------------------------------------------------------
  // writes
  // -----------------------------------------------------------------------------------------------

  def ban(
      ref: IdentityRef,
      duration: FiniteDuration,
      reason: String,
      tags: Seq[String] = Seq.empty,
      score: Int = 0,
      signals: JsValue = JsArray(Seq.empty)
  ): Future[BanEntry] = {
    val now   = System.currentTimeMillis()
    val entry = BanEntry(ref, reason, tags, score, now, now + duration.toMillis, nodeId, signals)
    // local first: the node that decided enforces on its very next request, whatever redis does
    cache.put(ref.key, entry)
    store
      .hset(hashKey, ref.key, Json.stringify(entry.json))
      .map { _ =>
        logger.info(s"banned ${ref.key} for ${duration.toSeconds}s — $reason")
        entry
      }
      .recover { case err: Throwable =>
        // the local ban still stands, it just will not propagate until a later write succeeds
        logger.error(s"could not share the ban on ${ref.key}, it stays local to this node", err)
        entry
      }
  }

  def unban(ref: IdentityRef): Future[Boolean] = {
    cache.remove(ref.key)
    store.hdel(hashKey, Seq(ref.key)).map(_ => true).recover { case _ => false }
  }

  def unbanAll(): Future[Long] = {
    val removed = cache.size.toLong
    cache.clear()
    store.del(hashKey).map(_ => removed).recover { case _ => removed }
  }

  // -----------------------------------------------------------------------------------------------
  // off the request path
  // -----------------------------------------------------------------------------------------------

  /**
   * Rebuilds the local view and prunes what has expired.
   *
   * A hash field carries no ttl of its own, so expiry lives in the entry and is enforced here and
   * on every read. That is deliberate: it keeps a ban auditable right up to the moment it lapses,
   * instead of having it vanish from redis with no trace.
   */
  def refresh(): Future[Int] = {
    val now = System.currentTimeMillis()
    store
      .hgetall(hashKey)
      .flatMap { raw =>
        val entries          = raw.values.toSeq.flatMap(v => Try(Json.parse(v)).toOption.flatMap(BanEntry.read))
        val (live, expired)  = entries.partition(!_.expired(now))
        cache.keySet.diff(live.map(_.ref.key).toSet).foreach(cache.remove)
        live.foreach(entry => cache.put(entry.ref.key, entry))
        lastRefresh = now
        lastError = None
        if (expired.isEmpty) live.size.vfuture
        else store.hdel(hashKey, expired.map(_.ref.key)).map(_ => live.size).recover { case _ => live.size }
      }
      .recover { case err: Throwable =>
        // keep serving what we have — dropping the list because redis blinked would silently
        // readmit every banned caller at once
        lastError = Some(err.getMessage)
        logger.warn(s"could not refresh the ban list, keeping ${cache.size} local entries", err)
        cache.size
      }
  }

  def status: JsValue = Json.obj(
    "bans"         -> cache.size,
    "last_refresh" -> lastRefresh,
    "last_error"   -> lastError,
    "node"         -> nodeId
  )
}
