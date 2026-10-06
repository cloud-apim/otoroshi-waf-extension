package com.cloud.apim.otoroshi.extensions.waf.traffic

import scala.collection.concurrent.TrieMap

/**
 * How a traffic baseline is learned and judged (BEH-4).
 *
 * Traffic is counted in buckets of `bucketSeconds`. Each finished bucket teaches the baseline, an
 * exponentially weighted average over about `learningBuckets` of them, unless it was itself a surge:
 * a baseline that learned the attack would call it normal after a while. Nothing is judged before
 * `warmupBuckets` have been seen.
 */
final case class TrafficSettings(bucketSeconds: Int = 10, learningBuckets: Int = 60, warmupBuckets: Int = 6, surgeFactor: Double = 3.0) {
  def bucketMillis: Long = bucketSeconds.max(1).toLong * 1000L
  def alpha: Double      = 2.0 / (learningBuckets.max(1) + 1.0)
}

/** What one key's traffic looks like right now. */
final case class TrafficReading(count: Long, baseline: Double, threshold: Double, learned: Int) {

  /** How many times its usual traffic this bucket already carries. */
  def ratio: Double = count / math.max(baseline, 1.0)

  def surging: Boolean = count > threshold
}

/** One key's counter and what it has learned. Guarded by its own lock: one key, one writer at a time. */
private[traffic] final class KeyTraffic(var bucket: Long) {
  var count: Long       = 0L
  var baseline: Double  = 0.0
  var learned: Int      = 0
  var lastSeen: Long    = 0L
  var surged: Boolean   = false
}

/**
 * Learned request rates, per key, on this node (BEH-4).
 *
 * Counted per node rather than cluster-wide: a counter shared through redis on every request costs
 * a round trip each time, and a load balancer spreads a flood over nodes in the same proportions as
 * normal traffic, so a ratio to the baseline means the same on one node as on twelve. Floors are
 * per node too, which the documentation says.
 *
 * The number of keys is bounded: past `maxKeys`, new keys are not tracked until idle ones are swept.
 */
final class TrafficBaselines(maxKeys: Int = 200000) {

  private val keys = new TrieMap[String, KeyTraffic]()

  def size: Int = keys.size

  /**
   * Counts one request for `key` at `now`, and says where its traffic stands.
   *
   * `floor` is the least a bucket must carry to be a surge, whatever the baseline: ten requests
   * against a baseline of one is a factor of ten and nobody's attack. `ceiling` is what a bucket
   * may carry during warm-up: past it, it is a surge already, and is not learned either.
   */
  def observe(key: String, now: Long, settings: TrafficSettings, floor: Double, ceiling: Double = Double.MaxValue): Option[TrafficReading] = {
    val bucket = now / settings.bucketMillis
    val kt     = keys.get(key) match {
      case Some(existing)                => Some(existing)
      case None if keys.size >= maxKeys  => None
      case None                          => Some(keys.getOrElseUpdate(key, new KeyTraffic(bucket)))
    }
    kt.map { t =>
      t.synchronized {
        if (bucket != t.bucket) roll(t, bucket, settings, floor)
        t.count += 1
        t.lastSeen = now
        val threshold =
          if (t.learned >= settings.warmupBuckets) math.max(t.baseline * settings.surgeFactor, floor) else ceiling
        val reading   = TrafficReading(t.count, t.baseline, threshold, t.learned)
        if (reading.surging) t.surged = true
        reading
      }
    }
  }

  /** The bucket that just ended teaches the baseline, unless it was a surge; empty ones in between decay it. */
  private def roll(t: KeyTraffic, bucket: Long, settings: TrafficSettings, floor: Double): Unit = {
    val a = settings.alpha
    if (!t.surged) {
      t.baseline = t.baseline + a * (t.count - t.baseline)
      t.learned += 1
    }
    val gap = math.min(bucket - t.bucket - 1, settings.learningBuckets.toLong * 4)
    if (gap > 0) {
      t.baseline = t.baseline * math.pow(1 - a, gap.toDouble)
      t.learned += gap.toInt
    }
    t.bucket = bucket
    t.count = 0L
    t.surged = false
  }

  /** Forgets keys not seen for `idleMillis`. */
  def sweep(now: Long, idleMillis: Long): Int = {
    val idle = keys.collect { case (k, t) if now - t.lastSeen > idleMillis => k }.toSeq
    idle.foreach(keys.remove)
    idle.size
  }
}
