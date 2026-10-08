package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.it.StudioApiClient.*
import com.cloud.apim.otoroshi.extensions.waf.security.{IdentityRef, SecurityModule}
import com.cloud.apim.otoroshi.extensions.waf.studio.ThreatStudio
import otoroshi.next.models.NgRoute
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * What one workspace sees of the gateway, and what it may do to the callers it saw.
 *
 * Bans, incidents and the api inventory are the gateway's, kept by caller or by route rather than by
 * workspace. The suite lays three workspaces over routes of two teams and checks that each one is
 * shown its own routes' share of that state, and acts only on what happened to it.
 */
class ThreatStudioScopedIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private given otoroshi.env.Env                 = Gateway.instance.env
  private given scala.concurrent.ExecutionContext = Gateway.ec

  private def ext: CloudApimWafExtension = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get
  private def studio: ThreatStudio       = ext.studio
  private def security: SecurityModule   = ext.security

  private val teamA       = Key("studio-scoped-team-a")
  private val tenantAdmin = Key("studio-scoped-tenant-admin")

  // ws_sa claims a1 (team-a), ws_sb claims b1 (team-b), ws_sm claims a2 (team-a) and b2 (team-b)
  private val table = CloudApimSecuritySuiteGlobalPresetConfig(
    Seq(rule("ws_sa", "scoped-a"), rule("ws_sb", "scoped-b"), rule("ws_sm", "scoped-mixed"))
  )

  private var backend: TestBackend = null
  private var routes: Seq[NgRoute] = Seq.empty
  private var contractId: String   = ""

  // callers of their own, so nothing here meets the test client another suite bans
  private val both    = IdentityRef(IdentityRef.Ip, "203.0.113.10")
  private val onlyB   = IdentityRef(IdentityRef.Ip, "203.0.113.11")
  private val fabricB = IdentityRef(IdentityRef.Ip, "203.0.113.12")
  private val fabricX = IdentityRef(IdentityRef.Ip, "203.0.113.13")

  private def seen(ref: IdentityRef, route: String): Unit = {
    security.incidents.record(ref, "waf", 5, Seq("sqli"), "log", s"seen on $route", routeId = Some(s"route_$route"), routeName = Some(route))
    ()
  }

  override def beforeAll(): Unit = {
    Gateway.theTable.acquire()
    createKey(teamA, teamRights("team-a"))
    createKey(tenantAdmin, tenantAdminRights)
    backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    routes = Seq(
      Gateway.createRoute("scoped-a1", backend.port, Seq.empty, tags = Seq("scoped-a"), location = of("team-a")),
      Gateway.createRoute("scoped-b1", backend.port, Seq.empty, tags = Seq("scoped-b"), location = of("team-b")),
      Gateway.createRoute("scoped-a2", backend.port, Seq.empty, tags = Seq("scoped-mixed"), location = of("team-a")),
      Gateway.createRoute("scoped-b2", backend.port, Seq.empty, tags = Seq("scoped-mixed"), location = of("team-b"))
    )
    Await.result(studio.writeTable(table, Some(true)), 10.seconds)
    seen(both, "scoped-a1")
    seen(both, "scoped-b1")
    seen(onlyB, "scoped-b1")
    contractId = (Gateway
      .post(
        "/apis/waf.extensions.cloud-apim.com/v1/api-contracts",
        Json.obj(
          "id"          -> "api-contract_scoped",
          "name"        -> "scoped",
          "description" -> "",
          "enabled"     -> true,
          "base_path"   -> "/",
          "spec"        -> Json.obj("openapi" -> "3.0.0", "info" -> Json.obj("title" -> "t", "version" -> "1"), "paths" -> Json.obj()).toString,
          "_loc"        -> Json.obj("tenant" -> "default", "teams" -> Json.arr("team-a"))
        )
      )
      .json \ "id").as[String]
  }

  override def afterAll(): Unit = {
    try {
      Seq(both, onlyB, fabricB, fabricX).foreach { ref =>
        Await.result(security.bans.unban(ref), 10.seconds)
        security.incidents.forget(ref.key)
      }
      Await.result(studio.writeTable(CloudApimSecuritySuiteGlobalPresetConfig(Seq.empty), Some(false)), 10.seconds)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/api-contracts/$contractId")
      routes.foreach(Gateway.deleteRoute)
      Seq(teamA, tenantAdmin).foreach(deleteKey)
      if (backend != null) backend.stop()
    } finally Gateway.theTable.release()
  }

  private def incidents(key: Key, ws: String): Seq[JsObject] = {
    val res = get(key, s"/workspaces/$ws/incidents")
    assertEquals(res.status, 200, res.body)
    (res.json \ "incidents").as[Seq[JsObject]]
  }

  private def incidentOf(key: Key, ws: String, ref: IdentityRef): Option[JsObject] =
    incidents(key, ws).find(i => (i \ "key").as[String] == ref.key)

  private def bans(key: Key, ws: String): Seq[String] = {
    val res = get(key, s"/workspaces/$ws/bans")
    assertEquals(res.status, 200, res.body)
    (res.json \ "bans").as[Seq[JsObject]].map(b => (b \ "key").as[String])
  }

  // ---------------------------------------------------------------------------------------------
  // incidents
  // ---------------------------------------------------------------------------------------------

  test("a workspace sees the callers seen on its routes, and only its routes' part of what they did") {
    val incident = incidentOf(teamA, "ws_sa", both).getOrElse(fail("the caller seen on a1 is missing"))
    assertEquals((incident \ "timeline").as[Seq[JsObject]].map(e => (e \ "route_id").as[String]), Seq("route_scoped-a1"))
    assertEquals((incident \ "timeline_elsewhere").as[Int], 1)
    assertEquals((incident \ "routes").as[Seq[String]], Seq("scoped-a1"))
    assert(incidentOf(teamA, "ws_sa", onlyB).isEmpty, "a caller seen only on b1 is not ws_sa's")
  }

  test("a workspace triages its incidents without touching another's, or the console's") {
    val res = send(teamA, "POST", "/workspaces/ws_sa/incidents/_state", Json.obj("key" -> both.key, "state" -> "acknowledged"))
    assertEquals(res.status, 200, res.body)
    assertEquals((incidentOf(teamA, "ws_sa", both).get \ "state").as[String], "acknowledged")
    assertEquals((incidentOf(tenantAdmin, "ws_sb", both).get \ "state").as[String], "open")
    assertEquals(Await.result(security.board.get(both.key), 10.seconds).flatMap(_.state), None)
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/incidents/_state", Json.obj("key" -> onlyB.key, "state" -> "resolved")).status, 404)
  }

  // ---------------------------------------------------------------------------------------------
  // bans
  // ---------------------------------------------------------------------------------------------

  test("a workspace bans a caller it saw, with what it saw as evidence, and nobody else") {
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/bans", Json.obj("ref" -> onlyB.key, "duration_seconds" -> 60, "reason" -> "x")).status, 403)
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/bans", Json.obj("ref" -> both.key, "duration_seconds" -> 60)).status, 400)
    val res = send(teamA, "POST", "/workspaces/ws_sa/bans", Json.obj("ref" -> both.key, "duration_seconds" -> 60, "reason" -> "sqli on a1"))
    assertEquals(res.status, 201, res.body)
    val ban = security.bans.check(both).getOrElse(fail("not banned"))
    assertEquals(ban.workspace, Some("ws_sa"))
    assertEquals(ban.timeline.flatMap(_.routeId).distinct, Seq("route_scoped-a1"), "the evidence is ws_sa's own")
    assert(ban.issuedBy.contains(teamA.id), ban.issuedBy)
    assert(bans(teamA, "ws_sa").contains(both.key))
    assert(!bans(tenantAdmin, "ws_sb").contains(both.key), "ws_sb did not ask for it and its evidence is not on b1")
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/bans/_extend", Json.obj("ref" -> both.key, "duration_seconds" -> 60)).status, 200)
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/bans/_unban", Json.obj("ref" -> both.key)).status, 200)
    assert(security.bans.check(both).isEmpty)
  }

  test("a workspace lifts a ban the fabric issued for its routes alone, not one issued for more") {
    Await.result(security.bans.ban(fabricB, 60.seconds, "fabric", timeline = security.incidents.record(fabricB, "waf", 5, Seq.empty, "block", "b1", routeId = Some("route_scoped-b1"), routeName = Some("scoped-b1")).timeline), 10.seconds)
    seen(fabricX, "scoped-a1")
    seen(fabricX, "scoped-b1")
    Await.result(security.bans.ban(fabricX, 60.seconds, "fabric", timeline = security.incidents.byKey(fabricX.key).toSeq.flatMap(_.timeline)), 10.seconds)
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/bans/_unban", Json.obj("ref" -> fabricB.key)).status, 404, "not ws_sa's to see")
    assertEquals(send(tenantAdmin, "POST", "/workspaces/ws_sb/bans/_unban", Json.obj("ref" -> fabricB.key)).status, 200)
    assertEquals(send(tenantAdmin, "POST", "/workspaces/ws_sa/bans/_unban", Json.obj("ref" -> fabricX.key)).status, 403)
    assertEquals(send(tenantAdmin, "POST", "/workspaces/ws_sa/bans/_extend", Json.obj("ref" -> fabricX.key, "duration_seconds" -> 60)).status, 403)
  }

  test("acting on the callers of a workspace needs every route it claims") {
    seen(both, "scoped-a2")
    val res = send(teamA, "POST", "/workspaces/ws_sm/bans", Json.obj("ref" -> both.key, "duration_seconds" -> 60, "reason" -> "x"))
    assertEquals(res.status, 403, res.body)
  }

  // ---------------------------------------------------------------------------------------------
  // traffic and inventory
  // ---------------------------------------------------------------------------------------------

  test("the api report of a workspace covers its routes, and nothing when the caller may read none of them") {
    val mine = get(teamA, "/workspaces/ws_sa/api-report")
    assertEquals(mine.status, 200, mine.body)
    assertEquals((mine.json \ "routes").as[Seq[JsObject]].map(r => (r \ "route_id").as[String]), Seq("route_scoped-a1"))
    val none = get(teamA, "/workspaces/ws_sb/api-report")
    assertEquals(none.status, 200, none.body)
    assertEquals((none.json \ "routes").as[Seq[JsObject]], Seq.empty[JsObject], "an empty scope is not the whole gateway")
  }

  test("a workspace runs the security and waf queries only") {
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/analytics/_query", Json.obj("query" -> "cloudapim_llm_usage")).status, 400)
    val res = send(teamA, "POST", "/workspaces/ws_sa/analytics/_query", Json.obj("query" -> "cloudapim_security_events_total"))
    // no user analytics exporter in this gateway: the query is forwarded and says so
    assert(res.status == 412 || res.status == 200, s"${res.status} ${res.body}")
  }

  // ---------------------------------------------------------------------------------------------
  // routes
  // ---------------------------------------------------------------------------------------------

  test("a workspace names the contract of its routes, the ones the caller may write") {
    val set = send(teamA, "PUT", "/workspaces/ws_sa/routes/route_scoped-a1/contract", Json.obj("contract_id" -> contractId))
    assertEquals(set.status, 200, set.body)
    val route = Gateway.await(Gateway.admin("/api/routes/route_scoped-a1").get()).json
    assertEquals((route \ "metadata" \ "cloud-apim-api-contract").asOpt[String], Some(contractId))
    assertEquals(send(teamA, "PUT", "/workspaces/ws_sa/routes/route_scoped-b1/contract", Json.obj("contract_id" -> contractId)).status, 404)
    // a route of team-b does not exist for a key of team-a, even in a workspace they share
    assertEquals(send(teamA, "PUT", "/workspaces/ws_sm/routes/route_scoped-b2/contract", Json.obj("contract_id" -> contractId)).status, 404)
    assertEquals(send(teamA, "PUT", "/workspaces/ws_sa/routes/route_scoped-a1/contract", Json.obj("contract_id" -> JsNull)).status, 200)
    val cleared = Gateway.await(Gateway.admin("/api/routes/route_scoped-a1").get()).json
    assertEquals((cleared \ "metadata" \ "cloud-apim-api-contract").asOpt[String], None)
  }

  // ---------------------------------------------------------------------------------------------
  // lookups, and the routes of the whole gateway
  // ---------------------------------------------------------------------------------------------

  test("a workspace's pages look addresses up, and an unknown workspace is a 404") {
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/reputation/_geo", Json.obj("ips" -> Json.arr("203.0.113.10"))).status, 200)
    assertEquals(send(teamA, "POST", "/workspaces/ws_sa/rules/_describe", Json.obj("ids" -> Json.arr(942100))).status, 200)
    assertEquals(send(teamA, "POST", "/workspaces/nope/reputation/_geo", Json.obj("ips" -> Json.arr("203.0.113.10"))).status, 404)
  }

  test("the routes of the whole gateway are served on the admin api, to an admin of the tenant") {
    assertEquals(get(teamA, "/api/extensions/cloud-apim/extensions/waf/security/_bans").status, 403)
    val res = get(tenantAdmin, "/api/extensions/cloud-apim/extensions/waf/security/_bans")
    assertEquals(res.status, 200, res.body)
    assert((res.json \ "bans").asOpt[Seq[JsObject]].isDefined, res.body)
    val forget = send(tenantAdmin, "POST", "/api/extensions/cloud-apim/extensions/waf/security/_ledger", Json.obj("ref" -> onlyB.key, "forget" -> true))
    assertEquals(forget.status, 200, forget.body)
  }
}
