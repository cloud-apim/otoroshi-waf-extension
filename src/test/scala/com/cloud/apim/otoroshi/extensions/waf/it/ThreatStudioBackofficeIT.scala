package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.it.StudioApiClient.*
import com.cloud.apim.otoroshi.extensions.waf.studio.ThreatStudio
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models.{OtoroshiAdminType, SimpleOtoroshiAdmin, TeamAccess, TenantAccess, UserRight, UserRights}
import otoroshi.next.models.NgRoute
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*
import play.api.libs.ws.{WSCookie, WSResponse}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Threat Studio used from the backoffice, the way its front calls it: through `/bo/api/proxy`, which
 * signs the person each call is made for.
 *
 * A person uses the studio as the rest of the backoffice: their rights decide, the extension's routes
 * are guarded for them as on the backoffice, and what they do is done in their name. What keeps a
 * workspace to what it owns stays for an edition's service account, which the suite calls too.
 */
class ThreatStudioBackofficeIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private given otoroshi.env.Env                 = Gateway.instance.env
  private given scala.concurrent.ExecutionContext = Gateway.ec

  private def ext: CloudApimWafExtension = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get
  private def studio: ThreatStudio       = ext.studio

  private val password    = "threat-studio-backoffice-it"
  private val superAdmin  = "super-admin@threat-studio-backoffice.it"
  private val tenantAdmin = "tenant-admin@threat-studio-backoffice.it"
  private val service     = Key("studio-backoffice-service")

  private var superCookies: Seq[WSCookie]  = Seq.empty
  private var tenantCookies: Seq[WSCookie] = Seq.empty

  private val sharedConfig = "waf-config_studio-backoffice-shared"
  private val otherConfig  = "waf-config_studio-backoffice-other"
  private val contract     = "api-contract_studio-backoffice"

  private var backend: TestBackend = null
  private var routes: Seq[NgRoute] = Seq.empty

  private val usesShared = CloudApimSecuritySuitePresetConfig.default.copy(wafConfig = Some(sharedConfig))

  private def register(username: String, rights: UserRights): Unit = {
    val admin = SimpleOtoroshiAdmin(
      username = username,
      password = BCrypt.hashpw(password, BCrypt.gensalt()),
      label = username,
      createdAt = DateTime.now(),
      typ = OtoroshiAdminType.SimpleAdmin,
      metadata = Map.empty,
      rights = rights,
      adminEntityValidators = Map.empty
    )
    Await.result(Gateway.instance.env.datastores.simpleAdminDataStore.registerUser(admin), 10.seconds)
  }

  private def login(username: String): Seq[WSCookie] = {
    val res = Gateway.await(
      Gateway.ws
        .url(s"http://127.0.0.1:${Gateway.port}/bo/simple/login")
        .withHttpHeaders("Host" -> Gateway.instance.env.backOfficeHost, "Content-Type" -> "application/json")
        .post(Json.stringify(Json.obj("username" -> username, "password" -> password)))
    )
    assertEquals(res.status, 200, s"could not log in as $username: ${res.body}")
    res.cookies.toSeq
  }

  /** A call of the admin api relayed by the backoffice; `path` is under the studio api unless it starts with `/api/`. */
  private def bo(cookies: Seq[WSCookie], method: String, path: String, body: JsValue = JsNull): WSResponse = {
    val req = Gateway.ws
      .url(s"http://127.0.0.1:${Gateway.port}/bo/api/proxy${if (path.startsWith("/api/")) path else s"$base$path"}")
      .withHttpHeaders("Host" -> Gateway.instance.env.backOfficeHost, "Accept" -> "application/json")
      .withCookies(cookies*)
      .withMethod(method)
    Gateway.await(
      if (body == JsNull) req.execute()
      else req.addHttpHeaders("Content-Type" -> "application/json").withBody(Json.stringify(body)).execute()
    )
  }

  private def writeTable(preset: CloudApimSecuritySuitePresetConfig): Unit =
    Await.result(
      studio.writeTable(
        CloudApimSecuritySuiteGlobalPresetConfig(Seq(rule("ws_bo", "backoffice-a").copy(preset = preset), rule("ws_bo2", "backoffice-b"))),
        Some(true)
      ),
      10.seconds
    )

  private def wafConfig(id: String, metadata: JsObject): Unit =
    Gateway.createWafConfig(
      Json.obj(
        "id"          -> id,
        "name"        -> id,
        "description" -> "",
        "enabled"     -> true,
        "block"       -> false,
        "rules"       -> Json.arr("SecRuleEngine On"),
        "metadata"    -> metadata,
        "_loc"        -> Json.obj("tenant" -> "default", "teams" -> Json.arr("*"))
      )
    )

  override def beforeAll(): Unit = {
    Gateway.theTable.acquire()
    register(superAdmin, UserRights.superAdmin)
    register(tenantAdmin, UserRights(Seq(UserRight(TenantAccess("default", true, true), Seq(TeamAccess("*", true, true))))))
    superCookies = login(superAdmin)
    tenantCookies = login(tenantAdmin)
    createKey(service, tenantAdminRights)
    backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    routes = Seq(Gateway.createRoute("backoffice-a1", backend.port, Seq.empty, tags = Seq("backoffice-a")))
    wafConfig(sharedConfig, Json.obj())
    // marked by the other workspace and named by nothing: that workspace's own
    wafConfig(otherConfig, Json.obj("threat_studio_workspace" -> "ws_bo2", "threat_studio_kind" -> "waf-configs"))
    assert(Gateway.post("/apis/waf.extensions.cloud-apim.com/v1/api-contracts", Json.obj("id" -> contract, "name" -> "unused")).status < 300)
    Await.result(ext.syncStates(), 30.seconds)
  }

  override def beforeEach(context: BeforeEach): Unit = writeTable(usesShared)

  override def afterAll(): Unit = {
    try {
      Await.result(studio.writeTable(CloudApimSecuritySuiteGlobalPresetConfig(Seq.empty), Some(false)), 10.seconds)
      Gateway.await(Gateway.admin("/apis/waf.extensions.cloud-apim.com/v1/waf-configs").get()).json.as[Seq[JsObject]]
        .filter(e => (e \ "metadata" \ "threat_studio_workspace").asOpt[String].contains("ws_bo"))
        .foreach(e => Gateway.deleteWafConfig((e \ "id").as[String]))
      Seq(sharedConfig, otherConfig).foreach(Gateway.deleteWafConfig)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/api-contracts/$contract")
      routes.foreach(Gateway.deleteRoute)
      deleteKey(service)
      val admins = Gateway.instance.env.datastores.simpleAdminDataStore
      Seq(superAdmin, tenantAdmin).foreach(u => Await.result(admins.deleteUser(u), 10.seconds))
      if (backend != null) backend.stop()
    } finally Gateway.theTable.release()
  }

  private val security = "/api/extensions/cloud-apim/extensions/waf/security"

  private def issuedBy(key: String): Option[String] =
    (bo(superCookies, "GET", s"$security/_bans").json \ "bans").as[Seq[JsObject]]
      .find(b => (b \ "key").asOpt[String].contains(key))
      .flatMap(b => (b \ "issued_by").asOpt[String])

  private def configIds(res: WSResponse): Seq[String] = {
    assertEquals(res.status, 200, res.body)
    res.json.as[Seq[JsObject]].map(i => (i \ "entity" \ "id").as[String])
  }

  // ---------------------------------------------------------------------------------------------

  test("the gateway's routes are guarded for a person as on the backoffice, and name them") {
    val ref = "ip:203.0.113.61"
    assertEquals(bo(tenantCookies, "GET", s"$security/_status").status, 200, "reading is any signed-in person's")
    val refused = bo(tenantCookies, "POST", s"$security/_ban", Json.obj("ref" -> ref, "duration_seconds" -> 60, "reason" -> "it"))
    assertEquals(refused.status, 403, refused.body)
    val banned = bo(superCookies, "POST", s"$security/_ban", Json.obj("ref" -> ref, "duration_seconds" -> 60, "reason" -> "it"))
    assert(banned.status < 300, banned.body)
    assertEquals(issuedBy(ref), Some(superAdmin))
    assertEquals(bo(tenantCookies, "POST", s"$security/_unban", Json.obj("ref" -> ref)).status, 403)
    assert(bo(superCookies, "POST", s"$security/_unban", Json.obj("ref" -> ref)).status < 300)
    // an edition's service account, an admin of the tenant, still acts on them
    val again = send(service, "POST", s"$security/_ban", Json.obj("ref" -> ref, "duration_seconds" -> 60, "reason" -> "it"))
    assert(again.status < 300, again.body)
    assert(send(service, "POST", s"$security/_unban", Json.obj("ref" -> ref)).status < 300)
  }

  test("in a workspace, a person bans, extends and lifts as a super admin, with nothing asked of what it saw") {
    val ref  = "ip:203.0.113.62"
    val body = Json.obj("ref" -> ref, "duration_seconds" -> 60, "reason" -> "it")
    assertEquals(bo(tenantCookies, "POST", "/workspaces/ws_bo/bans", body).status, 403)
    val nothingSeen = send(service, "POST", "/workspaces/ws_bo/bans", body)
    assertEquals(nothingSeen.status, 403, "a service account bans for what its workspace saw")
    val banned = bo(superCookies, "POST", "/workspaces/ws_bo/bans", body)
    assertEquals(banned.status, 201, banned.body)
    assertEquals(issuedBy(ref), Some(superAdmin))
    assertEquals(bo(superCookies, "POST", "/workspaces/ws_bo/bans/_extend", Json.obj("ref" -> ref, "duration_seconds" -> 120)).status, 200)
    assertEquals(bo(superCookies, "POST", "/workspaces/ws_bo/bans/_unban", Json.obj("ref" -> ref)).status, 200)
  }

  test("a person learns on a config the workspace shares, as a super admin; a service account is told to copy it") {
    val report = Json.obj("config_ref" -> sharedConfig)
    val shared = send(service, "POST", "/workspaces/ws_bo/learning/_report", report)
    assertEquals(shared.status, 409, shared.body)
    assertNotEquals(bo(tenantCookies, "POST", "/workspaces/ws_bo/learning/_report", report).status, 409)
    assertEquals(bo(tenantCookies, "POST", "/workspaces/ws_bo/learning/_start", report).status, 403)
    val started = bo(superCookies, "POST", "/workspaces/ws_bo/learning/_start", report)
    assertEquals(started.status, 200, started.body)
    assertEquals(bo(superCookies, "POST", "/workspaces/ws_bo/learning/_discard", report).status, 200)
  }

  test("a person sees every entity they may read: another workspace's, and a contract nobody uses") {
    val asPerson = configIds(bo(tenantCookies, "GET", "/workspaces/ws_bo/entities/waf-configs"))
    assert(asPerson.contains(otherConfig), asPerson)
    assert(!configIds(get(service, "/workspaces/ws_bo/entities/waf-configs")).contains(otherConfig))
    assert(configIds(bo(tenantCookies, "GET", "/workspaces/ws_bo/entities/api-contracts")).contains(contract))
    assert(!configIds(get(service, "/workspaces/ws_bo/entities/api-contracts")).contains(contract))
    assertEquals(send(service, "PATCH", "/workspaces/ws_bo/preset", Json.obj("api_contract_id" -> contract)).status, 404)
    val named = bo(superCookies, "PATCH", "/workspaces/ws_bo/preset", Json.obj("api_contract_id" -> contract))
    assertEquals(named.status, 200, named.body)
  }

  test("a person changes a shared entity where it is, and deletes one still in use") {
    val current = Gateway.await(Gateway.admin(s"/apis/waf.extensions.cloud-apim.com/v1/waf-configs/$sharedConfig").get()).json.as[JsObject]
    val changed = bo(superCookies, "PUT", s"/workspaces/ws_bo/entities/waf-configs/$sharedConfig", current ++ Json.obj("block" -> true))
    assertEquals(changed.status, 200, changed.body)
    assertEquals((changed.json \ "ownership").as[String], "shared")
    assertEquals((changed.json \ "entity" \ "metadata" \ "threat_studio_workspace").asOpt[String], None, "it stays nobody's")
    val created = send(service, "POST", "/workspaces/ws_bo/entities/waf-configs", Json.obj("name" -> "own", "rules" -> Json.arr("SecRuleEngine On")))
    assertEquals(created.status, 201, created.body)
    val own     = (created.json \ "entity" \ "id").as[String]
    writeTable(usesShared.copy(wafConfig = Some(own)))
    assertEquals(Gateway.await(as(service, s"/workspaces/ws_bo/entities/waf-configs/$own").delete()).status, 409)
    assertEquals(bo(superCookies, "DELETE", s"/workspaces/ws_bo/entities/waf-configs/$own").status, 204)
  }

  test("the routes of a workspace say which ones the admin api stores") {
    val res = bo(superCookies, "GET", "/workspaces/ws_bo/routes")
    assertEquals(res.status, 200, res.body)
    val stored = (res.json \ "routes").as[Seq[JsObject]].map(r => (r \ "route_id").as[String] -> (r \ "stored").as[Boolean]).toMap
    assertEquals(stored.get("route_backoffice-a1"), Some(true))
  }
}
