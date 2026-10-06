package com.cloud.apim.otoroshi.extensions.waf.traffic

import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimTrafficGuard, CloudApimTrafficGuardConfig}
import play.api.libs.json.Json

/**
 * BEH-4 on a clock this suite drives: nothing judged during warm-up, a surge seen against what was
 * learned, an attack never learned as normal, quiet decaying the baseline, and bounded memory.
 */
class TrafficSuite extends munit.FunSuite {

  private val settings = TrafficSettings(bucketSeconds = 10, learningBuckets = 10, warmupBuckets = 3, surgeFactor = 3.0)
  private val bucket   = settings.bucketMillis

  /** Sends `n` requests in bucket `b`, returns the last reading. */
  private def send(t: TrafficBaselines, key: String, b: Long, n: Int, floor: Double = 0.0): TrafficReading =
    (1 to n).map(i => t.observe(key, b * bucket + i, settings, floor).get).last

  private def learn(t: TrafficBaselines, key: String, buckets: Int, perBucket: Int): Unit =
    (0 until buckets).foreach(b => send(t, key, b, perBucket))

  test("nothing is judged during warm-up") {
    val t = new TrafficBaselines()
    assert(!send(t, "k", 0, 500).surging, "the first bucket has no baseline to be compared to")
  }

  test("a bucket past factor times the usual traffic is a surge, at the request that crosses it") {
    val t = new TrafficBaselines()
    learn(t, "k", 10, 20)
    val usual = send(t, "k", 10, 20)
    assert(!usual.surging, s"$usual")
    val t2 = new TrafficBaselines()
    learn(t2, "k", 10, 20)
    val readings = (1 to 120).map(i => t2.observe("k", 10 * bucket + i, settings, 0.0).get)
    val first    = readings.indexWhere(_.surging) + 1
    assert(first > 40 && first <= 70, s"the surge starts at request $first, the baseline being about ${readings.head.baseline}")
    assert(readings.last.ratio > 5.0)
  }

  test("an attack is never learned as normal") {
    val t = new TrafficBaselines()
    learn(t, "k", 10, 20)
    val before = send(t, "k", 10, 400).baseline
    (11 until 20).foreach(b => send(t, "k", b, 400))
    val after = t.observe("k", 20 * bucket, settings, 0.0).get.baseline
    assert(math.abs(after - before) < 1.0, s"the baseline moved from $before to $after during the attack")
  }

  test("a floor keeps a quiet key from surging on a handful of requests") {
    val t = new TrafficBaselines()
    learn(t, "k", 10, 1)
    assert(send(t, "k", 10, 30, floor = 50.0).surging == false)
    assert(send(t, "k", 11, 60, floor = 50.0).surging)
  }

  test("quiet decays the baseline, so traffic coming back after a lull is judged against little") {
    val t = new TrafficBaselines()
    learn(t, "k", 10, 100)
    val busy  = t.observe("k", 10 * bucket, settings, 0.0).get.baseline
    val quiet = t.observe("k", 60 * bucket, settings, 0.0).get.baseline
    assert(quiet < busy / 10, s"$busy then $quiet")
  }

  test("memory is bounded and idle keys are swept") {
    val t = new TrafficBaselines(maxKeys = 3)
    (1 to 5).foreach(i => t.observe(s"k$i", 0L, settings, 0.0))
    assertEquals(t.size, 3)
    assertEquals(t.observe("k5", 1L, settings, 0.0), None, "past the bound a new key is not tracked")
    assertEquals(t.sweep(10 * bucket, bucket), 3)
    assertEquals(t.size, 0)
  }

  test("a surge weighs more the further past its threshold it goes") {
    assertEquals(CloudApimTrafficGuard.escalate(40, TrafficReading(31, 10, 30, 10)), 40)
    assertEquals(CloudApimTrafficGuard.escalate(40, TrafficReading(61, 10, 30, 10)), 60)
    assertEquals(CloudApimTrafficGuard.escalate(40, TrafficReading(121, 10, 30, 10)), 80)
    assertEquals(CloudApimTrafficGuard.escalate(90, TrafficReading(500, 10, 30, 10)), 100)
  }

  test("a config survives a round trip, and the preset's sensitivity is a factor") {
    val cfg = CloudApimTrafficGuardConfig(asn = false, surgeFactor = 2.0, sourceFloorRps = 1.5, routeWeight = 30)
    assertEquals(CloudApimTrafficGuardConfig.format.reads(cfg.json).get, cfg)
    assertEquals(CloudApimTrafficGuardConfig.format.reads(Json.obj()).get, CloudApimTrafficGuardConfig.default)
    assertEquals(Seq("low", "medium", "high").map(CloudApimTrafficGuardConfig.surgeFactorOf), Seq(5.0, 3.0, 2.0))
    assertEquals(CloudApimTrafficGuardConfig.configFlow.filterNot(CloudApimTrafficGuardConfig.configSchema.keys.contains), Seq.empty[String])
  }
}
