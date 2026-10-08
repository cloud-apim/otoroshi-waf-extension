package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.studio.ThreatStudio
import otoroshi.models.{EntityLocation, TeamId, TenantId}
import otoroshi.next.models.NgRoute
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*
import play.api.libs.ws.{WSAuthScheme, WSRequest, WSResponse}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String

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

  private val base = "/api/extensions/cloud-apim/extensions/waf/studio"

  private def studio: ThreatStudio =
    Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.studio

  // ---------------------------------------------------------------------------------------------
  // keys
  // ---------------------------------------------------------------------------------------------

  private final case class Key(id: String) {
    def secret: String = s"$id-secret"
  }

  private val superKey    = Key("admin-api-apikey-id")
  private val teamA       = Key("studio-api-team-a")
  private val readerA     = Key("studio-api-reader-a")
  private val tenantAdmin = Key("studio-api-tenant-admin")

  private def createKey(key: Key, rights: JsValue): Unit = {
    val template = Gateway.await(Gateway.admin("/apis/apim.otoroshi.io/v1/apikeys/_template").get()).json.as[JsObject]
    val res      = Gateway.post(
      "/apis/apim.otoroshi.io/v1/apikeys",
      template ++ Json.obj(
        "clientId"           -> key.id,
        "clientSecret"       -> key.secret,
        "clientName"         -> key.id,
        "enabled"            -> true,
        "authorizedEntities" -> Json.arr("group_admin-api-group"),
        "metadata"           -> Json.obj("otoroshi-access-rights" -> Json.stringify(rights))
      )
    )
    if (res.status > 299) throw new RuntimeException(s"could not create the key ${key.id}: ${res.status} ${res.body}")
  }

  private def as(key: Key, path: String, headers: (String, String)*): WSRequest =
    Gateway.ws
      .url(s"http://127.0.0.1:${Gateway.port}$base$path")
      .withHttpHeaders((Seq("Host" -> "otoroshi-api.oto.tools", "Content-Type" -> "application/json") ++ headers)*)
      .withAuth(key.id, if (key == superKey) "admin-api-apikey-secret" else key.secret, WSAuthScheme.BASIC)

  private def get(key: Key, path: String): WSResponse = Gateway.await(as(key, path).get())
  private def send(key: Key, method: String, path: String, body: JsValue, headers: (String, String)*): WSResponse =
    Gateway.await(as(key, path, headers*).withMethod(method).withBody(Json.stringify(body)).execute())

  // ---------------------------------------------------------------------------------------------
  // the table
  // ---------------------------------------------------------------------------------------------

  private def tagged(tag: String) = CloudApimSecuritySuiteTarget(path = Some("$.tags"), value = JsString(s"Contains($tag)"))
  private def target(tag: String): JsValue = CloudApimSecuritySuiteTarget.format.writes(tagged(tag))

  private def rule(id: String, tag: String) =
    CloudApimSecuritySuiteGlobalRule(id = id, name = id, targets = Seq(tagged(tag)))

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

  private def of(team: String) = EntityLocation(TenantId.default, Seq(TeamId(team)))

  private var backend: TestBackend = null
  private var routes: Seq[NgRoute] = Seq.empty

  private def routeId(name: String) = s"route_$name"

  override def beforeAll(): Unit = {
    Gateway.theTable.acquire()
    createKey(teamA, Json.arr(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("team-a:rw"))))
    createKey(readerA, Json.arr(Json.obj("tenant" -> "default:r", "teams" -> Json.arr("team-a:r"))))
    createKey(tenantAdmin, Json.arr(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("*:rw"))))
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
      Seq(teamA, readerA, tenantAdmin).foreach(k => Gateway.delete(s"/apis/apim.otoroshi.io/v1/apikeys/${k.id}"))
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
    Seq("caller-rights", "workspace-permissions", "table-version", "rule-writes", "table-preview", "actor")
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

  // ---------------------------------------------------------------------------------------------
  // who the call is for
  // ---------------------------------------------------------------------------------------------

  test("the person a call is made for is an email, or nothing") {
    assertEquals(Gateway.await(as(teamA, "/workspaces", "Threat-Studio-User-Email" -> "not an email").get()).status, 400)
    assertEquals(Gateway.await(as(teamA, "/workspaces", "Threat-Studio-User-Email" -> "jane@example.com").get()).status, 200)
  }
}
