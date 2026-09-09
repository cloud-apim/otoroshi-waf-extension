package com.cloud.apim.otoroshi.extensions.waf.body

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * H1 in one sentence: the old reader materialised the whole body and *then* applied the limit, so
 * the limit protected the rule engine while the heap held the entire upload.
 *
 * The two properties that matter here are therefore "it does not read it all" and "it does not lose
 * any of it either".
 */
class BodyReaderSuite extends munit.FunSuite {

  given system: ActorSystem      = ActorSystem("body-suite")
  given mat: Materializer        = Materializer(system)
  given ec: ExecutionContext     = system.dispatcher

  override def afterAll(): Unit = { Await.result(system.terminate(), 10.seconds); () }

  private def await[A](f: Future[A]): A = Await.result(f, 20.seconds)

  private def body(totalBytes: Int, chunk: Int = 64 * 1024): (Source[ByteString, ?], ByteString, AtomicLong) = {
    val whole  = ByteString(Array.tabulate(totalBytes)(i => (i % 251).toByte))
    val pulled = new AtomicLong(0L)
    val src    = Source(whole.grouped(chunk).toList).map { bs => pulled.addAndGet(bs.size.toLong); bs }
    (src, whole, pulled)
  }

  private def collect(src: Source[ByteString, ?]): ByteString =
    await(src.runFold(ByteString.empty)(_ ++ _))

  test("a body under the limit is buffered whole and comes back unchanged") {
    val (src, whole, _) = body(50 * 1024)
    val prefix          = await(BodyReader.prefix(src, 1024 * 1024))
    assertEquals(prefix.truncated, false)
    assertEquals(prefix.bytes, whole)
    assertEquals(collect(prefix.resume), whole)
  }

  test("a body over the limit is cut for the engine — and forwarded whole") {
    val (src, whole, _) = body(300 * 1024)
    val prefix          = await(BodyReader.prefix(src, 100 * 1024))
    assert(prefix.truncated, "the caller must be able to tell inspection was incomplete")
    assertEquals(prefix.bytes.size, 100 * 1024)
    assertEquals(prefix.bytes, whole.take(100 * 1024))
    assertEquals(collect(prefix.resume), whole, "not one byte of the body may be lost on the way through")
  }

  test("the whole body is never pulled — this is the bug H1 is about") {
    val total            = 8 * 1024 * 1024
    val (src, _, pulled) = body(total)
    val prefix           = await(BodyReader.prefix(src, 128 * 1024))
    assert(prefix.truncated)
    assert(
      pulled.get() <= 1024L * 1024L,
      s"only the head should have been read, but ${pulled.get()} bytes of $total were pulled"
    )
  }

  test("the buffer never exceeds the limit by more than one chunk") {
    val (src, _, _) = body(4 * 1024 * 1024)
    val prefix      = await(BodyReader.prefix(src, 200 * 1024))
    assert(
      prefix.buffered.size <= 200 * 1024 + BodyReader.chunkSize,
      s"buffered ${prefix.buffered.size} for a limit of ${200 * 1024}"
    )
  }

  test("small network chunks still fill the buffer to the limit") {
    // chunk boundaries are whatever the network decided; a reader that counts chunks instead of
    // bytes under-reads here and then reports the short read as a complete one
    val (src, whole, _) = body(64 * 1024, chunk = 1024)
    val prefix          = await(BodyReader.prefix(src, 32 * 1024))
    assert(prefix.truncated)
    assertEquals(prefix.bytes.size, 32 * 1024)
    assertEquals(collect(prefix.resume), whole)
  }

  test("a body of one-byte chunks is still bounded and still complete") {
    val whole  = ByteString(Array.tabulate(40 * 1024)(i => (i % 251).toByte))
    val src    = Source(whole.map(b => ByteString(b)).toList)
    val prefix = await(BodyReader.prefix(src, 8 * 1024))
    assert(prefix.truncated)
    assertEquals(prefix.bytes.size, 8 * 1024)
    assert(prefix.buffered.size <= 8 * 1024 + BodyReader.chunkSize)
    assertEquals(collect(prefix.resume), whole)
  }

  test("a body exactly the size of the limit is not reported as truncated") {
    val (src, whole, _) = body(64 * 1024, chunk = 8 * 1024)
    val prefix          = await(BodyReader.prefix(src, 64 * 1024))
    assertEquals(prefix.truncated, false)
    assertEquals(prefix.bytes, whole)
  }

