package com.cloud.apim.otoroshi.extensions.waf.upload

import com.cloud.apim.otoroshi.extensions.waf.body.{DecompressionLimits, StreamDecoder}
import org.apache.pekko.util.ByteString

import java.nio.charset.StandardCharsets
import java.util.zip.{DataFormatException, Inflater}

/**
 * What an uploaded archive may expand to, and how deep it may nest.
 *
 * `maxDepth` counts archives: 1 is an archive with no archive inside, 3 lets a zip carry a war
 * carrying jars. The ratio is only judged past [[DecompressionLimits.ratioFloor]], as for request
 * bodies. `unreadable` decides what happens to what cannot be looked into (an encrypted entry, a
 * compression method nothing here decodes, a 7z or a rar): `reject` or `allow`.
 */
final case class ArchiveLimits(
    maxDepth: Int = 3,
    maxEntries: Int = 10000,
    maxExpandedSize: Long = 256L * 1024L * 1024L,
    maxRatio: Long = 100L,
    unreadable: String = "reject",
    checkEntryExtensions: Boolean = false
) {
  def rejectsUnreadable: Boolean = !unreadable.trim.equalsIgnoreCase("allow")
}

/**
 * What the archives of one upload have cost so far.
 *
 * The budget is the whole request's, not one archive's: two hundred archives each just under the
 * limit are as much of a bomb as one archive over it.
 */
final class ArchiveBudget {
  var compressed: Long = 0L
  var expanded: Long   = 0L
  var entries: Int     = 0
}

/** What one uploaded archive is read with: the shared budget, the policy, and where a violation goes. */
final class ArchiveContext(
    val limits: ArchiveLimits,
    budget: ArchiveBudget,
    deniedExtensions: Set[String],
    fail: UploadViolation => Unit,
    stopped: () => Boolean,
    field: Option[String],
    filename: Option[String]
) {
  def compressed: Long = budget.compressed
  def expanded: Long   = budget.expanded

  def halted: Boolean = stopped()

  def violation(reason: UploadReason, detail: String): Unit = fail(UploadViolation(reason, detail, field, filename))

  def unreadable(detail: String): Unit = if (limits.rejectsUnreadable) violation(UploadReason.UnreadableArchive, detail)

  def read(n: Long): Unit = budget.compressed += n

  def expand(n: Long): Unit = {
    budget.expanded += n
    if (limits.maxExpandedSize > 0L && expanded > limits.maxExpandedSize)
      violation(UploadReason.ArchiveBomb, s"the archives expand past ${limits.maxExpandedSize} bytes")
    else if (limits.maxRatio > 0L && expanded > DecompressionLimits.ratioFloor && expanded > compressed * limits.maxRatio)
      violation(UploadReason.ArchiveBomb, s"the archives expand more than ${limits.maxRatio} times")
  }

  def entry(name: String): Unit = {
    budget.entries += 1
    if (limits.maxEntries > 0 && budget.entries > limits.maxEntries) violation(UploadReason.ArchiveEntries, s"more than ${limits.maxEntries} entries")
    else if (FileName.escapes(name)) violation(UploadReason.ZipSlip, s"the entry '$name' escapes the directory it is extracted to")
    else {
      val checked = FileName.check(FileName.of(name), if (limits.checkEntryExtensions) deniedExtensions else Set.empty, Set.empty)
      checked.foreach { case (reason, detail) => violation(reason, s"archive entry '$name': $detail") }
    }
  }
}

/**
 * A zip read as it streams, entry by entry, from its local headers.
 *
 * The central directory sits at the end of the file and a stream reaches it last, so the local
 * headers are what is read, and every entry is actually inflated: the sizes a header declares can
 * lie, the bytes an entry inflates to cannot. An entry that is itself a zip is read the same way, one
 * level deeper.
 */
final class ZipScanner(depth: Int, ctx: ArchiveContext) {

  // 0: a header, 1: stored data, 2: deflated data, 3: a data descriptor, 4: done
  private var state                 = 0
  private var pending               = ByteString.empty
  private var remaining             = 0L
  private var descriptor            = false
  private var inflater: Inflater    = null
  private var content: EntryContent = null
  private val out                   = new Array[Byte](64 * 1024)

  // a header longer than this is not one
  private val maxHeader = 64 * 1024

