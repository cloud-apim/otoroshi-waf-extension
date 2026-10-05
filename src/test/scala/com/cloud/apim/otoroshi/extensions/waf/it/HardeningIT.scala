package com.cloud.apim.otoroshi.extensions.waf.it

import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import play.api.libs.json.{JsValue, Json}

/**
 * H6 through a real gateway: `_compile` checks what the gateway runs, composed the way it composes
 * it and read the way the engine reads it.
 */
class HardeningIT extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def ext = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get

  private def compile(body: JsValue) = ext.compileReport(body)

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
}
