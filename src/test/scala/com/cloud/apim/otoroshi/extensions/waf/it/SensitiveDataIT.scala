package com.cloud.apim.otoroshi.extensions.waf.it

import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimSecuritySuitePreset, CloudApimSensitiveDataGuard}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration.*
import scala.util.Try

/**
 * DLP-1 and DLP-2 through a real gateway: values masked in place with the length kept, the whole of
 * a large body read and not only its head, a compressed body read and sent decoded, a private key
 * refusing the response, monitoring changing nothing, and the preset laying the guard down.
 */
class SensitiveDataIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private val privateKey = "-----BEGIN PRIVATE KEY-----\\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\\n-----END PRIVATE KEY-----"

  private def guard(config: JsObject = Json.obj()) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimSensitiveDataGuard],
    config = NgPluginInstanceConfig(config)
  )

  private def gzip(bytes: ByteString): ByteString = {
    val out = new java.io.ByteArrayOutputStream()
    val gz  = new java.util.zip.GZIPOutputStream(out)
    gz.write(bytes.toArray)
    gz.close()
    ByteString(out.toByteArray)
  }

  private def backend(body: ByteString, contentType: String = "application/json", encoding: Option[String] = None) =
    new TestBackend(contentType = contentType, responseBody = body, responseEncoding = encoding)(using Gateway.system, Gateway.mat, Gateway.ec)

  private def withRoute[A](name: String, body: ByteString, plugins: Seq[NgPluginInstance], contentType: String = "application/json", encoding: Option[String] = None)(
      f: otoroshi.next.models.NgRoute => A
  ): A = {
    val b     = backend(body, contentType, encoding)
    val route = Gateway.createRoute(name, b.port, plugins)
    try f(route)
    finally {
      Gateway.deleteRoute(route); b.stop()
    }
  }

  test("a card and an IBAN are masked in place: same length, still JSON") {
    val body = ByteString("""{"pan":"4111 1111 1111 1111","iban":"FR76 3000 6000 0112 3456 7890 189","name":"Zoé"}""")
    withRoute("dlp-mask", body, Seq(guard())) { route =>
      val res = Gateway.call(route)
      assertEquals(res.status, 200)
      assertEquals((res.json \ "pan").as[String], "4111 **** **** 1111")
      assertEquals((res.json \ "iban").as[String], "FR76 **** **** **** **** ***0 189")
      assertEquals((res.json \ "name").as[String], "Zoé")
      assertEquals(res.header("Content-Length").map(_.toInt), Some(body.size))
    }
  }

  test("the whole of a large body is read, not only its head") {
    val rows = (1 to 20000).map(i => s"""{"id":$i,"pan":"4111111111111111"}""")
    val body = ByteString(rows.mkString("[", ",", "]"))
    assert(body.size > 512 * 1024)
    withRoute("dlp-large", body, Seq(guard())) { route =>
      val res = Gateway.call(route)
      assertEquals(res.status, 200)
      assertEquals(res.bodyAsBytes.size, body.size)
      assert(!res.body.contains("4111111111111111"), "a card got through")
      assertEquals("4111\\*{8}1111".r.findAllIn(res.body).size, 20000)
    }
  }

  test("a compressed body is read decoded, and sent decoded") {
    val body = ByteString("""{"pan":"5555555555554444"}""")
    withRoute("dlp-gzip", gzip(body), Seq(guard()), encoding = Some("gzip")) { route =>
      val res = Gateway.call(route)
      assertEquals(res.status, 200)
      assertEquals(res.header("Content-Encoding"), None)
      assertEquals((res.json \ "pan").as[String], "5555********4444")
    }
  }

  test("a private key refuses the response with a neutral error") {
    val body = ByteString(s"""{"key":"$privateKey"}""")
    withRoute("dlp-block", body, Seq(guard())) { route =>
      val res = Gateway.call(route)
      assertEquals(res.status, 500)
      assertEquals((res.json \ "error").as[String], "internal_error")
      assert(!res.body.contains("MIIE"))
    }
  }

  test("a private key past the head cuts the response before it is sent") {
    val filler = (1 to 3000).map(i => s"""{"id":$i}""").mkString(",")
    val body   = ByteString(s"""{"rows":[$filler],"key":"$privateKey"}""")
    withRoute("dlp-cut", body, Seq(guard(Json.obj("body_limit" -> 1024)))) { route =>
      Try(Gateway.call(route)).foreach { res =>
        assert(!res.body.contains("MIIE"), "the key got through")
      }
    }
  }

  test("in monitor mode everything goes through as it is") {
    val body = ByteString(s"""{"pan":"4111111111111111","key":"$privateKey"}""")
    withRoute("dlp-monitor", body, Seq(guard(Json.obj("mode" -> "monitor")))) { route =>
      val res = Gateway.call(route)
      assertEquals(res.status, 200)
      assertEquals(res.body, body.utf8String)
    }
  }

  test("server-sent events go through untouched") {
    val body = ByteString("data: 4111111111111111\n\n")
    withRoute("dlp-sse", body, Seq(guard()), contentType = "text/event-stream") { route =>
      assertEquals(Gateway.call(route).body, body.utf8String)
    }
  }

  test("the preset lays the guard down when sensitive_data is on, and not otherwise") {
    val body = ByteString("""{"pan":"4111111111111111"}""")
    def preset(on: Boolean) = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[CloudApimSecuritySuitePreset],
      config = NgPluginInstanceConfig(
        Json.obj(
          "gate"                     -> false,
          "bots"                     -> false,
          "reputation"               -> false,
          "waf"                      -> false,
          "response"                 -> false,
          "sensitive_data"           -> on,
          "sensitive_data_detectors" -> Json.obj("card" -> "mask")
        )
      )
    )
    withRoute("dlp-preset-on", body, Seq(preset(true))) { route =>
      assertEquals((Gateway.call(route).json \ "pan").as[String], "4111********1111")
    }
    withRoute("dlp-preset-off", body, Seq(preset(false))) { route =>
      assertEquals((Gateway.call(route).json \ "pan").as[String], "4111111111111111")
    }
  }
}
