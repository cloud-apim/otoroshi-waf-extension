package com.cloud.apim.otoroshi.extensions.waf.dlp

import com.cloud.apim.otoroshi.extensions.waf.body.{CorruptBodyException, StreamDecoder}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.util.ByteString
import play.api.libs.json.{JsValue, Json}

import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/** What one detector found in one response, and what was done about it. */
final case class DlpHit(detector: Detector, action: DlpAction, count: Int) {
  def json: JsValue = Json.obj("detector" -> detector.id, "family" -> detector.family, "count" -> count, "action" -> action.name)
}

/** A response cut short because it carried what a `block` detector refuses. */
final class SensitiveDataBlockedException(message: String) extends Exception(message)

/**
 * Reads a body as it goes by, finds what the detectors are after, and masks it in place (DLP-1, DLP-2).
 *
 * The whole body is read, not a prefix of it: a list endpoint that returns two megabytes of
 * customers would otherwise expose everything past the first few hundred kilobytes. Memory stays
 * bounded all the same, because the scanner only ever holds back [[window]] bytes, the longest value
 * an active detector can match, so that no value is ever split by a chunk boundary and missed.
 *
 * Masking replaces characters one for one, so what is emitted has the length of what came in and a
 * body that declared its length still has it.
 *
 * Not thread-safe: one scanner reads one body, from one stream stage.
 */
final class SensitiveScanner(rules: Seq[(Detector, DlpAction)], rewrite: Boolean, emailThreshold: Int) {

  private val active     = rules.filter(_._2 != DlpAction.Off)
  // enough of what was already emitted for a pattern's lookbehind to see the character before it
  private val lookbehind = 8

  /** How much of the stream is held back before it is emitted. */
  val window: Int = if (active.isEmpty) 0 else active.map(_._1.maxLength).max + 1

  private var before                    = ByteString.empty
  private var carry                     = ByteString.empty
  private val counts                    = mutable.LinkedHashMap.empty[Detector, Int]
  // distinct addresses, up to a bound: past it the count is a lower bound, which is all a report needs
  private val addresses                 = mutable.HashSet.empty[String]
  private val maxAddresses              = math.max(emailThreshold, 10000)
  private var blockedBy: Option[Detector] = None
  private var maskedCount               = 0
  // where the body emitted so far ends, and where each detector picks up again: a value that ran
  // past the cut is not looked at twice, so a stream cut anywhere is read exactly as a whole body
  private var emitted                   = 0L
  private val resume                    = mutable.HashMap.empty[Detector, Long]

  /** The first `block` detector that found something, if one did. */
  def blocked: Option[Detector] = blockedBy

  /** How many values were masked. */
  def masked: Int = maskedCount

  /** Everything found, by detector, in the order detectors first found something. */
  def hits: Seq[DlpHit] = counts.toSeq.map { case (d, n) => DlpHit(d, actionOf(d), n) }

  private def actionOf(d: Detector): DlpAction = active.collectFirst { case (`d`, a) => a }.getOrElse(DlpAction.Off)

  /** Reads `chunk` and returns what can be emitted already, masked when rewriting. */
  def push(chunk: ByteString): ByteString = scan(carry ++ chunk, last = false)

  /** Reads what was held back, now that nothing follows it, and returns it. */
  def finish(): ByteString = scan(carry, last = true)

  /** Reads a whole body at once. */
  def scanAll(body: ByteString): ByteString = push(body) ++ finish()

  private def scan(pending: ByteString, last: Boolean): ByteString = {
    if (active.isEmpty) {
      carry = ByteString.empty
      pending
    } else {
      val base  = before.size
      val bytes = (before ++ pending).toArray
      val total = bytes.length
      // a value starting before the cut ends before the end of what is in hand, so it is seen whole
      val cut   = if (last) total else total - window
      if (cut <= base) {
        carry = pending
        ByteString.empty
      } else {
        val text   = new String(bytes, StandardCharsets.ISO_8859_1)
        val origin = emitted - base
        active.foreach { case (detector, action) => find(detector, action, text, bytes, base, cut, origin) }
        emitted += cut - base
        val out    = ByteString.fromArray(bytes, base, cut - base)
        carry = ByteString.fromArray(bytes, cut, total - cut)
        before = ByteString.fromArray(bytes, math.max(0, cut - lookbehind), math.min(lookbehind, cut))
        out
      }
    }
  }

