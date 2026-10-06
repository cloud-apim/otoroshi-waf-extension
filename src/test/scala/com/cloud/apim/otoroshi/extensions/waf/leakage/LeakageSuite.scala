package com.cloud.apim.otoroshi.extensions.waf.leakage

import com.cloud.apim.seclang.model.{ByteString, Headers, RequestContext}

/**
 * DLP-3's detector away from a gateway: what each family of leak looks like in a real response,
 * and what an ordinary response that merely talks about errors looks like.
 */
class LeakageSuite extends munit.FunSuite {

  private def response(body: String, contentType: String = "text/html; charset=utf-8", status: Int = 500) = RequestContext(
    method = "GET",
    uri = "/api/things",
    headers = Headers(Map("Content-Type" -> List(contentType))),
    body = Some(ByteString(body)),
    status = Some(status),
    statusTxt = Some("Internal Server Error")
  )

  private def family(body: String, contentType: String = "text/html; charset=utf-8"): Option[String] =
    LeakageDetector.detect(response(body, contentType), 1).map(_.family)

  test("SQL error text from the database driver") {
    assertEquals(family("You have an error in your SQL syntax; check the manual that corresponds to your MySQL server version for the right syntax to use near ''' at line 1"), Some("sql"))
    assertEquals(family("""{"error":"org.postgresql.util.PSQLException: ERROR: syntax error at or near \"'\""}""", "application/json"), Some("sql"))
  }

  test("a Java stack trace") {
    assertEquals(family("java.lang.NullPointerException: boom\n\tat com.example.web.ThingController.show(ThingController.java:42)\n\tat java.base/java.lang.Thread.run(Thread.java:833)", "text/plain"), Some("java"))
  }

  test("a PHP fatal error") {
    assertEquals(family("<br />\n<b>Fatal error</b>:  Uncaught Error: Call to undefined function foo() in /var/www/html/index.php:3\nStack trace:\n#0 {main}\n  thrown in <b>/var/www/html/index.php</b> on line <b>3</b><br />"), Some("php"))
  }

  test("a directory listing") {
    assertEquals(family("<html><head><title>Index of /backup</title></head><body><h1>Index of /backup</h1><pre><a href=\"../\">../</a></pre></body></html>"), Some("directory_listing"))
  }

  test("what CRS does not cover: Python, Node, Go, .NET, Laravel") {
    assertEquals(family("Traceback (most recent call last):\n  File \"/app/views.py\", line 12, in show\n    return thing.name\nAttributeError: 'NoneType' object has no attribute 'name'", "text/plain"), Some("python"))
    assertEquals(family("<p>You're seeing this error because you have <code>DEBUG = True</code> in your Django settings file.</p>"), Some("python"))
    assertEquals(family("TypeError: Cannot read properties of undefined (reading 'id')\n    at show (/app/src/things.js:14:22)\n    at Layer.handle [as handle_request] (/app/node_modules/express/lib/router/layer.js:95:5)", "text/plain"), Some("node"))
    assertEquals(family("panic: runtime error: invalid memory address or nil pointer dereference\n\ngoroutine 1 [running]:\nmain.main()", "text/plain"), Some("go"))
    assertEquals(family("System.InvalidOperationException: nope\n   at Things.Controllers.ThingController.Show(Int32 id) in /src/Controllers/ThingController.cs:line 42", "text/plain"), Some("dotnet"))
    assertEquals(family("<title>Whoops, looks like something went wrong.</title>"), Some("php"))
  }

  test("server-sent events are not read unless asked for: holding them back stalls every event") {
    import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimErrorLeakageConfig
    assert(!CloudApimErrorLeakageConfig.default.inspects(Some("text/event-stream; charset=utf-8")))
    assert(CloudApimErrorLeakageConfig.default.inspects(Some("text/html; charset=utf-8")))
    assert(CloudApimErrorLeakageConfig(contentTypes = Seq("text/event-stream")).inspects(Some("text/event-stream")))
  }

  test("an ordinary response that talks about errors is not a leak") {
    assertEquals(family("<html><body><h1>Oops</h1><p>An error occurred, please try again later.</p></body></html>"), None)
    assertEquals(family("""{"error":"not_found","message":"No thing with id 42"}""", "application/json"), None)
    assertEquals(family("""{"items":[{"name":"java"},{"name":"python"},{"name":"go"}],"total":3}""", "application/json"), None)
  }
}
