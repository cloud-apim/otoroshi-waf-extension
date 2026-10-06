package com.cloud.apim.otoroshi.extensions.waf.upload

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString

import java.io.{ByteArrayOutputStream, DataInputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/**
 * WAF-5 against scanners that speak the protocols: clamd's INSTREAM and ICAP, each faked by a
 * socket that does what the real one does, plus the upload scanner waiting on their verdicts.
 */
class ScanSuite extends munit.FunSuite {

  private given system: ActorSystem   = ActorSystem("scan-suite")
  private given mat: Materializer     = Materializer(system)
  private given ec: ExecutionContext  = system.dispatcher

  override def afterAll(): Unit = Await.result(system.terminate(), 10.seconds)

  private val eicar = MalwareScanners.eicar

  /** A server answering each connection with `answer` once it has read what `complete` asks for. */
  private final class FakeServer(read: DataInputStream => Array[Byte], answer: Array[Byte] => Option[String]) {
    val server   = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val received = new ConcurrentLinkedQueue[Array[Byte]]()
    private val thread = new Thread(() => {
      while (!server.isClosed) {
        try {
          val socket: Socket = server.accept()
          val in             = new DataInputStream(socket.getInputStream)
          val body           = read(in)
          received.add(body)
          answer(body).foreach(a => socket.getOutputStream.write(a.getBytes(StandardCharsets.ISO_8859_1)))
          socket.getOutputStream.flush()
          Thread.sleep(50)
          socket.close()
        } catch { case _: Throwable => () }
      }
    })
    thread.setDaemon(true)
    thread.start()
    def port: Int    = server.getLocalPort
    def stop(): Unit = server.close()
  }

  /** clamd: `zINSTREAM\0`, then length-prefixed chunks up to an empty one. */
  private def readInstream(in: DataInputStream): Array[Byte] = {
    val command = new ByteArrayOutputStream()
    var b       = in.read()
    while (b > 0) { command.write(b); b = in.read() }
    assertEquals(command.toString(StandardCharsets.US_ASCII), "zINSTREAM")
    val content = new ByteArrayOutputStream()
    var size    = in.readInt()
    while (size > 0) {
      val chunk = new Array[Byte](size)
      in.readFully(chunk)
      content.write(chunk)
      size = in.readInt()
    }
    content.toByteArray
  }

  private def fakeClamd(): FakeServer = new FakeServer(
    readInstream,
    body => Some(if (ByteString(body).containsSlice(eicar)) "stream: Eicar-Test-Signature FOUND\u0000" else "stream: OK\u0000")
  )

  /** ICAP: headers, the encapsulated HTTP head, then a chunked body ending in a zero chunk. */
  private def readIcap(in: DataInputStream): Array[Byte] = {
    val all = new ByteArrayOutputStream()
    while (!all.toString(StandardCharsets.ISO_8859_1).endsWith("0\r\n\r\n")) all.write(in.read())
    all.toByteArray
  }

  private def file(bytes: ByteString): Path = {
    val p = Files.createTempFile("scan-suite-", ".bin")
    Files.write(p, bytes.toArray)
    p
  }

  private def await[A](f: Future[A]): A = Await.result(f, 20.seconds)

  // ---------------------------------------------------------------- clamd

  test("clamd: a clean file is clean, EICAR is found, and the file is streamed whole") {
    val clamd   = fakeClamd()
    val scanner = new MalwareScanners.Clamd("127.0.0.1", clamd.port, 5.seconds)
    try {
      val big = ByteString(Array.tabulate(300 * 1024)(i => (i % 251).toByte))
      assertEquals(await(scanner.scan(file(big))), ScanVerdict.Clean)
      assertEquals(ByteString(clamd.received.asScala.head), big, "the file reached clamd byte for byte")
      assertEquals(await(scanner.scan(file(ByteString("prefix ") ++ eicar))), ScanVerdict.Infected("Eicar-Test-Signature"))
    } finally clamd.stop()
  }

  test("clamd: an unreachable or silent scanner is a failed scan, not an exception") {
    val nobody  = new ServerSocket(0); val port = nobody.getLocalPort; nobody.close()
    val refused = scala.util.Try(await(new MalwareScanners.Clamd("127.0.0.1", port, 2.seconds).scan(file(ByteString("x")))))
    // the client fails, and the upload scanner turns that into a failed scan (see below)
    assert(refused.isFailure || refused.get.isInstanceOf[ScanVerdict.Failed], s"$refused")
    val silent  = new FakeServer(readInstream, _ => { Thread.sleep(3000); None })
    try {
      val verdict = scala.util.Try(await(new MalwareScanners.Clamd("127.0.0.1", silent.port, 1.second).scan(file(ByteString("x")))))
      assert(verdict.isFailure || verdict.get.isInstanceOf[ScanVerdict.Failed], s"$verdict")
    } finally silent.stop()
  }

  // ---------------------------------------------------------------- ICAP

  test("ICAP: 204 is clean, 200 names its threat, and the request is RESPMOD with a chunked body") {
    val icap    = new FakeServer(
      readIcap,
      body =>
        Some(
          if (ByteString(body).containsSlice(eicar))
            "ICAP/1.0 200 OK\r\nX-Infection-Found: Type=0; Resolution=2; Threat=Eicar-Test-Signature;\r\nEncapsulated: res-hdr=0, null-body=19\r\n\r\n"
          else "ICAP/1.0 204 No Content\r\nEncapsulated: null-body=0\r\n\r\n"
        )
    )
    val scanner = new MalwareScanners.Icap("127.0.0.1", icap.port, "avscan", "respmod", 5.seconds)
    try {
      assertEquals(await(scanner.scan(file(ByteString("hello")))), ScanVerdict.Clean)
      val request = new String(icap.received.asScala.head, StandardCharsets.ISO_8859_1)
      assert(request.startsWith(s"RESPMOD icap://127.0.0.1:${icap.port}/avscan ICAP/1.0\r\n"), request)
      assert(request.contains("Encapsulated: res-hdr=0, res-body="), request)
      assert(request.contains("\r\n5\r\nhello\r\n0\r\n\r\n"), request)
      assertEquals(await(scanner.scan(file(eicar))), ScanVerdict.Infected("Eicar-Test-Signature"))
    } finally icap.stop()
  }

  test("ICAP answers are read from their status line and the headers antivirus servers set") {
    assertEquals(MalwareScanners.icapVerdict("ICAP/1.0 204 No Content\r\n\r\n"), ScanVerdict.Clean)
    assertEquals(MalwareScanners.icapVerdict("ICAP/1.0 200 OK\r\nX-Virus-ID: Win.Test.EICAR_HDB-1\r\n\r\n"), ScanVerdict.Infected("Win.Test.EICAR_HDB-1"))
    assertEquals(MalwareScanners.icapVerdict("ICAP/1.0 500 Server Error\r\n\r\n"), ScanVerdict.Failed("the ICAP server answered 500"))
  }

  // ---------------------------------------------------------------- the upload scanner

  private val boundary = "----scan"

  private def multipart(files: (String, ByteString)*): ByteString =
    files.foldLeft(ByteString.empty) { case (acc, (name, body)) =>
      acc ++ ByteString(s"--$boundary\r\nContent-Disposition: form-data; name=\"f\"; filename=\"$name\"\r\n\r\n") ++ body ++ ByteString("\r\n")
    } ++ ByteString(s"--$boundary--\r\n")

  private def scanWith(settings: ScanSettings, body: ByteString): Option[UploadViolation] = {
    val scanner = new UploadScanner(boundary, UploadPolicy(scanning = Some(settings), deniedKinds = Set.empty))
    body.grouped(1000).foreach(scanner.feed)
    scanner.finish()
    assertEquals(scanner.violation, None)
    await(scanner.scanVerdict())
  }

  test("an upload waits for every file's verdict, and the first malware refuses it") {
    val clamd    = fakeClamd()
    val settings = ScanSettings(p => new MalwareScanners.Clamd("127.0.0.1", clamd.port, 5.seconds).scan(p), 25L * 1024 * 1024, failureRejects = true)
    try {
      assertEquals(scanWith(settings, multipart("a.txt" -> ByteString("hello"), "b.txt" -> ByteString("world"))), None)
      val found = scanWith(settings, multipart("a.txt" -> ByteString("hello"), "readme.txt" -> eicar))
      assertEquals(found.map(v => (v.reason, v.filename)), Some((UploadReason.Malware, Some("readme.txt"))))
      assertEquals(clamd.received.size, 4)
    } finally clamd.stop()
  }

  test("a scan that cannot be made refuses or lets through, as the route says") {
    val down = (_: Path) => Future.failed[ScanVerdict](new java.net.ConnectException("connection refused"))
    assertEquals(scanWith(ScanSettings(down, 1000L, failureRejects = true), multipart("a.txt" -> ByteString("x"))).map(_.reason), Some(UploadReason.ScanFailed))
    assertEquals(scanWith(ScanSettings(down, 1000L, failureRejects = false), multipart("a.txt" -> ByteString("x"))), None)
    val tooBig = scanWith(ScanSettings(_ => Future.successful(ScanVerdict.Clean), 10L, failureRejects = true), multipart("a.txt" -> ByteString("x" * 50)))
    assert(tooBig.exists(_.detail.contains("not scanned")), s"$tooBig")
    val missing = ScanSettings(_ => Future.successful(ScanVerdict.Clean), 0L, failureRejects = true, unavailable = Some("no such scanner"))
    assert(scanWith(missing, multipart("a.txt" -> ByteString("x"))).exists(_.detail.contains("no such scanner")))
  }

  test("spooled files are deleted once scanned") {
    val seen     = new ConcurrentLinkedQueue[Path]()
    val settings = ScanSettings(p => { seen.add(p); Future.successful(ScanVerdict.Clean) }, 1000000L, failureRejects = true)
    scanWith(settings, multipart("a.txt" -> ByteString("hello")))
    Thread.sleep(100)
    assertEquals(seen.size, 1)
    assert(!Files.exists(seen.peek()), "the spool is still on disk")
  }
}
