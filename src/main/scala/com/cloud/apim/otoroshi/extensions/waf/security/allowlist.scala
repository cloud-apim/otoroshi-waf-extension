package com.cloud.apim.otoroshi.extensions.waf.security

import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * One identity the fabric is not allowed to ban.
 *
 * `until` is optional and normally absent: the point of the primitive is the permanent case — the
 * partner whose integration test suite trips every rate limit, the monitoring probe, the office
 * address. A bounded one is still useful ("leave them alone while we tune"), so it is offered, but
 * it is not the default.
 */
final case class AllowlistEntry(
    ref: IdentityRef,
    reason: String,
    addedBy: String,
    addedAt: Long,
    until: Option[Long] = None
) {
  def permanent: Boolean           = until.isEmpty
  def expired(now: Long): Boolean  = until.exists(_ <= now)
  def json: JsValue = Json.obj(
    "ref"       -> ref.json,
    "key"       -> ref.key,
    "reason"    -> reason,
    "added_by"  -> addedBy,
    "added_at"  -> addedAt,
    "until"     -> until,
    "permanent" -> permanent
  )
}

object AllowlistEntry {
  def read(json: JsValue): Option[AllowlistEntry] = Try {
    AllowlistEntry(
      ref = IdentityRef((json \ "ref" \ "kind").as[String], (json \ "ref" \ "value").as[String]),
      reason = (json \ "reason").asOpt[String].getOrElse(""),
      addedBy = (json \ "added_by").asOpt[String].getOrElse("unknown"),
      addedAt = (json \ "added_at").asOpt[Long].getOrElse(0L),
      until = (json \ "until").asOpt[Long]
    )
  }.toOption
}

/**
 * The identities that can never be banned.
 *
 * It is deliberately **not** the same thing as a threat policy's `exemptions`. Those are address
 * ranges, configured ahead of time, on one policy, by whoever edits the policy. This is an
 * operational register: any kind of identity — apikey, user, fingerprint, address — added in one
 * click during an incident, applying to every route at once.
 *
 * The reason it exists as a store rather than as a UI shortcut for "unban" is the ledger. Unbanning
 * a caller who is still accumulating threat score buys minutes: the next window promotes them
 * again, and the operator who unbanned them looks wrong. So the check lives in [[BanStore.ban]] —
 * the single point every ban goes through, from every module — and the answer is the same whichever
 * path asks.
 *
 * Reads are node-local for the same reason bans are: the request path pays nothing.
 */
class AllowlistStore(hashKey: String, store: SharedStateStore, logger: Logger)(using ec: ExecutionContext) {

  private val cache = new TrieMap[String, AllowlistEntry]()

  @volatile var lastRefresh: Long         = 0L
  @volatile var lastError: Option[String] = None

  // -----------------------------------------------------------------------------------------------
  // request path — local only, no i/o
  // -----------------------------------------------------------------------------------------------

  def check(ref: IdentityRef): Option[AllowlistEntry] =
    cache.get(ref.key).filterNot(_.expired(System.currentTimeMillis()))

  def check(identity: ClientIdentity): Option[AllowlistEntry] = {
    val now = System.currentTimeMillis()
    identity.refs.iterator.flatMap(ref => cache.get(ref.key)).find(!_.expired(now))
  }

  def size: Int = cache.size

  def all: Seq[AllowlistEntry] = {
    val now = System.currentTimeMillis()
    cache.values.filterNot(_.expired(now)).toSeq.sortBy(-_.addedAt)
  }

  // -----------------------------------------------------------------------------------------------
  // writes
  // -----------------------------------------------------------------------------------------------

  def allow(
      ref: IdentityRef,
      reason: String,
      addedBy: String,
      until: Option[Long] = None
  ): Future[AllowlistEntry] = {
    val entry = AllowlistEntry(ref, reason, addedBy, System.currentTimeMillis(), until)
    // local first, like a ban: the node the operator is talking to honours it immediately
    cache.put(ref.key, entry)
    store
      .hset(hashKey, ref.key, Json.stringify(entry.json))
      .map { _ =>
        logger.info(s"allowlisted ${ref.key} — $reason")
        entry
      }
      .recover { case err: Throwable =>
        logger.error(s"could not share the allowlist entry for ${ref.key}, it stays local to this node", err)
        entry
      }
  }

  def remove(ref: IdentityRef): Future[Boolean] = {
    cache.remove(ref.key)
    store.hdel(hashKey, Seq(ref.key)).map(_ => true).recover { case _ => false }
  }

  // -----------------------------------------------------------------------------------------------
  // off the request path
  // -----------------------------------------------------------------------------------------------

  def refresh(): Future[Int] = {
    val now = System.currentTimeMillis()
    store
      .hgetall(hashKey)
      .flatMap { raw =>
        val entries         = raw.values.toSeq.flatMap(v => Try(Json.parse(v)).toOption.flatMap(AllowlistEntry.read))
        val (live, expired) = entries.partition(!_.expired(now))
        cache.keySet.diff(live.map(_.ref.key).toSet).foreach(cache.remove)
        live.foreach(entry => cache.put(entry.ref.key, entry))
        lastRefresh = now
        lastError = None
        if (expired.isEmpty) live.size.vfuture
        else store.hdel(hashKey, expired.map(_.ref.key)).map(_ => live.size).recover { case _ => live.size }
      }
      .recover { case err: Throwable =>
        // unlike the ban list, dropping this one would make the fabric *more* aggressive, so the
        // stale copy is kept for exactly the same reason: fail in the direction nobody has to
        // apologise for
        lastError = Some(err.getMessage)
        logger.warn(s"could not refresh the allowlist, keeping ${cache.size} local entries", err)
        cache.size
      }
  }

  def status: JsValue = Json.obj(
    "entries"      -> cache.size,
    "last_refresh" -> lastRefresh,
    "last_error"   -> lastError
  )
}
