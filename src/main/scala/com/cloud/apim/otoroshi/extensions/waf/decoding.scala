package com.cloud.apim.otoroshi.extensions.waf.body

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.util.ByteString

import java.util.zip.{DataFormatException, Inflater}
import scala.concurrent.ExecutionContext
import scala.util.Try

/**
 * How a `Content-Encoding` says a body has to be read.
 *
 * Before this, a compressed body reached the rule engine as compressed bytes: nothing in it could
 * match, so compressing a payload was enough to walk it past every rule. And nothing bounded what
 * it would expand to once the backend decompressed it.
 */
sealed trait BodyEncoding

object BodyEncoding {

  case object Identity extends BodyEncoding

  /** One coding the extension can decode: `gzip`, `deflate` or `br`. */
  final case class Decodable(name: String) extends BodyEncoding

  /** What it cannot read: `zstd`, `compress`, a chain of codings, brotli without its native library. */
  final case class Undecodable(codings: String) extends BodyEncoding

  def of(contentEncoding: Option[String]): BodyEncoding = {
    val codings = contentEncoding.toSeq.flatMap(_.split(',')).map(_.trim.toLowerCase).filter(c => c.nonEmpty && c != "identity")
    codings match {
      case Seq()                                 => Identity
      case Seq("gzip" | "x-gzip")                => Decodable("gzip")
      case Seq("deflate")                        => Decodable("deflate")
      case Seq("br") if BrotliChunkDecoder.ready => Decodable("br")
      case _                                     => Undecodable(codings.mkString(", "))
    }
  }

  /** The `Content-Encoding` of a header map whose keys may come in any case. */
  def of(headers: Map[String, String]): BodyEncoding =
    of(headers.collectFirst { case (name, value) if name.equalsIgnoreCase("Content-Encoding") => value })
}

/** A body that does not decode as what it says it is. */
final class CorruptBodyException(message: String) extends Exception(message)

/** A body stopped on its way to the backend, because of what it decompressed to. */
final class DecompressionLimitException(message: String) extends Exception(message)

/** Turns compressed bytes into what they stand for, one chunk at a time. */
private[body] trait ChunkDecoder {

  /** Decodes `input`, giving each piece of output to `out` for as long as it answers `true`. */
  def feed(input: ByteString)(out: ByteString => Boolean): Unit
  def close(): Unit
}

private[body] object ChunkDecoder {
  def apply(coding: String): ChunkDecoder = coding match {
    case "gzip"    => new InflaterChunkDecoder(gzip = true)
    case "deflate" => new InflaterChunkDecoder(gzip = false)
    case "br"      => new BrotliChunkDecoder()
    case other     => throw new IllegalArgumentException(s"no decoder for $other")
  }
}

/**
 * gzip and deflate, on the JDK's `Inflater`.
 *
 * gzip is read member by member: its header is parsed here, the deflate stream inflated, the
 * trailer skipped, and a following member read the same way. `deflate` is meant to be zlib-wrapped
 * (RFC 9110) but is often sent raw, so the first two bytes decide.
 */
