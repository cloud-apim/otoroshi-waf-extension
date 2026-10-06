package com.cloud.apim.otoroshi.extensions.waf.upload

import com.cloud.apim.otoroshi.extensions.waf.body.MediaType
import org.apache.pekko.util.ByteString
import play.api.libs.json.{JsValue, Json}

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * What a route accepts as uploaded files (WAF-4).
 *
 * Extensions and kinds each have a deny list and an allow list: a deny list names what is never
 * accepted, an allow list, when it is not empty, everything that is. Extensions are compared without
 * their dot and in lower case; kinds are [[Magic.kinds]].
 */
final case class UploadPolicy(
    allowedExtensions: Set[String] = Set.empty,
    deniedExtensions: Set[String] = UploadPolicy.defaultDeniedExtensions.toSet,
    allowedKinds: Set[String] = Set.empty,
    deniedKinds: Set[String] = UploadPolicy.defaultDeniedKinds.toSet,
    checkMismatch: Boolean = true,
    maxFiles: Int = 100,
    maxFileSize: Long = 0L,
    archives: ArchiveLimits = ArchiveLimits(),
    scanning: Option[ScanSettings] = None
)

/**
 * How files are handed to a malware scanner (WAF-5).
 *
 * `scan` looks at a file spooled to disk. A file past `maxFileSize` is not sent, a scanner refuses
 * what is larger than it accepts; it counts as a scan that failed, as does a scanner that times out
 * or cannot be reached, and `failureRejects` decides whether that refuses the upload.
 */
final case class ScanSettings(
    scan: Path => Future[ScanVerdict],
    maxFileSize: Long,
    failureRejects: Boolean,
    // set when no scanner can be reached at all: nothing is spooled, every file fails with this
    unavailable: Option[String] = None
)

object UploadPolicy {

  /** What a web server runs or a desktop executes when it finds it on disk. */
  val defaultDeniedExtensions: Seq[String] = Seq(
    "php", "php2", "php3", "php4", "php5", "php6", "php7", "php8", "phtml", "pht", "phar", "phps", "shtml",
    "asp", "aspx", "asa", "ascx", "ashx", "asmx", "axd", "cer", "cdx", "jsp", "jspx", "jspf", "jsw", "cfm", "cfml",
    "cgi", "pl", "sh", "bash", "bat", "cmd", "com", "exe", "dll", "scr", "msi", "ps1", "vbs", "vbe", "wsf", "hta",
    "htaccess", "htpasswd"
  )

  val defaultDeniedKinds: Seq[String] = Seq("executable", "script", "html")
}

/** One file of an upload, for the report. */
final case class UploadedFile(field: Option[String], filename: String, declared: Option[String], detected: Option[Detected], size: Long) {
  def json: JsValue = Json.obj(
    "field"    -> field,
    "filename" -> filename,
    "declared" -> declared,
    "kind"     -> detected.map(_.kind),
    "format"   -> detected.map(_.format),
    "size"     -> size
  )
}

/**
 * A `multipart/form-data` body read as it streams, part by part, and every file in it judged.
 *
 * The bytes are only watched, never rewritten: whoever forwards the body forwards what came, and
 * stops when [[violation]] says so. Memory stays bounded whatever is uploaded: the bytes that could
 * still be the start of a boundary, the head of the current file (what its kind is read from) and,
 * for an archive, the entry being inflated.
 */
final class UploadScanner(boundary: String, policy: UploadPolicy) {

  private val delimiter  = ByteString("\r\n--" + boundary)
  private val crlf       = ByteString("\r\n")
  private val blankLine  = ByteString("\r\n\r\n")
  private val maxHeaders = 16 * 1024
  private val budget     = new ArchiveBudget()

  // the first delimiter has no line break before it, so one is assumed
  private var pending                     = crlf
  // 0: preamble, 1: after a delimiter, 2: part headers, 3: part body, 4: epilogue
  private var state                       = 0
  private var part: Option[PartInspector] = None
  private var count                       = 0
  private val seen                        = ArrayBuffer.empty[UploadedFile]
  private val scans                       = ArrayBuffer.empty[(Option[String], String, Future[ScanVerdict])]
  @volatile private var found: Option[UploadViolation] = None

  /** The first reason to refuse the upload, once there is one. Nothing more is read after it. */
  def violation: Option[UploadViolation] = found