  private def find(detector: Detector, action: DlpAction, text: String, bytes: Array[Byte], base: Int, cut: Int, origin: Long): Unit = {
    val start      = math.max(base.toLong, resume.getOrElse(detector, 0L) - origin).toInt
    val candidates = detector.candidates(text, start, cut)
    if (candidates.hasNext) {
      val m    = detector.pattern.matcher(text)
      // a lookbehind sees what precedes the position it is tried at
      m.useTransparentBounds(true)
      m.useAnchoringBounds(false)
      var from = start
      candidates.foreach { at =>
        if (at >= from) {
          m.region(at, text.length)
          if (m.lookingAt()) detector.validate(text, m).foreach { span =>
            found(detector, action, text, bytes, span)
            from = math.max(span.end, at + 1)
            resume.update(detector, origin + from)
          }
        }
      }
    }
  }

  private def found(detector: Detector, action: DlpAction, text: String, bytes: Array[Byte], span: Span): Unit = {
    if (detector.volume) {
      if (addresses.size < maxAddresses) addresses += text.substring(span.start, span.end).toLowerCase
      if (addresses.size >= emailThreshold) {
        counts.update(detector, addresses.size)
        if (action == DlpAction.Block && blockedBy.isEmpty) blockedBy = Some(detector)
      }
    } else {
      counts.update(detector, counts.getOrElse(detector, 0) + 1)
      action match {
        case DlpAction.Mask if rewrite                   =>
          Masking(bytes, span)
          maskedCount += 1
        case DlpAction.Block if blockedBy.isEmpty        => blockedBy = Some(detector)
        case _                                           => ()
      }
    }
  }
}

object SensitiveDataFlow {

  /**
   * The body, scanned on its way to the client.
   *
   * A compressed body is decoded to be read. When it is rewritten, what is emitted is the decoded,
   * masked body, and the caller drops `Content-Encoding`; when it is only read, the original bytes go
   * through untouched and decoding happens on the side.
   *
   * A `block` finding past the head can no longer change the status, which is already on its way:
   * when `enforce` is set, the stream is cut before the value is emitted, so the client gets a broken
   * response rather than the value.
   */
  def apply(coding: Option[String], scanner: SensitiveScanner, rewrite: Boolean, enforce: Boolean)(
      onEnd: () => Unit
  ): Flow[ByteString, ByteString, NotUsed] = {
    val decoder  = coding.map(c => new StreamDecoder(c))
    var readable = true
    def refuseIfBlocked(): Unit =
      if (enforce) scanner.blocked.foreach(d => throw new SensitiveDataBlockedException(s"the response carries what the ${d.id} detector blocks"))
    Flow[ByteString]
      .statefulMap(() => ())(
        { (state, chunk) =>
          if (rewrite) {
            val plain = decoder.fold(chunk)(_.decode(chunk))
            decoder.flatMap(_.corrupt).foreach(c => throw new CorruptBodyException(c))
            val out   = scanner.push(plain)
            refuseIfBlocked()
            (state, out)
          } else {
            if (readable) {
              val plain = decoder.fold(chunk)(_.decode(chunk))
              // a body that does not decode cannot be read any further, and is not ours to break
              if (decoder.exists(_.corrupt.isDefined)) readable = false
              else scanner.push(plain)
              refuseIfBlocked()
            }
            (state, chunk)
          }
        },
        { _ =>
          val rest = if (readable) scanner.finish() else ByteString.empty
          refuseIfBlocked()
          Option.when(rewrite && rest.nonEmpty)(rest)
        }
      )
      .filter(_.nonEmpty)
      .watchTermination() { (notUsed, termination) =>
        termination.onComplete { _ =>
          decoder.foreach(_.close())
          onEnd()
        }(ExecutionContext.parasitic)
        notUsed
      }
  }
}
