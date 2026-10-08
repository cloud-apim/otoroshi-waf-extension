package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.it.StudioApiClient.*
import com.cloud.apim.otoroshi.extensions.waf.studio.ThreatStudio
import otoroshi.next.models.NgRoute
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Which workspace an entity belongs to, and what that lets each one do with it.
 *
 * The suite builds two workspaces over routes of two teams, a configuration both of them use, and
 * entities each one creates, then checks that a workspace changes what is its own, reads what is
 * shared, never sees what is another's, and copies what it shares to change it.
 */
class ThreatStudioEntitiesIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private given otoroshi.env.Env                 = Gateway.instance.env
  private given scala.concurrent.ExecutionContext = Gateway.ec

  private def studio: ThreatStudio =
    Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.studio

  private val teamA       = Key("studio-entities-team-a")
  private val teamB       = Key("studio-entities-team-b")
  private val tenantAdmin = Key("studio-entities-tenant-admin")

  private val sharedConfig = "waf-config_studio-entities-shared"

  private var backend: TestBackend = null
  private var routes: Seq[NgRoute] = Seq.empty

  private def writeTable(presetA: CloudApimSecuritySuitePresetConfig, presetB: CloudApimSecuritySuitePresetConfig): Unit =
    Await.result(
      studio.writeTable(
        CloudApimSecuritySuiteGlobalPresetConfig(Seq(rule("ws_ea", "entities-a").copy(preset = presetA), rule("ws_eb", "entities-b").copy(preset = presetB))),
        Some(true)
      ),
      10.seconds
    )

  private val usesShared = CloudApimSecuritySuitePresetConfig.default.copy(wafConfig = Some(sharedConfig))

  override def beforeAll(): Unit = {
    Gateway.theTable.acquire()
    createKey(teamA, teamRights("team-a"))
    createKey(teamB, teamRights("team-b"))
    createKey(tenantAdmin, tenantAdminRights)
    backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    routes = Seq(
      Gateway.createRoute("entities-a1", backend.port, Seq.empty, tags = Seq("entities-a"), location = of("team-a")),
      Gateway.createRoute("entities-b1", backend.port, Seq.empty, tags = Seq("entities-b"), location = of("team-b"))
    )
    Gateway.createWafConfig(
      Json.obj(
        "id"          -> sharedConfig,
        "name"        -> "shared baseline",
        "description" -> "",
        "enabled"     -> true,
        "block"       -> false,
        "rules"       -> Json.arr("SecRuleEngine On"),
        "_loc"        -> Json.obj("tenant" -> "default", "teams" -> Json.arr("*"))
      )
    )
  }

  override def beforeEach(context: BeforeEach): Unit = writeTable(usesShared, usesShared)

  override def afterAll(): Unit = {
    try {
      Await.result(studio.writeTable(CloudApimSecuritySuiteGlobalPresetConfig(Seq.empty), Some(false)), 10.seconds)
      // what the suite created through the api, found by the mark it carries
      Seq("waf-configs", "waf-rulesets", "challenge-providers").foreach { plural =>
        val all = Gateway.await(Gateway.admin(s"/apis/waf.extensions.cloud-apim.com/v1/$plural").get()).json.as[Seq[JsObject]]
        all.filter(e => (e \ "metadata" \ "threat_studio_workspace").asOpt[String].exists(Set("ws_ea", "ws_eb").contains))
          .foreach(e => Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/$plural/${(e \ "id").as[String]}"))
      }
      Gateway.deleteWafConfig(sharedConfig)
      routes.foreach(Gateway.deleteRoute)
      Seq(teamA, teamB, tenantAdmin).foreach(deleteKey)
      if (backend != null) backend.stop()
    } finally Gateway.theTable.release()
  }

  private def items(key: Key, ws: String, kind: String): Seq[JsObject] = {
    val res = get(key, s"/workspaces/$ws/entities/$kind")
    assertEquals(res.status, 200, res.body)
    res.json.as[Seq[JsObject]]
  }

  private def ownership(key: Key, ws: String, kind: String): Map[String, String] =
    items(key, ws, kind).map(i => (i \ "entity" \ "id").as[String] -> (i \ "ownership").as[String]).toMap

  private def create(key: Key, ws: String, kind: String, body: JsObject): JsObject = {
    val res = send(key, "POST", s"/workspaces/$ws/entities/$kind", body)
    assertEquals(res.status, 201, res.body)
    (res.json \ "entity").as[JsObject]
  }

  private def stored(plural: String, id: String): JsObject =
    Gateway.await(Gateway.admin(s"/apis/waf.extensions.cloud-apim.com/v1/$plural/$id").get()).json.as[JsObject]

  private def presetOf(ws: String): JsObject =
    (get(superKey, s"/workspaces/$ws").json \ "preset").as[JsObject]

  private val config = Json.obj("name" -> "own config", "rules" -> Json.arr("SecRuleEngine On"))

  // ---------------------------------------------------------------------------------------------

  test("what a workspace creates is marked as its own, where its creator can still see it") {
    val created = create(teamA, "ws_ea", "waf-configs", config)
    assertEquals((created \ "metadata" \ "threat_studio_workspace").as[String], "ws_ea")
    assertEquals((created \ "_loc" \ "teams").as[Seq[String]], Seq("team-a"))
    val own = (created \ "id").as[String]
    assertEquals(ownership(teamA, "ws_ea", "waf-configs").get(own), Some("workspace"))
    assertEquals(ownership(teamA, "ws_ea", "waf-configs").get(sharedConfig), Some("shared"))
    assertEquals(ownership(teamB, "ws_eb", "waf-configs").get(own), None, "another workspace's entity does not exist for it")
    assertEquals(get(tenantAdmin, s"/workspaces/ws_eb/entities/waf-configs/$own").status, 404)
  }

  test("a workspace changes what is its own, and is told to copy what it shares") {
    val own = (create(teamA, "ws_ea", "waf-configs", config) \ "id").as[String]
    val res = send(teamA, "PUT", s"/workspaces/ws_ea/entities/waf-configs/$own", stored("waf-configs", own) ++ Json.obj("block" -> true))
    assertEquals(res.status, 200, res.body)
    assertEquals((stored("waf-configs", own) \ "block").as[Boolean], true)
    assertEquals((stored("waf-configs", own) \ "metadata" \ "threat_studio_workspace").as[String], "ws_ea", "the mark is kept")
    val shared = send(tenantAdmin, "PUT", s"/workspaces/ws_ea/entities/waf-configs/$sharedConfig", stored("waf-configs", sharedConfig))
    assertEquals(shared.status, 409, shared.body)
    assertEquals((shared.json \ "shared_entity").as[String], sharedConfig)
  }

  test("an entity marked by a workspace and used by another is shared, and neither changes it") {
    val own = (create(teamA, "ws_ea", "waf-configs", config) \ "id").as[String]
    writeTable(usesShared, usesShared.copy(wafConfig = Some(own)))
    assertEquals(ownership(teamA, "ws_ea", "waf-configs").get(own), Some("shared"))
    // shared, though a key of team-b still cannot read an entity located in team-a
    assertEquals(ownership(tenantAdmin, "ws_eb", "waf-configs").get(own), Some("shared"))
    assertEquals(ownership(teamB, "ws_eb", "waf-configs").get(own), None)
    assertEquals(send(teamA, "PUT", s"/workspaces/ws_ea/entities/waf-configs/$own", stored("waf-configs", own)).status, 409)
  }

  test("a preset names what the workspace may see, nothing of another workspace") {
    val own = (create(teamA, "ws_ea", "waf-configs", config) \ "id").as[String]
    assertEquals(send(teamA, "PATCH", "/workspaces/ws_ea/preset", Json.obj("waf_config" -> own)).status, 200)
    assertEquals((presetOf("ws_ea") \ "waf_config").as[String], own)
    assertEquals(send(teamB, "PATCH", "/workspaces/ws_eb/preset", Json.obj("waf_config" -> own)).status, 404)
    assertEquals(send(teamB, "PATCH", "/workspaces/ws_eb/preset", Json.obj("waf_config" -> "waf-config_nope")).status, 404)
    // a field it does not change is not checked again
    assertEquals(send(teamA, "PATCH", "/workspaces/ws_ea/preset", Json.obj("fail2ban" -> true)).status, 200)
  }

  test("a copy of a shared entity is the workspace's own, in place of the original when asked") {
    val res = send(teamB, "POST", s"/workspaces/ws_eb/entities/waf-configs/$sharedConfig/_fork", Json.obj("use" -> true))
    assertEquals(res.status, 201, res.body)
    val copy = (res.json \ "entity" \ "id").as[String]
    assertNotEquals(copy, sharedConfig)
    assertEquals((presetOf("ws_eb") \ "waf_config").as[String], copy)
    assertEquals(ownership(teamB, "ws_eb", "waf-configs").get(copy), Some("workspace"))
    assertEquals((stored("waf-configs", copy) \ "rules").as[Seq[String]], Seq("SecRuleEngine On"))
  }

  test("an entity still in use is not deleted") {
    val own = (create(teamA, "ws_ea", "waf-configs", config) \ "id").as[String]
    assertEquals(send(teamA, "PATCH", "/workspaces/ws_ea/preset", Json.obj("waf_config" -> own)).status, 200)
    assertEquals(send(teamA, "DELETE", s"/workspaces/ws_ea/entities/waf-configs/$own", JsNull).status, 409)
    assertEquals(send(teamA, "PATCH", "/workspaces/ws_ea/preset", Json.obj("waf_config" -> JsNull)).status, 200)
    assertEquals(send(teamA, "DELETE", s"/workspaces/ws_ea/entities/waf-configs/$own", JsNull).status, 204)
  }

  test("what an entity of the workspace names has to be visible to it") {
    val rulesetB = (create(teamB, "ws_eb", "waf-rulesets", Json.obj("name" -> "b rules", "rules" -> Json.arr("SecRuleEngine On"))) \ "id").as[String]
    val res      = send(teamA, "POST", "/workspaces/ws_ea/entities/waf-configs", config ++ Json.obj("rulesets" -> Json.arr(rulesetB)))
    assertEquals(res.status, 404, res.body)
    val rulesetA = (create(teamA, "ws_ea", "waf-rulesets", Json.obj("name" -> "a rules", "rules" -> Json.arr("SecRuleEngine On"))) \ "id").as[String]
    create(teamA, "ws_ea", "waf-configs", config ++ Json.obj("rulesets" -> Json.arr(rulesetA)))
    assertEquals(send(teamA, "POST", "/workspaces/ws_ea/waf/_compile", Json.obj("rulesets" -> Json.arr(rulesetB), "rules" -> Json.arr())).status, 404)
    assertEquals(send(teamA, "POST", "/workspaces/ws_ea/waf/_compile", Json.obj("rulesets" -> Json.arr(rulesetA), "rules" -> Json.arr())).status, 200)
  }

  test("a secret sent back as the sentinel keeps its stored value, in a url too") {
    val created = create(
      teamA,
      "ws_ea",
      "challenge-providers",
      Json.obj("name" -> "captcha", "secret" -> "s3cr3t", "verify_url" -> "https://captcha.example/verify?key=k3y&v=1")
    )
    val id   = (created \ "id").as[String]
    val back = stored("challenge-providers", id) ++ Json.obj(
      "secret"     -> "__threat_studio_secret__",
      "verify_url" -> "https://captcha.example/verify?key=__threat_studio_secret__&v=2",
      "name"       -> "captcha!"
    )
    assertEquals(send(teamA, "PUT", s"/workspaces/ws_ea/entities/challenge-providers/$id", back).status, 200)
    val after = stored("challenge-providers", id)
    assertEquals((after \ "secret").as[String], "s3cr3t")
    assertEquals((after \ "verify_url").as[String], "https://captcha.example/verify?key=k3y&v=2")
    assertEquals((after \ "name").as[String], "captcha!")
  }

  test("the gateway's own kinds are not a workspace's") {
    assertEquals(get(tenantAdmin, "/workspaces/ws_ea/entities/threat-feeds").status, 404)
    assertEquals(get(tenantAdmin, "/workspaces/ws_ea/entities/alert-rules").status, 404)
  }

  test("a workspace is told how much of an entity's use is its own, and an admin who uses it") {
    val usage = get(teamA, s"/workspaces/ws_ea/entities/waf-configs/$sharedConfig/_usage")
    assertEquals(usage.status, 200, usage.body)
    assertEquals((usage.json \ "here").as[Int], 1)
    assertEquals((usage.json \ "elsewhere").as[Int], 1)
    assert((usage.json \ "referencers").toOption.isEmpty)
    val full = get(tenantAdmin, s"/workspaces/ws_ea/entities/waf-configs/$sharedConfig/_usage")
    assertEquals((full.json \ "referencers").as[Seq[JsObject]].map(r => (r \ "id").as[String]).toSet, Set("ws_ea", "ws_eb"))
  }

  test("tuning and learning change only a configuration of the workspace") {
    val matches = get(teamA, "/workspaces/ws_ea/tuning/matches")
    assertEquals(matches.status, 200, matches.body)
    assert((matches.json \ "matches").asOpt[Seq[JsObject]].isDefined)
    assertEquals(send(teamA, "POST", "/workspaces/ws_ea/learning/_start", Json.obj("config_ref" -> sharedConfig)).status, 409)
    assertEquals(send(teamA, "POST", "/workspaces/ws_ea/tuning/_apply", Json.obj("config_ref" -> sharedConfig, "seclang" -> "x")).status, 409)
    val own = (create(teamA, "ws_ea", "waf-configs", config) \ "id").as[String]
    assertEquals(send(teamA, "PATCH", "/workspaces/ws_ea/preset", Json.obj("waf_config" -> own)).status, 200)
    // the module knows a config once the extension's state has synced, a few seconds after it is written
    def running(): Seq[String] = (get(teamA, "/workspaces/ws_ea/learning").json \ "running").asOpt[Seq[String]].getOrElse(Seq.empty)
    val deadline = System.currentTimeMillis() + 20000L
    while (!running().contains(own) && System.currentTimeMillis() < deadline) {
      send(teamA, "POST", "/workspaces/ws_ea/learning/_start", Json.obj("config_ref" -> own))
      Thread.sleep(1000L)
    }
    assert(running().contains(own), "the window never started")
    assert(send(teamA, "POST", "/workspaces/ws_ea/learning/_discard", Json.obj("config_ref" -> own)).status < 300)
  }
}