  private def le16(b: ByteString, i: Int): Int = (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8)
  private def le32(b: ByteString, i: Int): Long = (le16(b, i) | (le16(b, i + 2) << 16)).toLong & 0xffffffffL
  private def le64(b: ByteString, i: Int): Long = le32(b, i) | (le32(b, i + 4) << 32)

  def feed(bytes: ByteString): Unit = if (!ctx.halted && state != 4) {
    pending = pending ++ bytes
    run()
  }

  /** The archive is over: an entry still open means it was cut short. */
  def finish(): Unit = {
    if (!ctx.halted && state != 4 && !(state == 0 && pending.isEmpty)) ctx.unreadable("the archive ends inside an entry")
    close()
  }

  def close(): Unit = {
    if (inflater != null) {
      inflater.end()
      inflater = null
    }
    if (content != null) content.close()
    state = 4
  }

  private def stop(detail: String): Unit = {
    ctx.unreadable(detail)
    close()
  }

  private def run(): Unit = {
    var going = true
    while (going && !ctx.halted && state != 4) {
      state match {
        case 0 => going = header()
        case 1 => going = stored()
        case 2 => going = deflated()
        case 3 => going = trailer()
        case _ => going = false
      }
    }
  }

  private def header(): Boolean = {
    if (pending.size < 4) false
    else
      le32(pending, 0) match {
        case 0x04034b50L =>
          if (pending.size < 30) false
          else {
            val nameLength  = le16(pending, 26)
            val extraLength = le16(pending, 28)
            if (30 + nameLength + extraLength > maxHeader) {
              stop("an entry header too long to be one")
              false
            } else if (pending.size < 30 + nameLength + extraLength) false
            else {
              val flags   = le16(pending, 6)
              val method  = le16(pending, 8)
              var size    = le32(pending, 18)
              val raw     = pending.slice(30, 30 + nameLength)
              val name    = raw.decodeString(if ((flags & 0x800) != 0) StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1)
              val extra   = pending.slice(30 + nameLength, 30 + nameLength + extraLength)
              if (size == 0xffffffffL) size = zip64Size(extra).getOrElse(-1L)
              pending = pending.drop(30 + nameLength + extraLength)
              ctx.entry(name)
              descriptor = (flags & 0x08) != 0
              if (ctx.halted) false
              else if ((flags & 0x01) != 0) {
                stop(s"the entry '$name' is encrypted")
                false
              } else if (size < 0) {
                stop(s"the entry '$name' has a size nothing here reads")
                false
              } else {
                content = new EntryContent(depth, ctx)
                method match {
                  case 0 if descriptor && size == 0 =>
                    stop(s"the stored entry '$name' does not say how long it is")
                    false
                  case 0                            =>
                    remaining = size
                    state = 1
                    true
                  case 8                            =>
                    inflater = new Inflater(true)
                    remaining = if (descriptor && size == 0) -1L else size
                    state = 2
                    true
                  case other                        =>
                    stop(s"the entry '$name' uses compression method $other")
                    false
                }
              }
            }
          }
        // the central directory and what follows it: every entry is behind us
        case 0x02014b50L | 0x06054b50L | 0x06064b50L | 0x07064b50L | 0x05054b50L =>
          close()
          false
        case 0x08074b50L =>
          // the marker a split archive starts with
          pending = pending.drop(4)
          true
        case _           =>
          stop("bytes that are not a zip entry where one was expected")
          false
      }
  }

  /** The compressed size from a zip64 extra field: the uncompressed size comes first in it. */
  private def zip64Size(extra: ByteString): Option[Long] = {
    var i = 0
    while (i + 4 <= extra.size) {
      val id   = le16(extra, i)
      val size = le16(extra, i + 2)
      if (id == 0x0001 && i + 4 + 16 <= extra.size) return Some(le64(extra, i + 4 + 8))
      i += 4 + size
    }
    None
  }

  private def stored(): Boolean = {
    if (remaining > 0 && pending.isEmpty) false
    else {
      val n = math.min(remaining, pending.size.toLong).toInt
      if (n > 0) {
        content.feed(pending.take(n))
        pending = pending.drop(n)
        remaining -= n
      }
      if (remaining == 0) {
        endEntry()
        true
      } else false
    }
  }

