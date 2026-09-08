package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.seclang.model.{Disposition, EngineResult, MatchEvent}
import play.api.libs.json.Json

/**
 * The WAF now contributes to the shared threat score. These tests pin the two properties that
 * matter: the contribution is additive, and nothing about the plugin's own blocking decision
 * depends on it.
 */
class WafFabricSuite extends munit.FunSuite {

  private def event(ruleId: Int) = MatchEvent(Some(ruleId), Some("msg"), List.empty, 2, "raw")
  private val defaults           = CloudApimWafConfigRef("waf-config_x")

  test("a route configured before the fabric existed reads with safe defaults") {
    val old = CloudApimWafConfigRef.format.reads(Json.obj("ref" -> "waf-config_x")).get
    assertEquals(old.ref, "waf-config_x")
    assertEquals(old.contribute, true, "an existing route starts contributing without being edited")
    assertEquals(old.blockWeight, 50)
    assertEquals(old.matchWeight, 20)
  }

  test("the config round-trips") {
    val cfg  = CloudApimWafConfigRef("r", contribute = false, blockWeight = 90, matchWeight = 5)
    val back = CloudApimWafConfigRef.format.reads(CloudApimWafConfigRef.format.writes(cfg)).get
    assertEquals(back, cfg)
  }

  test("a clean request contributes nothing") {
    val result = EngineResult(Disposition.Continue, List.empty)
    assertEquals(CloudApimWafFabric.signalFor(result, defaults), None)
  }

  test("a match that did not block still contributes — this is the point of the fabric") {
    val result = EngineResult(Disposition.Continue, List(event(942100)))
    val signal = CloudApimWafFabric.signalFor(result, defaults).get
    assertEquals(signal.tag, "waf:match")
    assertEquals(signal.weight, 20)
    assert(signal.detail.get.contains("without blocking"))
    assert(signal.detail.get.contains("942100"))
  }

  test("a block decision contributes the heavier weight") {
    val result = EngineResult(Disposition.Block(403, Some("sqli"), Some(942100)), List(event(942100)))
    val signal = CloudApimWafFabric.signalFor(result, defaults).get
    assertEquals(signal.tag, "waf:blocked")
    assertEquals(signal.weight, 50)
    assertEquals(signal.source, "waf.seclang")
  }

  test("weights are configurable per route") {
    val cfg    = defaults.copy(blockWeight = 100, matchWeight = 1)
    val block  = EngineResult(Disposition.Block(403, None, None), List(event(1)))
    val match_ = EngineResult(Disposition.Continue, List(event(1)))
    assertEquals(CloudApimWafFabric.signalFor(block, cfg).get.weight, 100)
    assertEquals(CloudApimWafFabric.signalFor(match_, cfg).get.weight, 1)
  }

  test("contribution can be switched off without touching the waf's own behaviour") {
    val result = EngineResult(Disposition.Block(403, None, None), List(event(942100)))
    assertEquals(CloudApimWafFabric.signalFor(result, defaults.copy(contribute = false)), None)
  }

  test("rule ids are deduplicated and the detail stays readable") {
    val result = EngineResult(
      Disposition.Continue,
      List(event(1), event(1), event(2), event(3), event(4), event(5), event(6), event(7))
    )
    val detail = CloudApimWafFabric.signalFor(result, defaults).get.detail.get
    assertEquals(detail.count(_ == ','), 4, "at most five rules are named")
    assert(!detail.contains("7"))
  }

  test("an event with no rule id still produces a signal") {
    val result = EngineResult(Disposition.Continue, List(MatchEvent(None, Some("m"), List.empty, 2, "raw")))
    val signal = CloudApimWafFabric.signalFor(result, defaults)
    assert(signal.isDefined)
    assert(!signal.get.detail.get.contains("(rules"))
  }
}