  def halted: Boolean = found.isDefined

  /** How many files the body carried, so far. */
  def fileCount: Int = count

  /** The files read so far, the first fifty. */
  def files: Seq[UploadedFile] = seen.toSeq

  /** Refuses the upload for a reason found outside the body's structure, its encoding for one. */
  def fail(v: UploadViolation): Unit = if (found.isEmpty) found = Some(v)

  private[upload] def scanning(field: Option[String], filename: String, verdict: Future[ScanVerdict]): Unit =
    scans += ((field, filename, verdict))

  /** Whether any file was handed to a malware scanner. */
  def scanned: Boolean = scans.nonEmpty

  /**
   * What the malware scanner said, once it has said it about every file: the first one it found
   * malware in, or a scan that could not be made when that refuses the upload.
   */
  def scanVerdict()(using ec: ExecutionContext): Future[Option[UploadViolation]] = {
    val rejectsFailures = policy.scanning.exists(_.failureRejects)
    Future.sequence(scans.toSeq.map { case (field, file, verdict) => verdict.map(v => (field, file, v)) }).map { results =>
      results
        .collectFirst { case (field, file, ScanVerdict.Infected(threat)) =>
          UploadViolation(UploadReason.Malware, s"the scanner found $threat", field, Some(file))
        }
        .orElse(results.collectFirst {
          case (field, file, ScanVerdict.Failed(reason)) if rejectsFailures =>
            UploadViolation(UploadReason.ScanFailed, s"the file could not be scanned: $reason", field, Some(file))
        })
    }
  }

  private def malformed(detail: String): Unit = fail(UploadViolation(UploadReason.Malformed, detail))

  def feed(chunk: ByteString): Unit = if (!halted && state != 4) {
    pending = (pending ++ chunk).compact
    run()
  }

  /** The body is over: it must have ended with its closing boundary. */
  def finish(): Unit = {
    if (!halted && state != 4) malformed("the body ends before its closing boundary")
    close()
  }

  /** Lets go of what an archive being read holds. */
  def close(): Unit = {
    part.foreach(_.close())
    part = None
  }

  private def run(): Unit = {
    var going = true
    while (going && !halted) {
      state match {
        case 0 | 3 =>
          val i = UploadScanner.indexOf(pending, delimiter)
          if (i < 0) {
            // all but what could be the start of a delimiter is content
            val n = pending.size - (delimiter.size - 1)
            if (n > 0) {
              if (state == 3) part.foreach(_.feed(pending.take(n)))
              pending = pending.drop(n)
            }
            going = false
          } else {
            if (state == 3) part.foreach { p =>
              p.feed(pending.take(i))
              if (!halted) p.end()
              if (seen.size < 50) seen += p.file
            }
            part = None
            pending = pending.drop(i + delimiter.size)
            state = 1
          }
        case 1     =>
          if (pending.size < 2) going = false
          else if (pending(0) == '-' && pending(1) == '-') {
            state = 4
            pending = ByteString.empty
            going = false
          } else {
            // transport padding may follow a boundary, then the line break ends it
            val end = UploadScanner.indexOf(pending, crlf)
            if (end < 0) {
              if (pending.size > 256) malformed("a boundary line that does not end")
              going = false
            } else if (pending.take(end).exists(b => b != ' ' && b != '\t')) {
              malformed("a boundary followed by something else than a line break")
            } else {
              pending = pending.drop(end + 2)
              state = 2
            }
          }
        case 2     =>
          if (pending.size >= 2 && pending(0) == '\r' && pending(1) == '\n') {
            start(ByteString.empty)
            pending = pending.drop(2)
            state = 3
          } else {
            val end = UploadScanner.indexOf(pending, blankLine)
            if (end < 0) {
              if (pending.size > maxHeaders) malformed(s"part headers longer than $maxHeaders bytes")
              going = false
            } else {
              start(pending.take(end))
              pending = pending.drop(end + 4)
              state = 3
            }
          }
        case _     =>
          pending = ByteString.empty
          going = false
      }
    }
  }

