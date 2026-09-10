package com.cloud.apim.otoroshi.extensions.waf.tuning

import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang
import com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset

/**
 * The engine guarantees the tuning assistant is built on.
 *
 * Every proposal it generates is one of these four directives, and each of them silently did
 * nothing against the embedded CRS until seclang-engine 2.2.0 — an exclusion that compiles, writes
 * cleanly to a ruleset and changes no behaviour is the worst possible outcome for a feature whose
 * whole purpose is to let people stop turning the WAF off. This suite fails loudly if a future
 * engine bump takes any of them away.
 */
class EngineSanitySuite extends munit.FunSuite {

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

  private val sqli = "1' or 1=1--"

  private def run(extra: List[String], arg: String = "comment") =
    factory.engine("SecRuleEngine On" :: "@import_preset crs" :: extra)
      .evaluate(request(Map(arg -> List(sqli))), List(1, 2, 5))

  private def fires(extra: List[String], arg: String = "comment"): Boolean =
    run(extra, arg).events.flatMap(_.ruleId).contains(942100)

  test("the rule fires with no exclusion") {
    assert(fires(Nil))
  }

  test("logdata names the parameter that matched") {
    val logs = run(Nil).events.filter(_.ruleId.contains(942100)).flatMap(_.logs)
    logs.foreach(l => println(s"LOGDATA >>> $l"))
    assert(logs.exists(_.contains("ARGS:comment")), s"no parameter name in $logs")
  }

  test("SecRuleUpdateTargetById reaches the preset, and only the named parameter") {
    val excl = List("""SecRuleUpdateTargetById 942100 "!ARGS:comment"""")
    assert(!fires(excl), "the exclusion did not take effect")
    assert(fires(excl, arg = "q"), "the exclusion shielded a parameter it was not aimed at")
  }

  test("ctl:ruleRemoveTargetById reaches the preset, scoped to its condition") {
    val excl = List(
      """SecRule REQUEST_URI "@beginsWith /search" "id:50001,phase:1,pass,nolog,ctl:ruleRemoveTargetById=942100;ARGS:comment""""
    )
    assert(!fires(excl))
    assert(fires(excl, arg = "q"))
  }

  test("SecRuleRemoveById reaches the preset, for every parameter") {
    val excl = List("SecRuleRemoveById 942100")
    assert(!fires(excl))
    assert(!fires(excl, arg = "q"))
  }
}
