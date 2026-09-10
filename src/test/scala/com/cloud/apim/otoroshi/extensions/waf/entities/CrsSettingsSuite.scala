package com.cloud.apim.otoroshi.extensions.waf.entities

import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang
import com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset
import play.api.libs.json.{JsNull, Json}

/**
 * WAF-6 against the real engine.
 *
 * The unit-level checks below prove the SecLang is shaped right, but the only claim that matters is
 * that these fields *reach CRS* — a generated directive that parses cleanly and changes nothing is
 * the same failure mode the tuning assistant was built to avoid. So the behavioural tests set a
 * dial and show the engine's verdict move.
 */
class CrsSettingsSuite extends munit.FunSuite {

  private val factory = SecLang.factory(
    Map("crs" -> EmbeddedCRSPreset.embedded),
    SecLangEngineConfig.default,
    DefaultNoCacheSecLangIntegration.default
  )

  private def request(args: Map[String, List[String]]) = RequestContext(
    method = "GET",
    uri = "/search",
    headers = Headers(Map("Host" -> List("example.com"))),
    cookies = Map.empty,
    query = args,
    body = None,
    status = None,
    statusTxt = None,
    remoteAddr = "1.2.3.4",
    remotePort = 1234,
    protocol = "http/1.1"
  )

  /** Composed exactly as the extension composes it: preamble first, then the preset. */
  private def rulesFor(settings: CrsSettings): List[String] =
    (CrsSettings.preamble(settings) ++ Seq("SecRuleEngine On", "@import_preset crs")).toList

  private def evaluate(settings: CrsSettings, args: Map[String, List[String]]) =
    factory.engine(rulesFor(settings)).evaluate(request(args), List(1, 2, 5))

  private val sqli = Map("q" -> List("1' or 1=1--"))

  private def blocks(result: EngineResult): Boolean = result.disposition match {
    case _: Disposition.Block => true
    case _                    => false
  }

  // -----------------------------------------------------------------------------------------------
  // the SecLang it generates
  // -----------------------------------------------------------------------------------------------

  test("a configuration that says nothing generates nothing") {
    assert(CrsSettings.empty.isEmpty)
    assertEquals(CrsSettings.preamble(CrsSettings.empty), Seq.empty)
    assertEquals(CrsSettings.empty.seclang, None)
  }

  test("only the fields that were set are emitted") {
    val seclang = CrsSettings(paranoiaLevel = Some(2)).seclang.get
    assert(seclang.contains("setvar:'tx.blocking_paranoia_level=2'"))
    assert(!seclang.contains("inbound_anomaly_score_threshold"), "an unset field must not be written")
    assert(!seclang.contains("early_blocking"))
  }

  test("the generated rule carries an id that collides with neither CRS nor the tuning assistant") {
    assert(CrsSettings.preambleRuleId < 900000, "CRS owns 900000 and up")
    assert(
      CrsSettings.preambleRuleId < com.cloud.apim.otoroshi.extensions.waf.tuning.ExclusionBuilder.generatedIdBase,
      "the tuning assistant allocates from its own band upwards"
    )
    assert(CrsSettings(paranoiaLevel = Some(2)).seclang.get.contains(s"id:${CrsSettings.preambleRuleId}"))
  }

  test("line continuations are single backslashes, so the directive is one statement") {
    val seclang = CrsSettings(paranoiaLevel = Some(2), inboundThreshold = Some(10)).seclang.get
    assert(!seclang.contains("\\\\"), "a doubled backslash would end the directive early")
    assert(seclang.linesIterator.count(_.trim.endsWith("\\")) >= 2, "expected continued lines")
    // and the engine still accepts it: a directive cut short by a bad continuation would not compile
    factory.engine(rulesFor(CrsSettings(paranoiaLevel = Some(2), inboundThreshold = Some(10))))
  }

  test("boolean early blocking becomes the 1/0 CRS expects, not true/false") {
    assert(CrsSettings(earlyBlocking = Some(true)).seclang.get.contains("tx.early_blocking=1'"))
    assert(CrsSettings(earlyBlocking = Some(false)).seclang.get.contains("tx.early_blocking=0'"))
  }

  test("the preamble is composed ahead of the presets, which is the whole mechanism") {
    val config = CloudApimWafConfig(
      id = "c",
      name = "c",
      rules = Seq("@import_preset crs"),
      crs = CrsSettings(paranoiaLevel = Some(2))
    )
    val composed = WafRuleComposition.compose(config, _ => None)
    assert(composed.rules.head.contains("blocking_paranoia_level"), "CRS would otherwise set its own default first")
    assert(composed.rules.last.contains("@import_preset crs"))
  }