  test("one byte over the limit is truncated") {
    val (src, _, _) = body(64 * 1024 + 1, chunk = 8 * 1024)
    assertEquals(await(BodyReader.prefix(src, 64 * 1024)).truncated, true)
  }

  test("an empty body reads as an empty prefix") {
    val prefix = await(BodyReader.prefix(Source.empty[ByteString], 1024))
    assertEquals(prefix.bytes, ByteString.empty)
    assertEquals(prefix.truncated, false)
    assertEquals(collect(prefix.resume), ByteString.empty)
  }

  test("a limit of zero inspects nothing and forwards everything") {
    val (src, whole, _) = body(20 * 1024)
    val prefix          = await(BodyReader.prefix(src, 0L))
    assertEquals(prefix.bytes, ByteString.empty)
    assertEquals(prefix.truncated, false)
    assertEquals(collect(prefix.resume), whole)
  }

  test("the tail keeps the network's own chunks — it is not re-cut on the way through") {
    // the tail of a large upload is the bulk of it; re-chunking or hopping it across an async
    // boundary per chunk would put the cost of inspection on the part nobody inspects
    val chunk           = 64 * 1024
    val (src, whole, _) = body(10 * chunk, chunk = chunk)
    val prefix          = await(BodyReader.prefix(src, 100 * 1024))
    val sizes           = await(prefix.resume.map(_.size).runWith(Sink.seq))
    // whatever the buffered head was re-cut into, the eight chunks past it arrive exactly as sent
    assertEquals(sizes.takeRight(8).toList, List.fill(8)(chunk))
    assertEquals(sizes.sum, 10 * chunk, "and nothing is lost in the process")
  }

  test("the body may be resumed once, and only once") {
    val (src, _, _) = body(200 * 1024)
    val prefix      = await(BodyReader.prefix(src, 64 * 1024))
    collect(prefix.resume)
    intercept[IllegalStateException](collect(prefix.resume))
  }

  test("the cut landing exactly on a chunk boundary leaves no remainder to re-emit") {
    // exercises the branch where `rest` is empty: limit + 1 falls on the end of an input chunk,
    // so the stage must emit the head alone rather than an empty second element
    val limit           = 8 * 1024 - 1
    val (src, whole, _) = body(40 * 1024, chunk = 8 * 1024)
    val prefix          = await(BodyReader.prefix(src, limit.toLong))
    assert(prefix.truncated)
    assertEquals(prefix.buffered.size, limit + 1)
    assertEquals(prefix.bytes.size, limit)
    assertEquals(collect(prefix.resume), whole)
  }

  test("a limit far larger than the body neither truncates nor overflows") {
    val (src, whole, _) = body(30 * 1024)
    val prefix          = await(BodyReader.prefix(src, Long.MaxValue))
    assertEquals(prefix.truncated, false, "the ceiling clamp must not make a small body look cut")
    assertEquals(prefix.bytes, whole)
    assertEquals(collect(prefix.resume), whole)
  }

  test("draining a body that ended inside the limit is a no-op, not a failure") {
    val (src, _, _) = body(4 * 1024)
    val prefix      = await(BodyReader.prefix(src, 64 * 1024))
    assertEquals(prefix.truncated, false)
    prefix.drain() // the tail is empty here; this must not throw or hang
  }

  // `Source.failed` concatenated directly poisons the stream at materialisation rather than where
  // it sits, so a test written that way measures the artefact and not the behaviour. Wrapping it
  // makes the failure happen where the data runs out, which is the case that actually occurs.
  private def failsAfter(chunks: List[ByteString], msg: String): Source[ByteString, ?] =
    Source(chunks).concat(Source.lazySource(() => Source.failed[ByteString](new RuntimeException(msg))))

  test("a connection dropped mid-upload leaves the break on the tail, not on the verdict") {
    // what did arrive is still inspectable, and gets inspected. the break belongs to whoever
    // forwards the body — the backend sees a broken upload and nobody gets a successful response,
    // so nothing is let through that should not be
    val prefix = await(BodyReader.prefix(failsAfter(List(ByteString("a" * 1024)), "connection reset"), 64 * 1024))
    assertEquals(prefix.bytes.size, 1024)
    assertEquals(prefix.truncated, false)
    val err = intercept[RuntimeException](collect(prefix.resume))
    assertEquals(err.getMessage, "connection reset")
  }

  test("a body that fails before sending anything fails the read itself") {
    val err = intercept[RuntimeException](await(BodyReader.prefix(failsAfter(Nil, "reset on connect"), 64 * 1024)))
    assertEquals(err.getMessage, "reset on connect")
  }

