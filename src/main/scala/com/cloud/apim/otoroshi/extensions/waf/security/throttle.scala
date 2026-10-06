package com.cloud.apim.otoroshi.extensions.waf.security

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** Where a caller stands against a throttle tier's quota. */
final case class ThrottleCount(count: Long, quota: Long, retryAfterMillis: Long) {
  def exceeded: Boolean = count > quota
}

/**
 * Requests per caller and window for the `throttle` tier (BEH-4), counted cluster-wide.
 *
 * A fixed window per policy and caller: one increment, and an expiry set by whoever opens the
 * window. A caller is counted only while its score holds it at a throttle tier, so the quota is what
 * a suspicious caller is still allowed, not a limit on everyone.
 */
final class ThrottleCounters(prefix: String, store: SharedStateStore) {

  def hit(policyId: String, caller: IdentityRef, quota: Long, window: FiniteDuration, now: Long = System.currentTimeMillis())(using
      ec: ExecutionContext
  ): Future[ThrottleCount] = {
    val millis = window.toMillis.max(1000L)
    val slot   = now / millis
    val key    = s"$prefix:$policyId:${caller.key}:$slot"
    store.incrBy(key, 1L).flatMap { count =>
      val expiry = if (count == 1L) store.pexpire(key, millis * 2) else Future.unit
      expiry.map(_ => ThrottleCount(count, quota.max(0L), (slot + 1) * millis - now))
    }
  }
}
