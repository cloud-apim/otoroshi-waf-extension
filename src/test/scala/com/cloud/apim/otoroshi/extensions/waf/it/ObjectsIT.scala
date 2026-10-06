package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.security.IdentityRef
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute, PluginIndex}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimObjectGuard, CloudApimThreatResponse}
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * BEH-1 and BEH-2 through a real gateway: a budget of distinct objects refuses the next new one and
 * not one already read, object requests mostly not found are scored up to a refusal, and a walk
 * through identifiers is reported while every request goes through.
 *
 * Each test calls from an address of its own, forwarded: what it refuses and reports is nobody
 * else's.
 */
class ObjectsIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private def mod = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.security

  private def caller(): IdentityRef = {
    val r = new scala.util.Random()
    IdentityRef("ip", s"203.0.${100 + r.nextInt(100)}.${1 + r.nextInt(250)}")
  }

  private def guard(config: JsObject) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimObjectGuard],
    config = NgPluginInstanceConfig(config),
    pluginIndex = PluginIndex(transformRequest = 4.0.some, transformResponse = 4.0.some).some
  )

  private def call(route: NgRoute, who: IdentityRef, path: String): WSResponse =
    Gateway.call(route, path = path, headers = Seq("X-Forwarded-For" -> who.value))

  private def forget(who: IdentityRef): Unit = {
    Await.result(mod.bans.unban(who), 30.seconds)
    Await.result(mod.ledger.forget(who), 30.seconds)
    mod.incidents.forget(who.key)
  }

  test("past its budget a consumer is refused a new object until the window ends, and keeps what it read") {
    val who     = caller()
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute("beh2-budget", backend.port, Seq(guard(Json.obj("paths" -> Json.arr("/api/orders/{id}"), "budget" -> 5))))
    try {
      (1 to 5).foreach(i => assertEquals(call(route, who, s"/api/orders/$i").status, 200))
      val refused = call(route, who, "/api/orders/6")
      assertEquals(refused.status, 429)
      assert(refused.header("Retry-After").exists(_.toInt > 60), s"${refused.headers}")
      assertEquals(call(route, who, "/api/orders/6").status, 429, "asking again is refused again")
      assertEquals(call(route, who, "/api/orders/3").status, 200, "what was read stays readable")
      assertEquals(call(route, who, "/api/invoices").status, 200, "what is not an object is not counted")
      assertEquals(call(route, caller(), "/api/orders/6").status, 200, "another consumer has its own budget")
      assertEquals(backend.calls.get(), 8L, "a refused object never reaches the backend")
      assert(mod.incidents.byKey(who.key).exists(_.tags.contains("objects:over_budget")))
    } finally {
      Gateway.deleteRoute(route); backend.stop(); forget(who)
    }
  }

  test("object requests mostly not found are scored, and the threat response refuses the consumer") {
    val who      = caller()
    val policyId = s"threat-policy_beh1_${java.util.UUID.randomUUID().toString.take(8)}"
    val created  = Gateway.post(
      "/apis/waf.extensions.cloud-apim.com/v1/threat-policies",
      Json.obj("id" -> policyId, "name" -> "beh1-it", "enabled" -> true, "dry_run" -> false, "tiers" -> Json.arr(Json.obj("min_score" -> 40, "action" -> "deny")))
    )
    assert(created.status < 300, created.body)
    Await.result(mod.syncStates(), 30.seconds)
    val backend  = new TestBackend(status = 404)(using Gateway.system, Gateway.mat, Gateway.ec)
    val response = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[CloudApimThreatResponse],
      config = NgPluginInstanceConfig(Json.obj("policy" -> policyId)),
      pluginIndex = PluginIndex(transformRequest = 900.0.some).some
    )
    val route    = Gateway.createRoute(
      "beh1-enumeration",
      backend.port,
      Seq(guard(Json.obj("contribute" -> true, "enumeration_min" -> 5)), response)
    )
    try {
      val codes = (1 to 10).map(i => call(route, who, s"/accounts/${1000 + i * 37}").status)
      assertEquals(codes.take(5), Seq.fill(5)(404), "the first ones reach the backend")
      assert(codes.drop(5).contains(403), s"the enumeration was never refused: $codes")
      assert(mod.incidents.byKey(who.key).exists(_.tags.contains("objects:enumeration")))
    } finally {
      Gateway.deleteRoute(route); backend.stop(); forget(who)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/threat-policies/$policyId")
    }
  }

  test("a walk through identifiers is reported, and alert only lets every request through") {
    val who     = caller()
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute("beh1-walk", backend.port, Seq(guard(Json.obj("sequential_min" -> 20))))
    try {
      val codes = (500 to 530).map(i => call(route, who, s"/users/$i").status)
      assert(codes.forall(_ == 200), s"$codes")
      val incident = mod.incidents.byKey(who.key)
      assert(incident.exists(_.tags.contains("objects:sequential")), s"$incident")
      assert(incident.forall(!_.tags.contains("objects:enumeration")))
    } finally {
      Gateway.deleteRoute(route); backend.stop(); forget(who)
    }
  }
}
