package com.cloud.apim.otoroshi.extensions.waf.entities

import play.api.libs.json.Json

/**
 * The three hardening items, seen from the entity: a body limit that always applies, an explicit
 * policy for what is over it, and a MIME comparison that matches the headers real servers send.
 */
class WafConfigSuite extends munit.FunSuite {

  private def config(
      inputLimit: Option[Long] = None,
      outputLimit: Option[Long] = None,
      mimetypes: Seq[String] = Seq.empty,
      oversize: String = CloudApimWafConfig.OversizeInspectPrefix
  ) = CloudApimWafConfig(
    id = "waf-config_test",
    name = "test",
    inputBodyLimit = inputLimit,
    outputBodyLimit = outputLimit,
    outputBodyMimetypes = mimetypes,
    oversizeBodyAction = oversize
  )

  test("an unset limit is the built-in cap, never unbounded") {
    assertEquals(config().effectiveInputBodyLimit, CloudApimWafConfig.defaultBodyLimit)
    assertEquals(config().effectiveOutputBodyLimit, CloudApimWafConfig.defaultBodyLimit)
    assertEquals(CloudApimWafConfig.defaultBodyLimit, 2L * 1024L * 1024L)
  }

  test("a configured limit is used as written") {
    assertEquals(config(inputLimit = Some(512L)).effectiveInputBodyLimit, 512L)
    assertEquals(config(outputLimit = Some(4096L)).effectiveOutputBodyLimit, 4096L)
  }

  test("the oversize policy defaults to inspecting the beginning, not to refusing") {
    assertEquals(config().rejectsOversizeBody, false)
    assertEquals(config(oversize = "reject").rejectsOversizeBody, true)
    assertEquals(config(oversize = "  REJECT ").rejectsOversizeBody, true)
  }

  test("a configured text/html matches the header a real server sends — this is H3") {
    val cfg = config(mimetypes = Seq("text/html"))
    assertEquals(cfg.inspectsContentType(Some("text/html; charset=utf-8")), true)
    assertEquals(cfg.inspectsContentType(Some("text/html")), true)
    assertEquals(cfg.inspectsContentType(Some("application/json")), false)
  }

  test("an empty MIME list means every type") {
    assertEquals(config().inspectsContentType(Some("image/png")), true)
    assertEquals(config().inspectsContentType(None), true)
  }

  test("a response with no content type is still evaluated, as it was before") {
    assertEquals(config(mimetypes = Seq("text/html")).inspectsContentType(None), true)
  }

  test("subtype wildcards work in the list") {
    val cfg = config(mimetypes = Seq("text/*"))
    assertEquals(cfg.inspectsContentType(Some("text/plain; charset=iso-8859-1")), true)
    assertEquals(cfg.inspectsContentType(Some("application/json")), false)
  }

  test("a config written before the cap existed reads with safe defaults") {
    val old = CloudApimWafConfig.format
      .reads(Json.obj("id" -> "waf-config_x", "name" -> "old", "description" -> "", "rules" -> Json.arr()))
      .get
    assertEquals(old.oversizeBodyAction, CloudApimWafConfig.OversizeInspectPrefix)
    assertEquals(old.effectiveInputBodyLimit, CloudApimWafConfig.defaultBodyLimit)
    assertEquals(old.rejectsOversizeBody, false, "an existing config must not start refusing uploads")
  }

  test("an unknown oversize action degrades to inspecting, never to refusing") {
    val cfg = CloudApimWafConfig.format
      .reads(Json.obj("id" -> "x", "name" -> "n", "description" -> "", "oversize_body_action" -> "explode"))
      .get
    assertEquals(cfg.oversizeBodyAction, CloudApimWafConfig.OversizeInspectPrefix)
  }

  test("the config round-trips") {
    val cfg  = config(inputLimit = Some(1024L), mimetypes = Seq("text/html"), oversize = "reject")
    val back = CloudApimWafConfig.format.reads(cfg.json).get
    assertEquals(back.inputBodyLimit, Some(1024L))
    assertEquals(back.outputBodyMimetypes, Seq("text/html"))
    assertEquals(back.oversizeBodyAction, "reject")
  }
}

