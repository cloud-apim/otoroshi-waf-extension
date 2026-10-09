package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.it.StudioApiClient.*
import com.cloud.apim.otoroshi.extensions.waf.studio.ThreatStudio
import otoroshi.models.{EntityLocation, TeamId, TenantId}
import otoroshi.next.models.NgRoute
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The studio's admin api, called with api keys that carry different rights.
 *
 * A workspace governs routes that belong to teams, so what a caller may change is decided by the
 * routes a change reaches: the suite lays a table over routes of two teams and checks, for each kind
 * of write, that a key of one team gets exactly the changes that stay within its routes.
 */
class ThreatStudioApiIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private given otoroshi.env.Env                 = Gateway.instance.env
  private given scala.concurrent.ExecutionContext = Gateway.ec

  private def studio: ThreatStudio =
    Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.studio

  // ---------------------------------------------------------------------------------------------
  // keys
  // ---------------------------------------------------------------------------------------------

  private val teamA       = Key("studio-api-team-a")
  private val readerA     = Key("studio-api-reader-a")
  private val tenantAdmin = Key("studio-api-tenant-admin")

  // ---------------------------------------------------------------------------------------------
  // the table
  // ---------------------------------------------------------------------------------------------

  // ws_a claims two routes of team-a, ws_mixed one of team-a and one of team-b, ws_empty nothing
  private val initialTable = CloudApimSecuritySuiteGlobalPresetConfig(
    Seq(rule("ws_a", "studio-api-a"), rule("ws_mixed", "studio-api-mixed"), rule("ws_empty", "studio-api-nothing"))
  )

  private def resetTable(): Unit = {
    Await.result(studio.writeTable(initialTable, Some(true)), 10.seconds)
  }

  private def table(key: Key): JsObject = {
    val res = get(key, "/workspaces")
    assertEquals(res.status, 200, res.body)
    res.json.as[JsObject]
  }

  private def workspace(key: Key, id: String): JsObject =
    (table(key) \ "workspaces").as[Seq[JsObject]].find(w => (w \ "id").as[String] == id).get

  private def claims(w: JsObject): Set[String] = (w \ "claims").as[Seq[JsObject]].map(o => (o \ "id").as[String]).toSet

  private def permissions(w: JsObject): Seq[String] = (w \ "permissions").as[Seq[String]]

  private def ruleIds(): Seq[String] =
    (table(superKey) \ "workspaces").as[Seq[JsObject]].map(w => (w \ "id").as[String])

  // ---------------------------------------------------------------------------------------------
  // routes of two teams
  // ---------------------------------------------------------------------------------------------

  private var backend: TestBackend = null
  private var routes: Seq[NgRoute] = Seq.empty

  private def routeId(name: String) = s"route_$name"

  override def beforeAll(): Unit = {
    Gateway.theTable.acquire()
    createKey(teamA, teamRights("team-a"))
    createKey(readerA, teamRights("team-a", write = false))
    createKey(tenantAdmin, tenantAdminRights)
    backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    routes = Seq(
      Gateway.createRoute("studio-api-a1", backend.port, Seq.empty, tags = Seq("studio-api-a", "studio-api-a1"), location = of("team-a")),
      Gateway.createRoute("studio-api-a2", backend.port, Seq.empty, tags = Seq("studio-api-a"), location = of("team-a")),
      Gateway.createRoute("studio-api-a3", backend.port, Seq.empty, tags = Seq("studio-api-mixed"), location = of("team-a")),
      Gateway.createRoute("studio-api-b1", backend.port, Seq.empty, tags = Seq("studio-api-mixed", "studio-api-b"), location = of("team-b"))
    )
  }

  override def afterAll(): Unit = {
    try {
      Await.result(studio.writeTable(CloudApimSecuritySuiteGlobalPresetConfig(Seq.empty), Some(false)), 10.seconds)
      routes.foreach(Gateway.deleteRoute)
      Seq(teamA, readerA, tenantAdmin).foreach(deleteKey)
      if (backend != null) backend.stop()
    } finally Gateway.theTable.release()
  }

  override def beforeEach(context: BeforeEach): Unit = resetTable()

  // ---------------------------------------------------------------------------------------------
  // reading
  // ---------------------------------------------------------------------------------------------

  test("_info says what the api offers") {
    val res = get(readerA, "/_info")
    assertEquals(res.status, 200, res.body)
    val features = (res.json \ "features").as[Seq[String]]
    Seq("caller-rights", "workspace-permissions", "table-version", "rule-writes", "table-preview", "actor", "entity-assign", "rule-preview")
      .foreach(f => assert(features.contains(f), s"$f missing from $features"))
  }

  test("a key of one team sees every rule, and only the routes of its team") {
    val res = get(teamA, "/workspaces")
    assertEquals(res.status, 200, res.body)
    assert(res.header("ETag").exists(_.contains((res.json \ "version").as[String])), s"${res.headers}")
    val mixed = workspace(teamA, "ws_mixed")
    assertEquals(claims(mixed), Set(routeId("studio-api-a3")))
    assertEquals((mixed \ "hidden_claims").as[Int], 1)
    assertEquals(claims(workspace(tenantAdmin, "ws_mixed")), Set(routeId("studio-api-a3"), routeId("studio-api-b1")))
    val routesOfMixed = get(teamA, "/workspaces/ws_mixed/routes")
    assertEquals(routesOfMixed.status, 200, routesOfMixed.body)
    assertEquals((routesOfMixed.json \ "routes").as[Seq[JsObject]].map(r => (r \ "route_id").as[String]), Seq(routeId("studio-api-a3")))
    assertEquals((routesOfMixed.json \ "hidden_claims").as[Int], 1)
  }

  test("what a key may do on a workspace follows the routes it claims") {
    assert(permissions(workspace(teamA, "ws_a")).contains("config:write"))
    assert(!permissions(workspace(teamA, "ws_mixed")).contains("config:write"), "a route of team-b is in it")
    assert(!permissions(workspace(teamA, "ws_empty")).contains("config:write"), "it claims nothing, which is the tenant admin's")
    assert(!permissions(workspace(readerA, "ws_a")).contains("config:write"), "a reader reads")
    assert(permissions(workspace(readerA, "ws_a")).contains("config:read"))
    assert(permissions(workspace(tenantAdmin, "ws_empty")).contains("config:write"))
  }

  test("an unknown workspace is a 404") {
    assertEquals(get(teamA, "/workspaces/nope").status, 404)
    assertEquals(send(tenantAdmin, "PATCH", "/workspaces/nope/preset", Json.obj("fail2ban" -> true)).status, 404)
  }

  // ---------------------------------------------------------------------------------------------
  // writing one rule
  // ---------------------------------------------------------------------------------------------

  test("a key of one team sets the protection of a workspace that only claims its routes, and only what it sends") {
    val version = (table(superKey) \ "version").as[String]
    val before  = workspace(superKey, "ws_a")
    val res     = send(teamA, "PATCH", "/workspaces/ws_a/preset", Json.obj("fail2ban" -> true))
    assertEquals(res.status, 200, res.body)
    assertNotEquals((res.json \ "version").as[String], version)
    val after   = workspace(superKey, "ws_a")
    assertEquals((after \ "preset" \ "fail2ban").as[Boolean], true)
    assertEquals((after \ "preset" \ "reputation_mode").as[String], (before \ "preset" \ "reputation_mode").as[String])
  }

  test("a change reaching a route of another team is refused, and nothing is written") {
    val version = (table(superKey) \ "version").as[String]
    val res     = send(teamA, "PATCH", "/workspaces/ws_mixed/preset", Json.obj("fail2ban" -> true))
    assertEquals(res.status, 403, res.body)
    assert((res.json \ "error_description").as[String].contains("1 route"), res.body)
    assertEquals((table(superKey) \ "version").as[String], version)
  }

  test("a change that reaches no route yet needs an admin of the tenant") {
    assertEquals(send(teamA, "PATCH", "/workspaces/ws_empty/preset", Json.obj("fail2ban" -> true)).status, 403)
    assertEquals(send(teamA, "PATCH", "/workspaces/ws_empty", Json.obj("name" -> "renamed")).status, 403)
    assertEquals(send(tenantAdmin, "PATCH", "/workspaces/ws_empty/preset", Json.obj("fail2ban" -> true)).status, 200)
  }

  test("a reader of the team writes nothing") {
    assertEquals(send(readerA, "PATCH", "/workspaces/ws_a/preset", Json.obj("fail2ban" -> true)).status, 403)
    assertEquals(send(readerA, "PATCH", "/workspaces/ws_a", Json.obj("name" -> "renamed")).status, 403)
  }

  test("a scope that would take a route of another team is refused, one that stays within the team is not") {
    val steal = send(teamA, "PUT", "/workspaces/ws_a/scope", Json.obj("targets" -> Json.arr(target("studio-api-b"))))
    assertEquals(steal.status, 403, steal.body)
    val narrow = send(teamA, "PUT", "/workspaces/ws_a/scope", Json.obj("targets" -> Json.arr(target("studio-api-a1"))))
    assertEquals(narrow.status, 200, narrow.body)
    assertEquals(claims(workspace(superKey, "ws_a")), Set(routeId("studio-api-a1")))
  }

  test("a target that selects on nothing is refused rather than read as matching nothing") {
    val res = send(tenantAdmin, "PUT", "/workspaces/ws_a/scope", Json.obj("targets" -> Json.arr(Json.obj("value" -> "Contains(x)"))))
    assertEquals(res.status, 400, res.body)
  }

  test("creating, moving and deleting a workspace follow the routes they move") {
    val nothing = send(teamA, "POST", "/workspaces", Json.obj("name" -> "claims nothing", "targets" -> Json.arr(target("studio-api-none"))))
    assertEquals(nothing.status, 403, nothing.body)
    val created = send(
      tenantAdmin,
      "POST",
      "/workspaces",
      Json.obj("id" -> "ws_new", "name" -> "new", "targets" -> Json.arr(target("studio-api-b")), "position" -> 0)
    )
    assertEquals(created.status, 201, created.body)
    assertEquals(claims(created.json.as[JsObject]), Set(routeId("studio-api-b1")))
    assertEquals(ruleIds().head, "ws_new")
    assertEquals(send(tenantAdmin, "POST", "/workspaces", Json.obj("id" -> "ws_new", "name" -> "again")).status, 409)
    assertEquals(send(tenantAdmin, "POST", "/workspaces", Json.obj("id" -> "a/b", "name" -> "slash")).status, 400)
    // moving ws_new below ws_mixed gives route b1 back to ws_mixed: team-b's route, not team-a's
    assertEquals(send(teamA, "POST", "/workspaces/ws_new/_move", Json.obj("to" -> 2)).status, 403)
    assertEquals(send(tenantAdmin, "POST", "/workspaces/ws_new/_move", Json.obj("to" -> 2)).status, 200)
    assertEquals(ruleIds(), Seq("ws_a", "ws_mixed", "ws_new", "ws_empty"))
    assertEquals(send(teamA, "DELETE", "/workspaces/ws_mixed", JsNull).status, 403)
    assertEquals(send(teamA, "DELETE", "/workspaces/ws_a", JsNull).status, 204)
    assert(!ruleIds().contains("ws_a"))
  }

  // ---------------------------------------------------------------------------------------------
  // another tenant
  // ---------------------------------------------------------------------------------------------

  test("an admin of another tenant makes a workspace there, and what the workspace owns stays in that tenant") {
    // the service account of a SaaS: it administers the tenant of its customers, and nothing else
    val tenant   = "studio-api-customers"
    val key      = Key("studio-api-customers-admin")
    val inTenant = "Otoroshi-Tenant" -> tenant
    val made     = Gateway.post("/apis/organize.otoroshi.io/v1/tenants", Json.obj("id" -> tenant, "name" -> tenant, "description" -> "", "metadata" -> Json.obj()))
    assert(made.status < 300, made.body)
    createKey(key, Json.arr(Json.obj("tenant" -> s"$tenant:rw", "teams" -> Json.arr("*:rw"))))
    val route = Gateway.createRoute("studio-api-c1", backend.port, Seq.empty, tags = Seq("studio-api-c"), location = EntityLocation(TenantId(tenant), Seq(TeamId.all)))
    try {
      val created = send(key, "POST", "/workspaces", Json.obj("id" -> "ws_customer", "name" -> "customer", "targets" -> Json.arr(target("studio-api-c"))), inTenant)
      assertEquals(created.status, 201, created.body)
      assertEquals(claims(created.json.as[JsObject]), Set(routeId("studio-api-c1")))

      // an entity of the workspace: in the tenant of the caller, not in the one of the template, whether it
      // is sent without a location, or as the studio front sends it, from the template
      val template = Gateway.await(as(key, "/workspaces/ws_customer/entities/waf-configs/_template", inTenant).get())
      assertEquals(template.status, 200, template.body)
      assertEquals((template.json \ "_loc" \ "tenant").as[String], tenant)
      val fromTemplate = send(key, "POST", "/workspaces/ws_customer/entities/waf-configs", template.json.as[JsObject] ++ Json.obj("name" -> "from the template"), inTenant)
      assertEquals(fromTemplate.status, 201, fromTemplate.body)
      val config = send(key, "POST", "/workspaces/ws_customer/entities/waf-configs", Json.obj("name" -> "customer config"), inTenant)
      assertEquals(config.status, 201, config.body)
      assertEquals((config.json \ "entity" \ "_loc" \ "tenant").as[String], tenant)
      val id = (config.json \ "entity" \ "id").as[String]
      val listed = Gateway.await(as(key, "/workspaces/ws_customer/entities/waf-configs", inTenant).get())
      assertEquals(listed.status, 200, listed.body)
      assert(listed.body.contains(id), listed.body)
      assertEquals(send(key, "PUT", s"/workspaces/ws_customer/entities/waf-configs/$id", (config.json \ "entity").as[JsObject] ++ Json.obj("name" -> "renamed"), inTenant).status, 200)
      assertEquals(send(key, "DELETE", s"/workspaces/ws_customer/entities/waf-configs/$id", JsNull, inTenant).status, 204)
      val other = (fromTemplate.json \ "entity" \ "id").as[String]
      assertEquals(send(key, "DELETE", s"/workspaces/ws_customer/entities/waf-configs/$other", JsNull, inTenant).status, 204)
      assertEquals(send(key, "DELETE", "/workspaces/ws_customer", JsNull, inTenant).status, 204)
    } finally {
      Gateway.deleteRoute(route)
      deleteKey(key)
      Gateway.delete(s"/apis/organize.otoroshi.io/v1/tenants/$tenant")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // the whole table
  // ---------------------------------------------------------------------------------------------

  test("writing the whole table needs the version it was read at") {
    val read  = table(superKey)
    val body  = Json.obj("rules" -> (read \ "workspaces").as[Seq[JsObject]].map(w => w - "claims" - "matches"), "skip_protected_routes" -> true)
    assertEquals(send(superKey, "PUT", "/workspaces", body).status, 428)
    val stale = send(superKey, "PUT", "/workspaces", body, "If-Match" -> "\"0000000000000000\"")
    assertEquals(stale.status, 409, stale.body)
    assertEquals((stale.json \ "version").as[String], (read \ "version").as[String])
    val renamed = body ++ Json.obj("rules" -> (body \ "rules").as[Seq[JsObject]].map(r => r ++ Json.obj("name" -> s"${(r \ "id").as[String]}!")))
    val saved   = send(superKey, "PUT", "/workspaces", renamed, "If-Match" -> s"\"${(read \ "version").as[String]}\"")
    assertEquals(saved.status, 200, saved.body)
    assertNotEquals((saved.json \ "version").as[String], (read \ "version").as[String])
    assertEquals((workspace(superKey, "ws_a") \ "name").as[String], "ws_a!")
  }

  test("a preview lists the routes a table would move, those the caller may read, and whether it may write it") {
    val read     = table(teamA)
    // ws_mixed above ws_a, with ws_a taking route b1 through its tag
    val proposed = Json.obj(
      "skip_protected_routes" -> true,
      "rules"                 -> Json.arr(
        CloudApimSecuritySuiteGlobalRule.format.writes(rule("ws_a", "studio-api-b")),
        CloudApimSecuritySuiteGlobalRule.format.writes(rule("ws_mixed", "studio-api-mixed")),
        CloudApimSecuritySuiteGlobalRule.format.writes(rule("ws_empty", "studio-api-nothing"))
      )
    )
    val res      = send(teamA, "POST", "/table/_preview", proposed)
    assertEquals(res.status, 200, res.body)
    val changed  = (res.json \ "changes").as[Seq[JsObject]].map(c => (c \ "route" \ "id").as[String]).toSet
    assertEquals(changed, Set(routeId("studio-api-a1"), routeId("studio-api-a2")))
    assertEquals((res.json \ "hidden").as[Int], 1, "route b1 moves too, and team-a cannot read it")
    assertEquals((res.json \ "allowed").as[Boolean], false)
    assertEquals((res.json \ "version").as[String], (read \ "version").as[String])
  }

  test("a change of one rule is previewed as its write would make it, and nothing is written") {
    // ws_a taking route b1 through its tag: b1 leaves ws_mixed, a1 and a2 fall to no workspace
    val scope   = send(teamA, "POST", "/workspaces/ws_a/scope/_preview", Json.obj("targets" -> Json.arr(target("studio-api-b"))))
    assertEquals(scope.status, 200, scope.body)
    assertEquals((scope.json \ "changes").as[Seq[JsObject]].map(c => (c \ "route" \ "id").as[String]).toSet, Set(routeId("studio-api-a1"), routeId("studio-api-a2")))
    assertEquals((scope.json \ "hidden").as[Int], 1)
    assertEquals((scope.json \ "allowed").as[Boolean], false)
    // ws_a deleted, its routes fall to no workspace: team-a's own routes
    val delete  = send(teamA, "POST", "/workspaces/ws_a/_delete/_preview", JsNull)
    assertEquals(delete.status, 200, delete.body)
    val changes = (delete.json \ "changes").as[Seq[JsObject]]
    assertEquals(changes.map(c => (c \ "route" \ "id").as[String]).toSet, Set(routeId("studio-api-a1"), routeId("studio-api-a2")))
    assert(changes.forall(c => (c \ "before" \ "id").as[String] == "ws_a" && (c \ "after").toOption.forall(_ == JsNull)))
    assertEquals((delete.json \ "allowed").as[Boolean], true)
    // ws_mixed above ws_a takes nothing from it
    val move    = send(tenantAdmin, "POST", "/workspaces/ws_mixed/_move/_preview", Json.obj("to" -> 0))
    assertEquals(move.status, 200, move.body)
    assertEquals((move.json \ "changes").as[Seq[JsObject]], Seq.empty)
    assertEquals(send(tenantAdmin, "POST", "/workspaces/ws_nope/_delete/_preview", JsNull).status, 404)
    assertEquals(send(tenantAdmin, "POST", "/workspaces/ws_a/_move/_preview", Json.obj("to" -> 9)).status, 400)
    assertEquals(ruleIds(), Seq("ws_a", "ws_mixed", "ws_empty"))
    assertEquals(claims(workspace(superKey, "ws_a")), Set(routeId("studio-api-a1"), routeId("studio-api-a2")))
  }

  // ---------------------------------------------------------------------------------------------
  // who the call is for
  // ---------------------------------------------------------------------------------------------

  test("the person a call is made for is an email, or nothing") {
    assertEquals(Gateway.await(as(teamA, "/workspaces", "Threat-Studio-User-Email" -> "not an email").get()).status, 400)
    assertEquals(Gateway.await(as(teamA, "/workspaces", "Threat-Studio-User-Email" -> "jane@example.com").get()).status, 200)
  }
}
