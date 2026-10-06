package com.cloud.apim.otoroshi.extensions.waf.objects

import com.cloud.apim.otoroshi.extensions.waf.security.SharedStateStore

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** Where an object request stands against its consumer's budget. */
sealed trait BudgetVerdict

object BudgetVerdict {

  /** Already read in this window: reading it again costs nothing. */
  case object Seen extends BudgetVerdict

  /** A new object, and the `count`-th of the window. */
  final case class Within(count: Long) extends BudgetVerdict

  /** A new object past the budget: refused, and not counted, so asking again is refused again. */
  final case class Over(budget: Long, retryAfterMillis: Long) extends BudgetVerdict
}

/**
 * How many distinct objects each consumer has read per window, cluster-wide (BEH-2).
 *
 * A rate limit counts requests; a scraper that keeps under it still walks the whole catalogue. This
 * counts objects: one set per consumer, kind and window, holding what was read, and a counter of
 * its size. Reading again what was already read is free; a new object past the budget is refused
 * and taken back out, so it stays refused for the rest of the window. A set never holds more than
 * the budget.
 *
 * Once a node has seen a consumer go over, it only asks whether an object was read before, one
 * round trip, until the window ends.
 */
final class ObjectBudgets(prefix: String, store: SharedStateStore) {

  private val over = new TrieMap[String, Long]()

  def take(key: String, id: String, budget: Long, window: FiniteDuration, now: Long = System.currentTimeMillis())(using
      ec: ExecutionContext
  ): Future[BudgetVerdict] = {
    val millis = window.toMillis.max(1000L)
    val slot   = now / millis
    val set    = s"$prefix:$key:$slot"
    val count  = s"$set:count"
    val retry  = (slot + 1) * millis - now
    val known  = s"$key|$budget"
    if (over.get(known).contains(slot))
      store.sismember(set, id).map(seen => if (seen) BudgetVerdict.Seen else BudgetVerdict.Over(budget, retry))
    else
      store.sadd(set, id).flatMap {
        case false => Future.successful(BudgetVerdict.Seen)
        case true  =>
          store.incrBy(count, 1L).flatMap { n =>
            val expiry = if (n == 1L) store.pexpire(set, millis * 2).flatMap(_ => store.pexpire(count, millis * 2)) else Future.unit
            expiry.flatMap { _ =>
              if (n <= budget) Future.successful(BudgetVerdict.Within(n))
              else {
                if (over.size > 10000) over.clear()
                over.put(known, slot)
                store.srem(set, id).flatMap(_ => store.incrBy(count, -1L)).map(_ => BudgetVerdict.Over(budget, retry))
              }
            }
          }
      }
  }
}
