package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.security.*
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimThreatGate
import play.api.libs.json.Json

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * OPS-7 through a real gateway.
 *
 * The unit tests prove the stores in isolation, against an in-memory shared state. Two things only
 * a running gateway can answer: that the allowlist actually reaches the request path — a refusal
 * that never gets read is not a refusal — and that an incident survives the round trip through the
 * real datastore with its timeline intact, which is the whole reason the board publishes at all.
 */
class IncidentConsoleIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private def ext = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get
  private def mod = ext.security

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 30.seconds)

  private val caller = IdentityRef("ip", "127.0.0.1")

  /** A policy that actually enforces — the built-in one records and lets everything through. */
  private def enforcingPolicy(): String = {
    val id  = s"threat-policy_${java.util.UUID.randomUUID().toString.take(8)}"
    val res = Gateway.post(
      "/apis/waf.extensions.cloud-apim.com/v1/threat-policies",
      Json.obj(
        "id"          -> id,
        "name"        -> "ops7-it",
        "description" -> "",
        "enabled"     -> true,
        "dry_run"     -> false,
        "tiers"       -> Json.arr(),
        "exemptions"  -> Json.arr()
      )
    )
    if (res.status > 299) throw new RuntimeException(s"could not create the threat policy: ${res.status} ${res.body}")
    // the module reads policies from its own state, refreshed on the extension's sync tick
    Thread.sleep(2000L)
    id
  }

  private def gate(policy: String) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimThreatGate],
    config = NgPluginInstanceConfig(Json.obj("policy" -> policy))
  )

  private def cleanup(): Unit = {
    await(mod.allowlist.remove(caller))
    await(mod.bans.unban(caller))
    mod.incidents.forget(caller.key)
  }

  test("an allowlisted caller reaches the backend, and cannot be banned again by any path") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val policy  = enforcingPolicy()
    val route   = Gateway.createRoute("ops7", backend.port, Seq(gate(policy)))
    try {
      cleanup()

      // 1. an ordinary caller gets through
      assertEquals(Gateway.call(route).status, 200)

      // 2. banned, the gate refuses before anything else runs
      assert(await(mod.bans.ban(caller, 1.hour, "ops7 integration test")).issued)
      assertEquals(Gateway.call(route).status, 403, "the threat gate must refuse a banned caller")

      // 3. allowlisted and released — the deciding node enforces the change on the next request,
      //    with no refresh in between
      await(mod.allowlist.allow(caller, "the integration test itself", "ops@example.com"))
      await(mod.bans.unban(caller))
      assertEquals(Gateway.call(route).status, 200, "an allowlisted caller must reach the backend")

      // 4. and nothing can put them back — this is the promise the console makes
      val refused = await(mod.bans.ban(caller, 1.hour, "trying again"))
      assert(!refused.issued, "an allowlisted identity must not be bannable")
      assertEquals((refused.json \ "refused").asOpt[String], Some("allowlisted"))
      assertEquals(Gateway.call(route).status, 200)

      // 5. removing the entry hands the fabric its authority back
      await(mod.allowlist.remove(caller))
      assert(await(mod.bans.ban(caller, 1.hour, "and now it sticks")).issued)
      assertEquals(Gateway.call(route).status, 403)
    } finally {
      cleanup()
      Gateway.deleteRoute(route)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/threat-policies/$policy")
      backend.stop()
    }
  }

  test("an incident published to the real datastore comes back with its evidence") {
    cleanup()
    try {
      mod.incidents.record(
        ref = caller,
        category = "waf",
        score = 90,
        tags = Seq("waf:match"),
        action = "deny",
        message = "sqli in ARGS:q",
        enforced = true,
        routeId = Some("route_ops7"),
        routeName = Some("ops7")
      )
      await(mod.board.publish())

      val view = await(mod.board.all()).find(_.key == caller.key)
      assert(view.isDefined, "the caller must appear on the board")
      assertEquals(view.get.incident.count, 1)
      assertEquals(view.get.incident.enforcedCount, 1)
      assertEquals(view.get.incident.timeline.map(_.message), Seq("sqli in ARGS:q"))
      assertEquals(view.get.incident.routes, Set("ops7"))
      assertEquals(view.get.effectiveState, IncidentState.Open)

      // the state is the half a team works from, and it lives in the shared state rather than in
      // the node's memory
      await(mod.board.setState(caller.key, IncidentState.Acknowledged, "ops@example.com", Some("looking at it")))
      val acked = await(mod.board.all()).find(_.key == caller.key).get
      assertEquals(acked.effectiveState, IncidentState.Acknowledged)
      assertEquals(acked.state.flatMap(_.note), Some("looking at it"))

      await(mod.board.setState(caller.key, IncidentState.Open, "ops@example.com", None))
      assertEquals(await(mod.board.all()).find(_.key == caller.key).get.state, None)
    } finally cleanup()
  }

  test("a ban carries what the caller did, and it outlives the incident") {
    cleanup()
    try {
      mod.incidents.record(caller, "waf", 90, Seq("waf:match"), "deny", "sqli in ARGS:q", enforced = true)
      mod.incidents.record(caller, "reputation", 40, Seq("rep"), "log", "listed on firehol1")

      val timeline = mod.incidents.byKey(caller.key).toSeq.flatMap(_.timeline)
      val ban      = await(mod.bans.ban(caller, 1.hour, "accumulated", timeline = timeline)).entry.get
      assertEquals(ban.timeline.size, 2)

      // the incident is gone; the ban still says why it exists
      mod.incidents.forget(caller.key)
      assertEquals(mod.incidents.byKey(caller.key), None)
      await(mod.bans.refresh())
      val reread = mod.bans.check(caller).get
      assertEquals(reread.timeline.map(_.category), Seq("reputation", "waf"))
    } finally cleanup()
  }
}
