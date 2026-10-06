package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.security.IdentityRef
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute, PluginIndex}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimLoginGuard, CloudApimThreatResponse}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * BEH-3 through a real gateway: failed logins counted on the way back, the threat response refusing
 * the source once its pattern is seen, a likely takeover reported, and a failure told by its body.
 */
class LoginIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  // the threat response refuses the test client itself: no suite allowlisting it may run meanwhile
  override def beforeAll(): Unit = Gateway.theCaller.acquire()
  override def afterAll(): Unit  = {
    Await.result(mod.bans.unban(caller), 30.seconds)
    mod.incidents.forget(caller.key)
    Gateway.theCaller.release()
  }

  private def mod    = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.security
  private val caller = IdentityRef("ip", "127.0.0.1")

  private def denyingPolicy(): String = {
    val id  = s"threat-policy_beh3_${java.util.UUID.randomUUID().toString.take(8)}"
    val res = Gateway.post(
      "/apis/waf.extensions.cloud-apim.com/v1/threat-policies",
      Json.obj(
        "id"      -> id,
        "name"    -> "beh3-it",
        "enabled" -> true,
        "dry_run" -> false,
        "tiers"   -> Json.arr(Json.obj("min_score" -> 40, "action" -> "deny", "status" -> 403))
      )
    )
    assert(res.status < 300, res.body)
    Await.result(mod.syncStates(), 30.seconds)
    id
  }

  private def guard(config: JsObject) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimLoginGuard],
    config = NgPluginInstanceConfig(config),
    pluginIndex = PluginIndex(transformRequest = 3.0.some, transformResponse = 3.0.some).some
  )

  private def response(policy: String) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimThreatResponse],
    config = NgPluginInstanceConfig(Json.obj("policy" -> policy)),
    pluginIndex = PluginIndex(transformRequest = 900.0.some).some
  )

  private def login(route: NgRoute, user: String) =
    Gateway.call(route, "/login", "POST", Some(ByteString(Json.stringify(Json.obj("email" -> user, "password" -> "nope")))), "application/json")

  private def backend(status: Int, body: String = """{"ok":false}""") =
    new TestBackend(status = status, responseBody = ByteString(body))(using Gateway.system, Gateway.mat, Gateway.ec)

  // counting happens on the way back, after the response has gone: give it a moment
  private def settle(): Unit = Thread.sleep(200)

  test("a source that keeps failing is refused by the threat response once it is credential stuffing") {
    val realm   = s"beh3-${System.nanoTime()}"
    val policy  = denyingPolicy()
    val b       = backend(401)
    val route   = Gateway.createRoute(
      "beh3-stuffing",
      b.port,
      Seq(guard(Json.obj("login_paths" -> Json.arr("/login"), "realm" -> realm, "source_failures" -> 3, "source_accounts" -> 0)), response(policy))
    )
    try {
      (1 to 3).foreach { _ =>
        assertEquals(login(route, "jane@example.com").status, 401)
        settle()
      }
      assertEquals(login(route, "jane@example.com").status, 403, "the fourth attempt is stuffing")
      assertEquals(b.calls.get(), 3L)
      val incident = mod.incidents.byKey(caller.key)
      assert(incident.exists(_.tags.contains("login:credential_stuffing")), s"$incident")
    } finally {
      Gateway.deleteRoute(route); b.stop()
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/threat-policies/$policy")
    }
  }

  test("a success from a source that just failed against several accounts is a likely takeover") {
    val realm  = s"beh3-${System.nanoTime()}"
    val failing = backend(401)
    val passing = backend(200, """{"token":"t"}""")
    val config  = Json.obj("login_paths" -> Json.arr("/login"), "realm" -> realm, "source_failures" -> 0, "source_accounts" -> 0, "takeover_accounts" -> 2)
    val r1      = Gateway.createRoute("beh3-ato-fail", failing.port, Seq(guard(config)))
    val r2      = Gateway.createRoute("beh3-ato-ok", passing.port, Seq(guard(config)))
    try {
      assertEquals(login(r1, "alice@example.com").status, 401)
      assertEquals(login(r1, "bob@example.com").status, 401)
      settle()
      assertEquals(login(r2, "carol@example.com").status, 200, "the guard reports, it does not refuse")
      settle()
      val incident = mod.incidents.byKey(caller.key)
      assert(incident.exists(_.tags.contains("login:account_takeover")), s"$incident")
      // in the timeline rather than as the last message: other suites record on 127.0.0.1 meanwhile
      assert(incident.exists(_.timeline.exists(_.message.contains("c***@example.com"))), s"${incident.map(_.timeline)}")
    } finally {
      Gateway.deleteRoute(r1); Gateway.deleteRoute(r2); failing.stop(); passing.stop()
    }
  }

  test("an application answering 200 either way is read by its failure marker") {
    val realm = s"beh3-${System.nanoTime()}"
    val b     = backend(200, """{"error":"Invalid credentials"}""")
    val route = Gateway.createRoute(
      "beh3-marker",
      b.port,
      Seq(guard(Json.obj("login_paths" -> Json.arr("/login"), "realm" -> realm, "failure_marker" -> "Invalid credentials")))
    )
    try {
      (1 to 2).foreach(_ => login(route, "dave@example.com"))
      settle()
      val state = Await.result(mod.logins.state(realm, "127.0.0.1", mod.logins.account(realm, "dave@example.com"), 900000L), 10.seconds)
      assertEquals(state.accountFailures, 2L)
    } finally {
      Gateway.deleteRoute(route); b.stop()
    }
  }
}
