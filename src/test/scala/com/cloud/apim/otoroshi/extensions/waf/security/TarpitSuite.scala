package com.cloud.apim.otoroshi.extensions.waf.security

import play.api.libs.json.Json

/** BEH-5's bound: a node never holds more requests than it was told it can afford. */
class TarpitSuite extends munit.FunSuite {

  test("slots are handed out up to the bound, and not one more") {
    val gate  = new TarpitGate(2)
    val first = gate.acquire()
    val other = gate.acquire()
    assert(first.isDefined && other.isDefined)
    assertEquals(gate.acquire(), None)
    assertEquals(gate.current, 2)
  }

  test("a released slot is free again, and releasing it twice frees it once") {
    val gate = new TarpitGate(1)
    val slot = gate.acquire().get
    slot.release()
    slot.release()
    assertEquals(gate.current, 0)
    assert(gate.acquire().isDefined)
    assertEquals(gate.acquire(), None)
  }

  test("a bound of zero holds nothing") {
    assertEquals(new TarpitGate(0).acquire(), None)
  }

  test("the policy reads its slow refusal, and refuses at once by default") {
    val base = Json.obj("id" -> "threat-policy_x", "name" -> "x")
    assertEquals(ThreatPolicy.format.reads(base).get.slowRefusalMillis, 0L)
    assertEquals(ThreatPolicy.format.reads(base ++ Json.obj("slow_refusal_millis" -> 2500)).get.slowRefusalMillis, 2500L)
    assertEquals(ThreatPolicy.format.reads(base ++ Json.obj("slow_refusal_millis" -> -5)).get.slowRefusalMillis, 0L)
    assertEquals((ThreatPolicy.format.writes(ThreatPolicy(id = "p", name = "p", slowRefusalMillis = 1200L)) \ "slow_refusal_millis").as[Long], 1200L)
  }
}
