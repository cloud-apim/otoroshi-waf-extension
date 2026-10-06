package com.cloud.apim.otoroshi.extensions.waf.feeds

import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang
import com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.security.{KeyPair, KeyPairGenerator, Signature}
import java.util.Base64

/** Signs bundles the way a feed's publisher does, for the tests here and in the gateway ones. */
object TestSigner {

  def keys(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

  /** The public key as the feed's `trusted_keys` take it: base64 of its SubjectPublicKeyInfo. */
  def publicKey(keys: KeyPair): String = Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)

  def envelope(bundle: JsValue, keys: KeyPair*): String = {
    val bytes      = Json.stringify(bundle).getBytes(StandardCharsets.UTF_8)
    val signatures = keys.map { k =>
      val s = Signature.getInstance("Ed25519")
      s.initSign(k.getPrivate)
      s.update(bytes)
      Json.obj("key_id" -> "test", "signature" -> Base64.getEncoder.encodeToString(s.sign()))
    }
    Json.stringify(Json.obj("bundle" -> Base64.getEncoder.encodeToString(bytes), "signatures" -> JsArray(signatures)))
  }

  /** A pack refusing a path traversal in any argument, with a test of each kind. */
  def lfiPack(extraRules: Seq[String] = Seq.empty, expectOnTraversal: String = "block"): JsObject = Json.obj(
    "id"          -> "lfi-guard",
    "name"        -> "Path traversal guard",
    "kind"        -> "pack",
    "description" -> "Refuses ../ in arguments",
    "references"  -> Json.arr("https://owasp.org/www-community/attacks/Path_Traversal"),
    "rules"       -> (Seq("""SecRule ARGS "@contains ../" "id:9100001,phase:2,deny,status:403,msg:'path traversal'"""") ++ extraRules),
    "tests"       -> Json.arr(
      Json.obj("name" -> "traversal", "request" -> Json.obj("method" -> "GET", "uri" -> "/file?f=../../etc/passwd"), "expect" -> expectOnTraversal),
      Json.obj("name" -> "plain file", "request" -> Json.obj("method" -> "GET", "uri" -> "/file?f=report.pdf"), "expect" -> "pass")
    )
  )

  def bundle(version: String, publishedAt: Long, packs: JsObject*): JsObject =
    Json.obj("format" -> 1, "feed" -> "test", "version" -> version, "published_at" -> publishedAt, "packs" -> JsArray(packs))
}

/**
 * WAF-2 and WAF-3 without a gateway: what a signature proves and what it does not, what makes a
 * bundle unfit, and pack tests run against the local engine.
 */
class FeedSuite extends munit.FunSuite {

  import TestSigner.*

  private val factory = SecLang.factory(Map("crs" -> EmbeddedCRSPreset.embedded), SecLangEngineConfig.default, new NoLogSecLangIntegration())
  private val engine  = (rules: Seq[String]) => factory.engine(rules.toList)

  private val signer   = keys()
  private val stranger = keys()
  private val b1       = bundle("2026.10.06-1", 1000L, lfiPack())

  test("a bundle signed by a trusted key opens, and says who signed it") {
    val opened = RuleBundles.open(envelope(b1, signer), Seq(publicKey(signer)), allowUnsigned = false)
    assertEquals(opened.map(_.bundle.version), Right("2026.10.06-1"))
    assertEquals(opened.toOption.flatMap(_.signedBy), RuleBundles.publicKey(publicKey(signer)).map(RuleBundles.fingerprint))
  }

  test("a trusted key is read from SPKI base64, a bare 32-byte key, or PEM") {
    val spki = signer.getPublic.getEncoded
    val raw  = Base64.getEncoder.encodeToString(spki.takeRight(32))
    val pem  = s"-----BEGIN PUBLIC KEY-----\n${Base64.getMimeEncoder.encodeToString(spki)}\n-----END PUBLIC KEY-----"
    Seq(raw, pem).foreach { key =>
      assert(RuleBundles.open(envelope(b1, signer), Seq(key), allowUnsigned = false).isRight, key)
    }
  }

  test("a stranger's signature, a tampered bundle, or no trusted key, are refused") {
    assert(RuleBundles.open(envelope(b1, stranger), Seq(publicKey(signer)), allowUnsigned = false).left.exists(_.contains("no signature")))
    val tampered = {
      val env    = Json.parse(envelope(b1, signer)).as[JsObject]
      val forged = Base64.getEncoder.encodeToString(Json.stringify(bundle("2026.10.06-1", 1000L, lfiPack(Seq("SecAction \"id:9100002,phase:1,pass\"")))).getBytes)
      Json.stringify(env ++ Json.obj("bundle" -> forged))
    }
    assert(RuleBundles.open(tampered, Seq(publicKey(signer)), allowUnsigned = false).isLeft, "the bundle changed after it was signed")
    assert(RuleBundles.open(envelope(b1, signer), Seq.empty, allowUnsigned = false).left.exists(_.contains("no trusted key")))
  }

  test("an unsigned bundle opens only where unsigned is allowed") {
    assert(RuleBundles.open(Json.stringify(b1), Seq(publicKey(signer)), allowUnsigned = false).left.exists(_.contains("unsigned")))
    assertEquals(RuleBundles.open(Json.stringify(b1), Seq.empty, allowUnsigned = true).map(_.signedBy), Right(None))
  }

  test("a bundle without a version, without packs, or with a bad or repeated pack id is unfit") {
    assert(RuleBundles.parse(Json.stringify(b1 - "version")).isLeft)
    assert(RuleBundles.parse(Json.stringify(b1 ++ Json.obj("packs" -> Json.arr()))).isLeft)
    assert(RuleBundles.parse(Json.stringify(bundle("v", 1L, lfiPack() ++ Json.obj("id" -> "Bad Id")))).left.exists(_.contains("Bad Id")))
    assert(RuleBundles.parse(Json.stringify(bundle("v", 1L, lfiPack(), lfiPack()))).left.exists(_.contains("twice")))
  }

  test("a pack is compiled and its tests run against the local engine") {
    val parsed = RuleBundles.parse(Json.stringify(b1)).toOption.get
    val checks = RuleBundles.check(parsed.packs, engine)
    assertEquals(checks.map(c => (c.id, c.ok, c.tests, c.passed)), Seq(("lfi-guard", true, 2, 2)))
  }

  test("a pack whose tests disagree with its rules, or that does not compile, fails") {
    val wrong   = RuleBundles.parse(Json.stringify(bundle("v", 1L, lfiPack(expectOnTraversal = "pass")))).toOption.get
    val checked = RuleBundles.check(wrong.packs, engine).head
    assert(!checked.ok && checked.errors.head.contains("expected the request to be let through, it was blocked"), checked.errors.toString)
    val broken  = RuleBundles.parse(Json.stringify(bundle("v", 1L, lfiPack(Seq("SecRule NOT_A_VARIABLE \"@rx (\" \"id:9100003\""))))).toOption.get
    assert(!RuleBundles.check(broken.packs, engine).head.ok)
  }

  test("a pack that requires CRS runs its tests with CRS loaded") {
    val needsCrs = Json.obj(
      "id"       -> "crs-tuned",
      "name"     -> "CRS tuned",
      "requires" -> Json.arr("crs"),
      "rules"    -> Json.arr("SecRuleRemoveById 920350"),
      "tests"    -> Json.arr(Json.obj("name" -> "sqli", "request" -> Json.obj("method" -> "GET", "uri" -> "/?q=1'%20or%201=1--"), "expect" -> "block"))
    )
    val parsed   = RuleBundles.parse(Json.stringify(bundle("v", 1L, needsCrs))).toOption.get
    assert(RuleBundles.check(parsed.packs, engine).head.ok, RuleBundles.check(parsed.packs, engine).head.errors.toString)
  }
}
