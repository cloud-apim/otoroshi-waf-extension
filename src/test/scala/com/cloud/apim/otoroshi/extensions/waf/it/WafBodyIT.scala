package com.cloud.apim.otoroshi.extensions.waf.it

import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimWaf
import play.api.libs.json.Json

/**
 * The three hardening fixes, through a real gateway.
 *
 * The unit tests prove the reader is bounded and lossless; these prove the plugin is wired to it —
 * that the body a backend receives is the body the client sent, that a request refused for being
 * oversize never reaches the backend, and that response inspection now happens at all.
 */
class WafBodyIT extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val marker = "ATTACKMARKER"
  private val leak   = "LEAKMARKER"

  private def wafConfig(
      rules: Seq[String],
      inputLimit: Option[Long] = None,
      inspectOutput: Boolean = false,
      outputMimetypes: Seq[String] = Seq.empty,
      oversize: String = "inspect_prefix",
      extra: play.api.libs.json.JsObject = Json.obj()
  ): String = Gateway.createWafConfig(
    extra ++ Json.obj(
      "id"                   -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"                 -> "it",
      "description"          -> "",
      "enabled"              -> true,
      "block"                -> true,
      "inspect_input_body"   -> true,
      "inspect_output_body"  -> inspectOutput,
      "input_body_limit"     -> inputLimit,
      "output_body_limit"    -> Json.toJson(Option.empty[Long]),
      "output_body_mimetypes" -> outputMimetypes,
      "oversize_body_action" -> oversize,
      "rules"                -> rules
    )
  )

  private def wafPlugin(ref: String) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimWaf],
    config = NgPluginInstanceConfig(Json.obj("ref" -> ref))
  )

  private def gzip(bytes: ByteString): ByteString = {
    val out = new java.io.ByteArrayOutputStream()
    val gz  = new java.util.zip.GZIPOutputStream(out)
    gz.write(bytes.toArray)
    gz.close()
    ByteString(out.toByteArray)
  }

  private def payload(totalBytes: Int, needle: String, atStart: Boolean): ByteString = {
    val filler = "x" * (totalBytes - needle.length)
    ByteString(if (atStart) needle + filler else filler + needle)
  }

  // -----------------------------------------------------------------------------------------------
  // H1
  // -----------------------------------------------------------------------------------------------

  test("H1 — a body larger than the limit reaches the backend whole") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(
      Seq(s"""SecRule REQUEST_BODY "@contains $marker" "id:9001,phase:2,deny,status:403"""", "SecRuleEngine On"),
      inputLimit = Some(4096L)
    )
    val route   = Gateway.createRoute("h1-forward", backend.port, Seq(wafPlugin(ref)))
    try {
      val body = payload(200 * 1024, marker, atStart = false)
      val res  = Gateway.call(route, "/", "POST", Some(body))
      assertEquals(res.status, 200, "the marker sits past the limit, so nothing should have matched")
      assertEquals(backend.received.get(), body.size.toLong, "the backend must receive every byte that was sent")
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("H1 — a payload inside the limit is still blocked") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(
      Seq(s"""SecRule REQUEST_BODY "@contains $marker" "id:9001,phase:2,deny,status:403"""", "SecRuleEngine On"),
      inputLimit = Some(4096L)
    )
    val route   = Gateway.createRoute("h1-block", backend.port, Seq(wafPlugin(ref)))
    try {
      val res = Gateway.call(route, "/", "POST", Some(payload(200 * 1024, marker, atStart = true)))
      assertEquals(res.status, 403)
      assertEquals(backend.calls.get(), 0L, "a blocked request must never reach the backend")
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("H1 — with reject, a body past the limit is refused and never forwarded") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(Seq("SecRuleEngine On"), inputLimit = Some(4096L), oversize = "reject")
    val route   = Gateway.createRoute("h1-reject", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/", "POST", Some(payload(64 * 1024, "z", atStart = true))).status, 413)
      assertEquals(backend.calls.get(), 0L)
      // and a body under the limit still goes through untouched
      val small = payload(1024, "z", atStart = true)
      assertEquals(Gateway.call(route, "/", "POST", Some(small)).status, 200)
      assertEquals(backend.received.get(), small.size.toLong)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  // -----------------------------------------------------------------------------------------------
  // H2 and H3
  // -----------------------------------------------------------------------------------------------

  test("H2 — a plain GET has its response inspected") {
    val backend = new TestBackend(responseBody = ByteString(s"""{"data":"$leak"}"""))(using
      Gateway.system, Gateway.mat, Gateway.ec
    )
    val ref   = wafConfig(
      Seq(s"""SecRule RESPONSE_BODY "@contains $leak" "id:9002,phase:4,deny,status:403"""", "SecRuleEngine On"),
      inspectOutput = true
    )
    val route = Gateway.createRoute("h2-get", backend.port, Seq(wafPlugin(ref)))
    try {
      // the request carries no body at all; before the fix that alone skipped response inspection
      assertEquals(Gateway.call(route, "/", "GET").status, 403)
      assertEquals(backend.calls.get(), 1L, "the backend is still called — the response is judged after it")
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("H3 — a configured text/html matches a charset-carrying response") {
    val backend = new TestBackend(
      contentType = "text/html; charset=utf-8",
      responseBody = ByteString(s"<html><body>$leak</body></html>")
    )(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref   = wafConfig(
      Seq(s"""SecRule RESPONSE_BODY "@contains $leak" "id:9002,phase:4,deny,status:403"""", "SecRuleEngine On"),
      inspectOutput = true,
      outputMimetypes = Seq("text/html")
    )
    val route = Gateway.createRoute("h3-mime", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/", "GET").status, 403)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("H3 — a type outside the list is left alone") {
    val backend = new TestBackend(
      contentType = "application/json",
      responseBody = ByteString(s"""{"data":"$leak"}""")
    )(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref   = wafConfig(
      Seq(s"""SecRule RESPONSE_BODY "@contains $leak" "id:9002,phase:4,deny,status:403"""", "SecRuleEngine On"),
      inspectOutput = true,
      outputMimetypes = Seq("text/html")
    )
    val route = Gateway.createRoute("h3-skip", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/", "GET").status, 200)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  // -----------------------------------------------------------------------------------------------
  // PRO-3
  // -----------------------------------------------------------------------------------------------

  private val bodyRule = Seq(s"""SecRule REQUEST_BODY "@contains $marker" "id:9001,phase:2,deny,status:403"""", "SecRuleEngine On")

  private def brotli(bytes: ByteString): ByteString =
    ByteString(com.aayushatharva.brotli4j.encoder.Encoder.compress(bytes.toArray))

  private def brotliReady: Boolean = com.aayushatharva.brotli4j.Brotli4jLoader.isAvailable

  // Play's server decodes gzip and deflate request bodies itself, before any plugin: the WAF reads
  // them in clear, and only brotli, zstd and what Play does not know reach it still encoded

  test("PRO-3 — a gzip payload is inspected in clear") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(bodyRule)
    val route   = Gateway.createRoute("pro3-gzip", backend.port, Seq(wafPlugin(ref)))
    try {
      val gz = Seq("Content-Encoding" -> "gzip")
      assertEquals(Gateway.call(route, "/", "POST", Some(gzip(ByteString(s"hello $marker"))), "text/plain", gz).status, 403)
      assertEquals(Gateway.call(route, "/", "POST", Some(gzip(ByteString("hello " * 1000))), "text/plain", gz).status, 200)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("PRO-3 — a brotli payload is read decompressed, and forwarded as it came") {
    assume(brotliReady, "no brotli native library on this platform")
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(bodyRule)
    val route   = Gateway.createRoute("pro3-br", backend.port, Seq(wafPlugin(ref)))
    try {
      val br = Seq("Content-Encoding" -> "br")
      assertEquals(Gateway.call(route, "/", "POST", Some(brotli(ByteString(s"hello $marker"))), "text/plain", br).status, 403)
      assertEquals(backend.calls.get(), 0L)
      val clean = brotli(ByteString("hello " * 1000))
      assertEquals(Gateway.call(route, "/", "POST", Some(clean), "text/plain", br).status, 200)
      assertEquals(backend.received.get(), clean.size.toLong, "the backend gets the compressed bytes, untouched")
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("PRO-3 — a decompression bomb is refused before anything reaches the backend") {
    assume(brotliReady, "no brotli native library on this platform")
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = wafConfig(bodyRule)
    val route   = Gateway.createRoute("pro3-bomb", backend.port, Seq(wafPlugin(ref)))
    try {
      // a few dozen bytes on the wire, 50 MiB once decompressed
      val bomb = brotli(ByteString(new Array[Byte](50 * 1024 * 1024)))
      assertEquals(Gateway.call(route, "/", "POST", Some(bomb), "application/json", Seq("Content-Encoding" -> "br")).status, 413)
      assertEquals(backend.calls.get(), 0L)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }

  test("PRO-3 — an encoding no rule can read is refused, or inspected as it is when configured so") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val strict  = wafConfig(bodyRule)
    val lenient = wafConfig(bodyRule, extra = Json.obj("undecodable_body_action" -> "inspect_raw"))
    val r1      = Gateway.createRoute("pro3-zstd-strict", backend.port, Seq(wafPlugin(strict)))
    val r2      = Gateway.createRoute("pro3-zstd-lenient", backend.port, Seq(wafPlugin(lenient)))
    try {
      val zstd = Seq("Content-Encoding" -> "zstd")
      assertEquals(Gateway.call(r1, "/", "POST", Some(ByteString("opaque")), "text/plain", zstd).status, 415)
      // a body that lies about its encoding is not let through either
      if (brotliReady) assertEquals(Gateway.call(r1, "/", "POST", Some(ByteString("not brotli")), "text/plain", Seq("Content-Encoding" -> "br")).status, 400)
      assertEquals(Gateway.call(r2, "/", "POST", Some(ByteString("opaque")), "text/plain", zstd).status, 200)
    } finally {
      Gateway.deleteRoute(r1); Gateway.deleteRoute(r2); Gateway.deleteWafConfig(strict); Gateway.deleteWafConfig(lenient); backend.stop()
    }
  }

  test("PRO-3 — a compressed response is inspected decompressed") {
    val backend = new TestBackend(responseBody = gzip(ByteString(s"""{"data":"$leak"}""")), responseEncoding = Some("gzip"))(using
      Gateway.system, Gateway.mat, Gateway.ec
    )
    val ref   = wafConfig(
      Seq(s"""SecRule RESPONSE_BODY "@contains $leak" "id:9002,phase:4,deny,status:403"""", "SecRuleEngine On"),
      inspectOutput = true
    )
    val route = Gateway.createRoute("pro3-response", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/", "GET").status, 403)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); backend.stop()
    }
  }
}
