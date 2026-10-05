package com.cloud.apim.otoroshi.extensions.waf.rules

import com.cloud.apim.otoroshi.extensions.waf.entities.*
import com.cloud.apim.seclang.model.{Disposition, RequestContext}
import com.cloud.apim.seclang.scaladsl.SecLang
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.WafExtensionState

import java.util.concurrent.atomic.AtomicInteger

/**
 * H5 and H6 away from a gateway: the engine a config runs is built once per change and shared
 * without sharing a transaction, and `_compile` checks the rules the way the engine reads them.
 */
class EngineSuite extends munit.FunSuite {

  private val factory               = SecLang.factory(Map.empty)
  private val crs: String => Boolean = _ == "crs"

  private def composed(rules: String*) = ComposedRules(rules, Seq.empty, Seq.empty)

  private def cache(builds: AtomicInteger = new AtomicInteger(), broken: AtomicInteger = new AtomicInteger()) =
    new EngineCache(
      rules => { builds.incrementAndGet(); factory.engine(rules.toList) },
      crs,
      (_, _) => { broken.incrementAndGet(); () }
    )

  private def get(uri: String = "/") = RequestContext(method = "GET", uri = uri)

  // -----------------------------------------------------------------------------------------------
  // the check
  // -----------------------------------------------------------------------------------------------

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

  // -----------------------------------------------------------------------------------------------
  // the shared engine
  // -----------------------------------------------------------------------------------------------

  private val counting = composed(
    """SecRuleEngine On
      |SecAction "id:1,phase:1,pass,nolog,setvar:tx.seen=+1"
      |SecRule TX:seen "@gt 1" "id:2,phase:1,deny,status:403"""".stripMargin
  )

  test("one engine per composition: the same composition is never rebuilt") {
    val builds = new AtomicInteger()
    val engines = cache(builds)
    val first   = engines.engineFor("waf-config_a", counting).toOption.get
    val second  = engines.engineFor("waf-config_a", counting).toOption.get
    assert(first eq second)
    assertEquals(builds.get(), 1)
  }

  test("a new composition is a change, and is rebuilt") {
    val builds  = new AtomicInteger()
    val engines = cache(builds)
    engines.engineFor("waf-config_a", counting)
    engines.engineFor("waf-config_a", counting.copy(rules = counting.rules :+ "SecRuleEngine DetectionOnly"))
    assertEquals(builds.get(), 2)
  }

  test("requests share the engine, never the transaction") {
    val engine = cache().engineFor("waf-config_a", counting).toOption.get
    // with one TX for everyone, the second request would see seen=2 and be denied
    assertEquals(engine.exchange().evaluate(get(), List(1)).disposition, Disposition.Continue)
    assertEquals(engine.exchange().evaluate(get(), List(1)).disposition, Disposition.Continue)
  }

  test("the two halves of one exchange do share theirs, as request and response phases must") {
    val exchange = cache().engineFor("waf-config_a", counting).toOption.get.exchange()
    assertEquals(exchange.evaluate(get(), List(1)).disposition, Disposition.Continue)
    assert(exchange.evaluate(get(), List(1)).disposition.isInstanceOf[Disposition.Block])
  }

  test("a composition that does not compile is reported once, with the rule at fault, and not rebuilt") {
    val builds   = new AtomicInteger()
    val broken   = new AtomicInteger()
    val engines  = cache(builds, broken)
    val bad      = composed("SecRuleEngine On", """SecRule ARGS "@contains x" "id:10,phase:2,deny""")
    val first    = engines.engineFor("waf-config_b", bad)
    val second   = engines.engineFor("waf-config_b", bad)
    assert(first.isLeft && second.isLeft)
    assert(first.left.toOption.get.startsWith("rule 2: does not parse"), first.toString)
    assertEquals(builds.get(), 1)
    assertEquals(broken.get(), 1)
  }

  test("the engines of configs that are gone are dropped") {
    val builds  = new AtomicInteger()
    val engines = cache(builds)
    engines.engineFor("waf-config_a", counting)
    engines.retain(Set("waf-config_other"))
    engines.engineFor("waf-config_a", counting)
    assertEquals(builds.get(), 2)
  }

  // -----------------------------------------------------------------------------------------------
  // what the cache is keyed on
  // -----------------------------------------------------------------------------------------------

  test("a sync that changes nothing keeps the composition, so nothing built from it is rebuilt") {
    val state  = new WafExtensionState()
    val config = CloudApimWafConfig(id = "waf-config_a", name = "a", rules = Seq("SecRuleEngine On"))
    state.updateConfigs(Seq(config))
    val before = state.composedFor(config)
    // what every sync tick does: the same entities, read again
    state.updateConfigs(Seq(config.copy()))
    assert(state.composedFor(config) eq before)
    val edited = config.copy(rules = Seq("SecRuleEngine DetectionOnly"))
    state.updateConfigs(Seq(edited))
    assert(!(state.composedFor(edited) eq before))
    assertEquals(state.rulesFor(edited), Seq("SecRuleEngine DetectionOnly"))
  }
}