private[body] final class InflaterChunkDecoder(gzip: Boolean) extends ChunkDecoder {

  private val outBuf             = new Array[Byte](64 * 1024)
  private var pending            = ByteString.empty
  private var current: Array[Byte] = Array.emptyByteArray
  private var inflater: Inflater = null
  private var stopped            = false
  // 0: member header, 1: inflating, 2: gzip trailer, 3: a next gzip member or the end, 4: done
  private var state              = if (gzip) 0 else 1

  // a header longer than this is a way to make the gateway buffer, not a header
  private val maxHeader = 64 * 1024

  def feed(input: ByteString)(out: ByteString => Boolean): Unit = {
    if (stopped || state == 4) return
    pending = pending ++ input
    var going = true
    while (going && !stopped) {
      state match {
        case 0 =>
          headerLength(pending) match {
            case -1 =>
              if (pending.size > maxHeader) throw new CorruptBodyException("gzip header too long")
              going = false
            case n  =>
              pending = pending.drop(n)
              inflater = new Inflater(true)
              state = 1
          }
        case 1 =>
          if (inflater == null) {
            // deflate: zlib-wrapped when its two first bytes make a zlib header, raw otherwise
            if (pending.size < 2) going = false
            else {
              val cmf = pending(0) & 0xff
              val flg = pending(1) & 0xff
              inflater = new Inflater(!((cmf & 0x0f) == 8 && ((cmf << 8) | flg) % 31 == 0))
            }
          } else if (inflater.needsInput()) {
            if (pending.isEmpty) going = false
            else {
              current = pending.toArray
              pending = ByteString.empty
              inflater.setInput(current)
            }
          } else {
            val n =
              try inflater.inflate(outBuf)
              catch { case e: DataFormatException => throw new CorruptBodyException(s"corrupt ${if (gzip) "gzip" else "deflate"} stream: ${e.getMessage}") }
            if (n > 0) {
              if (!out(ByteString.fromArray(outBuf, 0, n))) stopped = true
            } else if (inflater.finished()) {
              val remaining = inflater.getRemaining
              pending = ByteString.fromArray(current, current.length - remaining, remaining) ++ pending
              inflater.end()
              inflater = null
              state = if (gzip) 2 else 4
            } else if (inflater.needsDictionary()) {
              throw new CorruptBodyException("deflate stream asks for a preset dictionary")
            }
          }
        case 2 =>
          if (pending.size < 8) going = false
          else {
            pending = pending.drop(8)
            state = 3
          }
        case 3 =>
          if (pending.size < 2) going = false
          else if ((pending(0) & 0xff) == 0x1f && (pending(1) & 0xff) == 0x8b) state = 0
          else {
            // what follows the last member is not a body anyone decodes
            pending = ByteString.empty
            state = 4
          }
        case _ =>
          pending = ByteString.empty
          going = false
      }
    }
  }

  /** The length of a complete gzip member header at the start of `b`, or -1 while it is arriving. */
  private def headerLength(b: ByteString): Int = {
    if (b.size < 10) return -1
    if ((b(0) & 0xff) != 0x1f || (b(1) & 0xff) != 0x8b) throw new CorruptBodyException("not a gzip stream")
    if ((b(2) & 0xff) != 8) throw new CorruptBodyException("unknown gzip compression method")
    val flags = b(3) & 0xff
    var pos   = 10
    if ((flags & 0x04) != 0) {
      if (b.size < pos + 2) return -1
      pos += 2 + ((b(pos) & 0xff) | ((b(pos + 1) & 0xff) << 8))
      if (b.size < pos) return -1
    }
    if ((flags & 0x08) != 0) {
      val end = b.indexOf(0.toByte, pos)
      if (end < 0) return -1
      pos = end + 1
    }
    if ((flags & 0x10) != 0) {
      val end = b.indexOf(0.toByte, pos)
      if (end < 0) return -1
      pos = end + 1
    }
    if ((flags & 0x02) != 0) {
      pos += 2
      if (b.size < pos) return -1
    }
    pos
  }

  def close(): Unit = if (inflater != null) {
    inflater.end()
    inflater = null
  }
}

/** brotli, on the brotli4j library Otoroshi already ships for its own compression. */
private[body] final class BrotliChunkDecoder extends ChunkDecoder {

  import com.aayushatharva.brotli4j.decoder.DecoderJNI

  // the native library is loaded by the loader, not by the decoder: without this, whoever creates
  // the first decoder before anything asked the loader gets an UnsatisfiedLinkError
  com.aayushatharva.brotli4j.Brotli4jLoader.ensureAvailability()

  private val wrapper = new DecoderJNI.Wrapper(64 * 1024)
  private var stopped = false
  private var closed  = false

  def feed(input: ByteString)(out: ByteString => Boolean): Unit = {
    var remaining = input
    var going     = !stopped && !closed
    def emit(): Unit = if (!out(ByteString(wrapper.pull()))) stopped = true
    while (going && !stopped) {
      wrapper.getStatus match {
        case DecoderJNI.Status.DONE              => going = false
        case DecoderJNI.Status.OK                => wrapper.push(0)
        case DecoderJNI.Status.NEEDS_MORE_OUTPUT => emit()
        case DecoderJNI.Status.NEEDS_MORE_INPUT  =>
          if (wrapper.hasOutput) emit()
          else if (remaining.isEmpty) going = false
          else {
            val buffer = wrapper.getInputBuffer
            buffer.clear()
            val n = remaining.copyToBuffer(buffer)
            wrapper.push(n)
            remaining = remaining.drop(n)
          }
        case _                                   => throw new CorruptBodyException("corrupt brotli stream")
      }
    }
  }

  def close(): Unit = if (!closed) {
    closed = true
    wrapper.destroy()
  }
}

private[body] object BrotliChunkDecoder {
  // the native library is there on the platforms Otoroshi ships it for; elsewhere br is undecodable
  lazy val ready: Boolean = Try(com.aayushatharva.brotli4j.Brotli4jLoader.isAvailable).getOrElse(false)
}

