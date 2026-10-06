package com.cloud.apim.otoroshi.extensions.waf.objects

import com.cloud.apim.otoroshi.extensions.waf.security.InMemorySharedStateStore
import com.cloud.apim.otoroshi.extensions.waf.traffic.TrafficSettings
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimObjectGuardConfig
import play.api.libs.json.Json

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * BEH-1 and BEH-2 on a clock this suite drives: which object a path names, a walk through
 * identifiers, mostly refused requests, a surge before and after a pace is learned, and a budget of
 * distinct objects that re-reading does not spend.
 */
class ObjectsSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val settings = ObjectSettings(
    surge = TrafficSettings(bucketSeconds = 60, learningBuckets = 10, warmupBuckets = 3, surgeFactor = 5.0),
    surgeFloor = 10.0,
    warmupCeiling = 50.0,
    sequentialMin = 20,
    sequentialWindowMillis = 600000L,
    sequentialMaxGap = 3L,
    enumerationMin = 10,
    enumerationRatio = 0.5,
    enumerationWindowMillis = 300000L
  )

  test("a template names the object by its parameters, for its methods, with a trailing * for the rest") {
    val orders = ObjectTemplate("/api/orders/{id}", name = "order", methods = Seq("GET"))
    assertEquals(orders.resolve("GET", ObjectPaths.split("/api/orders/42")), Some(ObjectRef("order", "42")))
    assertEquals(orders.resolve("DELETE", ObjectPaths.split("/api/orders/42")), None)
    assertEquals(orders.resolve("GET", ObjectPaths.split("/api/orders/42/lines")), None)
    assertEquals(orders.resolve("GET", ObjectPaths.split("/api/invoices/42")), None)
    val lines  = ObjectTemplate("/users/{user}/orders/{order}/*")
    assertEquals(lines.resolve("POST", ObjectPaths.split("/users/7/orders/9/lines/3?x=1")), Some(ObjectRef("/users/{user}/orders/{order}/*", "7/9")))
    assertEquals(ObjectTemplate.read(Json.toJson("/a/{id}")), Some(ObjectTemplate("/a/{id}")))
    assertEquals(
      ObjectTemplate.read(Json.obj("path" -> "/a/{id}", "name" -> "a", "methods" -> Json.arr("get"), "budget" -> 10)),
      Some(ObjectTemplate("/a/{id}", "a", Seq("GET"), Some(10L)))
    )
    assertEquals(ObjectTemplate.read(Json.obj("name" -> "no path")), None)
  }

  test("without a template, what looks like an identifier names the object, and the rest of the path its kind") {
    assertEquals(ObjectPaths.auto(ObjectPaths.split("/users/42/orders/7")), Some(ObjectRef("/users/{id}/orders/{id}", "42/7")))
    assertEquals(
      ObjectPaths.auto(ObjectPaths.split("/docs/3f2b8c1e-9a4d-4e2f-8b1a-0c9d8e7f6a5b")).map(_.kind),
      Some("/docs/{id}")
    )
    assert(ObjectPaths.idLike("507f1f77bcf86cd799439011"), "an ObjectId")
    assert(ObjectPaths.idLike("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "a ULID")
    assertEquals(Seq("v1", "orders", "me", "1.5", "", "1234567890123456789").filter(ObjectPaths.idLike), Seq.empty[String])
    assertEquals(ObjectPaths.auto(ObjectPaths.split("/api/v1/orders")), None)
    assertEquals(ObjectRef("k", "7/42").number, Some(42L))
    assertEquals(ObjectRef("k", "abc").number, None)
  }

  test("templates come first, detection after, and a preflight is never an object") {
    val templates = Seq(ObjectTemplate("/api/orders/{id}", name = "order"))
    assertEquals(ObjectPaths.resolve(templates, true, "GET", "/api/orders/abc").map(_._1), Some(ObjectRef("order", "abc")))
    assertEquals(ObjectPaths.resolve(templates, true, "GET", "/api/items/12").map(_._1), Some(ObjectRef("/api/items/{id}", "12")))
    assertEquals(ObjectPaths.resolve(templates, false, "GET", "/api/items/12"), None)
    assertEquals(ObjectPaths.resolve(templates, true, "OPTIONS", "/api/orders/12"), None)
  }

  test("an object read again is not new, and a dense run of new numbered ones is a walk") {
    val w     = new ObjectWatches()
    val first = w.touch("k", ObjectRef("o", "100"), 1000L, settings).get
    assert(first.fresh)
    assert(!w.touch("k", ObjectRef("o", "100"), 1001L, settings).get.fresh)
    val walk  = (101 to 125).map(i => w.touch("k", ObjectRef("o", i.toString), 1000L + i, settings).get).flatMap(_.sequential)
    assert(walk.nonEmpty, "twenty-six consecutive identifiers")
    assertEquals(walk.last.medianGap, 1L)
    assertEquals((walk.last.from, walk.last.to), (100L, 125L))
  }

  test("identifiers spread apart, or read too slowly, are not a walk") {
    val w = new ObjectWatches()
    val r = new scala.util.Random(7)
    assert((1 to 60).flatMap(i => w.touch("spread", ObjectRef("o", (r.nextInt(1000000) + 1).toString), i.toLong, settings).get.sequential).isEmpty)
    // one a minute: never twenty within ten minutes
    assert((1 to 60).flatMap(i => w.touch("slow", ObjectRef("o", i.toString), i * 60000L, settings).get.sequential).isEmpty)
  }

  test("object requests mostly refused or not found are an enumeration, and a few are not") {
    val w = new ObjectWatches()
    w.touch("k", ObjectRef("o", "1"), 1000L, settings)
    (1 to 9).foreach(_ => w.settle("k", denied = true, 1000L, settings))
    assertEquals(w.touch("k", ObjectRef("o", "2"), 1001L, settings).get.enumeration, None, "nine is under the minimum")
    w.settle("k", denied = true, 1002L, settings)
    assertEquals(w.touch("k", ObjectRef("o", "3"), 1003L, settings).get.enumeration, Some(Enumeration(10, 10)))
    (1 to 11).foreach(_ => w.settle("k", denied = false, 1004L, settings))
    assertEquals(w.touch("k", ObjectRef("o", "4"), 1005L, settings).get.enumeration, None, "under half refused")
    assertEquals(w.touch("k", ObjectRef("o", "5"), 1000L + 400000L, settings).get.enumeration, None, "a window later")
  }

  test("a new consumer is held to the warm-up ceiling, and then to its own pace") {
    val w     = new ObjectWatches()
    val bucket = 60000L
    val eager = (1 to 80).map(i => w.touch("eager", ObjectRef("o", s"x$i"), i.toLong, settings).get)
    assert(eager.take(50).forall(_.surge.isEmpty))
    assert(eager.drop(50).forall(_.surge.isDefined), "past the ceiling before any pace is learned")
    val calm  = (0 until 6).flatMap(b => (1 to 8).map(i => w.touch("calm", ObjectRef("o", s"$b-$i"), b * bucket + i, settings).get))
    assert(calm.forall(_.surge.isEmpty))
    val burst = (1 to 60).map(i => w.touch("calm", ObjectRef("o", s"burst-$i"), 6 * bucket + i, settings).get)
    val first = burst.indexWhere(_.surge.isDefined) + 1
    assert(first > 10 && first <= 45, s"five times a pace of about eight, at request $first")
  }

  test("memory is bounded and idle consumers are swept") {
    val w = new ObjectWatches(maxKeys = 2)
    (1 to 4).foreach(i => w.touch(s"k$i", ObjectRef("o", "1"), 0L, settings))
    assertEquals(w.size, 2)
    assertEquals(w.touch("k9", ObjectRef("o", "1"), 0L, settings), None)
    assertEquals(w.sweep(7200000L, 3600000L), 2)
    assertEquals(w.size, 0)
  }

  test("a budget counts distinct objects: reading again is free, a new one past it is refused and stays refused") {
    val budgets = new ObjectBudgets("objects", new InMemorySharedStateStore())
    def take(id: String, at: Long = 10000L) = await(budgets.take("k", id, 3L, 1.hour, at))
    assertEquals(take("a"), BudgetVerdict.Within(1L))
    assertEquals(take("b"), BudgetVerdict.Within(2L))
    assertEquals(take("a"), BudgetVerdict.Seen)
    assertEquals(take("c"), BudgetVerdict.Within(3L))
    assertEquals(take("d"), BudgetVerdict.Over(3L, 3600000L - 10000L))
    assertEquals(take("d"), BudgetVerdict.Over(3L, 3600000L - 10000L), "asking again is refused again")
    assertEquals(take("b"), BudgetVerdict.Seen, "what was read stays readable")
    assertEquals(await(budgets.take("other", "d", 3L, 1.hour, 10000L)), BudgetVerdict.Within(1L), "another consumer")
    assertEquals(take("d", 3600000L + 1L), BudgetVerdict.Within(1L), "the next window")
  }

  test("a budget seen over by another node is still refused here, and still free for what was read") {
    val store = new InMemorySharedStateStore()
    val one   = new ObjectBudgets("objects", store)
    val two   = new ObjectBudgets("objects", store)
    (1 to 2).foreach(i => await(one.take("k", s"o$i", 2L, 1.hour, 10L)))
    assert(await(two.take("k", "o3", 2L, 1.hour, 20L)).isInstanceOf[BudgetVerdict.Over])
    assertEquals(await(two.take("k", "o1", 2L, 1.hour, 30L)), BudgetVerdict.Seen)
  }

  test("a config survives a round trip, a bare path stays a string, and every field of the flow is described") {
    val cfg = CloudApimObjectGuardConfig(
      paths = Seq(ObjectTemplate("/a/{id}"), ObjectTemplate("/b/{id}", "b", Seq("GET"), Some(20L))),
      contribute = true,
      budget = 100L,
      budgetAction = "log",
      sequentialMaxGap = 2L
    )
    assertEquals(CloudApimObjectGuardConfig.format.reads(cfg.json).get, cfg)
    assertEquals((cfg.json \ "paths" \ 0).as[String], "/a/{id}")
    assertEquals(CloudApimObjectGuardConfig.format.reads(Json.obj()).get, CloudApimObjectGuardConfig.default)
    assertEquals(CloudApimObjectGuardConfig.configFlow.filterNot(CloudApimObjectGuardConfig.configSchema.keys.contains), Seq.empty[String])
  }
}
