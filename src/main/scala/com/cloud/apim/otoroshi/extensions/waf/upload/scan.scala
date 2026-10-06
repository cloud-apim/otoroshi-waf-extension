package com.cloud.apim.otoroshi.extensions.waf.upload

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source, Tcp}
import org.apache.pekko.util.ByteString

import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** What a malware scanner said about one file (WAF-5). */
sealed trait ScanVerdict

object ScanVerdict {
  case object Clean                              extends ScanVerdict
  final case class Infected(threat: String)      extends ScanVerdict
  final case class Failed(reason: String)        extends ScanVerdict

  /** A scan that threw, said in words an operator reads: the cause, not the stream stage it surfaced in. */
  def failure(e: Throwable): Failed = {
    val root = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).toSeq.last
    root match {
      case _: java.util.concurrent.TimeoutException => Failed("the scanner did not answer in time")
      case _: java.net.ConnectException             => Failed("the scanner could not be reached")
      case other                                    => Failed(Option(other.getMessage).getOrElse(other.getClass.getSimpleName))
    }
  }
}

/** Something that can look at a file and say whether it carries malware. */
trait MalwareScanning {
  def scan(file: Path)(using mat: Materializer, ec: ExecutionContext): Future[ScanVerdict]
}

/**
 * The two protocols every antivirus product speaks to a gateway.
 *
 * Both are sent over a plain TCP connection, the file streamed from disk, and the answer read up to
 * the end of its header: neither waits for the server to close the connection, which ICAP servers
 * in particular keep open.
 */
object MalwareScanners {

  /**
   * clamd's `INSTREAM`: the file in chunks, each prefixed by its length on four bytes, a zero-length
   * chunk to end it, and one line back, `stream: OK` or `stream: <threat> FOUND`.
   *
   * clamd refuses a stream past its `StreamMaxLength` (25 MB by default), which is why a scanner's
   * `max_file_size` must stay under it.
   */
  final class Clamd(host: String, port: Int, timeout: FiniteDuration)(using system: ActorSystem) extends MalwareScanning {

    private def framed(chunk: ByteString): ByteString =
      ByteString.newBuilder.putInt(chunk.size)(using ByteOrder.BIG_ENDIAN).result() ++ chunk

    override def scan(file: Path)(using mat: Materializer, ec: ExecutionContext): Future[ScanVerdict] = {
      val request = Source
        .single(ByteString("zINSTREAM\u0000"))
        .concat(FileIO.fromPath(file, 64 * 1024).map(framed))
        .concat(Source.single(ByteString(0, 0, 0, 0)))
      MalwareScanners.exchange(request, host, port, timeout, _.contains(0.toByte)).map { raw =>
        val answer = raw.takeWhile(_ != 0).utf8String.trim
        if (answer.endsWith(": OK")) ScanVerdict.Clean
        else if (answer.endsWith(" FOUND")) ScanVerdict.Infected(answer.stripPrefix("stream:").stripSuffix(" FOUND").trim)
        else ScanVerdict.Failed(if (answer.isEmpty) "clamd answered nothing" else s"clamd answered: $answer")
      }
    }
  }

