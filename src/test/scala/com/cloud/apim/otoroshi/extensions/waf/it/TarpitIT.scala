package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.security.IdentityRef
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimThreatGate, CloudApimThreatResponse, CloudApimWaf}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * BEH-5 through a real gateway: a refusal is held before it is sent when the policy says so, at the
 * gate for a caller already banned and in the response engine for a fresh verdict, and every held
 * request gives its slot back.
 */
class TarpitIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private def mod                            = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.security
  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 30.seconds)
  private val caller                         = IdentityRef("ip", "127.0.0.1")

  private def policy(slowRefusal: Long, extra: JsObject = Json.obj()): String = {
    val id  = s"threat-policy_${java.util.UUID.randomUUID().toString.take(8)}"
    val res = Gateway.post(
      "/apis/waf.extensions.cloud-apim.com/v1/threat-policies",
      Json.obj(
        "id"                  -> id,
        "name"                -> "beh5-it",
        "description"         -> "",
        "enabled"             -> true,
        "dry_run"             -> false,
        "tiers"               -> Json.arr(),
        "exemptions"          -> Json.arr(),
        "slow_refusal_millis" -> slowRefusal
      ) ++ extra
    )
    if (res.status > 299) throw new RuntimeException(s"could not create the threat policy: ${res.status} ${res.body}")
    // the module reads policies from its own state, refreshed on the extension's sync tick
    Thread.sleep(2000L)
    id
  }

  private def plugin[A](id: String, config: JsObject) = NgPluginInstance(plugin = id, config = NgPluginInstanceConfig(config))

  private def timed[A](f: => A): (A, Long) = {
    val start = System.currentTimeMillis()
    val res   = f
    (res, System.currentTimeMillis() - start)
  }

  test("a banned caller is refused slowly at the gate, and the slot is given back") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val slow    = policy(1500L)
    val fast    = policy(0L)
    val r1      = Gateway.createRoute("beh5-gate-slow", backend.port, Seq(plugin(NgPluginHelper.pluginId[CloudApimThreatGate], Json.obj("policy" -> slow))))
    val r2      = Gateway.createRoute("beh5-gate-fast", backend.port, Seq(plugin(NgPluginHelper.pluginId[CloudApimThreatGate], Json.obj("policy" -> fast))))
    try {
      await(mod.allowlist.remove(caller))
      assert(await(mod.bans.ban(caller, 1.hour, "beh5 integration test")).issued)
      val (slowRes, slowMs) = timed(Gateway.call(r1))
      assertEquals(slowRes.status, 403)
      assert(slowMs >= 1400L, s"refused after ${slowMs} ms")
      val (fastRes, fastMs) = timed(Gateway.call(r2))
      assertEquals(fastRes.status, 403)
      assert(fastMs < 1000L, s"refused after ${fastMs} ms")
      assertEquals(mod.tarpit.current, 0)
      assertEquals(backend.calls.get(), 0L)
    } finally {
      await(mod.bans.unban(caller))
      mod.incidents.forget(caller.key)
      Gateway.deleteRoute(r1); Gateway.deleteRoute(r2)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/threat-policies/$slow")
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/threat-policies/$fast")
      backend.stop()
    }
  }

  test("a deny from the response engine is held before it is sent") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    // the WAF only scores here: in monitoring it refuses nothing itself
    val waf     = Gateway.createWafConfig(
      Json.obj(
        "id"                 -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
        "name"               -> "beh5-it",
        "description"        -> "",
        "enabled"            -> true,
        "block"              -> false,
        "inspect_input_body" -> true,
        "rules"              -> Json.arr("""SecRule ARGS:q "@contains attack" "id:9101,phase:1,deny,status:403,msg:'attack'"""", "SecRuleEngine On")
      )
    )
    val pol     = policy(1500L, Json.obj("tiers" -> Json.arr(Json.obj("min_score" -> 40, "action" -> "deny", "status" -> 403))))
    val route   = Gateway.createRoute(
      "beh5-response",
      backend.port,
      Seq(
        plugin(NgPluginHelper.pluginId[CloudApimWaf], Json.obj("ref" -> waf)),
        plugin(NgPluginHelper.pluginId[CloudApimThreatResponse], Json.obj("policy" -> pol))
      )
    )
    try {
      await(mod.allowlist.remove(caller))
      await(mod.bans.unban(caller))
      val (clean, cleanMs) = timed(Gateway.call(route, "/?q=hello"))
      assertEquals(clean.status, 200)
      assert(cleanMs < 1000L, s"a clean request took ${cleanMs} ms")
      val (refused, refusedMs) = timed(Gateway.call(route, "/?q=attack"))
      assertEquals(refused.status, 403)
      assert(refusedMs >= 1400L, s"refused after ${refusedMs} ms")
      assertEquals(mod.tarpit.current, 0)
      assertEquals(backend.calls.get(), 1L)
    } finally {
      await(mod.bans.unban(caller))
      mod.incidents.forget(caller.key)
      Gateway.deleteRoute(route)
      Gateway.deleteWafConfig(waf)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/threat-policies/$pol")
      backend.stop()
    }
  }
}
