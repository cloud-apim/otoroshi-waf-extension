package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.seclang.model.RequestContext
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.{CloudApimWafAuditEvent, CloudApimWafExtension}
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimWaf, CloudApimWafPlugins, CloudApimWafTrailEvent, RequestContextBuilder, WafResolution}
import play.api.libs.json.{JsValue, Json}

/**
 * H4 to H8 through a real gateway: the engine is built once and survives the sync ticks, a WAF that
 * cannot run is refused or let through as `waf.fail-open` says rather than failing with a 500,
 * `_compile` checks what the gateway runs, and the events say which route and which environment
 * they come from.
 */
class HardeningIT extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def ext = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get

  private def config(rules: Seq[String], rulesets: Seq[String] = Seq.empty, block: Boolean = true): String = Gateway.createWafConfig(
    Json.obj(
      "id"                  -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"                -> "hardening-it",
      "description"         -> "",
      "enabled"             -> true,
      "block"               -> block,
      "inspect_input_body"  -> false,
      "inspect_output_body" -> false,
      "rulesets"            -> rulesets,
      "rules"               -> rules
    )
  )

  private def wafPlugin(ref: String) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimWaf],
    config = NgPluginInstanceConfig(Json.obj("ref" -> ref))
  )

  private def compile(body: JsValue) = ext.compileReport(body)

  test("a config's engine is built once, survives the sync ticks, and shares no transaction between requests") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    // the second request through one shared transaction would see seen=2 and be denied
    val ref     = config(
      Seq(
        """SecRuleEngine On
          |SecAction "id:1,phase:1,pass,nolog,setvar:tx.seen=+1"
          |SecRule TX:seen "@gt 1" "id:2,phase:1,deny,status:403"""".stripMargin
      )
    )
    val route   = Gateway.createRoute("hardening-engine", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route).status, 200)
      assertEquals(Gateway.call(route).status, 200)
      val engine = ext.engineFor(ext.states.config(ref).get).toOption.get
      // the state sync runs every 5 seconds in this harness, and re-reads every entity each time
      Thread.sleep(6000L)
      assert(ext.engineFor(ext.states.config(ref).get).toOption.get eq engine, "an unchanged config must keep its engine")
      assertEquals(Gateway.call(route).status, 200)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  private val broken = Seq("SecRuleEngine On", """SecRule ARGS "@contains x" "id:10,phase:2,deny""")

  test("fail-open is off unless configured") {
    assertEquals(CloudApimWafPlugins.failOpen(using Gateway.instance.env), false)
    assertEquals(ext.failOpen, false)
  }

  test("a blocking config that does not compile refuses its requests rather than letting them through uninspected") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    // saved through the api, which does not compile what it stores
    val ref     = config(broken)
    val route   = Gateway.createRoute("hardening-broken", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/?q=y").status, 503)
      val reason = ext.engineFor(ext.states.config(ref).get).left.toOption.get
      assert(reason.startsWith("rule 2: does not parse"), reason)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("a config in monitoring mode that does not compile is not a reason to refuse anything") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = config(broken, block = false)
    val route   = Gateway.createRoute("hardening-broken-monitoring", backend.port, Seq(wafPlugin(ref)))
    try assertEquals(Gateway.call(route, "/?q=y").status, 200)
    finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("a route pointing at a waf config that does not exist refuses its requests") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute("hardening-missing", backend.port, Seq(wafPlugin("waf-config_does-not-exist")))
    try assertEquals(Gateway.call(route).status, 503)
    finally {
      Gateway.deleteRoute(route); backend.stop()
    }
  }

  test("with waf.fail-open, a WAF that cannot run lets the request through, and a switched-off config never refuses") {
    val brokenRef   = config(broken)
    val disabledRef = Gateway.createWafConfig(
      Json.obj(
        "id"          -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
        "name"        -> "hardening-it-off",
        "description" -> "",
        "enabled"     -> false,
        "block"       -> true,
        "rules"       -> broken
      )
    )
    try {
      val deadline = System.currentTimeMillis() + 10000L
      while (Seq(brokenRef, disabledRef).exists(ext.states.config(_).isEmpty) && System.currentTimeMillis() < deadline) Thread.sleep(200L)
      // the gateway reads the flag once, at startup: the decision itself is what is exercised here
      assertEquals(CloudApimWafPlugins.resolve(Some(ext), failOpen = true, brokenRef), WafResolution.Off)
      assertEquals(CloudApimWafPlugins.resolve(Some(ext), failOpen = true, "waf-config_does-not-exist"), WafResolution.Off)
      assertEquals(CloudApimWafPlugins.resolve(None, failOpen = true, brokenRef), WafResolution.Off)
      assert(CloudApimWafPlugins.resolve(Some(ext), failOpen = false, brokenRef).isInstanceOf[WafResolution.Unavailable])
      assert(CloudApimWafPlugins.resolve(None, failOpen = false, brokenRef).isInstanceOf[WafResolution.Unavailable])
      assertEquals(CloudApimWafPlugins.resolve(Some(ext), failOpen = false, disabledRef), WafResolution.Off)
    } finally {
      Gateway.deleteWafConfig(brokenRef); Gateway.deleteWafConfig(disabledRef)
    }
  }

  test("_compile checks the presets, and the rules element by element, the way the gateway reads them") {
    assertEquals((compile(Json.obj("rules" -> Json.arr("@import_preset crs", "SecRuleEngine On"))) \ "done").as[Boolean], true)

    val typo = compile(Json.obj("rules" -> Json.arr("@import_preset crss", "SecRuleEngine On")))
    assertEquals((typo \ "done").as[Boolean], false)
    assert((typo \ "error").as[String].contains("unknown preset 'crss'"), typo.toString)
    assertEquals((typo \ "errors" \ 0 \ "origin").as[String], "rule 1")

    // joined into one text, as it used to be checked, this chain was valid. it does not run that way
    val split = compile(
      Json.obj(
        "rules" -> Json.arr(
          """SecRule ARGS "@contains a" "id:10,phase:2,deny,status:403,chain"""",
          """SecRule ARGS "@contains b" "t:none""""
        )
      )
    )
    assertEquals((split \ "done").as[Boolean], false)
    assert((split \ "error").as[String].contains("ends on a chained rule (10)"), split.toString)
  }

  test("_compile names the ruleset a broken rule comes from, and still reports a missing one as before") {
    val rs = Gateway.createWafRuleset(
      Json.obj(
        "id"          -> s"waf-ruleset_${java.util.UUID.randomUUID().toString.take(8)}",
        "name"        -> "hardening-broken",
        "description" -> "",
        "enabled"     -> true,
        "rules"       -> Json.arr("""SecRule ARGS "@contains x" "id:10,phase:2,deny""")
      )
    )
    try {
      val deadline = System.currentTimeMillis() + 10000L
      while (ext.states.ruleset(rs).isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(200L)
      val broken = compile(Json.obj("rulesets" -> Json.arr(rs), "rules" -> Json.arr("SecRuleEngine On")))
      assertEquals((broken \ "done").as[Boolean], false)
      assert((broken \ "error").as[String].startsWith("ruleset 'hardening-broken', rule 1: does not parse"), broken.toString)

      val missing = compile(Json.obj("rulesets" -> Json.arr("waf-ruleset_nope"), "rules" -> Json.arr("SecRuleEngine On")))
      assertEquals((missing \ "done").as[Boolean], true)
      assertEquals((missing \ "missing_rulesets").as[Seq[String]], Seq("waf-ruleset_nope"))
    } finally Gateway.deleteWafRuleset(rs)
  }

  test("the waf events say which route and which environment they come from") {
    val env     = Gateway.instance.env
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute("hardening-events", backend.port, Seq.empty)
    try {
      val trail = CloudApimWafTrailEvent(None, List.empty, Json.obj(), Some(route), blocking = true).toJson(using env)
      assertEquals((trail \ "@serviceId").as[String], route.id)
      assertEquals((trail \ "@service").as[String], route.name)
      assertEquals((trail \ "@env").as[String], env.env)

      val context = RequestContext(
        method = "GET",
        uri = "/",
        variables = Map(RequestContextBuilder.RouteIdVariable -> route.id, RequestContextBuilder.RouteNameVariable -> route.name)
      )
      val state   = com.cloud.apim.seclang.model.RuntimeState(
        mode = com.cloud.apim.seclang.model.EngineMode.On,
        webAppId = None,
        disabledIds = Set.empty,
        events = Nil,
        logs = Nil,
        txMap = new scala.collection.concurrent.TrieMap[String, String](),
        envMap = new scala.collection.concurrent.TrieMap[String, String](),
        uidRef = new java.util.concurrent.atomic.AtomicReference[String](null)
      )
      val audit   = CloudApimWafAuditEvent(942100, context, state, 2, "msg", List.empty).toJson(using env)
      assertEquals((audit \ "@serviceId").as[String], route.id)
      assertEquals((audit \ "@service").as[String], route.name)
      assertEquals((audit \ "@env").as[String], env.env)
    } finally {
      Gateway.deleteRoute(route); backend.stop()
    }
  }
}
