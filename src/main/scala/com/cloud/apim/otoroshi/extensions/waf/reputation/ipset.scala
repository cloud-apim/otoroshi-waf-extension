package com.cloud.apim.otoroshi.extensions.waf.reputation

import scala.collection.mutable
import scala.util.Try

/**
 * Strict, DNS-free parsing of IPv4 / IPv6 literals and CIDR blocks.
 *
 * `java.net.InetAddress.getByName` is deliberately avoided here: it falls back to a name
 * resolution when the input is not a valid literal, and feed content is untrusted input that
 * must never be able to trigger a DNS lookup from the gateway.
 */
object IpParser {

  private val v4Regex = """^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""".r

  private def isHexDigit(c: Char): Boolean =
    c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  def parseV4(str: String): Option[Long] = str match {
    case v4Regex(a, b, c, d) =>
      val parts = Array(a, b, c, d).map(p => Try(p.toInt).getOrElse(-1))
      if (parts.exists(p => p < 0 || p > 255)) None
      else Some((parts(0).toLong << 24) | (parts(1).toLong << 16) | (parts(2).toLong << 8) | parts(3).toLong)
    case _                   => None
  }

  def parseV6(input: String): Option[BigInt] = {
    val zoneIdx = input.indexOf('%')
    val raw     = if (zoneIdx >= 0) input.substring(0, zoneIdx) else input
    if (raw.isEmpty || !raw.contains(':')) None
    else if (!raw.forall(c => isHexDigit(c) || c == ':' || c == '.')) None
    else {
      // an embedded trailing dotted quad (::ffff:1.2.3.4) becomes two hextets
      val lastColon = raw.lastIndexOf(':')
      val tailPart  = raw.substring(lastColon + 1)
      val normalized: Option[String] =
        if (tailPart.contains('.')) {
          parseV4(tailPart).map { v =>
            val hi = ((v >> 16) & 0xffffL).toHexString
            val lo = (v & 0xffffL).toHexString
            raw.substring(0, lastColon + 1) + hi + ":" + lo
          }
        } else if (raw.indexOf('.') < 0) Some(raw)
        else None

      normalized.flatMap { s =>
        val dbl = s.indexOf("::")
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) None
        else {
          def hextets(part: String): Option[Vector[Int]] =
            if (part.isEmpty) Some(Vector.empty)
            else {
              val items = part.split(":", -1).toVector
              if (items.exists(i => i.isEmpty || i.length > 4)) None
              else {
                val parsed = items.map(i => Try(Integer.parseInt(i, 16)).toOption)
                if (parsed.exists(_.isEmpty)) None else Some(parsed.map(_.get))
              }
            }
          val groups =
            if (dbl >= 0) {
              for {
                head <- hextets(s.substring(0, dbl))
                tail <- hextets(s.substring(dbl + 2))
                if head.size + tail.size <= 7
              } yield head ++ Vector.fill(8 - head.size - tail.size)(0) ++ tail
            } else {
              hextets(s).filter(_.size == 8)
            }
          groups.map(_.foldLeft(BigInt(0))((acc, g) => (acc << 16) | BigInt(g)))
        }
      }
    }
  }

  /** Parses `1.2.3.4`, `1.2.3.0/24`, `2001:db8::1` or `2001:db8::/32` into an inclusive range. */
  def parseEntry(raw: String): Option[Either[(Long, Long), (BigInt, BigInt)]] = {
    val s = raw.trim
    if (s.isEmpty) None
    else {
      val slash = s.indexOf('/')
      if (slash < 0) {
        parseV4(s).map(v => Left((v, v))).orElse(parseV6(s).map(v => Right((v, v))))
      } else {
        val addr = s.substring(0, slash)
        Try(s.substring(slash + 1).toInt).toOption.flatMap { prefix =>
          parseV4(addr)
            .filter(_ => prefix >= 0 && prefix <= 32)
            .map { v =>
              val mask  = if (prefix == 0) 0L else (0xffffffffL << (32 - prefix)) & 0xffffffffL
              val start = v & mask
              Left((start, start | (~mask & 0xffffffffL)))
            }
            .orElse {
              parseV6(addr)
                .filter(_ => prefix >= 0 && prefix <= 128)
                .map { v =>
                  val hostBits = 128 - prefix
                  val start    = (v >> hostBits) << hostBits
                  Right((start, start + ((BigInt(1) << hostBits) - 1)))
                }
            }
        }
      }
    }
  }
}

/**
 * An immutable, sorted and merged set of IP ranges with O(log n) membership tests.
 *
 * Snapshots are rebuilt off the request path by the feed refresher and swapped in atomically,
 * so lookups never allocate and never block.
 */
final class IpRangeSet private (
    private val v4Starts: Array[Long],
    private val v4Ends: Array[Long],
    private val v6Starts: Array[BigInt],
    private val v6Ends: Array[BigInt]
) {

  val size: Int          = v4Starts.length + v6Starts.length
  def isEmpty: Boolean   = size == 0
  def nonEmpty: Boolean  = size > 0

  def contains(ip: String): Boolean = {
    IpParser.parseV4(ip) match {
      case Some(v) => containsV4(v)
      case None    => IpParser.parseV6(ip).exists(containsV6)
    }
  }

  private def containsV4(value: Long): Boolean = {
    var lo    = 0
    var hi    = v4Starts.length - 1
    var found = false
    while (lo <= hi && !found) {
      val mid = (lo + hi) >>> 1
      if (v4Starts(mid) <= value) {
        if (value <= v4Ends(mid)) found = true else lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    found
  }

  private def containsV6(value: BigInt): Boolean = {
    var lo    = 0
    var hi    = v6Starts.length - 1
    var found = false
    while (lo <= hi && !found) {
      val mid = (lo + hi) >>> 1
      if (v6Starts(mid) <= value) {
        if (value <= v6Ends(mid)) found = true else lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    found
  }
}

object IpRangeSet {

  val empty: IpRangeSet = new IpRangeSet(Array.empty, Array.empty, Array.empty, Array.empty)

  final case class BuildResult(set: IpRangeSet, accepted: Int, rejected: Int)

  def build(entries: IterableOnce[String]): BuildResult = {
    val v4       = mutable.ArrayBuffer.empty[(Long, Long)]
    val v6       = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    var rejected = 0
    entries.iterator.foreach { raw =>
      IpParser.parseEntry(raw) match {
        case Some(Left(r))  => v4.addOne(r)
        case Some(Right(r)) => v6.addOne(r)
        case None           => rejected += 1
      }
    }
    val accepted = v4.size + v6.size

    val mergedV4 = mutable.ArrayBuffer.empty[(Long, Long)]
    v4.sortInPlaceBy(_._1).foreach { case (start, end) =>
      mergedV4.lastOption match {
        case Some((ls, le)) if start <= le + 1L => mergedV4(mergedV4.size - 1) = (ls, math.max(le, end))
        case _                                  => mergedV4.addOne((start, end))
      }
    }

    val mergedV6 = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    v6.sortInPlaceBy(_._1).foreach { case (start, end) =>
      mergedV6.lastOption match {
        case Some((ls, le)) if start <= le + 1 => mergedV6(mergedV6.size - 1) = (ls, le.max(end))
        case _                                 => mergedV6.addOne((start, end))
      }
    }

    BuildResult(
      new IpRangeSet(
        mergedV4.map(_._1).toArray,
        mergedV4.map(_._2).toArray,
        mergedV6.map(_._1).toArray,
        mergedV6.map(_._2).toArray
      ),
      accepted,
      rejected
    )
  }
}
