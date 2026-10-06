package com.cloud.apim.otoroshi.extensions.waf.objects

import com.cloud.apim.otoroshi.extensions.waf.traffic.{TrafficBaselines, TrafficReading, TrafficSettings}

import scala.collection.concurrent.TrieMap
import scala.util.hashing.MurmurHash3

/**
 * How the object detectors judge (BEH-1).
 *
 * A surge counts objects a consumer had not touched lately, per bucket, against what it usually
 * does; before that is learned, `warmupCeiling` holds. A sequential walk is `sequentialMin`
 * numbered objects touched within `sequentialWindowMillis` whose identifiers sit a median of at most
 * `sequentialMaxGap` apart. An enumeration is at least `enumerationMin` object requests refused or
 * not found in a window, and at least `enumerationRatio` of them.
 */
final case class ObjectSettings(
    surge: TrafficSettings = TrafficSettings(bucketSeconds = 60, learningBuckets = 60, warmupBuckets = 10, surgeFactor = 5.0),
    surgeFloor: Double = 30.0,
    warmupCeiling: Double = 150.0,
    sequentialMin: Int = 30,
    sequentialWindowMillis: Long = 600000L,
    sequentialMaxGap: Long = 3L,
    enumerationMin: Int = 20,
    enumerationRatio: Double = 0.5,
    enumerationWindowMillis: Long = 300000L
)

/** Numbered objects touched in a dense run: the shape of a walk through identifiers. */
final case class SequentialWalk(ids: Int, medianGap: Long, from: Long, to: Long)

/** Object requests refused or not found, out of all of them, in the current window. */
final case class Enumeration(denied: Int, total: Int) {
  def ratio: Double = denied.toDouble / math.max(total, 1)
}

/** What touching one object says about whoever touched it. */
final case class ObjectSighting(
    fresh: Boolean,
    surge: Option[TrafficReading],
    sequential: Option[SequentialWalk],
    enumeration: Option[Enumeration]
)

/** One consumer's recent objects of one kind. Guarded by its own lock. */
private[objects] final class ObjectWatch {
  // hashes of the objects lately touched: what tells a new object from one read again
  val recent: Array[Long]      = new Array[Long](ObjectWatches.Recent)
  var recentAt: Int            = 0
  var recentSize: Int          = 0
  // the numbered ones among the new, and when
  val numbers: Array[Long]     = new Array[Long](ObjectWatches.Numbers)
  val numberTimes: Array[Long] = new Array[Long](ObjectWatches.Numbers)
  var numberAt: Int            = 0
  var numberSize: Int          = 0
  // what the responses said, in the current window
  var windowStart: Long        = 0L
  var total: Int               = 0
  var denied: Int              = 0
  var lastSeen: Long           = 0L
}

/**
 * Which objects each consumer touches, on this node (BEH-1).
 *
 * Per node, like the traffic baselines and for the same reasons: nothing here costs a round trip,
 * and a consumer spread over nodes shows each of them the same proportions. Each key keeps a few
 * kilobytes, hashes rather than identifiers, and the number of keys is bounded.
 */
final class ObjectWatches(maxKeys: Int = 20000) {

  private val keys      = new TrieMap[String, ObjectWatch]()
  private val baselines = new TrafficBaselines(maxKeys)

  def size: Int = keys.size

  private def watch(key: String): Option[ObjectWatch] = keys.get(key) match {
    case Some(existing)               => Some(existing)
    case None if keys.size >= maxKeys => None
    case None                         => Some(keys.getOrElseUpdate(key, new ObjectWatch()))
  }

  /** Notes that `key` touched `ref`, and says what that adds up to. */
  def touch(key: String, ref: ObjectRef, now: Long, settings: ObjectSettings): Option[ObjectSighting] =
    watch(key).map { w =>
      val (fresh, walk, enumeration) = w.synchronized {
        w.lastSeen = now
        val h     = ObjectWatches.hash(ref.id)
        val fresh = !ObjectWatches.contains(w.recent, w.recentSize, h)
        if (fresh) {
          w.recent(w.recentAt) = h
          w.recentAt = (w.recentAt + 1) % ObjectWatches.Recent
          w.recentSize = math.min(w.recentSize + 1, ObjectWatches.Recent)
        }
        val walk  = if (fresh) ref.number.flatMap { n =>
          w.numbers(w.numberAt) = n
          w.numberTimes(w.numberAt) = now
          w.numberAt = (w.numberAt + 1) % ObjectWatches.Numbers
          w.numberSize = math.min(w.numberSize + 1, ObjectWatches.Numbers)
          ObjectWatches.sequential(w, now, settings)
        }
        else None
        (fresh, walk, ObjectWatches.enumeration(w, now, settings))
      }
      val surge = if (fresh) baselines.observe(key, now, settings.surge, settings.surgeFloor, settings.warmupCeiling).filter(_.surging) else None
      ObjectSighting(fresh, surge, walk, enumeration)
    }

  /** Counts what the backend answered for an object `key` touched. */
  def settle(key: String, denied: Boolean, now: Long, settings: ObjectSettings): Unit =
    keys.get(key).foreach { w =>
      w.synchronized {
        if (now - w.windowStart > settings.enumerationWindowMillis) {
          w.windowStart = now
          w.total = 0
          w.denied = 0
        }
        w.total += 1
        if (denied) w.denied += 1
      }
    }

  /** Forgets keys not seen for `idleMillis`. */
  def sweep(now: Long, idleMillis: Long): Int = {
    val idle = keys.collect { case (k, w) if now - w.lastSeen > idleMillis => k }.toSeq
    idle.foreach(keys.remove)
    baselines.sweep(now, idleMillis)
    idle.size
  }
}

object ObjectWatches {

  val Recent: Int  = 256
  val Numbers: Int = 64

  /** 64 bits out of two seeds: two identifiers sharing a 32 bit hash among 256 is not a worry, sharing both is less of one. */
  def hash(id: String): Long =
    (MurmurHash3.stringHash(id, 0x5bd1e995).toLong << 32) | (MurmurHash3.stringHash(id, 0x1b873593).toLong & 0xffffffffL)

  private[objects] def contains(values: Array[Long], size: Int, value: Long): Boolean = {
    var i = 0
    while (i < size) {
      if (values(i) == value) return true
      i += 1
    }
    false
  }

  /** The numbered objects of the window, sorted: a walk is many of them, close together. */
  private[objects] def sequential(w: ObjectWatch, now: Long, settings: ObjectSettings): Option[SequentialWalk] = {
    val since  = now - settings.sequentialWindowMillis
    val within = (0 until w.numberSize).filter(i => w.numberTimes(i) >= since).map(w.numbers(_)).distinct.sorted
    if (within.size < math.min(settings.sequentialMin.max(2), Numbers)) None
    else {
      val gaps   = within.sliding(2).map(p => p(1) - p(0)).toVector.sorted
      val median = gaps(gaps.size / 2)
      Option.when(median <= settings.sequentialMaxGap)(SequentialWalk(within.size, median, within.head, within.last))
    }
  }

  private[objects] def enumeration(w: ObjectWatch, now: Long, settings: ObjectSettings): Option[Enumeration] =
    if (now - w.windowStart > settings.enumerationWindowMillis) None
    else {
      val e = Enumeration(w.denied, w.total)
      Option.when(e.denied >= settings.enumerationMin && e.ratio >= settings.enumerationRatio)(e)
    }
}
