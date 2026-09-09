package com.cloud.apim.otoroshi.extensions.waf.it

import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimWaf
import play.api.libs.json.Json

/**
 * WAF-1 through a real gateway.
 *
 * Composition is easy to get right in a unit test and easy to get wrong in the wiring — the engine
 * is built from state, the state is recomposed on write, and either of those could quietly keep
 * serving the rules a config was created with.
 */
class WafRulesetIT extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val marker = "RULESETMARKER"
  private val denyIt = s"""SecRule REQUEST_BODY "@contains $marker" "id:9101,phase:2,deny,status:403""""

  private def ruleset(rules: Seq[String], enabled: Boolean = true): String = Gateway.createWafRuleset(
    Json.obj(
      "id"          -> s"waf-ruleset_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"        -> "it-ruleset",
      "description" -> "",
      "enabled"     -> enabled,
      "rules"       -> rules
    )
  )

  private def config(rulesets: Seq[String], rules: Seq[String]): String = Gateway.createWafConfig(
    Json.obj(
      "id"                   -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"                 -> "it",
      "description"          -> "",
      "enabled"              -> true,
      "block"                -> true,
      "inspect_input_body"   -> true,
      "inspect_output_body"  -> false,
      "oversize_body_action" -> "inspect_prefix",
      "rulesets"             -> rulesets,
      "rules"                -> rules
    )
  )

  private def wafPlugin(ref: String) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimWaf],
    config = NgPluginInstanceConfig(Json.obj("ref" -> ref))
  )

  test("a rule that lives in a referenced ruleset is the rule that runs") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val rs      = ruleset(Seq(denyIt))
    val ref     = config(rulesets = Seq(rs), rules = Seq("SecRuleEngine On"))
    val route   = Gateway.createRoute("waf1-compose", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/", "POST", Some(ByteString(s"hello $marker"))).status, 403)
      assertEquals(Gateway.call(route, "/", "POST", Some(ByteString("hello world"))).status, 200)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); Gateway.deleteWafRuleset(rs); backend.stop()
    }
  }

  test("editing the ruleset changes what the config runs, without touching the config") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val rs      = ruleset(Seq(denyIt))
    val ref     = config(rulesets = Seq(rs), rules = Seq("SecRuleEngine On"))
    val route   = Gateway.createRoute("waf1-live", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/", "POST", Some(ByteString(s"hello $marker"))).status, 403)
      // the shared ruleset goes away; the config is never rewritten
      Gateway.deleteWafRuleset(rs)
      Thread.sleep(1500L)
      assertEquals(
        Gateway.call(route, "/", "POST", Some(ByteString(s"hello $marker"))).status,
        200,
        "a reference that no longer resolves must stop contributing, and must not break the route"
      )
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("a config with no rulesets runs on its inline rules exactly as before") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = config(rulesets = Seq.empty, rules = Seq(denyIt, "SecRuleEngine On"))
    val route   = Gateway.createRoute("waf1-inline", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/", "POST", Some(ByteString(s"hello $marker"))).status, 403)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("a disabled ruleset contributes nothing") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val rs      = ruleset(Seq(denyIt), enabled = false)
    val ref     = config(rulesets = Seq(rs), rules = Seq("SecRuleEngine On"))
    val route   = Gateway.createRoute("waf1-disabled", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/", "POST", Some(ByteString(s"hello $marker"))).status, 200)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); Gateway.deleteWafRuleset(rs); backend.stop()
    }
  }

  test("two configs share one ruleset — the reason the entity exists") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val rs      = ruleset(Seq(denyIt))
    val refA    = config(rulesets = Seq(rs), rules = Seq("SecRuleEngine On"))
    val refB    = config(rulesets = Seq(rs), rules = Seq("SecRuleEngine On"))
    val routeA  = Gateway.createRoute("waf1-share-a", backend.port, Seq(wafPlugin(refA)))
    val routeB  = Gateway.createRoute("waf1-share-b", backend.port, Seq(wafPlugin(refB)))
    try {
      assertEquals(Gateway.call(routeA, "/", "POST", Some(ByteString(s"x $marker"))).status, 403)
      assertEquals(Gateway.call(routeB, "/", "POST", Some(ByteString(s"x $marker"))).status, 403)
    } finally {
      Gateway.deleteRoute(routeA); Gateway.deleteRoute(routeB)
      Gateway.deleteWafConfig(refA); Gateway.deleteWafConfig(refB); Gateway.deleteWafRuleset(rs); backend.stop()
    }
  }
}
