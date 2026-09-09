package com.cloud.apim.otoroshi.extensions.waf.body

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

/**
 * A bounded look at the head of a body, and the means to put the body back together.
 *
 * `bytes` is what the rule engine gets to see. `resume` is the stream to forward on — the buffered
 * head followed by whatever was never read — so a body far larger than the inspection limit costs
 * the limit in heap rather than its own size.
 *
 * Exactly one of [[resume]] and [[drain]] must be called, exactly once. The tail is a substream of
 * the body being read and cannot be materialised twice; a tail that is neither forwarded nor
 * consumed leaves the upstream connection hanging until it times out.
 */
final case class BodyPrefix(
    buffered: ByteString,
    limit: Long,
    truncated: Boolean,
    private val tail: Source[ByteString, ?]
) {

  /** What the rule engine gets to see: never more than the limit. */
  lazy val bytes: ByteString = if (truncated) buffered.take(limit.toInt) else buffered

  def size: Int = buffered.size

  /** The whole body again: everything buffered, then everything that was not. */
  def resume: Source[ByteString, ?] =
    if (buffered.isEmpty) tail
    else Source(buffered.grouped(BodyReader.chunkSize).toList).concat(tail)

  /**
   * Reads the rest and throws it away.
   *
   * Used when the request is refused: the caller may still be uploading, and a body that is neither
   * forwarded nor consumed leaves the connection half-read until something times out.
   */
  def drain()(using mat: Materializer): Unit = { tail.runWith(Sink.ignore); () }
}

object BodyReader {

  /** How finely the buffered head is re-cut when the body is put back together. */
  val chunkSize: Int = 16 * 1024

  /** Buffering more than this is never a good idea, and `limit.toInt` has to be safe. */
  private val hardCeiling: Long = 64L * 1024L * 1024L

  /**
   * Emits the first `limit + 1` bytes as a single element, then passes everything else through
   * untouched.
   *
   * The pass-through matters as much as the split: the tail of a large upload is the bulk of it,
   * and it must reach the backend without being re-cut, re-copied or carried across an async
   * boundary one chunk at a time.
   */
  private def splitAt(limit: Int): Flow[ByteString, ByteString, org.apache.pekko.NotUsed] =
    Flow[ByteString]
      .statefulMap(() => (ByteString.empty, false))(
        { case ((buf, done), bs) =>
          if (done) {
            ((ByteString.empty, true), bs :: Nil)
          } else {
            val acc = buf ++ bs
            if (acc.size > limit) {
              // one byte past the limit, which is what tells a body that ends here from one that
              // goes on — the oversize policy turns on exactly that difference
              val head = acc.take(limit + 1)
              val rest = acc.drop(limit + 1)
              ((ByteString.empty, true), if (rest.isEmpty) head :: Nil else head :: rest :: Nil)
            } else {
              ((acc, false), Nil)
            }
          }
        },
        { case (buf, done) => if (done || buf.isEmpty) None else Some(buf :: Nil) }
      )
      .mapConcat(identity)

  /**
   * Reads at most `limit` bytes and leaves the rest in the stream.
   *
   * The naive version of this — `runFold(ByteString.empty)(_ ++ _)` and then `take(limit)` — reads
   * the *whole* body first, so the limit protects the rule engine while the heap holds the entire
   * upload. One request is then enough to matter. Here nothing beyond `limit + 1` bytes is ever
   * materialised, whatever the caller sends.
   *
   * It costs one materialisation and no per-chunk asynchrony: the head is gathered by a fused
   * stage, and `prefixAndTail(1)` hands back the rest of the same stream rather than a second one.
   */
  def prefix(source: Source[ByteString, ?], limit: Long)(using
      ec: ExecutionContext,
      mat: Materializer
  ): Future[BodyPrefix] = {
    if (limit <= 0L) {
      Future.successful(BodyPrefix(ByteString.empty, limit, truncated = false, tail = source))
    } else {
      val capped = math.min(limit, hardCeiling).toInt
      source
        .via(splitAt(capped))
        .prefixAndTail(1)
        .runWith(Sink.head)
        .map { case (head, tail) =>
          val buffered = head.headOption.getOrElse(ByteString.empty)
          BodyPrefix(buffered, capped.toLong, truncated = buffered.size > capped, tail = tail)
        }
    }
  }
}

object ResponseBody {

  /**
   * Whether the response carries a body worth inspecting.
   *
   * The question the plugin used to ask was whether the *request* had one, which for a plain `GET`
   * is false — so response inspection quietly did nothing for the single most common request shape
   * on the web, whatever `inspect_output_body` was set to.
   *
   * The rules here are HTTP's own: a `HEAD` never has a response body, and neither does `204`,
   * `304` or any `1xx`. Everything else does unless it says its length is zero; an absent length
   * means chunked or close-delimited, which is a body.
   */
  def hasBody(requestMethod: String, status: Int, contentLength: Option[Long]): Boolean = {
    if (requestMethod.trim.equalsIgnoreCase("HEAD")) false
    else if (status == 204 || status == 304 || (status >= 100 && status < 200)) false
    else contentLength.forall(_ > 0L)
  }
}

object MediaType {

  /**
   * The type and subtype of a `Content-Type`, without its parameters.
   *
   * `text/html; charset=utf-8` is the ordinary shape of the header, so comparing the raw value
   * against a configured `text/html` never matches — which silently turns a MIME allowlist into a
   * filter that excludes everything.
   */
  def of(contentType: String): String = contentType.takeWhile(_ != ';').trim.toLowerCase

  /** An exact match, case-insensitively — or a subtype wildcard, written `text` followed by a slash and a star. */
  def matches(pattern: String, contentType: String): Boolean = {
    val actual  = of(contentType)
    val allowed = of(pattern)
    if (allowed == "*" || allowed == "*/*") true
    else if (allowed.endsWith("/*")) actual.startsWith(allowed.dropRight(1))
    else allowed == actual
  }

  def matchesAny(patterns: Seq[String], contentType: String): Boolean =
    patterns.exists(p => matches(p, contentType))
}
