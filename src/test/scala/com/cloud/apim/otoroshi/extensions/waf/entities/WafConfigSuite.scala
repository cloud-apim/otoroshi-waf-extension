package com.cloud.apim.otoroshi.extensions.waf.entities

import play.api.libs.json.Json

/**
 * The three hardening items, seen from the entity: a body limit that always applies, an explicit
 * policy for what is over it, and a MIME comparison that matches the headers real servers send.
 */
class WafConfigSuite extends munit.FunSuite {

  private def config(
      inputLimit: Option[Long] = None,
      outputLimit: Option[Long] = None,
      mimetypes: Seq[String] = Seq.empty,
      oversize: String = CloudApimWafConfig.OversizeInspectPrefix
  ) = CloudApimWafConfig(
    id = "waf-config_test",
    name = "test",
    inputBodyLimit = inputLimit,
    outputBodyLimit = outputLimit,
    outputBodyMimetypes = mimetypes,
    oversizeBodyAction = oversize
  )

  test("an unset limit is the built-in cap, never unbounded") {
    assertEquals(config().effectiveInputBodyLimit, CloudApimWafConfig.defaultBodyLimit)
    assertEquals(config().effectiveOutputBodyLimit, CloudApimWafConfig.defaultBodyLimit)
    assertEquals(CloudApimWafConfig.defaultBodyLimit, 2L * 1024L * 1024L)
  }

  test("a configured limit is used as written") {
    assertEquals(config(inputLimit = Some(512L)).effectiveInputBodyLimit, 512L)
    assertEquals(config(outputLimit = Some(4096L)).effectiveOutputBodyLimit, 4096L)
  }

  test("the oversize policy defaults to inspecting the beginning, not to refusing") {
    assertEquals(config().rejectsOversizeBody, false)
    assertEquals(config(oversize = "reject").rejectsOversizeBody, true)
    assertEquals(config(oversize = "  REJECT ").rejectsOversizeBody, true)
  }

  test("a configured text/html matches the header a real server sends — this is H3") {
    val cfg = config(mimetypes = Seq("text/html"))
    assertEquals(cfg.inspectsContentType(Some("text/html; charset=utf-8")), true)
    assertEquals(cfg.inspectsContentType(Some("text/html")), true)
    assertEquals(cfg.inspectsContentType(Some("application/json")), false)
  }

  test("an empty MIME list means every type") {
    assertEquals(config().inspectsContentType(Some("image/png")), true)
    assertEquals(config().inspectsContentType(None), true)
  }

  test("a response with no content type is still evaluated, as it was before") {
    assertEquals(config(mimetypes = Seq("text/html")).inspectsContentType(None), true)
  }

  test("subtype wildcards work in the list") {
    val cfg = config(mimetypes = Seq("text/*"))
    assertEquals(cfg.inspectsContentType(Some("text/plain; charset=iso-8859-1")), true)
    assertEquals(cfg.inspectsContentType(Some("application/json")), false)
  }

  test("a config written before the cap existed reads with safe defaults") {
    val old = CloudApimWafConfig.format
      .reads(Json.obj("id" -> "waf-config_x", "name" -> "old", "description" -> "", "rules" -> Json.arr()))
      .get
    assertEquals(old.oversizeBodyAction, CloudApimWafConfig.OversizeInspectPrefix)
    assertEquals(old.effectiveInputBodyLimit, CloudApimWafConfig.defaultBodyLimit)
    assertEquals(old.rejectsOversizeBody, false, "an existing config must not start refusing uploads")
  }

  test("an unknown oversize action degrades to inspecting, never to refusing") {
    val cfg = CloudApimWafConfig.format
      .reads(Json.obj("id" -> "x", "name" -> "n", "description" -> "", "oversize_body_action" -> "explode"))
      .get
    assertEquals(cfg.oversizeBodyAction, CloudApimWafConfig.OversizeInspectPrefix)
  }

  test("the config round-trips") {
    val cfg  = config(inputLimit = Some(1024L), mimetypes = Seq("text/html"), oversize = "reject")
    val back = CloudApimWafConfig.format.reads(cfg.json).get
    assertEquals(back.inputBodyLimit, Some(1024L))
    assertEquals(back.outputBodyMimetypes, Seq("text/html"))
    assertEquals(back.oversizeBodyAction, "reject")
  }
}
