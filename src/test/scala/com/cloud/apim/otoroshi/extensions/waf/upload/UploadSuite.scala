package com.cloud.apim.otoroshi.extensions.waf.upload

import org.apache.pekko.util.ByteString

import java.io.ByteArrayOutputStream
import java.util.zip.{CRC32, GZIPOutputStream, ZipEntry, ZipOutputStream}

/**
 * WAF-4 without a gateway: names read the way a server stores them, content recognised from its
 * bytes, multipart bodies cut anywhere, and archives inflated against their budget.
 */
class UploadSuite extends munit.FunSuite {

  // ---------------------------------------------------------------- fixtures

  private val png  = ByteString(Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)) ++ ByteString(Array.fill(2000)(7.toByte))
  private val pdf  = ByteString("%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n")
  private val pe   = ByteString("MZ") ++ ByteString(Array.fill(500)(0.toByte))
  private val gif  = ByteString("GIF89a;<?php system($_GET['c']); ?>")
  private val text = ByteString("name,email\nJane,jane@example.com\n")

  private final case class Part(field: String, filename: Option[String], contentType: Option[String], body: ByteString, extended: Boolean = false)

  private val boundary = "----cloudapimboundary7MA4YWxkTrZu0gW"

  private def multipart(parts: Part*): ByteString =
    parts.foldLeft(ByteString.empty) { (acc, p) =>
      val disposition = p.filename match {
        case Some(f) if p.extended => s"""form-data; name="${p.field}"; filename*=UTF-8''$f"""
        case Some(f)               => s"""form-data; name="${p.field}"; filename="$f""""
        case None                  => s"""form-data; name="${p.field}""""
      }
      val headers     = s"--$boundary\r\nContent-Disposition: $disposition\r\n" + p.contentType.fold("")(ct => s"Content-Type: $ct\r\n") + "\r\n"
      acc ++ ByteString(headers) ++ p.body ++ ByteString("\r\n")
    } ++ ByteString(s"--$boundary--\r\n")

  private def file(name: String, body: ByteString, ct: Option[String] = Some("application/octet-stream")) = Part("file", Some(name), ct, body)

  private def scan(body: ByteString, policy: UploadPolicy = UploadPolicy(), chunk: Int = 0): UploadScanner = {
    val scanner = new UploadScanner(boundary, policy)
    if (chunk <= 0) scanner.feed(body) else body.grouped(chunk).foreach(scanner.feed)
    scanner.finish()
    scanner
  }

  private def reason(body: ByteString, policy: UploadPolicy = UploadPolicy(), chunk: Int = 0): Option[String] =
    scan(body, policy, chunk).violation.map(_.reason.name)

  private def zip(entries: (String, ByteString)*)(stored: Boolean = false): ByteString = {
    val out = new ByteArrayOutputStream()
    val z   = new ZipOutputStream(out)
    entries.foreach { case (name, bytes) =>
      val e = new ZipEntry(name)
      if (stored) {
        val crc = new CRC32()
        crc.update(bytes.toArray)
        e.setMethod(ZipEntry.STORED)
        e.setSize(bytes.size.toLong)
        e.setCrc(crc.getValue)
      }
      z.putNextEntry(e)
      z.write(bytes.toArray)
      z.closeEntry()
    }
    z.close()
    ByteString(out.toByteArray)
  }

  private def gzip(bytes: ByteString): ByteString = {
    val out = new ByteArrayOutputStream()
    val gz  = new GZIPOutputStream(out)
    gz.write(bytes.toArray)
    gz.close()
    ByteString(out.toByteArray)
  }

  private val zeros = ByteString(Array.fill(20 * 1024 * 1024)(0.toByte))

  // ---------------------------------------------------------------- names

  test("a name is read the way the server storing it reads it") {
    assertEquals(FileName.of("shell.php.jpg").extensions, Seq("php", "jpg"))
    assertEquals(FileName.of("C:\\Users\\jane\\avatar.PNG").effective, "avatar.PNG")
    assertEquals(FileName.of("../../avatar.png").effective, "avatar.png")
    assertEquals(FileName.of("shell.php::$DATA").extensions, Seq("php"))
    assertEquals(FileName.of("shell.php. . .").extensions, Seq("php"))
    assertEquals(FileName.of("shell.asp;.jpg").extensions, Seq("asp", "jpg"))
    assertEquals(FileName.of(".htaccess").extensions, Seq("htaccess"))
    assert(FileName.of("shell.php\u0000.jpg").nullByte)
  }

  test("a denied extension is refused last or hidden, an allow list takes only itself") {
    val denied = UploadPolicy.defaultDeniedExtensions.toSet
    def check(name: String, allowed: Set[String] = Set.empty) = FileName.check(FileName.of(name), denied, allowed).map(_._1)
    assertEquals(check("avatar.png"), None)
    assertEquals(check("shell.PHP"), Some(UploadReason.DeniedExtension))
    assertEquals(check("shell.php.jpg"), Some(UploadReason.DoubleExtension))
    assertEquals(check("shell.php5"), Some(UploadReason.DeniedExtension))
    assertEquals(check("shell.php\u0000.jpg"), Some(UploadReason.NullByte))
    assertEquals(check("report.pdf", Set("png", "jpg")), Some(UploadReason.ExtensionNotAllowed))
    assertEquals(check("README", Set("png")), Some(UploadReason.ExtensionNotAllowed))
    assertEquals(check("photo.jpg", Set("png", "jpg")), None)
  }

  test("an archive entry escaping its directory is seen") {
    Seq("../../etc/cron.d/x", "/etc/passwd", "C:\\Windows\\x.dll", "a/../../b", "..\\..\\web.config").foreach(n => assert(FileName.escapes(n), n))
    Seq("a/b/c.txt", "a..b/c", "dir/..hidden").foreach(n => assert(!FileName.escapes(n), n))
  }

  // ---------------------------------------------------------------- content

  test("content is recognised from its bytes") {
    assertEquals(Magic.sniff(png).format, "png")
    assertEquals(Magic.sniff(ByteString(Array(0xff, 0xd8, 0xff, 0xe0).map(_.toByte))).format, "jpeg")
    assertEquals(Magic.sniff(pdf).kind, "document")
    assertEquals(Magic.sniff(zip("a.txt" -> text)()).format, "zip")
    assertEquals(Magic.sniff(gzip(text)).format, "gzip")
    assertEquals(Magic.sniff(pe).kind, "executable")
    assertEquals(Magic.sniff(ByteString(Array(0x7f, 0x45, 0x4c, 0x46, 2, 1).map(_.toByte))).format, "elf")
    assertEquals(Magic.sniff(ByteString("<?php echo 1;")).kind, "script")
    assertEquals(Magic.sniff(ByteString("#!/bin/sh\nrm -rf /")).kind, "script")
    assertEquals(Magic.sniff(ByteString("<!DOCTYPE html><html><body>hi</body></html>")).kind, "html")
    assertEquals(Magic.sniff(ByteString("""<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>""")), Detected("image", "svg"))
    assertEquals(Magic.sniff(ByteString("""<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>""")).kind, "html")
    assertEquals(Magic.sniff(text).kind, "text")
    assertEquals(Magic.sniff(ByteString(Array.tabulate(256)(i => (i * 37).toByte))).kind, "binary")
  }

  test("an image that also carries code is a polyglot, a plain one is not") {
    assert(Magic.polyglot(Magic.sniff(gif), gif))
    assert(!Magic.polyglot(Magic.sniff(png), png))
  }

  test("a mismatch is between families, not between formats") {
    assert(Magic.mismatch(Magic.sniff(pdf), Some("jpg"), None).isDefined)
    assertEquals(Magic.mismatch(Magic.sniff(png), Some("jpg"), Some("image/jpeg")), None)
    assert(Magic.mismatch(Magic.sniff(text), None, Some("image/png")).isDefined)
    assertEquals(Magic.mismatch(Magic.sniff(text), Some("csv"), Some("application/octet-stream")), None)
    assertEquals(Magic.mismatch(Detected("binary", "binary"), Some("jpg"), Some("image/jpeg")), None)
    assertEquals(Magic.mismatch(Magic.sniff(zip("word/document.xml" -> text)()), Some("docx"), None), None)
  }

  // ---------------------------------------------------------------- multipart

  test("a clean upload goes through, with its form fields") {
    val s = scan(multipart(Part("title", None, None, ByteString("hello")), file("avatar.png", png, Some("image/png"))))
    assertEquals(s.violation, None)
    assertEquals(s.fileCount, 1)
    assertEquals(s.files.map(f => (f.filename, f.detected.map(_.format), f.size)), Seq(("avatar.png", Some("png"), png.size.toLong)))
  }

  test("each disguise is refused for what it is") {
    assertEquals(reason(multipart(file("shell.php", text))), Some("denied_extension"))
    assertEquals(reason(multipart(file("avatar.php.jpg", png))), Some("double_extension"))
    assertEquals(reason(multipart(file("avatar.png", pe, Some("image/png")))), Some("denied_type"))
    assertEquals(reason(multipart(file("avatar.gif", gif, Some("image/gif")))), Some("polyglot"))
    assertEquals(reason(multipart(file("avatar.png", pdf, Some("image/png")))), Some("type_mismatch"))
    assertEquals(reason(multipart(file("page.html", ByteString("<html><script>alert(1)</script></html>")))), Some("denied_type"))
    assertEquals(reason(multipart(file("notes.txt", ByteString("<?php system($_GET['c']);")))), Some("denied_type"))
  }

  test("an RFC 8187 file name is decoded before it is judged") {
    assertEquals(reason(multipart(Part("file", Some("shell.ph%70"), None, text, extended = true))), Some("denied_extension"))
  }

  test("the verdict does not depend on where the body is cut") {
    val clean   = multipart(Part("a", None, None, ByteString("x" * 300)), file("avatar.png", png), file("data.csv", text))
    val refused = multipart(file("avatar.png", png), file("avatar.gif", gif, Some("image/gif")))
    Seq(1, 2, 3, 7, 13, 64, 1000).foreach { size =>
      assertEquals(reason(clean, chunk = size), None, s"chunks of $size")
      assertEquals(scan(clean, chunk = size).fileCount, 2)
      assertEquals(reason(refused, chunk = size), Some("polyglot"), s"chunks of $size")
    }
  }

  test("a body without its closing boundary, or with endless part headers, is malformed") {
    val cut = multipart(file("avatar.png", png)).dropRight(boundary.length + 6)
    assertEquals(reason(cut), Some("malformed_multipart"))
    val endless = ByteString(s"--$boundary\r\nX-Pad: ${"a" * 20000}")
    assertEquals(reason(endless), Some("malformed_multipart"))
  }

  test("file counts and sizes are bounded when asked") {
    val three = multipart(file("a.png", png), file("b.png", png), file("c.png", png))
    assertEquals(reason(three, UploadPolicy(maxFiles = 2)), Some("too_many_files"))
    assertEquals(reason(three, UploadPolicy(maxFileSize = 1000)), Some("file_too_large"))
    assertEquals(reason(three, UploadPolicy(allowedKinds = Set("document"))), Some("type_not_allowed"))
  }

  // ---------------------------------------------------------------- archives

  test("a clean archive is inflated and accepted, stored or deflated") {
    val entries = Seq("docs/readme.txt" -> text, "img/a.png" -> png)
    assertEquals(reason(multipart(file("bundle.zip", zip(entries*)()))), None)
    assertEquals(reason(multipart(file("bundle.zip", zip(entries*)(stored = true)))), None)
  }

  test("an entry escaping its directory is a zip slip") {
    assertEquals(reason(multipart(file("bundle.zip", zip("ok.txt" -> text, "../../etc/cron.d/x" -> text)()))), Some("zip_slip"))
  }

  test("an archive that expands too much is a bomb, whatever its headers declare") {
    assertEquals(reason(multipart(file("bomb.zip", zip("zeros.bin" -> zeros)()))), Some("archive_bomb"))
    assertEquals(reason(multipart(file("bomb.gz", gzip(zeros)))), Some("archive_bomb"))
    assertEquals(reason(multipart(file("bomb.zip", zip("zeros.bin" -> zeros)())), chunk = 4096), Some("archive_bomb"))
    val lenient = UploadPolicy(archives = ArchiveLimits(maxRatio = 0L, maxExpandedSize = 0L))
    assertEquals(reason(multipart(file("big.zip", zip("zeros.bin" -> zeros)())), lenient), None)
  }

  test("archives nest only so deep") {
    val inner = zip("a.txt" -> text)()
    val two   = zip("inner.zip" -> inner)()
    val three = zip("two.zip" -> two)()
    val four  = zip("three.zip" -> three)()
    assertEquals(reason(multipart(file("n.zip", three))), None)
    assertEquals(reason(multipart(file("n.zip", four))), Some("archive_depth"))
    assertEquals(reason(multipart(file("n.zip", two)), UploadPolicy(archives = ArchiveLimits(maxDepth = 1))), Some("archive_depth"))
  }

  test("an archive nothing can read is refused, or let through when told to") {
    val encrypted = {
      val z = zip("secret.txt" -> text)().toArray
      z(6) = (z(6) | 0x01).toByte
      ByteString(z)
    }
    val sevenZip  = ByteString(Array(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c, 0, 4).map(_.toByte)) ++ text
    assertEquals(reason(multipart(file("a.zip", encrypted))), Some("unreadable_archive"))
    assertEquals(reason(multipart(file("a.7z", sevenZip))), Some("unreadable_archive"))
    val allow = UploadPolicy(archives = ArchiveLimits(unreadable = "allow"))
    assertEquals(reason(multipart(file("a.zip", encrypted)), allow), None)
    assertEquals(reason(multipart(file("a.7z", sevenZip)), allow), None)
  }

  test("entry names are held to the denied extensions only when asked") {
    val shell = multipart(file("site.zip", zip("www/index.html" -> text, "www/shell.php" -> text)()))
    assertEquals(reason(shell), None)
    assertEquals(reason(shell, UploadPolicy(archives = ArchiveLimits(checkEntryExtensions = true))), Some("denied_extension"))
  }

  test("a boundary is required, and found in quotes too") {
    assertEquals(UploadScanner.boundaryOf(Some("multipart/form-data; boundary=abc")), Some(Right("abc")))
    assertEquals(UploadScanner.boundaryOf(Some("""multipart/form-data; charset=utf-8; boundary="a b;c"""")), Some(Right("a b;c")))
    assertEquals(UploadScanner.boundaryOf(Some("multipart/form-data")), Some(Left("no boundary")))
    assertEquals(UploadScanner.boundaryOf(Some("application/json")), None)
  }
}