/**
 * A whole compressed stream, decoded chunk by chunk, for a reader that sees every byte once and keeps
 * none of them.
 *
 * A body that turns out not to decode stops decoding: `corrupt` says why, and nothing more comes out.
 * It must be closed, gzip and brotli decoders hold native memory.
 */
final class StreamDecoder(coding: String) {

  private val decoder = ChunkDecoder(coding)

  @volatile var corrupt: Option[String] = None

  def decode(chunk: ByteString): ByteString =
    if (corrupt.isDefined) ByteString.empty
    else {
      var out = ByteString.empty
      try decoder.feed(chunk) { piece =>
          out = out ++ piece
          true
        }
      catch { case e: CorruptBodyException => corrupt = Some(e.getMessage) }
      out
    }

  def close(): Unit = Try(decoder.close())
}

/**
 * What a compressed body may expand to before it is refused.
 *
 * `maxSize` caps the decompressed bytes, `maxRatio` the decompressed size over the compressed size
 * read so far. The ratio is only judged past [[DecompressionLimits.ratioFloor]]: a few repeated
 * bytes compress a hundredfold without harming anyone, a decompression bomb only matters once it
 * is large. Zero or less switches either one off.
 */
final case class DecompressionLimits(maxSize: Long, maxRatio: Long)

object DecompressionLimits {
  val ratioFloor: Long = 1024L * 1024L
}

sealed trait DecompressionBreach {
  def reason: String
  def message: String
}

object DecompressionBreach {
  final case class TooLarge(limit: Long) extends DecompressionBreach {
    val reason  = "decompressed_size"
    def message = s"the body decompresses past $limit bytes"
  }
  final case class TooCompressed(limit: Long) extends DecompressionBreach {
    val reason  = "compression_ratio"
    def message = s"the body expands more than ${limit} times"
  }
}

/**
 * A compressed body, decoded as it goes by.
 *
 * It keeps the first `keep` decoded bytes, which is what the rule engine reads, and holds the whole
 * body to `limits` when there are some. It never decodes more than something still needs: past the
 * kept head, and with no limits to enforce, decoding stops, so a bomb costs nothing to look at.
 *
 * It is fed sequentially, the head first and then the rest of the stream as it is forwarded, and
 * must be closed: gzip and brotli decoders hold native memory.
 */
final class DecodedBody(coding: String, keep: Long, limits: Option[DecompressionLimits]) {

  private val decoder              = ChunkDecoder(coding)
  @volatile private var kept       = ByteString.empty
  @volatile private var compressed = 0L
  @volatile private var expanded   = 0L
  @volatile private var done       = false

  @volatile var breach: Option[DecompressionBreach] = None
  @volatile var corrupt: Option[String]             = None

  def compressedBytes: Long   = compressed
  def decompressedBytes: Long = expanded

  def feed(chunk: ByteString): Unit = if (!done && breach.isEmpty && corrupt.isEmpty) {
    compressed += chunk.size
    try {
      decoder.feed(chunk) { out =>
        expanded += out.size
        if (kept.length <= keep) kept = kept ++ out.take((keep + 1 - kept.length).toInt)
        limits.foreach { l =>
          if (l.maxSize > 0L && expanded > l.maxSize) breach = Some(DecompressionBreach.TooLarge(l.maxSize))
          else if (l.maxRatio > 0L && expanded > DecompressionLimits.ratioFloor && expanded > compressed * l.maxRatio)
            breach = Some(DecompressionBreach.TooCompressed(l.maxRatio))
        }
        val wanted = breach.isEmpty && (limits.isDefined || kept.length <= keep)
        if (!wanted) done = true
        wanted
      }
    } catch {
      case e: CorruptBodyException => corrupt = Some(e.getMessage)
    }
  }

  /** The decoded head, at most `keep` bytes. */
  def head: ByteString = kept.take(keep.toInt)

  /** Whether the decoded body goes on past what was kept. */
  def truncated: Boolean = kept.length > keep

  def close(): Unit = Try(decoder.close())

  /**
   * The rest of the body, forwarded untouched while it is decoded and held to the limits.
   *
   * Past the head, the request is already on its way, so a breach can only cut it: the backend sees
   * a broken upload rather than decompressing what is left of it.
   */
  def guard(onBreach: DecompressionBreach => Unit): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString]
      .map { chunk =>
        feed(chunk)
        breach match {
          case Some(b) =>
            onBreach(b)
            throw new DecompressionLimitException(b.message)
          case None    => chunk
        }
      }
      .watchTermination() { (notUsed, termination) =>
        termination.onComplete(_ => close())(ExecutionContext.parasitic)
        notUsed
      }
}
