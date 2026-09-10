package com.cloud.apim.otoroshi.extensions.waf.security

import otoroshi.utils.syntax.implicits.*
import play.api.Logger

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

final case class Fail2BanOutcome(
    count: Long,
    threshold: Int,
    ban: Option[BanEntry],
    allowlisted: Option[AllowlistEntry] = None
) {
  def banned: Boolean  = ban.isDefined
  def reached: Boolean = count >= threshold
}

object Fail2Ban {

  /**
   * Every ban this component issues carries it.
   *
   * That is what lets the plugin enforce *its own* bans when it runs alone, without claiming
   * authority over the ban list as a whole — which belongs to the threat gate.
   */
  val tag: String = "fail2ban"
}

/**
 * Counts failed responses per offender and promotes a repeat offender to a cluster-wide ban.
 *
 * Otoroshi's own `fail2ban` plugin keeps both the counters and the bans in a node-local `TrieMap`,
 * with a comment in the source saying as much. On a cluster of N nodes that means N times the
 * configured `max_retry` failures before anyone is banned, and a ban that applies on one node while
 * the other N-1 keep serving the same caller. This counts in the shared store and bans through
 * [[BanStore]], so both numbers mean what they say however many nodes are running.
 *
 * The window **slides**: every failure pushes the expiry out, matching the [[ThreatLedger]]. A
 * caller who keeps failing therefore never ages out of it, which is the case a fixed lookback
 * window is worst at.
 *
 * Counting is a write and never blocks a response; the ban it produces is read back on the request
 * path from the node-local ban cache, at no I/O cost.
 */
class Fail2BanCounter(
    prefix: String,
    store: SharedStateStore,
    bans: BanStore,
    logger: Logger
)(using ec: ExecutionContext) {

  private def keyOf(scope: String): String = s"$prefix:$scope"

  def countOf(scope: String): Future[Long] =
    store.get(keyOf(scope)).map(_.flatMap(_.toLongOption).getOrElse(0L))

  def forget(scope: String): Future[Unit] = store.del(keyOf(scope))

  /**
   * Records one failure and bans when the threshold is reached.
   *
   * `enforce` false counts and reports without ever issuing a ban — the observation the whole suite
   * defaults to. Note that it stops *this* component from banning, not the fabric: the caller still
   * charges the ledger, and the ledger reaches its own conclusions.
   */
  def fail(
      scope: String,
      ref: IdentityRef,
      window: FiniteDuration,
      maxRetry: Int,
      banFor: FiniteDuration,
      reason: String,
      tags: Seq[String] = Seq.empty,
      enforce: Boolean = true
  ): Future[Fail2BanOutcome] = {
    val key = keyOf(scope)
    store
      .incrBy(key, 1L)
      .flatMap { count =>
        store.pexpire(key, window.toMillis).flatMap { _ =>
          if (count < maxRetry) {
            Fail2BanOutcome(count, maxRetry, None).vfuture
          } else if (!enforce) {
            // consumed anyway, so a dry run reports "would have banned" once per window rather than
            // on every single request after the threshold
            store.del(key).map(_ => Fail2BanOutcome(count, maxRetry, None))
          } else if (bans.check(ref).isDefined) {
            Fail2BanOutcome(count, maxRetry, None).vfuture
          } else {
            bans
              .ban(
                ref = ref,
                duration = banFor,
                reason = s"$count failed requests within ${window.toSeconds}s — $reason",
                tags = (tags :+ Fail2Ban.tag).distinct,
                score = 100
              )
              // the counter is consumed by the decision — ban or refusal — otherwise the next
              // failure re-attempts instantly
              .flatMap { outcome =>
                store.del(key).map { _ =>
                  Fail2BanOutcome(
                    count,
                    maxRetry,
                    outcome.entry,
                    outcome match {
                      case BanOutcome.Allowlisted(allow) => Some(allow)
                      case _                             => None
                    }
                  )
                }
              }
          }
        }
      }
      .recover { case err: Throwable =>
        // a counter that cannot be written must not cost anyone their response
        logger.warn(s"could not count a failure against $scope", err)
        Fail2BanOutcome(0L, maxRetry, None)
      }
  }
}
