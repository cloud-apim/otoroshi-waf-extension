package com.cloud.apim.otoroshi.extensions.waf.upload

import org.apache.pekko.util.ByteString
import play.api.libs.json.{JsValue, Json}

import java.nio.charset.StandardCharsets

/**
 * Why an upload is refused (WAF-4), with the status it is refused with and how much it weighs as
 * evidence against the caller.
 *
 * The weights split what only an attacker does (a script named `.php.jpg`, an image that carries
 * PHP, an archive entry escaping its directory) from what a user can do by mistake (a file too
 * large, an extension the route does not take).
 */
sealed abstract class UploadReason(val name: String, val status: Int, val weight: Int)

object UploadReason {
  case object DeniedExtension     extends UploadReason("denied_extension", 415, 60)
  case object DoubleExtension     extends UploadReason("double_extension", 415, 70)
  case object NullByte            extends UploadReason("null_byte", 400, 70)
  case object ExtensionNotAllowed extends UploadReason("extension_not_allowed", 415, 20)
  case object DeniedType          extends UploadReason("denied_type", 415, 60)
  case object TypeNotAllowed      extends UploadReason("type_not_allowed", 415, 20)
  case object TypeMismatch        extends UploadReason("type_mismatch", 415, 40)
  case object Polyglot            extends UploadReason("polyglot", 415, 80)
  case object ZipSlip             extends UploadReason("zip_slip", 400, 80)
  case object ArchiveBomb         extends UploadReason("archive_bomb", 413, 60)
  case object ArchiveDepth        extends UploadReason("archive_depth", 413, 40)
  case object ArchiveEntries      extends UploadReason("archive_entries", 413, 40)
  case object UnreadableArchive   extends UploadReason("unreadable_archive", 415, 20)
  case object TooManyFiles        extends UploadReason("too_many_files", 413, 10)
  case object FileTooLarge        extends UploadReason("file_too_large", 413, 10)
  case object Malformed           extends UploadReason("malformed_multipart", 400, 30)
  case object UndecodableBody     extends UploadReason("undecodable_body", 415, 10)
}

/** One refused upload: why, in which field and file, and what was seen. */
final case class UploadViolation(reason: UploadReason, detail: String, field: Option[String] = None, filename: Option[String] = None) {
  def json: JsValue = Json.obj("reason" -> reason.name, "detail" -> detail, "field" -> field, "filename" -> filename)
}

/**
 * A file name as the server that stores it will read it.
 *
 * What a client sends is rarely what ends up on disk: a path is reduced to its last segment,
 * Windows drops trailing dots and spaces and stops at an alternate data stream (`shell.php::$DATA`),
 * C stops at a NUL byte (`shell.php\u0000.jpg`), and IIS 6 stops an extension at a semicolon
 * (`shell.asp;.jpg`). Every extension is kept, not only the last: Apache runs `shell.php.jpg` as PHP
 * when a handler is mapped by extension anywhere in the name.
 */
final case class FileName(raw: String, effective: String, extensions: Seq[String], nullByte: Boolean) {
  def last: Option[String] = extensions.lastOption
}

object FileName {

  def of(raw: String): FileName = {
    val base      = raw.split("[/\\\\]").lastOption.getOrElse(raw)
    val nul       = base.indexOf('\u0000')
    val beforeNul = if (nul >= 0) base.substring(0, nul) else base
    val stream    = beforeNul.toLowerCase.indexOf("::$")
    val noStream  = if (stream >= 0) beforeNul.substring(0, stream) else beforeNul
    val trimmed   = noStream.reverse.dropWhile(c => c == '.' || c == ' ').reverse
    val exts      = trimmed.split('.').toSeq.drop(1).map(_.takeWhile(_ != ';').trim.toLowerCase).filter(_.nonEmpty)
    FileName(raw, trimmed, exts, nul >= 0)
  }

  /** What is wrong with a name under a policy, if anything. `allowed` is ignored when empty. */
  def check(name: FileName, denied: Set[String], allowed: Set[String]): Option[(UploadReason, String)] =
    if (name.nullByte) Some((UploadReason.NullByte, s"the name stops at a NUL byte, after '${name.effective}'"))
    else
      name.last match {
        case Some(ext) if denied.contains(ext)                       => Some((UploadReason.DeniedExtension, s"'.$ext' files are refused"))
        case _ if name.extensions.dropRight(1).exists(denied.contains) =>
          val hidden = name.extensions.dropRight(1).find(denied.contains).getOrElse("")
          Some((UploadReason.DoubleExtension, s"'.$hidden' hidden before the last extension"))
        case last if allowed.nonEmpty && !last.exists(allowed.contains) =>
          Some((UploadReason.ExtensionNotAllowed, last.fold("a file without an extension")(e => s"'.$e' is not an accepted extension")))
        case _                                                         => None
      }

  /** Whether an archive entry's name escapes the directory it is extracted to. */
  def escapes(entry: String): Boolean = {
    val n = entry.replace('\\', '/')
    n.startsWith("/") || n.matches("^[A-Za-z]:.*") || n.split('/').contains("..")
  }
}

