package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.studio.ThreatStudio
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgPlugins, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The studio's one irreducible computation: a table of selectors turned into "which routes does this
 * workspace actually govern".
 *
 * Everything else the console shows is an admin api it merely reads. This is the part that has to be
 * run against a real router, with real routes, because the selectors go through the expression
 * language and the table is read off the live global configuration.
 */
class ThreatStudioIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private given otoroshi.env.Env                 = Gateway.instance.env
  private given scala.concurrent.ExecutionContext = Gateway.ec

  private def studio: ThreatStudio =
    Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.studio

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 30.seconds)

  private def rule(
      id: String,
      targets: Seq[CloudApimSecuritySuiteTarget] = Seq.empty,
      skip: Boolean = false,
      enabled: Boolean = true,
      preset: CloudApimSecuritySuitePresetConfig = CloudApimSecuritySuitePresetConfig.default
  ) = CloudApimSecuritySuiteGlobalRule(id = id, name = id, enabled = enabled, skip = skip, targets = targets, preset = preset)

  private def onPath(path: String, value: String) =
    CloudApimSecuritySuiteTarget(path = Some(path), value = JsString(value))

  private def writeTable(rules: Seq[CloudApimSecuritySuiteGlobalRule], skipProtected: Boolean = true): Unit = {
    await(studio.writeTable(CloudApimSecuritySuiteGlobalPresetConfig(rules, skipProtected), Some(true)))
    // the global config is read from its own cache on the request path
    Thread.sleep(1500L)
  }

  private def clearTable(): Unit = {
    await(studio.writeTable(CloudApimSecuritySuiteGlobalPresetConfig(Seq.empty), Some(false)))
    Thread.sleep(500L)
  }

  private def workspaces(): JsObject = studio.workspacesJson.asObject

  private def workspace(id: String): JsObject =
    (workspaces() \ "workspaces").as[Seq[JsObject]].find(w => (w \ "id").as[String] == id).get

  private def names(arr: Seq[JsObject]): Set[String] = arr.map(o => (o \ "name").as[String]).toSet

  private var backend: TestBackend = null
  private var routes: Seq[NgRoute] = Seq.empty

  override def beforeAll(): Unit = {
    backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    routes = Seq(
      Gateway.createRoute("studio-public", backend.port, Seq.empty, tags = Seq("public", "v2")),
      Gateway.createRoute("studio-partner", backend.port, Seq.empty, tags = Seq("public", "partner")),
      Gateway.createRoute("studio-internal", backend.port, Seq.empty, metadata = Map("env" -> "internal")),
      // a route protected by hand: the table is meant to leave it alone
      Gateway.createRoute(
        "studio-own",
        backend.port,
        Seq(NgPluginInstance(NgPluginHelper.pluginId[CloudApimThreatGate], config = NgPluginInstanceConfig(Json.obj()))),
        tags = Seq("public")
      )
    )
  }

  override def afterAll(): Unit = {
    // the table lives on the global configuration, which every other suite in this jvm shares
    clearTable()
    routes.foreach(Gateway.deleteRoute)
    if (backend != null) backend.stop()
  }

  test("an empty install reports no table rather than an error") {
    clearTable()
    val ws = workspaces()
    assertEquals((ws \ "workspaces").as[Seq[JsObject]], Seq.empty[JsObject])
    assert((ws \ "fleet" \ "summary" \ "total").as[Int] >= 4, "the fleet is still reported")
  }

  test("a workspace resolves to the routes its selectors claim") {
    writeTable(Seq(rule("public", Seq(onPath("$.tags", "Contains(public)")))))
    val ws = workspace("public")
    // studio-own carries its own gate, so the table stands down on it
    assertEquals(names((ws \ "claims").as[Seq[JsObject]]), Set("studio-public", "studio-partner"))
    assertEquals((ws \ "summary" \ "total").as[Int], 2)
  }

  test("a route carrying its own fabric is reported as self-managed, not as governed") {
    writeTable(Seq(rule("public", Seq(onPath("$.tags", "Contains(public)")))))
    val fleet = (workspaces() \ "fleet").as[JsObject]
    assert(names((fleet \ "self_managed").as[Seq[JsObject]]).contains("studio-own"))
    assertEquals(names((fleet \ "unclaimed").as[Seq[JsObject]]).contains("studio-own"), false)
  }

  test("first match wins, and the rule that lost still reports the route as matched") {
    writeTable(
      Seq(
        rule("partners", Seq(onPath("$.tags", "Contains(partner)"))),
        rule("public", Seq(onPath("$.tags", "Contains(public)")))
      )
    )
    assertEquals(names((workspace("partners") \ "claims").as[Seq[JsObject]]), Set("studio-partner"))
    assertEquals(names((workspace("public") \ "claims").as[Seq[JsObject]]), Set("studio-public"))
    assert(
      names((workspace("public") \ "matches").as[Seq[JsObject]]).contains("studio-partner"),
      "the route is matched by both, which is what explains why one of them looks small"
    )
  }

  test("a route no rule claims is the finding the page exists for") {
    writeTable(Seq(rule("public", Seq(onPath("$.tags", "Contains(public)")))))
    assert(names((workspaces() \ "fleet" \ "unclaimed").as[Seq[JsObject]]).contains("studio-internal"))
  }

  test("a rule sitting under a catch-all is reported unreachable rather than silently dead") {
    writeTable(Seq(rule("everything"), rule("public", Seq(onPath("$.tags", "Contains(public)")))))
    assertEquals((workspace("everything") \ "unreachable").as[Boolean], false)
    assertEquals((workspace("public") \ "unreachable").as[Boolean], true)
    assertEquals((workspace("public") \ "claims").as[Seq[JsObject]], Seq.empty[JsObject])
  }

  test("a skip rule claims nothing, and stops the rules below it from claiming either") {
    writeTable(
      Seq(
        rule("left-alone", Seq(onPath("$.tags", "Contains(partner)")), skip = true),
        rule("public", Seq(onPath("$.tags", "Contains(public)")))
      )
    )
    assertEquals((workspace("left-alone") \ "claims").as[Seq[JsObject]], Seq.empty[JsObject])
    assertEquals(names((workspace("public") \ "claims").as[Seq[JsObject]]), Set("studio-public"))
  }

  test("a disabled rule claims nothing and does not shadow the one below it") {
    writeTable(
      Seq(
        rule("off", Seq(onPath("$.tags", "Contains(public)")), enabled = false),
        rule("public", Seq(onPath("$.tags", "Contains(public)")))
      )
    )
    assertEquals((workspace("off") \ "claims").as[Seq[JsObject]], Seq.empty[JsObject])
    assertEquals(names((workspace("public") \ "claims").as[Seq[JsObject]]), Set("studio-public", "studio-partner"))
  }

  test("a governed route reports the posture the workspace gives it, not an empty one") {
    writeTable(
      Seq(
        rule(
          "armed",
          Seq(onPath("$.tags", "Contains(public)")),
          preset = CloudApimSecuritySuitePresetConfig(fail2ban = true, reputationMode = "monitor")
        )
      )
    )
    val posture = com.cloud.apim.otoroshi.extensions.waf.analytics.PostureReport.json.asObject
    val entry   = (posture \ "routes").as[Seq[JsObject]].find(o => (o \ "route_name").as[String] == "studio-public").get
    assertEquals((entry \ "covered").as[Boolean], true, "the table protects it although the route carries nothing")
    assertEquals((entry \ "source").as[String], "workspace")
    assertEquals((entry \ "workspace").as[String], "armed")
    assertEquals((entry \ "reputation").as[String], "monitor")
    assertEquals((entry \ "fail2ban").as[String], "dry run")
    assertEquals((posture \ "governance" \ "installed").as[Boolean], true)
  }

  test("a route selected by metadata rather than by tag resolves the same way") {
    writeTable(Seq(rule("internal", Seq(CloudApimSecuritySuiteTarget(expression = Some("${route.metadata.env}"), value = JsString("internal"))))))
    assertEquals(names((workspace("internal") \ "claims").as[Seq[JsObject]]), Set("studio-internal"))
    assertEquals((workspaces() \ "dynamic").as[Boolean], false, "a route-only table can be resolved with no request")
  }

  test("a table reading the request says so, because no console can resolve it statically") {
    writeTable(Seq(rule("dynamic", Seq(CloudApimSecuritySuiteTarget(expression = Some("${req.host}"), value = JsString("Regex(.*)"))))))
    assertEquals((workspaces() \ "dynamic").as[Boolean], true)
  }

  test("saving materialises ids, and leaves the other global plugins where they were") {
    val marker = NgPluginInstance(
      plugin = "cp:otoroshi.next.plugins.OverrideHost",
      config = NgPluginInstanceConfig(Json.obj())
    )
    val gc     = await(Gateway.instance.env.datastores.globalConfigDataStore.singleton())
    val before = NgPlugins.readFrom(gc.plugins.config.select("ng")).slots.filterNot(_.plugin == CloudApimSecuritySuiteGlobalPreset.pluginId)
    await(
      Gateway.instance.env.datastores.globalConfigDataStore.set(
        gc.copy(plugins = gc.plugins.copy(config = gc.plugins.config.asObject ++ Json.obj("ng" -> JsArray((before :+ marker).map(_.json))))),
        None
      )
    )
    Thread.sleep(500L)

    // a rule with no id at all, exactly as a hand written table has it
    await(
      studio.writeTable(
        CloudApimSecuritySuiteGlobalPresetConfig(Seq(CloudApimSecuritySuiteGlobalRule(name = "no id"))),
        Some(true)
      )
    )
    Thread.sleep(1500L)

    val after = NgPlugins.readFrom(
      await(Gateway.instance.env.datastores.globalConfigDataStore.singleton()).plugins.config.select("ng")
    ).slots
    assert(after.exists(_.plugin == marker.plugin), "an unrelated global plugin must survive a table write")
    assert(after.exists(_.plugin == CloudApimSecuritySuiteGlobalPreset.pluginId))
    val saved = (workspaces() \ "workspaces").as[Seq[JsObject]].head
    assert((saved \ "id").as[String].nonEmpty, "a rule without an id is given one on save")

    // put the global plugins back the way they were found
    val gc2 = await(Gateway.instance.env.datastores.globalConfigDataStore.singleton())
    await(
      Gateway.instance.env.datastores.globalConfigDataStore.set(
        gc2.copy(plugins = gc2.plugins.copy(config = gc2.plugins.config.asObject ++ Json.obj("ng" -> JsArray(before.map(_.json))))),
        None
      )
    )
    Thread.sleep(500L)
  }
}