  private def deflated(): Boolean = {
    val available = if (remaining >= 0) math.min(remaining, pending.size.toLong).toInt else pending.size
    if (available == 0 && !inflater.finished()) {
      if (remaining == 0) stop("an entry whose compressed data ends early")
      false
    } else {
      val input = pending.take(available).toArray
      inflater.setInput(input)
      var going = true
      try {
        while (going && !ctx.halted) {
          val n = inflater.inflate(out)
          if (n > 0) content.feed(ByteString.fromArray(out, 0, n))
          else if (inflater.finished() || inflater.needsInput()) going = false
          else if (inflater.needsDictionary()) throw new DataFormatException("a preset dictionary")
        }
      } catch {
        case e: DataFormatException =>
          stop(s"an entry that does not inflate: ${e.getMessage}")
          return false
      }
      val consumed = input.length - inflater.getRemaining
      pending = pending.drop(consumed)
      if (remaining >= 0) remaining -= consumed
      if (ctx.halted) false
      else if (inflater.finished()) {
        inflater.end()
        inflater = null
        if (remaining > 0) {
          stop("an entry longer than what it inflates")
          false
        } else {
          endEntry()
          true
        }
      } else if (remaining == 0) {
        stop("an entry whose compressed data ends early")
        false
      } else false
    }
  }

  private def endEntry(): Unit = {
    content.finish()
    content = null
    state = if (descriptor) 3 else 0
  }

  private def trailer(): Boolean = {
    if (pending.size < 4) false
    else {
      val length = if (le32(pending, 0) == 0x08074b50L) 16 else 12
      if (pending.size < length) false
      else {
        pending = pending.drop(length)
        state = 0
        true
      }
    }
  }
}

/** One entry's inflated bytes: counted against the budget, and read as a zip when they are one. */
private[upload] final class EntryContent(depth: Int, ctx: ArchiveContext) {

  private var head                  = ByteString.empty
  private var nested: ZipScanner    = null
  private var decided               = false

  def feed(bytes: ByteString): Unit = {
    ctx.expand(bytes.size.toLong)
    if (!ctx.halted) {
      if (decided) {
        if (nested != null) nested.feed(bytes)
      } else {
        head = head ++ bytes
        if (head.size >= 4) decide()
      }
    }
  }

  private def decide(): Unit = {
    decided = true
    val zip = head.size >= 4 && head(0) == 'P' && head(1) == 'K' && head(2) == 3 && head(3) == 4
    if (zip) {
      if (depth >= ctx.limits.maxDepth) ctx.violation(UploadReason.ArchiveDepth, s"archives nested more than ${ctx.limits.maxDepth} deep")
      else {
        nested = new ZipScanner(depth + 1, ctx)
        nested.feed(head)
      }
    }
    head = ByteString.empty
  }

  def finish(): Unit = {
    if (!decided && head.nonEmpty) decide()
    if (nested != null) nested.finish()
  }

  def close(): Unit = if (nested != null) nested.close()
}

/**
 * What one uploaded file is, read as it streams: its kind from its first bytes, its archive
 * contents when it is one, its size.
 */
private[upload] final class ArchiveReader(format: String, ctx: ArchiveContext) {

  private val zip: Option[ZipScanner]      = Option.when(format == "zip")(new ZipScanner(1, ctx))
  private val gzip: Option[StreamDecoder]  = Option.when(format == "gzip")(new StreamDecoder("gzip"))

  if (zip.isEmpty && gzip.isEmpty) ctx.unreadable(s"a $format archive, which nothing here reads")

  def feed(bytes: ByteString): Unit = if (!ctx.halted) {
    ctx.read(bytes.size.toLong)
    zip.foreach(_.feed(bytes))
    gzip.foreach { decoder =>
      // counted piece by piece: a bomb is stopped before it is held
      decoder.feed(bytes) { piece =>
        ctx.expand(piece.size.toLong)
        !ctx.halted
      }
      if (decoder.corrupt.isDefined) ctx.unreadable("a gzip file that does not decode")
    }
  }

  def finish(): Unit = {
    zip.foreach(_.finish())
    gzip.foreach(_.close())
  }

  def close(): Unit = {
    zip.foreach(_.close())
    gzip.foreach(_.close())
  }
}