  test("a failure past the limit is carried by the tail, not by the prefix") {
    // the head was already read and is valid; whatever goes wrong afterwards belongs to whoever
    // consumes the rest of the body, not to the inspection decision
    val head   = ByteString("h" * (70 * 1024))
    val prefix = await(BodyReader.prefix(failsAfter(head.grouped(16 * 1024).toList, "reset mid-upload"), 32 * 1024))
    assert(prefix.truncated)
    assertEquals(prefix.bytes.size, 32 * 1024)
    intercept[RuntimeException](collect(prefix.resume))
  }

  test("the forwarded head is re-cut into bounded pieces, not pushed as one block") {
    val (src, _, _) = body(2 * 1024 * 1024)
    val prefix      = await(BodyReader.prefix(src, 1024 * 1024))
    val sizes       = await(prefix.resume.map(_.size).runWith(Sink.seq))
    assert(
      sizes.forall(_ <= math.max(BodyReader.chunkSize, 64 * 1024)),
      s"a 1 MB head must not reach the backend as one element: ${sizes.take(3)}"
    )
  }

  test("draining consumes the tail instead of leaving it dangling") {
    val (src, _, pulled) = body(2 * 1024 * 1024)
    val prefix           = await(BodyReader.prefix(src, 64 * 1024))
    prefix.drain()
    // the drain is asynchronous; what matters is that it runs to completion rather than hanging
    val deadline = System.currentTimeMillis() + 10000L
    while (pulled.get() < 2 * 1024 * 1024 && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertEquals(pulled.get(), 2L * 1024L * 1024L, "the rest of the upload must be read off the connection")
  }
}

class ResponseBodySuite extends munit.FunSuite {

  test("a plain GET has a response body — the case H2 silently skipped") {
    assertEquals(ResponseBody.hasBody("GET", 200, None), true)
    assertEquals(ResponseBody.hasBody("GET", 200, Some(1200L)), true)
  }

  test("HEAD never has a response body, whatever the headers say") {
    assertEquals(ResponseBody.hasBody("HEAD", 200, Some(1200L)), false)
    assertEquals(ResponseBody.hasBody("head", 200, None), false)
  }

  test("the statuses that cannot carry a body") {
    assertEquals(ResponseBody.hasBody("GET", 204, None), false)
    assertEquals(ResponseBody.hasBody("GET", 304, None), false)
    assertEquals(ResponseBody.hasBody("GET", 100, None), false)
    assertEquals(ResponseBody.hasBody("GET", 101, None), false)
  }

  test("a declared length of zero is not a body") {
    assertEquals(ResponseBody.hasBody("POST", 200, Some(0L)), false)
  }

  test("an absent length is chunked or close-delimited, which is a body") {
    assertEquals(ResponseBody.hasBody("POST", 500, None), true)
  }
}

class MediaTypeSuite extends munit.FunSuite {

  test("parameters are dropped — the whole of H3") {
    assertEquals(MediaType.of("text/html; charset=utf-8"), "text/html")
    assertEquals(MediaType.of("  APPLICATION/JSON  "), "application/json")
    assertEquals(MediaType.of("text/plain"), "text/plain")
  }

  test("a configured text/html matches the header a real server sends") {
    assert(MediaType.matches("text/html", "text/html; charset=utf-8"))
    assert(MediaType.matches("TEXT/HTML", "text/html;charset=UTF-8"))
  }

  test("a different type does not match") {
    assertEquals(MediaType.matches("text/html", "application/json"), false)
    assertEquals(MediaType.matches("text/html", "text/plain; charset=utf-8"), false)
  }

  test("subtype wildcards") {
    assert(MediaType.matches("text/*", "text/html; charset=utf-8"))
    assert(MediaType.matches("text/*", "text/plain"))
    assertEquals(MediaType.matches("text/*", "application/json"), false)
  }

  test("a bare star matches everything") {
    assert(MediaType.matches("*", "application/octet-stream"))
    assert(MediaType.matches("*/*", "text/html"))
  }

  test("any of a list") {
    val allowed = Seq("text/html", "application/json")
    assert(MediaType.matchesAny(allowed, "application/json; charset=utf-8"))
    assertEquals(MediaType.matchesAny(allowed, "image/png"), false)
    assertEquals(MediaType.matchesAny(Seq.empty, "text/html"), false)
  }
}
