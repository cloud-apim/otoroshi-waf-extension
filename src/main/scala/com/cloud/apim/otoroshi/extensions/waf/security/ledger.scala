package com.cloud.apim.otoroshi.extensions.waf.security

import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

final case class LedgerSettings(
    enabled: Boolean = true,
    window: FiniteDuration = 1.hour,
    banThreshold: Int = 100,
    banDuration: FiniteDuration = 1.hour
)

/**
 * The gateway's memory of a caller, across requests.
 *
 * This is the piece the WAF structurally cannot have on its own: a single request is judged on its
 * own content, so a caller who trips one rule per minute for an hour looks innocent every single
 * time. The ledger accumulates those judgements per identity over a sliding window and, once the
 * total crosses a threshold, promotes the caller to a ban.
 *
 * It is fed **asynchronously**, from the analytics event stream, and read **never** on the request
 * path — the promotion writes into the [[BanStore]], whose lookups are node-local and free. That is
 * deliberate: a per-request datastore round trip to read a running total would undo the reason for
 * having a cheap reputation layer at all.
 */
class ThreatLedger(
    prefix: String,
    store: SharedStateStore,
    bans: BanStore,
    settings: () => LedgerSettings,
    logger: Logger
)(using ec: ExecutionContext) {

  private def keyOf(ref: IdentityRef): String = s"$prefix:${ref.kind}:${ref.value}"

  /**
   * Records weight against one identity and bans it if the running total crosses the threshold.
   *
   * The window slides: every contribution pushes the expiry out, so a caller who keeps misbehaving
   * never ages out, while one who stops is forgotten after `window`.
   */
  def record(ref: IdentityRef, weight: Int, reason: String, tags: Seq[String] = Seq.empty): Future[Long] = {
    val current = settings()
    if (!current.enabled || weight <= 0) {
      0L.vfuture
    } else {
      val key = keyOf(ref)
      store
        .incrBy(key, weight.toLong)
        .flatMap { total =>
          store.pexpire(key, current.window.toMillis).flatMap { _ =>
            if (total >= current.banThreshold && bans.check(ref).isEmpty) {
              logger.info(s"ledger promoted ${ref.key} to a ban: $total >= ${current.banThreshold}")
              bans
                .ban(
                  ref = ref,
                  duration = current.banDuration,
                  reason = s"accumulated threat score $total over ${current.window.toMinutes}m — $reason",
                  tags = tags,
                  score = math.min(100, total.toInt)
                )
                // the total is consumed by the ban, otherwise the next contribution re-bans instantly
                .flatMap(_ => store.del(key))
                .map(_ => total)
            } else {
              total.vfuture
            }
          }
        }
        .recover { case err: Throwable =>
          logger.warn(s"could not record ${weight} against ${ref.key}", err)
          0L
        }
    }
  }

  def recordAll(identity: ClientIdentity, weight: Int, reason: String, tags: Seq[String]): Future[Unit] = {
    // only the most specific identity is charged — charging both an apikey and the shared address
    // behind it would double-count the same actor and punish everyone on that address
    identity.refs.headOption match {
      case None      => ().vfuture
      case Some(ref) => record(ref, weight, reason, tags).map(_ => ())
    }
  }

  def scoreOf(ref: IdentityRef): Future[Long] =
    store.get(keyOf(ref)).map(_.flatMap(_.toLongOption).getOrElse(0L))

  def forget(ref: IdentityRef): Future[Boolean] =
    store.del(keyOf(ref)).map(_ => true)

  def top(limit: Int = 50): Future[Seq[JsValue]] = {
    store.keys(s"$prefix:*").flatMap { keys =>
      Future
        .sequence(keys.take(500).map { k =>
          store.get(k).map { v =>
            val total = v.flatMap(_.toLongOption).getOrElse(0L)
            Json.obj("key" -> k.drop(prefix.length + 1), "score" -> total)
          }
        })
        .map(_.sortBy(j => -(j \ "score").asOpt[Long].getOrElse(0L)).take(limit))
    }
  }
}