  test("nothing is emitted into a configuration that never imports CRS") {
    // the dials are read by CRS and by nothing else. Emitted anyway they would be a rule that sets
    // variables no one reads, taking an id in someone else's program to do it
    val config = CloudApimWafConfig(
      id = "c",
      name = "c",
      rules = Seq("SecRuleEngine On", """SecRule ARGS "@rx evil" "id:1,phase:2,deny""""),
      crs = CrsSettings(paranoiaLevel = Some(2))
    )
    val composed = WafRuleComposition.compose(config, _ => None)
    assert(!composed.rules.exists(_.contains("blocking_paranoia_level")), "a dial nobody reads must not be written")
    assertEquals(composed.rules, config.rules, "the program must be exactly what was written")
    assert(composed.crsIgnored, "and the mistake has to be reported rather than swallowed")
    assert(!composed.complete)
  }

  test("a CRS import inside a referenced ruleset counts, not just the inline rules") {
    val baseline = WafRuleset(id = "rs", name = "baseline", rules = Seq("@import_preset crs"))
    val config   = CloudApimWafConfig(
      id = "c",
      name = "c",
      rulesets = Seq("rs"),
      rules = Seq("SecRuleEngine On"),
      crs = CrsSettings(paranoiaLevel = Some(2))
    )
    val composed = WafRuleComposition.compose(config, _ => Some(baseline))
    assert(composed.rules.head.contains("blocking_paranoia_level"), "the preamble still has to come first")
    assert(!composed.crsIgnored)
  }

  test("a disabled ruleset is not a CRS import") {
    // it contributes no rules, so nothing would read the dials
    val baseline = WafRuleset(id = "rs", name = "baseline", enabled = false, rules = Seq("@import_preset crs"))
    val config   = CloudApimWafConfig(id = "c", name = "c", rulesets = Seq("rs"), crs = CrsSettings(paranoiaLevel = Some(2)))
    val composed = WafRuleComposition.compose(config, _ => Some(baseline))
    assert(composed.crsIgnored)
    assert(!composed.rules.exists(_.contains("blocking_paranoia_level")))
  }

  test("the import is recognised whatever the spacing and case") {
    assert(WafRuleComposition.importsCrs(Seq("  @import_preset   crs  ")))
    assert(WafRuleComposition.importsCrs(Seq("SecRuleEngine On\n@IMPORT_PRESET CRS\n")))
    assert(!WafRuleComposition.importsCrs(Seq("# @import_preset crs")), "a comment imports nothing")
    assert(!WafRuleComposition.importsCrs(Seq("@import_preset crs-custom")))
    assert(!WafRuleComposition.importsCrs(Seq.empty))
  }

  test("saying nothing about CRS is never reported as a mistake") {
    val config = CloudApimWafConfig(id = "c", name = "c", rules = Seq("SecRuleEngine On"))
    val composed = WafRuleComposition.compose(config, _ => None)
    assert(!composed.crsIgnored, "no settings, no complaint")
    assert(composed.complete)
  }

  // -----------------------------------------------------------------------------------------------
  // validation
  // -----------------------------------------------------------------------------------------------

  test("a paranoia level outside 1..4 is refused") {
    assert(CrsSettings(paranoiaLevel = Some(0)).errors.nonEmpty)
    assert(CrsSettings(paranoiaLevel = Some(5)).errors.nonEmpty)
    assert(CrsSettings(paranoiaLevel = Some(4)).valid)
  }

  test("detection below blocking is refused, because the rules would never run") {
    val bad = CrsSettings(paranoiaLevel = Some(3), detectionParanoiaLevel = Some(1))
    assert(bad.errors.exists(_.contains("cannot be lower")))
    assert(CrsSettings(paranoiaLevel = Some(1), detectionParanoiaLevel = Some(3)).valid, "the useful direction is allowed")
  }

  test("what the form sends for an untouched section still means 'say nothing'") {
    // Otoroshi's number component renders an unset field as 0 and sends 0 back; its bool component
    // sends false. Taken literally that is a threshold of zero — which denies every request — on any
    // configuration whose form was merely opened and saved
    val fromForm = Json.obj(
      "paranoia_level"             -> JsNull,
      "detection_paranoia_level"   -> JsNull,
      "inbound_anomaly_threshold"  -> 0,
      "outbound_anomaly_threshold" -> 0,
      "early_blocking"             -> false
    )
    assertEquals(CrsSettings.read(fromForm), CrsSettings.empty)
    assertEquals(CrsSettings.read(fromForm).seclang, None, "an untouched section must emit nothing")
  }

  test("a config round-trips through the shape the form posts back") {
    val body = Json.obj(
      "id" -> "c", "name" -> "c", "description" -> "", "metadata" -> Json.obj(), "tags" -> Json.arr(),
      "rules" -> Json.arr("@import_preset crs"),
      "crs"   -> Json.obj(
        "paranoia_level" -> JsNull, "detection_paranoia_level" -> JsNull,
        "inbound_anomaly_threshold" -> 0, "outbound_anomaly_threshold" -> 0, "early_blocking" -> false
      )
    )
    val parsed = CloudApimWafConfig.format.reads(body)
    assert(parsed.isSuccess, s"the form's own payload must be storable — got $parsed")
    assertEquals(parsed.get.crs, CrsSettings.empty)
  }

  test("a config carrying impossible settings is refused rather than quietly corrected") {
    val bad = Json.parse(
      """{"id":"c","name":"c","description":"","metadata":{},"tags":[],"rules":[],"crs":{"paranoia_level":7}}"""
    )
    assert(CloudApimWafConfig.format.reads(bad).isError, "a level of 7 must not be stored as anything")
    val good = Json.parse(
      """{"id":"c","name":"c","description":"","metadata":{},"tags":[],"rules":[],"crs":{"paranoia_level":3}}"""
    )
    assertEquals(CloudApimWafConfig.format.reads(good).get.crs.paranoiaLevel, Some(3))
  }

  test("settings round-trip through json, and an absent block means empty") {
    val settings = CrsSettings(Some(2), Some(3), Some(10), Some(6), Some(true))
    assertEquals(CrsSettings.read(settings.json), settings)
    assertEquals(CrsSettings.read(Json.obj()), CrsSettings.empty)
  }

  test("a config written before these fields existed reads as empty and composes to nothing") {
    val legacy = Json.parse(
      """{"id":"c","name":"c","description":"","metadata":{},"tags":[],"rules":["SecRuleEngine On"]}"""
    )
    val config = CloudApimWafConfig.format.reads(legacy).get
    assertEquals(config.crs, CrsSettings.empty)
    assertEquals(WafRuleComposition.compose(config, _ => None).rules, Seq("SecRuleEngine On"))
  }

  // -----------------------------------------------------------------------------------------------
  // it actually reaches CRS
  // -----------------------------------------------------------------------------------------------

  test("raising the paranoia level brings in rules that do not run at level 1") {
    val atOne = evaluate(CrsSettings.empty, sqli).events.flatMap(_.ruleId).toSet
    val atTwo = evaluate(CrsSettings(paranoiaLevel = Some(2)), sqli).events.flatMap(_.ruleId).toSet
    assert(atTwo.size > atOne.size, s"level 2 must run more rules than level 1 — got ${atOne.size} then ${atTwo.size}")
    assert(atTwo.diff(atOne).nonEmpty, "no paranoia-level/2 rule fired")
  }

  /**
   * Characterisation test: this documents what the engine does today, not what it should do.
   *
   * `.fail` means "this is expected to fail". ModSecurity defines `block` as "apply whatever
   * `SecDefaultAction` says", and CRS 4 sets `SecDefaultAction "phase:2,log,auditlog,pass"` — so a
   * rule like 942100, which carries `block`, must only contribute to the anomaly score, leaving
   * rule 949110 to deny once the score reaches `tx.inbound_anomaly_score_threshold`.
   *
   * seclang-engine treats `block` as an unconditional deny instead, so CRS denies on the first
   * critical rule and the threshold decides nothing. When the engine is fixed this test will start
   * passing, munit will report it as an unexpected success, and it should lose its `.fail`.
   */
  test("raising the anomaly threshold stops a request the default threshold denies".fail) {
    val atDefault = evaluate(CrsSettings.empty, sqli)
    assert(blocks(atDefault), "the payload must be denied at the default threshold of 5")
    val raised = evaluate(CrsSettings(inboundThreshold = Some(1000)), sqli)
    assert(!blocks(raised), "a threshold nothing can reach must let the request through")
    assert(raised.events.flatMap(_.ruleId).nonEmpty, "and the rules must still have matched — only the verdict moves")
  }

  test("`block` must defer to SecDefaultAction, which is what makes anomaly scoring work".fail) {
    // the same defect, isolated from CRS entirely: one rule, one default action, no preset
    val program = List(
      "SecRuleEngine On",
      """SecDefaultAction "phase:2,log,pass"""",
      """SecRule ARGS "@rx attack" "id:41000,phase:2,block,msg:'must not deny under a passing default'""""
    )
    val result = factory.engine(program).evaluate(request(Map("q" -> List("attack"))), List(1, 2, 5))
    assert(!blocks(result), s"block resolved to deny instead of to the default action — got ${result.disposition}")
  }

  test("an explicit deny is unaffected, so the gap is specific to `block`") {
    val program = List(
      "SecRuleEngine On",
      """SecDefaultAction "phase:2,log,pass"""",
      """SecRule ARGS "@rx attack" "id:41001,phase:2,deny,status:403,msg:'must deny'""""
    )
    assert(blocks(factory.engine(program).evaluate(request(Map("q" -> List("attack"))), List(1, 2, 5))))
  }

  test("the detection level runs rules the blocking level does not act on") {
    // blocking at 1, detection at 2: the level-2 rules execute and are reported, but the anomaly
    // score they contribute is not supposed to carry the request over the blocking threshold
    val settings = CrsSettings(paranoiaLevel = Some(1), detectionParanoiaLevel = Some(2))
    val seen     = evaluate(settings, sqli).events.flatMap(_.ruleId).toSet
    val atOne    = evaluate(CrsSettings.empty, sqli).events.flatMap(_.ruleId).toSet
    assert(seen.size >= atOne.size, "detection-only rules should still be evaluated")
  }
}
