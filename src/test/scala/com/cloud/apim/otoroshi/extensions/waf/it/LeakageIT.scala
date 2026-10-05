package com.cloud.apim.otoroshi.extensions.waf.it

import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimErrorLeakageGuard, CloudApimSecuritySuitePreset}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration.*

/**
 * DLP-3 through a real gateway: what leaks is replaced by a neutral error in the response's own
 * shape, what does not leak goes through untouched, monitoring only reports, a compressed leak is
 * read decompressed, and the preset lays the guard down when told to.
 */
class LeakageIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private val javaTrace =
    "<html><body><h1>HTTP Status 500</h1><pre>java.lang.NullPointerException: boom\n\tat com.example.web.ThingController.show(ThingController.java:42)</pre></body></html>"
  private val sqlError  = """{"error":"You have an error in your SQL syntax; check the manual that corresponds to your MySQL server version"}"""

  private def guard(config: JsObject = Json.obj()) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimErrorLeakageGuard],
    config = NgPluginInstanceConfig(config)
  )

  private def gzip(bytes: ByteString): ByteString = {
    val out = new java.io.ByteArrayOutputStream()
    val gz  = new java.util.zip.GZIPOutputStream(out)
    gz.write(bytes.toArray)
    gz.close()
    ByteString(out.toByteArray)
  }

  private def backend(status: Int, contentType: String, body: ByteString, encoding: Option[String] = None) =
    new TestBackend(status = status, contentType = contentType, responseBody = body, responseEncoding = encoding)(using
      Gateway.system, Gateway.mat, Gateway.ec
    )

  test("a stack trace in an error page becomes a neutral error, with the request's reference") {
    val b     = backend(500, "text/html; charset=utf-8", ByteString(javaTrace))
    val route = Gateway.createRoute("dlp3-java", b.port, Seq(guard()))
    try {
      val res = Gateway.call(route)
      assertEquals(res.status, 500)
      assert(!res.body.contains("NullPointerException"), res.body)
      assert(res.body.contains("Something went wrong") && res.body.contains("Reference: "), res.body)
      assert(res.header("Content-Type").exists(_.startsWith("text/html")))
    } finally {
      Gateway.deleteRoute(route); b.stop()
    }
  }

  test("a success that leaks a SQL error becomes a 500, in JSON for a JSON response") {
    val b     = backend(200, "application/json", ByteString(sqlError))
    val route = Gateway.createRoute("dlp3-sql", b.port, Seq(guard()))
    try {
      val res = Gateway.call(route)
      assertEquals(res.status, 500)
      assertEquals((res.json \ "error").as[String], "internal_error")
      assert((res.json \ "reference").as[String].nonEmpty)
      assert(!res.body.contains("SQL syntax"))
    } finally {
      Gateway.deleteRoute(route); b.stop()
    }
  }

  test("a response that does not leak goes through untouched") {
    val body  = ByteString("""{"error":"not_found","message":"No thing with id 42"}""")
    val b     = backend(404, "application/json", body)
    val route = Gateway.createRoute("dlp3-clean", b.port, Seq(guard()))
    try {
      val res = Gateway.call(route)
      assertEquals(res.status, 404)
      assertEquals(res.body, body.utf8String)
    } finally {
      Gateway.deleteRoute(route); b.stop()
    }
  }

  test("in monitor mode a leak is reported and goes through as it is") {
    val b     = backend(500, "text/html; charset=utf-8", ByteString(javaTrace))
    val route = Gateway.createRoute("dlp3-monitor", b.port, Seq(guard(Json.obj("mode" -> "monitor"))))
    try {
      val res = Gateway.call(route)
      assertEquals(res.status, 500)
      assert(res.body.contains("NullPointerException"))
    } finally {
      Gateway.deleteRoute(route); b.stop()
    }
  }

  test("a compressed leak is read decompressed, and masked") {
    val b     = backend(500, "text/html; charset=utf-8", gzip(ByteString(javaTrace)), encoding = Some("gzip"))
    val route = Gateway.createRoute("dlp3-gzip", b.port, Seq(guard()))
    try {
      val res = Gateway.call(route)
      assertEquals(res.status, 500)
      assert(res.body.contains("Something went wrong"), res.body)
      assertEquals(res.header("Content-Encoding"), None)
    } finally {
      Gateway.deleteRoute(route); b.stop()
    }
  }

  test("the preset lays the guard down when error_leakage is on, and not otherwise") {
    val b   = backend(500, "text/html; charset=utf-8", ByteString(javaTrace))
    def preset(on: Boolean) = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[CloudApimSecuritySuitePreset],
      config = NgPluginInstanceConfig(
        Json.obj("gate" -> false, "bots" -> false, "reputation" -> false, "waf" -> false, "response" -> false, "error_leakage" -> on)
      )
    )
    val on  = Gateway.createRoute("dlp3-preset-on", b.port, Seq(preset(true)))
    val off = Gateway.createRoute("dlp3-preset-off", b.port, Seq(preset(false)))
    try {
      assert(Gateway.call(on).body.contains("Something went wrong"))
      assert(Gateway.call(off).body.contains("NullPointerException"))
    } finally {
      Gateway.deleteRoute(on); Gateway.deleteRoute(off); b.stop()
    }
  }
}
