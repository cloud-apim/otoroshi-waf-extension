package com.cloud.apim.otoroshi.extensions.waf.security

import play.api.Logger

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * The point of porting Otoroshi's fail2ban rather than reusing it is that the counters and the bans
 * are shared. These tests are mostly about that: the same counter seen from two nodes, and a ban
 * that lands in the store the gate reads.
 */
class Fail2BanSuite extends munit.FunSuite {

  given ExecutionContext = ExecutionContext.global

  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)
  private val ref = IdentityRef("ip", "1.2.3.4")

  private def fixture(): (SharedStateStore, BanStore, Fail2BanCounter) = {
    val shared = new InMemorySharedStateStore()
    val bans   = new BanStore("bans", shared, "node-1", Logger("test"))
    (shared, bans, new Fail2BanCounter("f2b", shared, bans, Logger("test")))
  }

  private def hit(counter: Fail2BanCounter, maxRetry: Int = 3, enforce: Boolean = true) =
    await(counter.fail("route-a-1.2.3.4", ref, 10.minutes, maxRetry, 1.hour, "test", enforce = enforce))

  test("failures below the threshold count and do nothing else") {
    val (_, bans, counter) = fixture()
    assertEquals(hit(counter).count, 1L)
    assertEquals(hit(counter).count, 2L)
    assertEquals(bans.check(ref), None)
  }

  test("the threshold bans, and the ban carries the fail2ban tag so the plugin can own it") {
    val (_, bans, counter) = fixture()
    hit(counter); hit(counter)
    val outcome = hit(counter)
    assert(outcome.banned)
    val entry = bans.check(ref).get
    assert(entry.tags.contains(Fail2Ban.tag), s"expected the fail2ban tag, got ${entry.tags}")
    assert(entry.reason.contains("3 failed requests"))
  }

  test("the counter is consumed by the ban, or the next failure re-bans instantly") {
    val (_, _, counter) = fixture()
    hit(counter); hit(counter); hit(counter)
    assertEquals(await(counter.countOf("route-a-1.2.3.4")), 0L)
  }

  test("an already banned caller is not re-banned by a later failure") {
    val (_, bans, counter) = fixture()
    hit(counter); hit(counter)
    val first = hit(counter).ban.get
    hit(counter); hit(counter); hit(counter)
    assertEquals(bans.check(ref).map(_.issuedAt), Some(first.issuedAt), "the original ban still stands, untouched")
  }

  test("dry run counts, reports reaching the threshold, and never bans") {
    val (_, bans, counter) = fixture()
    hit(counter, enforce = false); hit(counter, enforce = false)
    val outcome = hit(counter, enforce = false)
    assert(outcome.reached, "it must still report that the threshold was reached")
    assertEquals(outcome.banned, false)
    assertEquals(bans.check(ref), None)
  }

  test("the window slides, so a caller who keeps failing never ages out") {
    val (shared, _, counter) = fixture()
    await(counter.fail("s", ref, 200.millis, 10, 1.hour, "test"))
    Thread.sleep(120)
    await(counter.fail("s", ref, 200.millis, 10, 1.hour, "test"))
    Thread.sleep(120)
    assertEquals(await(counter.countOf("s")), 2L, "the second failure pushed the window out")
  }

  test("a caller who stops is forgotten") {
    val (_, _, counter) = fixture()
    await(counter.fail("s", ref, 100.millis, 10, 1.hour, "test"))
    Thread.sleep(150)
    assertEquals(await(counter.countOf("s")), 0L)
  }

  test("two nodes share one counter — the whole reason for this port") {
    val shared = new InMemorySharedStateStore()
    val bansA  = new BanStore("bans", shared, "a", Logger("test"))
    val bansB  = new BanStore("bans", shared, "b", Logger("test"))
    val nodeA  = new Fail2BanCounter("f2b", shared, bansA, Logger("test"))
    val nodeB  = new Fail2BanCounter("f2b", shared, bansB, Logger("test"))

    await(nodeA.fail("s", ref, 10.minutes, 3, 1.hour, "test"))
    await(nodeB.fail("s", ref, 10.minutes, 3, 1.hour, "test"))
    val outcome = await(nodeB.fail("s", ref, 10.minutes, 3, 1.hour, "test"))

    assert(outcome.banned, "three failures spread over two nodes must reach a threshold of three")
    assertEquals(bansB.check(ref).isDefined, true, "the node that decided enforces immediately")
    assertEquals(bansA.check(ref), None, "the other node has not refreshed yet")
    assertEquals(await(bansA.refresh()), 1)
    assertEquals(bansA.check(ref).isDefined, true, "and picks it up on the next refresh")
  }

  test("a store that throws costs the response nothing") {
    val broken = new InMemorySharedStateStore() {
      override def incrBy(key: String, by: Long): Future[Long] = Future.failed(new RuntimeException("down"))
    }
    val bans    = new BanStore("bans", broken, "node-1", Logger("test"))
    val counter = new Fail2BanCounter("f2b", broken, bans, Logger("test"))
    assertEquals(await(counter.fail("s", ref, 1.minute, 1, 1.hour, "test")).banned, false)
  }

  test("forgetting a scope resets it") {
    val (_, _, counter) = fixture()
    hit(counter); hit(counter)
    await(counter.forget("route-a-1.2.3.4"))
    assertEquals(await(counter.countOf("route-a-1.2.3.4")), 0L)
  }
}