  /**
   * ICAP (RFC 3507): the file wrapped in an HTTP message, `RESPMOD` or `REQMOD`, and sent chunked.
   * `204 No Content` means the scanner has nothing to change, so nothing to object to; `200` means
   * it would replace the content, which an antivirus only does for something it found.
   */
  final class Icap(host: String, port: Int, service: String, mode: String, timeout: FiniteDuration)(using system: ActorSystem)
      extends MalwareScanning {

    private val respmod = !mode.trim.equalsIgnoreCase("reqmod")

    private val encapsulatedHttp: String =
      if (respmod) "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n"
      else s"POST /upload HTTP/1.1\r\nHost: $host\r\nContent-Type: application/octet-stream\r\n\r\n"

    private def head: ByteString = {
      val method = if (respmod) "RESPMOD" else "REQMOD"
      val parts  = if (respmod) "res-hdr=0, res-body" else "req-hdr=0, req-body"
      ByteString(
        s"$method icap://$host:$port/${service.stripPrefix("/")} ICAP/1.0\r\n" +
          s"Host: $host\r\n" +
          "Allow: 204\r\n" +
          "Connection: close\r\n" +
          s"Encapsulated: $parts=${encapsulatedHttp.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n" +
          encapsulatedHttp
      )
    }

    private def chunk(bytes: ByteString): ByteString = ByteString(s"${bytes.size.toHexString}\r\n") ++ bytes ++ ByteString("\r\n")

    override def scan(file: Path)(using mat: Materializer, ec: ExecutionContext): Future[ScanVerdict] = {
      val request = Source
        .single(head)
        .concat(FileIO.fromPath(file, 64 * 1024).filter(_.nonEmpty).map(chunk))
        .concat(Source.single(ByteString("0\r\n\r\n")))
      MalwareScanners.exchange(request, host, port, timeout, b => UploadScanner.indexOf(b, ByteString("\r\n\r\n")) >= 0).map { raw =>
        MalwareScanners.icapVerdict(raw.utf8String)
      }
    }
  }

  /** What an ICAP answer says, from its status line and the headers antivirus servers set. */
  def icapVerdict(answer: String): ScanVerdict = {
    val lines   = answer.split("\r\n").toSeq
    val status  = lines.headOption.flatMap(_.split(' ').lift(1)).flatMap(_.toIntOption).getOrElse(0)
    val headers = lines.drop(1).takeWhile(_.nonEmpty).flatMap { l =>
      val i = l.indexOf(':')
      Option.when(i > 0)(l.substring(0, i).trim.toLowerCase -> l.substring(i + 1).trim)
    }.toMap
    // `X-Infection-Found: Type=0; Resolution=2; Threat=Eicar-Test-Signature;` is the common form
    def threat: String = headers
      .get("x-infection-found")
      .flatMap(_.split(';').map(_.trim).find(_.toLowerCase.startsWith("threat=")).map(_.drop(7)))
      .orElse(headers.get("x-virus-id"))
      .orElse(headers.get("x-violations-found"))
      .getOrElse("a threat the scanner did not name")
    status match {
      case 204                                                                       => ScanVerdict.Clean
      case 200 if headers.contains("x-infection-found") || headers.contains("x-virus-id") => ScanVerdict.Infected(threat)
      case 200                                                                       => ScanVerdict.Infected(threat)
      case 0                                                                         => ScanVerdict.Failed("the ICAP server answered nothing readable")
      case other                                                                     => ScanVerdict.Failed(s"the ICAP server answered $other")
    }
  }

  /** Sends `request`, reads until `complete` says the answer is whole, within `timeout`. */
  private def exchange(request: Source[ByteString, ?], host: String, port: Int, timeout: FiniteDuration, complete: ByteString => Boolean)(using
      system: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext
  ): Future[ByteString] =
    request
      .via(Tcp(system).outgoingConnection(host, port))
      .scan(ByteString.empty)(_ ++ _)
      .takeWhile(acc => !complete(acc) && acc.size < 64 * 1024, inclusive = true)
      .completionTimeout(timeout)
      .runWith(Sink.lastOption)
      .map(_.getOrElse(ByteString.empty))

  /**
   * The EICAR test file, which every antivirus reports and which harms nothing.
   *
   * Assembled at run time from its base64 form, so no source file and no class file of this
   * extension carries the string an antivirus on a developer's machine would quarantine.
   */
  def eicar: ByteString =
    ByteString(Base64.getDecoder.decode("WDVPIVAlQEFQWzRcUFpYNTQoUF4pN0NDKTd9JEVJQ0FSLVNUQU5EQVJELUFOVElWSVJVUy1URVNULUZJTEUhJEgrSCo="))
}
