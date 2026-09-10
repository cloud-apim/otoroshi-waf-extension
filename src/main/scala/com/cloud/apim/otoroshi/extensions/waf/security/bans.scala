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
    /** What the detectors contributed, when a scored decision issued this ban. */
    signals: JsValue = JsArray(Seq.empty),
    /**
     * What the caller actually did, copied from the incident at the moment of the ban.
     *
     * A copy rather than a lookup because the two have different lifetimes: an incident is evicted
     * after the correlation window, and a ban routinely outlives it. Without this, a ban issued an
     * hour ago has nothing behind it by the time anyone asks why.
     */
    timeline: Seq[IncidentEvent] = Seq.empty,
    /** Set the first time an operator touches a ban the fabric issued, and never unset. */
    lastAction: Option[String] = None,
    lastActionBy: Option[String] = None,
    lastActionAt: Option[Long] = None
) {
  def expired(now: Long): Boolean = until <= now
  def remainingMs(now: Long): Long = math.max(0L, until - now)

  /**
   * Pushes the end further out.
   *
   * Measured from whichever is later, now or the current expiry, so extending a ban that lapsed
   * while the operator was reading it does not silently give the caller the elapsed time back.
   */
  def extendedBy(duration: FiniteDuration, by: String, now: Long): BanEntry = copy(
    until = math.max(now, until) + duration.toMillis,
    lastAction = Some("extended"),
    lastActionBy = Some(by),
    lastActionAt = Some(now)
  )

  def json: JsValue = Json.obj(
    "ref"            -> ref.json,
    "key"            -> ref.key,
    "reason"         -> reason,
    "tags"           -> tags,
    "score"          -> score,
    "issued_at"      -> issuedAt,
    "until"          -> until,
    "issued_by"      -> issuedBy,
    "signals"        -> signals,
    "timeline"       -> JsArray(timeline.map(_.json)),
    "last_action"    -> lastAction,
    "last_action_by" -> lastActionBy,
    "last_action_at" -> lastActionAt
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
      signals = (json \ "signals").asOpt[JsValue].getOrElse(JsArray(Seq.empty)),
      timeline = (json \ "timeline").asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty).flatMap(IncidentEvent.read),
      lastAction = (json \ "last_action").asOpt[String],
      lastActionBy = (json \ "last_action_by").asOpt[String],
      lastActionAt = (json \ "last_action_at").asOpt[Long]
    )
  }.toOption
}

/**
 * What happened when something asked for a ban.
 *
 * Modelled rather than reduced to `Option[BanEntry]` because the two outcomes are not "worked" and
 * "failed": a refusal is a decision the operator made earlier, and the caller usually wants to say
 * so — in a log line, in an api response, or by not consuming a counter.
 */
sealed trait BanOutcome {
  def entry: Option[BanEntry]
  def issued: Boolean = entry.isDefined
  def json: JsValue
}

object BanOutcome {
  final case class Issued(ban: BanEntry) extends BanOutcome {
    override def entry: Option[BanEntry] = Some(ban)
    override def json: JsValue           = Json.obj("done" -> true, "ban" -> ban.json)
  }
  final case class Allowlisted(allow: AllowlistEntry) extends BanOutcome {
    override def entry: Option[BanEntry] = None
    override def json: JsValue           =
      Json.obj("done" -> false, "refused" -> "allowlisted", "allowlist" -> allow.json)
  }
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
 *
 * `allowlisted` is consulted here rather than at each of the five places that issue a ban, because
 * "this caller is never banned" is only worth promising if it holds on every path — including the
 * ones added later.
 */
class BanStore(
    hashKey: String,
    store: SharedStateStore,
    nodeId: String,
    logger: Logger,
    allowlisted: IdentityRef => Option[AllowlistEntry] = _ => None
)(using ec: ExecutionContext) {

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
      signals: JsValue = JsArray(Seq.empty),
      timeline: Seq[IncidentEvent] = Seq.empty,
      issuedBy: Option[String] = None
  ): Future[BanOutcome] = allowlisted(ref) match {
    case Some(allow) =>
      logger.info(s"refused to ban ${ref.key}: allowlisted — ${allow.reason}")
      BanOutcome.Allowlisted(allow).vfuture
    case None        =>
      val now   = System.currentTimeMillis()
      val entry = BanEntry(
        ref = ref,
        reason = reason,
        tags = tags,
        score = score,
        issuedAt = now,
        until = now + duration.toMillis,
        issuedBy = issuedBy.getOrElse(nodeId),
        signals = signals,
        timeline = timeline
      )
      // local first: the node that decided enforces on its very next request, whatever redis does
      cache.put(ref.key, entry)
      store
        .hset(hashKey, ref.key, Json.stringify(entry.json))
        .map { _ =>
          logger.info(s"banned ${ref.key} for ${duration.toSeconds}s — $reason")
          BanOutcome.Issued(entry)
        }
        .recover { case err: Throwable =>
          // the local ban still stands, it just will not propagate until a later write succeeds
          logger.error(s"could not share the ban on ${ref.key}, it stays local to this node", err)
          BanOutcome.Issued(entry)
        }
  }

  /**
   * Pushes an existing ban's expiry out.
   *
   * Nothing is created if there is no live ban to extend: an operator who extends a ban that just
   * lapsed should be told it lapsed, not handed a fresh one they did not ask for.
   */
  def extend(ref: IdentityRef, duration: FiniteDuration, by: String): Future[Option[BanEntry]] = {
    val now = System.currentTimeMillis()
    cache.get(ref.key).filterNot(_.expired(now)) match {
      case None          => Option.empty[BanEntry].vfuture
      case Some(current) =>
        val next = current.extendedBy(duration, by, now)
        cache.put(ref.key, next)
        store
          .hset(hashKey, ref.key, Json.stringify(next.json))
          .map(_ => Some(next))
          .recover { case err: Throwable =>
            logger.error(s"could not share the extended ban on ${ref.key}", err)
            Some(next)
          }
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