/** What the bytes of a file say it is: a kind a policy can name, and the precise format. */
final case class Detected(kind: String, format: String)

/**
 * Recognises a file from its first bytes, whatever its name and declared type say.
 *
 * Kinds: `image`, `document`, `archive`, `media`, `executable`, `script` (server-side code: PHP,
 * JSP, ASP, a shebang), `html` (markup a browser runs, an SVG carrying script included), `text`,
 * and `binary` for what nothing here recognises.
 */
object Magic {

  val kinds: Seq[String] = Seq("image", "document", "archive", "media", "executable", "script", "html", "text", "binary")

  private def at(head: ByteString, offset: Int, bytes: Int*): Boolean =
    head.size >= offset + bytes.size && bytes.indices.forall(i => (head(offset + i) & 0xff) == bytes(i))

  private def ascii(head: ByteString, offset: Int, s: String): Boolean =
    at(head, offset, s.map(_.toInt)*)

  def sniff(head: ByteString): Detected = {
    def is(kind: String, format: String) = Some(Detected(kind, format))
    val binary =
      if (at(head, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) is("image", "png")
      else if (at(head, 0, 0xff, 0xd8, 0xff)) is("image", "jpeg")
      else if (ascii(head, 0, "GIF87a") || ascii(head, 0, "GIF89a")) is("image", "gif")
      else if (ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) is("image", "webp")
      else if (ascii(head, 0, "RIFF") && (ascii(head, 8, "WAVE") || ascii(head, 8, "AVI "))) is("media", "riff")
      else if (ascii(head, 0, "BM") && at(head, 6, 0, 0, 0, 0)) is("image", "bmp")
      else if (at(head, 0, 0x49, 0x49, 0x2a, 0x00) || at(head, 0, 0x4d, 0x4d, 0x00, 0x2a)) is("image", "tiff")
      else if (at(head, 0, 0x00, 0x00, 0x01, 0x00)) is("image", "ico")
      else if (ascii(head, 4, "ftyp")) {
        val brand = head.slice(8, 12).utf8String
        if (Set("heic", "heix", "mif1", "msf1", "avif", "avis").contains(brand)) is("image", brand) else is("media", "mp4")
      } else if (ascii(head, 0, "%PDF-")) is("document", "pdf")
      else if (at(head, 0, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) is("document", "ole")
      else if (at(head, 0, 0x50, 0x4b, 0x03, 0x04) || at(head, 0, 0x50, 0x4b, 0x05, 0x06)) is("archive", "zip")
      else if (at(head, 0, 0x1f, 0x8b)) is("archive", "gzip")
      else if (at(head, 0, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)) is("archive", "7z")
      else if (ascii(head, 0, "Rar!") && at(head, 4, 0x1a, 0x07)) is("archive", "rar")
      else if (at(head, 0, 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00)) is("archive", "xz")
      else if (at(head, 0, 0x28, 0xb5, 0x2f, 0xfd)) is("archive", "zstd")
      else if (ascii(head, 0, "BZh") && head.size > 3 && head(3) >= '1' && head(3) <= '9') is("archive", "bzip2")
      else if (ascii(head, 257, "ustar")) is("archive", "tar")
      else if (at(head, 0, 0x7f, 0x45, 0x4c, 0x46)) is("executable", "elf")
      else if (ascii(head, 0, "MZ")) is("executable", "pe")
      else if (
        at(head, 0, 0xfe, 0xed, 0xfa, 0xce) || at(head, 0, 0xfe, 0xed, 0xfa, 0xcf) || at(head, 0, 0xce, 0xfa, 0xed, 0xfe) ||
        at(head, 0, 0xcf, 0xfa, 0xed, 0xfe)
      ) is("executable", "mach-o")
      else if (at(head, 0, 0xca, 0xfe, 0xba, 0xbe)) is("executable", "java-class")
      else if (ascii(head, 0, "dex\n")) is("executable", "dex")
      else if (at(head, 0, 0x00, 0x61, 0x73, 0x6d)) is("executable", "wasm")
      else if (ascii(head, 0, "ID3") || ascii(head, 0, "OggS") || ascii(head, 0, "fLaC") || at(head, 0, 0x1a, 0x45, 0xdf, 0xa3))
        is("media", "audio-video")
      else None
    binary.getOrElse(textual(head))
  }

  private def textual(head: ByteString): Detected = {
    val window   = head.take(8192)
    val controls = window.count(b => (b >= 0 && b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != '\f' && b != 0x1b) || b == 0x7f)
    if (window.contains(0.toByte) || controls * 10 > window.size) Detected("binary", "binary")
    else {
      val lower = lowered(head)
      val start = lower.dropWhile(c => c.isWhitespace || c == '﻿' || c == 'ï' || c == '»' || c == '¿')
      if (start.startsWith("#!")) Detected("script", "shebang")
      else if (serverScript(lower)) Detected("script", "server-script")
      else if (lower.take(2048).contains("<svg")) {
        if (activeContent(lower)) Detected("html", "svg-with-script") else Detected("image", "svg")
      } else if (Seq("<!doctype html", "<html", "<head", "<body", "<script", "<iframe").exists(start.startsWith)) Detected("html", "html")
      else Detected("text", "text")
    }
  }

  /** The head read one character per byte, in lower case, for markers that are plain ASCII. */
  def lowered(head: ByteString): String = head.decodeString(StandardCharsets.ISO_8859_1).toLowerCase

  /** Server-side code: PHP, JSP or ASP opening tags. */
  def serverScript(lower: String): Boolean =
    lower.contains("<?php") || lower.contains("<?=") || lower.contains("<%@") || lower.contains("<%=") || lower.contains("<jsp:")

  private val handler = "\\son[a-z]+\\s*=".r

  /** Markup a browser runs: a script element, a `javascript:` link or an event handler. */
  def activeContent(lower: String): Boolean =
    lower.contains("<script") || lower.contains("javascript:") || lower.contains("<foreignobject") || handler.findFirstIn(lower).isDefined

  /**
   * An image, a document or a media file that also carries code a server or a browser would run.
   *
   * The GIF that starts `GIF89a;<?php` is valid to an image library and to a PHP interpreter alike;
   * whichever the server hands it to decides what it is. Markers are long enough that random bytes
   * of a real image do not produce them.
   */
  def polyglot(detected: Detected, head: ByteString): Boolean =
    Set("image", "document", "media").contains(detected.kind) && detected.format != "svg" && {
      val lower = lowered(head)
      lower.contains("<?php") || lower.contains("<%@") || lower.contains("<script")
    }

  /** The kinds a file with this extension can be. */
  val byExtension: Map[String, Set[String]] = {
    def all(kinds: Set[String], exts: String*) = exts.map(_ -> kinds)
    Map(
      all(Set("image"), "jpg", "jpeg", "jpe", "jfif", "png", "gif", "webp", "bmp", "tif", "tiff", "ico", "heic", "heif", "avif", "svg")*
    ) ++ Map(all(Set("document"), "pdf", "doc", "xls", "ppt", "msg")*) ++
      Map(all(Set("archive"), "zip", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "jar", "war", "ear", "apk", "xpi", "gz", "tgz", "tar", "7z", "rar", "bz2", "xz", "zst")*) ++
      Map(all(Set("media"), "mp3", "mp4", "m4a", "m4v", "mov", "avi", "mkv", "webm", "ogg", "oga", "ogv", "wav", "flac")*) ++
      Map(all(Set("text"), "txt", "csv", "tsv", "json", "xml", "md", "log", "yaml", "yml", "ini")*) ++
      Map(all(Set("html", "text"), "html", "htm")*)
  }

  /** The kinds a declared media type allows, when it says anything at all. */
  def byMediaType(mediaType: String): Set[String] = mediaType match {
    case "application/octet-stream" | ""                                                     => Set.empty
    case "image/svg+xml"                                                                     => Set("image")
    case mt if mt.startsWith("image/")                                                       => Set("image")
    case mt if mt.startsWith("audio/") || mt.startsWith("video/")                            => Set("media")
    case "application/pdf" | "application/msword"                                           => Set("document")
    case mt if mt.startsWith("application/vnd.ms-")                                          => Set("document")
    case mt if mt.startsWith("application/vnd.openxmlformats") || mt.startsWith("application/vnd.oasis.opendocument") ||
        Set("application/zip", "application/x-zip-compressed", "application/java-archive", "application/gzip", "application/x-gzip",
          "application/x-tar", "application/x-7z-compressed", "application/vnd.rar", "application/x-rar-compressed",
          "application/x-bzip2", "application/x-xz", "application/zstd", "application/epub+zip").contains(mt) => Set("archive")
    case "text/html" | "application/xhtml+xml"                                               => Set("html", "text")
    case mt if mt.startsWith("text/") || mt == "application/json" || mt == "application/xml" => Set("text")
    case _                                                                                   => Set.empty
  }

  /**
   * Whether what a file is contradicts what its name or its declared type say.
   *
   * Families are compared, not formats: a PNG named `.jpg` is a mislabelled image, which browsers and
   * image libraries cope with, while a PDF named `.jpg` is something pretending. A file nothing here
   * recognises contradicts nothing.
   */
  def mismatch(detected: Detected, extension: Option[String], mediaType: Option[String]): Option[String] =
    if (detected.kind == "binary") None
    else {
      val byName = extension.flatMap(byExtension.get).filterNot(_.contains(detected.kind)).map(_ => s"named '.${extension.get}'")
      val byType = mediaType.map(byMediaType).filter(k => k.nonEmpty && !k.contains(detected.kind)).map(_ => s"declared ${mediaType.get}")
      byName.orElse(byType).map(said => s"${detected.format} content $said")
    }
}
