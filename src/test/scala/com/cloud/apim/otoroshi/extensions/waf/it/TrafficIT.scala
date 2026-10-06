package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.security.IdentityRef
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute, PluginIndex}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimThreatResponse, CloudApimTrafficGuard}
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * BEH-4 through a real gateway: steady traffic is learned and goes through, a burst far above it is
 * refused, or throttled, by the threat response once it crosses the surge threshold, and once it is
 * over the same caller goes through again, with no one lifting anything.
 *
 * Each test calls from an address of its own, forwarded: what it refuses, charges and bans is
 * nobody else's, and the suites that ban or allowlist 127.0.0.1 can run meanwhile.
 */
class TrafficIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private def mod = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.security

  private def caller(): IdentityRef = {
    val r = new scala.util.Random()
    IdentityRef("ip", s"198.51.${100 + r.nextInt(100)}.${1 + r.nextInt(250)}")
  }

  private def forget(who: IdentityRef): Unit = {
    Await.result(mod.bans.unban(who), 30.seconds)
    Await.result(mod.ledger.forget(who), 30.seconds)
    mod.incidents.forget(who.key)
  }

  private def policy(tier: JsObject): String = {
    val policyId = s"threat-policy_beh4_${java.util.UUID.randomUUID().toString.take(8)}"
    val created  = Gateway.post(
      "/apis/waf.extensions.cloud-apim.com/v1/threat-policies",
      Json.obj("id" -> policyId, "name" -> "beh4-it", "enabled" -> true, "dry_run" -> false, "tiers" -> Json.arr(tier))
    )
    assert(created.status < 300, created.body)
    Await.result(mod.syncStates(), 30.seconds)
    policyId
  }

  private def route(name: String, policyId: String, backend: TestBackend): NgRoute = {
    val guard    = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[CloudApimTrafficGuard],
      config = NgPluginInstanceConfig(
        Json.obj(
          "route" -> false, "consumer" -> false, "asn" -> false,
          "bucket_seconds" -> 1, "learning_buckets" -> 5, "warmup_buckets" -> 2, "surge_factor" -> 3.0, "source_floor_rps" -> 1.0
        )
      ),
      pluginIndex = PluginIndex(validateAccess = 5.0.some).some
    )
    val response = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[CloudApimThreatResponse],
      config = NgPluginInstanceConfig(Json.obj("policy" -> policyId)),
      pluginIndex = PluginIndex(transformRequest = 900.0.some).some
    )
    Gateway.createRoute(name, backend.port, Seq(guard, response))
  }

  private def call(route: NgRoute, who: IdentityRef): WSResponse =
    Gateway.call(route, headers = Seq("X-Forwarded-For" -> who.value))

  /** A few requests a second, long enough to be learned. */
  private def learn(route: NgRoute, who: IdentityRef): Unit =
    (1 to 5).foreach { _ =>
      (1 to 3).foreach(_ => assertEquals(call(route, who).status, 200))
      Thread.sleep(1000)
    }

  test("a burst far above the learned traffic is refused, and the source goes through again once it stops") {
    val who      = caller()
    val policyId = policy(Json.obj("min_score" -> 40, "action" -> "deny"))
    val backend  = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route    = this.route("beh4-burst", policyId, backend)
    try {
      learn(route, who)
      val burst = (1 to 150).map(_ => call(route, who).status)
      assert(burst.take(5).forall(_ == 200), s"the burst starts within the usual traffic: ${burst.take(10)}")
      assert(burst.contains(403), s"the burst was never refused: ${burst.groupBy(identity).view.mapValues(_.size).toMap}")
      val incident = mod.incidents.byKey(who.key)
      assert(incident.exists(_.tags.contains("traffic:source_surge")), s"$incident")
      // the surge is over once its buckets are: the same caller goes through again
      Thread.sleep(2500)
      assertEquals(call(route, who).status, 200)
    } finally {
      Gateway.deleteRoute(route); backend.stop(); forget(who)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/threat-policies/$policyId")
    }
  }

  test("a throttle tier holds a surging source to its quota, with a Retry-After, and lets it go once it stops") {
    val who      = caller()
    val policyId = policy(Json.obj("min_score" -> 40, "action" -> "throttle", "throttle_quota" -> 10, "throttle_window_seconds" -> 30))
    val backend  = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route    = this.route("beh4-throttle", policyId, backend)
    try {
      learn(route, who)
      val burst = (1 to 150).map(_ => call(route, who))
      val codes = burst.map(_.status)
      assert(!codes.contains(403), s"a throttle never refuses with a 403: ${codes.groupBy(identity).view.mapValues(_.size).toMap}")
      val first = codes.indexOf(429)
      assert(first > 10, s"the quota goes through first: ${codes.take(first + 1)}")
      assert(burst(first).header("Retry-After").exists(_.toInt >= 1), s"${burst(first).headers}")
      assertEquals(Await.result(mod.ledger.scoreOf(who), 10.seconds), 0L, "a throttled request is not charged")
      Thread.sleep(2500)
      assertEquals(call(route, who).status, 200)
    } finally {
      Gateway.deleteRoute(route); backend.stop(); forget(who)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/threat-policies/$policyId")
    }
  }
}
