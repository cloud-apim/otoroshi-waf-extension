package com.cloud.apim.otoroshi.extensions.waf.body

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString

import java.io.ByteArrayOutputStream
import java.util.zip.{CRC32, Deflater, DeflaterOutputStream, GZIPOutputStream}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Random

/**
 * PRO-3 away from a gateway: a compressed body is read the way its backend will read it, whatever
 * the chunking, and what it decompresses to is held to the limits without decoding more than that.
 */
class DecodingSuite extends munit.FunSuite {

  given system: ActorSystem  = ActorSystem("decoding-suite")
  given mat: Materializer    = Materializer(system)
  given ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = { Await.result(system.terminate(), 10.seconds); () }

  private val text = ByteString(("""{"comment":"1' or 1=1--","n":""" + ("x" * 5000) + "}").getBytes("UTF-8"))

  private def gzip(bytes: ByteString): ByteString = {
    val out = new ByteArrayOutputStream()
    val gz  = new GZIPOutputStream(out)
    gz.write(bytes.toArray)
    gz.close()
    ByteString(out.toByteArray)
  }

  private def deflate(bytes: ByteString, raw: Boolean): ByteString = {
    val out = new ByteArrayOutputStream()
    val df  = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, raw))
    df.write(bytes.toArray)
    df.close()
    ByteString(out.toByteArray)
  }

  private def zeros(n: Int): ByteString = ByteString(new Array[Byte](n))

  // mostly incompressible, so its size is not reached through the ratio
  private def noise(n: Int): ByteString = ByteString(Array.fill(n)(Random.nextInt(256).toByte))

  private def decode(coding: String, body: ByteString, chunk: Int, keep: Long = 1L << 20, limits: Option[DecompressionLimits] = None): DecodedBody = {
    val decoded = new DecodedBody(coding, keep, limits)
    body.grouped(chunk).foreach(decoded.feed)
    decoded.close()
    decoded
  }

  test("the encoding is read from the header, and only what can be decoded is called decodable") {
    assertEquals(BodyEncoding.of(None), BodyEncoding.Identity)
    assertEquals(BodyEncoding.of(Some("identity")), BodyEncoding.Identity)
    assertEquals(BodyEncoding.of(Some("gzip")), BodyEncoding.Decodable("gzip"))
    assertEquals(BodyEncoding.of(Some(" X-GZIP ")), BodyEncoding.Decodable("gzip"))
    assertEquals(BodyEncoding.of(Some("deflate")), BodyEncoding.Decodable("deflate"))
    assertEquals(BodyEncoding.of(Some("zstd")), BodyEncoding.Undecodable("zstd"))
    // a chain of codings is a way to hide one behind another
    assertEquals(BodyEncoding.of(Some("gzip, br")), BodyEncoding.Undecodable("gzip, br"))
    assertEquals(BodyEncoding.of(Map("content-encoding" -> "gzip")), BodyEncoding.Decodable("gzip"))
  }

  test("gzip decodes to the original, whatever the chunking, the header split included") {
    val compressed = gzip(text)
    Seq(1, 3, 7, 11, 512, 64 * 1024).foreach { chunk =>
      val decoded = decode("gzip", compressed, chunk)
      assertEquals(decoded.corrupt, None, s"chunk $chunk")
      assertEquals(decoded.head, text, s"chunk $chunk")
      assertEquals(decoded.truncated, false)
    }
  }

  test("a gzip header with every optional field is skipped") {
    val crc = new CRC32()
    crc.update(text.toArray)
    val header = ByteString(0x1f, 0x8b, 8, 0x04 | 0x08 | 0x10 | 0x02, 0, 0, 0, 0, 0, 0xff) ++
      ByteString(3, 0) ++ ByteString("abc") ++ ByteString("name.json\u0000") ++ ByteString("a comment\u0000") ++ ByteString(0, 0)
    val size    = text.size
    val trailer = ByteString(
      (crc.getValue & 0xff).toByte, ((crc.getValue >> 8) & 0xff).toByte, ((crc.getValue >> 16) & 0xff).toByte, ((crc.getValue >> 24) & 0xff).toByte,
      (size & 0xff).toByte, ((size >> 8) & 0xff).toByte, ((size >> 16) & 0xff).toByte, ((size >> 24) & 0xff).toByte
    )
    val decoded = decode("gzip", header ++ deflate(text, raw = true) ++ trailer, 5)
    assertEquals(decoded.corrupt, None)
    assertEquals(decoded.head, text)
  }

  test("every gzip member is read, and what follows the last one is not") {
    val decoded = decode("gzip", gzip(ByteString("first,")) ++ gzip(ByteString("second")) ++ ByteString("trailing garbage"), 4)
    assertEquals(decoded.corrupt, None)
    assertEquals(decoded.head.utf8String, "first,second")
  }

  test("deflate is read zlib-wrapped, as RFC 9110 says, and raw, as it is often sent") {
    assertEquals(decode("deflate", deflate(text, raw = false), 9).head, text)
    assertEquals(decode("deflate", deflate(text, raw = true), 9).head, text)
  }

  test("brotli decodes to the original") {
    assume(BrotliChunkDecoder.ready, "no brotli native library on this platform")
    val compressed = ByteString(com.aayushatharva.brotli4j.encoder.Encoder.compress(text.toArray))
    Seq(1, 13, 64 * 1024).foreach { chunk =>
      val decoded = decode("br", compressed, chunk)
      assertEquals(decoded.corrupt, None)
      assertEquals(decoded.head, text, s"chunk $chunk")
    }
  }

  test("only the head is kept, and with no limits to hold, decoding stops there") {
    val bomb    = gzip(zeros(50 * 1024 * 1024))
    val decoded = decode("gzip", bomb, 64 * 1024, keep = 1024)
    assertEquals(decoded.head.size, 1024)
    assertEquals(decoded.truncated, true)
    assert(decoded.decompressedBytes < 1024 * 1024, s"decoded ${decoded.decompressedBytes} bytes for a 1 KiB head")
  }

  test("a decompression bomb is refused on its ratio, soon after the floor") {
    val bomb    = gzip(zeros(50 * 1024 * 1024))
    val decoded = decode("gzip", bomb, 16 * 1024, limits = Some(DecompressionLimits(maxSize = 64L * 1024 * 1024, maxRatio = 100)))
    assertEquals(decoded.breach, Some(DecompressionBreach.TooCompressed(100)))
    assert(decoded.decompressedBytes < 4L * 1024 * 1024, s"decoded ${decoded.decompressedBytes} bytes before refusing")
  }

  test("a body that is not a bomb is still refused past the absolute size") {
    val decoded = decode("gzip", gzip(noise(3 * 1024 * 1024)), 64 * 1024, limits = Some(DecompressionLimits(maxSize = 1024 * 1024, maxRatio = 100)))
    assertEquals(decoded.breach, Some(DecompressionBreach.TooLarge(1024 * 1024)))
  }

  test("an ordinary body stays inside the limits, and zero switches them off") {
    val ordinary = decode("gzip", gzip(text), 1024, limits = Some(DecompressionLimits(64L * 1024 * 1024, 100)))
    assertEquals(ordinary.breach, None)
    val off = decode("gzip", gzip(zeros(5 * 1024 * 1024)), 64 * 1024, limits = Some(DecompressionLimits(0, 0)))
    assertEquals(off.breach, None)
    assertEquals(off.decompressedBytes, 5L * 1024 * 1024)
  }

  test("a body that does not decode as what it claims is reported, not thrown") {
    assert(decode("gzip", ByteString("this is not gzip at all"), 4).corrupt.isDefined)
    assert(decode("gzip", gzip(text).take(10) ++ noise(200), 16).corrupt.isDefined)
    assert(decode("deflate", noise(300), 16).corrupt.isDefined)
  }

  test("past the head, the guard forwards the body untouched and cuts it on a breach") {
    val ordinary = gzip(noise(200 * 1024))
    val decoded  = new DecodedBody("gzip", 1024, Some(DecompressionLimits(64L * 1024 * 1024, 100)))
    val through  = Await.result(Source(ordinary.grouped(8 * 1024).toList).via(decoded.guard(_ => ())).runFold(ByteString.empty)(_ ++ _), 20.seconds)
    assertEquals(through, ordinary)

    @volatile var cut: Option[DecompressionBreach] = None
    val bomb    = gzip(zeros(50 * 1024 * 1024))
    val guarded = new DecodedBody("gzip", 1024, Some(DecompressionLimits(64L * 1024 * 1024, 100)))
    val run     = Source(bomb.grouped(8 * 1024).toList).via(guarded.guard(b => cut = Some(b))).runWith(Sink.ignore)
    intercept[DecompressionLimitException](Await.result(run, 20.seconds))
    assertEquals(cut, Some(DecompressionBreach.TooCompressed(100)))
  }
}
