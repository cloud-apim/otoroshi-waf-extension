package com.cloud.apim.otoroshi.extensions.waf.rules

import com.cloud.apim.otoroshi.extensions.waf.entities.*

/**
 * H6 away from a gateway: `_compile` checks the rules the way the engine reads them.
 */
class EngineSuite extends munit.FunSuite {

  private val crs: String => Boolean = _ == "crs"

  private def check(rules: String*) = RuleCheck.check(rules.zipWithIndex.map { case (r, i) => (s"rule ${i + 1}", r) }, crs)

  test("rules that parse and compile, and a preset that exists, raise nothing") {
    assertEquals(
      check("@import_preset crs", "SecRuleEngine On", """SecRule ARGS "@contains x" "id:10,phase:2,deny,status:403""""),
      Seq.empty
    )
  }

  test("a preset name the factory does not know is an error rather than a silent skip") {
    val problems = check("@import_preset crss", "SecRuleEngine On")
    assertEquals(problems.map(_.index), Seq(0))
    assert(problems.head.text.contains("unknown preset 'crss'"), problems.head.text)
  }

  test("a rule that does not parse is reported on the element that carries it") {
    val problems = check("SecRuleEngine On", """SecRule ARGS "@contains x" "id:10,phase:2,deny""")
    assertEquals(problems.map(_.index), Seq(1))
    assert(problems.head.text.startsWith("rule 2: does not parse"), problems.head.text)
  }

  test("a chain split across two elements is an error, since each element compiles on its own") {
    val split = check(
      """SecRule ARGS "@contains a" "id:10,phase:2,deny,status:403,chain"""",
      """SecRule ARGS "@contains b" "t:none""""
    )
    assertEquals(split.map(_.index), Seq(0))
    assert(split.head.message.contains("ends on a chained rule (10)"), split.head.message)
    // the same chain in one element is fine
    assertEquals(
      check("SecRule ARGS \"@contains a\" \"id:10,phase:2,deny,status:403,chain\"\nSecRule ARGS \"@contains b\" \"t:none\""),
      Seq.empty
    )
  }

  test("the composition says where each rule comes from, in the order the engine reads them") {
    val rs     = WafRuleset(id = "waf-ruleset_a", name = "baseline", rules = Seq("@import_preset crs", "SecRuleEngine On"))
    val config = CloudApimWafConfig(
      id = "waf-config_a",
      name = "a",
      rulesets = Seq(rs.id),
      rules = Seq("SecRuleEngine DetectionOnly"),
      crs = CrsSettings(paranoiaLevel = Some(2))
    )
    val labels = WafRuleComposition.compose(config, id => Option.when(id == rs.id)(rs)).labelled.map(_._1)
    assertEquals(labels.last, "rule 1")
    assertEquals(labels.dropRight(1).takeRight(2), Seq("ruleset 'baseline', rule 1", "ruleset 'baseline', rule 2"))
    assert(labels.head == "the Core Rule Set options", labels.toString)
  }
}