  /** A part begins: a form field is the WAF's to read, a file is judged here. */
  private def start(headers: ByteString): Unit = {
    val fields      = UploadScanner.headers(headers)
    val disposition = fields.get("content-disposition").map(UploadScanner.params).getOrElse(Map.empty)
    val field       = disposition.get("name")
    val filename    = disposition.get("filename*").flatMap(UploadScanner.extended).orElse(disposition.get("filename"))
    filename.filter(_.nonEmpty) match {
      case None      => ()
      case Some(raw) =>
        count += 1
        if (policy.maxFiles > 0 && count > policy.maxFiles)
          fail(UploadViolation(UploadReason.TooManyFiles, s"more than ${policy.maxFiles} files", field, Some(raw)))
        else {
          val name = FileName.of(raw)
          FileName.check(name, policy.deniedExtensions, policy.allowedExtensions) match {
            case Some((reason, detail)) => fail(UploadViolation(reason, detail, field, Some(raw)))
            case None                   => part = Some(new PartInspector(field, raw, name, fields.get("content-type"), this, policy, budget))
          }
        }
    }
  }
}

object UploadScanner {

  /**
   * The boundary of a `multipart/form-data` body: `None` when the body is not one, a `Left` when it
   * says it is one and gives no usable boundary.
   */
  def boundaryOf(contentType: Option[String]): Option[Either[String, String]] =
    contentType.filter(ct => MediaType.of(ct) == "multipart/form-data").map { ct =>
      params(ct).get("boundary").map(_.trim).filter(b => b.nonEmpty && b.length <= 200).toRight("no boundary")
    }

  /** Where `needle` first starts in `hay`, or -1. */
  def indexOf(hay: ByteString, needle: ByteString): Int = {
    val first = needle(0)
    var i     = hay.indexOf(first)
    while (i >= 0 && i + needle.size <= hay.size) {
      var k = 1
      while (k < needle.size && hay(i + k) == needle(k)) k += 1
      if (k == needle.size) return i
      i = hay.indexOf(first, i + 1)
    }
    -1
  }

  /** A part's headers, by lower-cased name. Browsers send UTF-8 file names as they are. */
  def headers(block: ByteString): Map[String, String] =
    block.utf8String
      .split("\r\n")
      .toSeq
      .flatMap { line =>
        val i = line.indexOf(':')
        Option.when(i > 0)(line.substring(0, i).trim.toLowerCase -> line.substring(i + 1).trim)
      }
      .toMap

  /** The parameters of a header value, `form-data; name="f"; filename="a.png"`, by lower-cased name. */
  def params(value: String): Map[String, String] = {
    val segments = ArrayBuffer.empty[String]
    val current  = new StringBuilder
    var quoted   = false
    var i        = 0
    while (i < value.length) {
      val c = value.charAt(i)
      if (c == '"') quoted = !quoted
      if (c == '\\' && quoted && i + 1 < value.length && value.charAt(i + 1) == '"') {
        current.append("\\\"")
        i += 1
      } else if (c == ';' && !quoted) {
        segments += current.toString
        current.clear()
      } else current.append(c)
      i += 1
    }
    segments += current.toString
    segments.toSeq.drop(1).flatMap { segment =>
      val eq = segment.indexOf('=')
      Option.when(eq > 0) {
        val v = segment.substring(eq + 1).trim
        segment.substring(0, eq).trim.toLowerCase -> (
          if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v.substring(1, v.length - 1).replace("\\\"", "\"") else v
        )
      }
    }.toMap
  }

  /** An RFC 8187 value, `UTF-8''na%C3%AFve.png`, decoded. */
  def extended(value: String): Option[String] = {
    val i = value.indexOf("''")
    Option.when(i >= 0)(value).flatMap { v =>
      Try(java.net.URLDecoder.decode(v.substring(i + 2).replace("+", "%2B"), v.substring(0, i).trim)).toOption
    }
  }
}

