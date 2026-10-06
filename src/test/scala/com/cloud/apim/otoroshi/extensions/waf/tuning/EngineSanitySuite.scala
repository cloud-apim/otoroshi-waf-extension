package com.cloud.apim.otoroshi.extensions.waf.tuning

import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang

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
    Map("crs" -> com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset.embedded),
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

  // the embedded preset keys its data files by their path in the jar, its rules name them bare: until
  // seclang-engine 2.5.1 found them under both, no CRS rule reading a data file ever fired
  test("a CRS rule reading a data file fires: a scanner's user agent is seen") {
    val scan = RequestContext(
      method = "GET",
      uri = "/",
      headers = Headers(Map("Host" -> List("example.com"), "Accept" -> List("*/*"), "User-Agent" -> List("sqlmap/1.7.2#stable (https://sqlmap.org)"))),
      remoteAddr = "1.2.3.4",
      protocol = "HTTP/1.1"
    )
    val res = factory.engine(List("SecRuleEngine DetectionOnly", "@import_preset crs")).evaluate(scan, List(1, 2))
    assert(res.events.flatMap(_.ruleId).contains(913100), s"913100 did not fire: ${res.events.flatMap(_.ruleId)}")
  }

  test("the rule fires with no exclusion") {
    assert(fires(Nil))
  }

  // until seclang-engine 2.5.0, only application/json and text/json were split into ARGS, so an
  // injection in a JSON:API or problem+json body got past every CRS rule that reads ARGS
  test("a +json body is inspected like a json one") {
    Seq("application/json", "application/vnd.api+json", "application/problem+json", "application/x-amz-json-1.1").foreach { ct =>
      val post = RequestContext(
        method = "POST",
        uri = "/api/comments",
        headers = Headers(Map("Host" -> List("example.com"), "Content-Type" -> List(ct))),
        body = Some(ByteString(s"""{"comment":"$sqli"}""")),
        remoteAddr = "1.2.3.4",
        protocol = "http/1.1"
      )
      val res = factory.engine(List("SecRuleEngine On", "@import_preset crs")).evaluate(post, List(1, 2, 5))
      assert(res.events.flatMap(_.ruleId).contains(942100), s"$ct: the injection in the body was not seen")
    }
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
