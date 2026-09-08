package com.cloud.apim.otoroshi.extensions.waf.reputation

import scala.collection.mutable
import scala.reflect.ClassTag

/**
 * Like [[IpRangeSet]], but each range carries a value.
 *
 * The set answers "is this address listed"; this answers "what is this address". Ranges are sorted
 * and searched the same way, but deliberately **not merged** — merging adjacent ranges would lose
 * the very values the structure exists to return.
 *
 * Values are interned during the build: an ASN table has half a million ranges and a few tens of
 * thousands of distinct organisations, so sharing the instances is the difference between a few
 * megabytes and a few hundred.
 */
final class IpRangeMap[A] private (
    private val v4Starts: Array[Long],
    private val v4Ends: Array[Long],
    private val v4Values: Array[A],
    private val v6Starts: Array[BigInt],
    private val v6Ends: Array[BigInt],
    private val v6Values: Array[A]
) {

  val size: Int         = v4Starts.length + v6Starts.length
  def isEmpty: Boolean  = size == 0
  def nonEmpty: Boolean = size > 0

  def get(ip: String): Option[A] = {
    IpParser.parseV4(ip) match {
      case Some(value) => lookupV4(value)
      case None        => IpParser.parseV6(ip).flatMap(lookupV6)
    }
  }

  private def lookupV4(value: Long): Option[A] = {
    var lo     = 0
    var hi     = v4Starts.length - 1
    var found  = -1
    while (lo <= hi && found < 0) {
      val mid = (lo + hi) >>> 1
      if (v4Starts(mid) <= value) {
        if (value <= v4Ends(mid)) found = mid else lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    if (found >= 0) Some(v4Values(found)) else None
  }

  private def lookupV6(value: BigInt): Option[A] = {
    var lo    = 0
    var hi    = v6Starts.length - 1
    var found = -1
    while (lo <= hi && found < 0) {
      val mid = (lo + hi) >>> 1
      if (v6Starts(mid) <= value) {
        if (value <= v6Ends(mid)) found = mid else lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    if (found >= 0) Some(v6Values(found)) else None
  }
}

object IpRangeMap {

  final case class BuildResult[A](map: IpRangeMap[A], accepted: Int, rejected: Int)

  def empty[A: ClassTag]: IpRangeMap[A] =
    new IpRangeMap[A](Array.empty, Array.empty, Array.empty[A], Array.empty, Array.empty, Array.empty[A])

  /**
   * Entries are `(startAddress, endAddress, value)` with **inclusive** bounds — the shape the ASN
   * tables publish. Overlaps are resolved by keeping the first range seen, so a more specific table
   * loaded first wins.
   */
  def build[A: ClassTag](entries: IterableOnce[(String, String, A)]): BuildResult[A] = {
    val v4       = mutable.ArrayBuffer.empty[(Long, Long, A)]
    val v6       = mutable.ArrayBuffer.empty[(BigInt, BigInt, A)]
    val interned = mutable.HashMap.empty[A, A]
    var rejected = 0

    entries.iterator.foreach { case (rawStart, rawEnd, value) =>
      val shared = interned.getOrElseUpdate(value, value)
      (IpParser.parseV4(rawStart), IpParser.parseV4(rawEnd)) match {
        case (Some(s), Some(e)) if s <= e => v4.addOne((s, e, shared))
        case _                            =>
          (IpParser.parseV6(rawStart), IpParser.parseV6(rawEnd)) match {
            case (Some(s), Some(e)) if s <= e => v6.addOne((s, e, shared))
            case _                            => rejected += 1
          }
      }
    }

    val sortedV4 = v4.sortInPlaceBy(_._1)
    val sortedV6 = v6.sortInPlaceBy(_._1)

    // drop anything that starts inside the previous range: the search assumes disjoint ranges,
    // and an overlapping table is a data problem we would otherwise answer wrongly and silently
    val keptV4 = mutable.ArrayBuffer.empty[(Long, Long, A)]
    sortedV4.foreach { entry =>
      keptV4.lastOption match {
        case Some((_, prevEnd, _)) if entry._1 <= prevEnd => rejected += 1
        case _                                            => keptV4.addOne(entry)
      }
    }
    val keptV6 = mutable.ArrayBuffer.empty[(BigInt, BigInt, A)]
    sortedV6.foreach { entry =>
      keptV6.lastOption match {
        case Some((_, prevEnd, _)) if entry._1 <= prevEnd => rejected += 1
        case _                                            => keptV6.addOne(entry)
      }
    }

    BuildResult(
      new IpRangeMap[A](
        keptV4.map(_._1).toArray,
        keptV4.map(_._2).toArray,
        keptV4.map(_._3).toArray,
        keptV6.map(_._1).toArray,
        keptV6.map(_._2).toArray,
        keptV6.map(_._3).toArray
      ),
      keptV4.size + keptV6.size,
      rejected
    )
  }
}