/** One uploaded file, read as it streams: its kind from its first bytes, its size, its archive contents. */
private[upload] final class PartInspector(
    field: Option[String],
    raw: String,
    name: FileName,
    declared: Option[String],
    scanner: UploadScanner,
    policy: UploadPolicy,
    budget: ArchiveBudget
) {

  // enough to recognise any format here, and to find code hidden after an image header
  private val sniffSize                         = 64 * 1024
  private var size                              = 0L
  private var head                              = ByteString.empty
  private var classified                        = false
  private var detected: Option[Detected]        = None
  private var archive: Option[ArchiveReader]    = None

  private def fail(reason: UploadReason, detail: String): Unit = scanner.fail(UploadViolation(reason, detail, field, Some(raw)))

  // WAF-5: the file as it arrives, for the malware scanner to read once it has all of it
  private var spool: Option[(Path, FileChannel)] = None
  private var unspooled                          = false

  def file: UploadedFile = UploadedFile(field, raw, declared, detected, size)

  private def toSpool(bytes: ByteString): Unit = policy.scanning.filter(_.unavailable.isEmpty).foreach { settings =>
    if (!unspooled) {
      if (size > settings.maxFileSize) {
        // past what the scanner accepts, nothing is sent and nothing more is kept
        unspooled = true
        dropSpool()
      } else {
        val (_, channel) = spool.getOrElse {
          val path = Files.createTempFile("cloud-apim-upload-", ".scan")
          val ch   = FileChannel.open(path, StandardOpenOption.WRITE)
          spool = Some((path, ch))
          (path, ch)
        }
        val buffer = bytes.asByteBuffer
        while (buffer.hasRemaining) channel.write(buffer)
      }
    }
  }

  private def dropSpool(): Unit = {
    spool.foreach { case (path, channel) =>
      Try(channel.close())
      Try(Files.deleteIfExists(path))
    }
    spool = None
  }

  /** Hands the spooled file to the scanner, which deletes it once it has answered. */
  private def scan(): Unit = policy.scanning.foreach { settings =>
    if (settings.unavailable.isDefined) scanner.scanning(field, raw, Future.successful(ScanVerdict.Failed(settings.unavailable.get)))
    else if (unspooled) scanner.scanning(field, raw, Future.successful(ScanVerdict.Failed(s"larger than ${settings.maxFileSize} bytes, not scanned")))
    else
      spool.foreach { case (path, channel) =>
        Try(channel.close())
        spool = None
        // a scanner that cannot be reached is a failed scan, never a failed upload of its own
        val verdict = Try(settings.scan(path))
          .fold(e => Future.successful(ScanVerdict.failure(e)), identity)
          .recover { case e => ScanVerdict.failure(e) }(using ExecutionContext.parasitic)
        verdict.onComplete(_ => Try(Files.deleteIfExists(path)))(using ExecutionContext.parasitic)
        scanner.scanning(field, raw, verdict)
      }
  }

  def feed(bytes: ByteString): Unit = if (!scanner.halted && bytes.nonEmpty) {
    size += bytes.size
    toSpool(bytes)
    if (policy.maxFileSize > 0L && size > policy.maxFileSize) fail(UploadReason.FileTooLarge, s"larger than ${policy.maxFileSize} bytes")
    else if (classified) archive.foreach(_.feed(bytes))
    else {
      head = head ++ bytes
      if (head.size >= sniffSize) classify()
    }
  }

  def end(): Unit = {
    if (!scanner.halted && !classified) classify()
    if (!scanner.halted) {
      archive.foreach(_.finish())
      scan()
    } else close()
  }

  def close(): Unit = {
    archive.foreach(_.close())
    dropSpool()
  }

  private def classify(): Unit = {
    classified = true
    val d         = Magic.sniff(head)
    detected = Some(d)
    val mediaType = declared.map(MediaType.of)
    if (policy.deniedKinds.contains(d.kind)) fail(UploadReason.DeniedType, s"${d.format} content, a kind that is refused (${d.kind})")
    else if (policy.allowedKinds.nonEmpty && !policy.allowedKinds.contains(d.kind))
      fail(UploadReason.TypeNotAllowed, s"${d.format} content, a kind that is not accepted (${d.kind})")
    else if (Magic.polyglot(d, head)) fail(UploadReason.Polyglot, s"${d.format} content that also carries code")
    else if (policy.checkMismatch) Magic.mismatch(d, name.last, mediaType).foreach(m => fail(UploadReason.TypeMismatch, m))
    if (!scanner.halted && d.kind == "archive") {
      val ctx    = new ArchiveContext(policy.archives, budget, policy.deniedExtensions, scanner.fail, () => scanner.halted, field, Some(raw))
      val reader = new ArchiveReader(d.format, ctx)
      archive = Some(reader)
      reader.feed(head)
    }
    head = ByteString.empty
  }
}