/**
 * Composition is the whole point of WAF-1: a config lists the rulesets it wants and adds its own
 * rules on top. Order carries meaning in SecLang, so most of this is about order.
 */
class WafRulesetSuite extends munit.FunSuite {

  private def ruleset(id: String, rules: Seq[String], enabled: Boolean = true) =
    WafRuleset(id = id, name = id, rules = rules, enabled = enabled)

  private def config(rulesets: Seq[String] = Seq.empty, rules: Seq[String] = Seq.empty) =
    CloudApimWafConfig(id = "waf-config_test", name = "test", rulesets = rulesets, rules = rules)

  private def resolver(all: WafRuleset*): String => Option[WafRuleset] =
    id => all.find(_.id == id)

  test("rulesets are applied in the order they are listed, then the inline rules") {
    val composed = WafRuleComposition.compose(
      config(rulesets = Seq("a", "b"), rules = Seq("inline")),
      resolver(ruleset("a", Seq("a1", "a2")), ruleset("b", Seq("b1")))
    )
    assertEquals(composed.rules, Seq("a1", "a2", "b1", "inline"))
    assert(composed.complete)
  }

  test("listing order is what changes, not entity order") {
    val rs = resolver(ruleset("a", Seq("a1")), ruleset("b", Seq("b1")))
    assertEquals(WafRuleComposition.compose(config(rulesets = Seq("b", "a")), rs).rules, Seq("b1", "a1"))
  }

  test("a config with no rulesets is exactly what it always was") {
    val composed = WafRuleComposition.compose(config(rules = Seq("@import_preset crs", "SecRuleEngine On")), resolver())
    assertEquals(composed.rules, Seq("@import_preset crs", "SecRuleEngine On"))
    assert(composed.complete)
  }

  test("a reference to nothing is reported, not swallowed") {
    val composed = WafRuleComposition.compose(
      config(rulesets = Seq("a", "ghost"), rules = Seq("inline")),
      resolver(ruleset("a", Seq("a1")))
    )
    assertEquals(composed.rules, Seq("a1", "inline"), "the rest still runs")
    assertEquals(composed.missing, Seq("ghost"))
    assertEquals(composed.complete, false)
  }

  test("a disabled ruleset contributes nothing, and says so") {
    val composed = WafRuleComposition.compose(
      config(rulesets = Seq("a", "off")),
      resolver(ruleset("a", Seq("a1")), ruleset("off", Seq("never"), enabled = false))
    )
    assertEquals(composed.rules, Seq("a1"))
    assertEquals(composed.disabled, Seq("off"))
    assertEquals(composed.complete, false)
  }

  test("an empty ruleset is not a missing one") {
    val composed = WafRuleComposition.compose(config(rulesets = Seq("a")), resolver(ruleset("a", Seq.empty)))
    assertEquals(composed.rules, Seq.empty[String])
    assert(composed.complete)
  }

  test("a config written before rulesets existed reads with none") {
    val old = CloudApimWafConfig.format
      .reads(Json.obj("id" -> "waf-config_x", "name" -> "old", "description" -> "", "rules" -> Json.arr("SecRuleEngine On")))
      .get
    assertEquals(old.rulesets, Seq.empty[String])
    assertEquals(old.rules, Seq("SecRuleEngine On"))
  }

  test("the config round-trips its references") {
    val cfg  = config(rulesets = Seq("a", "b"), rules = Seq("inline"))
    val back = CloudApimWafConfig.format.reads(cfg.json).get
    assertEquals(back.rulesets, Seq("a", "b"))
    assertEquals(back.rules, Seq("inline"))
  }

  test("blank references are dropped on read rather than becoming a missing ruleset") {
    val cfg = CloudApimWafConfig.format
      .reads(Json.obj("id" -> "x", "name" -> "n", "description" -> "", "rulesets" -> Json.arr("a", "", "  ")))
      .get
    assertEquals(cfg.rulesets, Seq("a"))
  }

  test("the ruleset round-trips") {
    val rs   = WafRuleset(id = "waf-ruleset_1", name = "base", description = "d", rules = Seq("r1", "r2"), enabled = false)
    val back = WafRuleset.format.reads(rs.json).get
    assertEquals(back, rs)
  }
}
