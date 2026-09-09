package com.cloud.apim.otoroshi.extensions.waf.it

import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.Json

/**
 * OPS-1's posture view, through a real gateway.
 *
 * The finding this page exists for is the route with nothing on it, so most of these check that a
 * route is reported as *less* protected than someone might assume.
 */
class PostureIT extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  // the http route is a backoffice-session endpoint, so it is reached with the admin ui's cookie and
  // not an apikey. what is worth testing is the report, which the endpoint returns verbatim
  private def posture(): play.api.libs.json.JsValue =
    com.cloud.apim.otoroshi.extensions.waf.analytics.PostureReport.json(using Gateway.instance.env)

  private def routeEntry(id: String) =
    (posture() \ "routes").as[Seq[play.api.libs.json.JsObject]].find(o => (o \ "route_id").as[String] == s"route_$id")

  private def wafConfig(block: Boolean): String = Gateway.createWafConfig(
    Json.obj(
      "id"          -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"        -> "posture-waf",
      "description" -> "",
      "enabled"     -> true,
      "block"       -> block,
      "rules"       -> Json.arr("SecRuleEngine On")
    )
  )

  test("a route with no security plugin at all is reported as unprotected") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute("posture-bare", backend.port, Seq.empty)
    try {
      val entry = routeEntry("posture-bare").get
      assertEquals((entry \ "covered").as[Boolean], false)
      assertEquals((entry \ "enforcing").as[Boolean], false)
      assertEquals((entry \ "waf").asOpt[String], None)
    } finally { Gateway.deleteRoute(route); backend.stop() }
  }

  test("a WAF in monitoring mode is covered but not enforcing") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(block = false)
    val route   = Gateway.createRoute(
      "posture-monitor",
      backend.port,
      Seq(NgPluginInstance(NgPluginHelper.pluginId[CloudApimWaf], config = NgPluginInstanceConfig(Json.obj("ref" -> ref))))
    )
    try {
      val entry = routeEntry("posture-monitor").get
      assertEquals((entry \ "covered").as[Boolean], true)
      assertEquals((entry \ "waf").as[String], "posture-waf")
      assertEquals((entry \ "waf_blocking").as[Boolean], false)
      assertEquals((entry \ "enforcing").as[Boolean], false, "monitoring is not enforcement")
    } finally { Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop() }
  }

  test("a blocking WAF is enforcing") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(block = true)
    val route   = Gateway.createRoute(
      "posture-block",
      backend.port,
      Seq(NgPluginInstance(NgPluginHelper.pluginId[CloudApimWaf], config = NgPluginInstanceConfig(Json.obj("ref" -> ref))))
    )
    try {
      val entry = routeEntry("posture-block").get
      assertEquals((entry \ "waf_blocking").as[Boolean], true)
      assertEquals((entry \ "enforcing").as[Boolean], true)
    } finally { Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop() }
  }

  test("the preset is read from its flags, without running the expansion") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(block = true)
    val route   = Gateway.createRoute(
      "posture-preset",
      backend.port,
      Seq(
        NgPluginInstance(
          NgPluginHelper.pluginId[CloudApimSecuritySuitePreset],
          config = NgPluginInstanceConfig(
            Json.obj("waf_config" -> ref, "bots" -> true, "reputation" -> true, "reputation_mode" -> "monitor")
          )
        )
      )
    )
    try {
      val entry = routeEntry("posture-preset").get
      assertEquals((entry \ "via_preset").as[Boolean], true)
      assertEquals((entry \ "waf").as[String], "posture-waf")
      assertEquals((entry \ "bots").as[Boolean], true)
      assertEquals((entry \ "reputation").as[String], "monitor")
      assertEquals((entry \ "gate").as[Boolean], true, "the gate defaults on in the preset")
      assertEquals((entry \ "fail2ban").asOpt[String], None, "fail2ban defaults off, so it must not be claimed")
    } finally { Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop() }
  }

  test("a fabric with no policy counts as dry run, because the built-in one is") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute(
      "posture-fabric",
      backend.port,
      Seq(NgPluginInstance(NgPluginHelper.pluginId[CloudApimThreatResponse], config = NgPluginInstanceConfig(Json.obj())))
    )
    try {
      val entry = routeEntry("posture-fabric").get
      assertEquals((entry \ "response").as[Boolean], true)
      assertEquals((entry \ "policy_dry_run").as[Boolean], true)
      assertEquals((entry \ "enforcing").as[Boolean], false)
    } finally { Gateway.deleteRoute(route); backend.stop() }
  }

  test("the summary counts what the page leads with") {
    val summary = (posture() \ "summary").as[play.api.libs.json.JsObject]
    val total     = (summary \ "total").as[Int]
    assertEquals((summary \ "covered").as[Int] + (summary \ "uncovered").as[Int], total)
    assert((summary \ "enforcing").as[Int] <= (summary \ "covered").as[Int])
  }
}
