package com.cloud.apim.otoroshi.extensions.waf.feeds

import com.cloud.apim.seclang.impl.engine.SecLangEngine
import com.cloud.apim.seclang.model.{ByteString, Disposition, Headers, RequestContext}
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, MessageDigest, PublicKey, Signature}
import java.util.Base64
import scala.util.Try

/**
 * One request a pack must block, or let through (WAF-2).
 *
 * A pack carries its own tests, and a pack whose tests fail against this gateway's engine is never
 * installed: the rules are checked where they will run, not where they were written.
 */
final case class RulePackTest(name: String, method: String, uri: String, headers: Map[String, Seq[String]], body: Option[String], expectBlock: Boolean) {
  def request: RequestContext = {
    // the engine reads ARGS from `query`, not from the uri: a test's query string is split here
    val query = uri.split('?').lift(1).toSeq.flatMap(_.split('&')).filter(_.nonEmpty).map { pair =>
      val i = pair.indexOf('=')
      def decode(s: String) = Try(java.net.URLDecoder.decode(s, "UTF-8")).getOrElse(s)
      if (i < 0) decode(pair) -> "" else decode(pair.substring(0, i)) -> decode(pair.substring(i + 1))
    }
    RequestContext(
      method = method,
      uri = uri,
      headers = Headers((Map("Host" -> Seq("example.com")) ++ headers).map { case (k, v) => k -> v.toList }),
      cookies = Map.empty,
      query = query.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2).toList },
      body = body.map(b => ByteString(b)),
      status = None,
      statusTxt = None,
      remoteAddr = "203.0.113.10",
      remotePort = 40000,
      protocol = "HTTP/1.1"
    )
  }
}

/**
 * A named body of rules a feed delivers: a curated pack (WAF-2) or a virtual patch for one
 * vulnerability (WAF-3). `requires: ["crs"]` runs its tests with the Core Rule Set loaded first.
 */
final case class RulePack(
    id: String,
    name: String,
    kind: String,
    description: String,
    references: Seq[String],
    requires: Seq[String],
    rules: Seq[String],
    tests: Seq[RulePackTest]
) {
  def summary: JsValue = Json.obj(
    "id"          -> id,
    "name"        -> name,
    "kind"        -> kind,
    "description" -> description,
    "references"  -> references,
    "requires"    -> requires,
    "rules"       -> rules.size,
    "tests"       -> tests.size
  )
}

/** A version of a feed: what it says it is, and the packs it carries. */
final case class RuleBundle(feed: String, version: String, publishedAt: Long, packs: Seq[RulePack])

/** What checking a pack against the local engine found. */
final case class PackCheck(id: String, name: String, errors: Seq[String], tests: Int, passed: Int) {
  def ok: Boolean   = errors.isEmpty
  def json: JsValue = Json.obj("id" -> id, "name" -> name, "ok" -> ok, "errors" -> errors, "tests" -> tests, "passed" -> passed)
}

/** A bundle as fetched: who signed it, the bundle itself, and its exact bytes. */
final case class VerifiedBundle(bundle: RuleBundle, signedBy: Option[String], raw: String)

object RuleBundles {

  // the DER header of an Ed25519 SubjectPublicKeyInfo: a bare 32-byte key is this plus the key
  private val spkiPrefix = Array[Byte](0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

  private val packId = "^[a-z0-9][a-z0-9_-]{0,63}$".r

  /** An Ed25519 public key from a PEM block, a base64 SubjectPublicKeyInfo, or a base64 raw key. */
  def publicKey(text: String): Option[PublicKey] = Try {
    val cleaned = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "")
    val bytes   = Base64.getDecoder.decode(cleaned)
    val der     = if (bytes.length == 32) spkiPrefix ++ bytes else bytes
    KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der))
  }.toOption

  /** A short, stable name for a key, for events and for the console: the start of its SHA-256. */
  def fingerprint(key: PublicKey): String =
    MessageDigest.getInstance("SHA-256").digest(key.getEncoded).take(8).map("%02x".format(_)).mkString

  def verify(key: PublicKey, data: Array[Byte], signature: Array[Byte]): Boolean = Try {
    val s = Signature.getInstance("Ed25519")
    s.initVerify(key)
    s.update(data)
    s.verify(signature)
  }.getOrElse(false)

  /**
   * Opens what a feed served.
   *
   * A signed feed serves an envelope, `{"bundle": "<base64>", "signatures": [{"key_id", "signature"}]}`,
   * and the bundle is trusted when one of its signatures verifies with one of `trustedKeys`, over its
   * exact bytes. A bare bundle is only accepted when the feed says unsigned is fine.
   */
  def open(served: String, trustedKeys: Seq[String], allowUnsigned: Boolean): Either[String, VerifiedBundle] = {
    val json = Try(Json.parse(served)).toOption
    json match {
      case None                                                     => Left("the feed did not serve JSON")
      case Some(envelope) if (envelope \ "bundle").isDefined        =>
        val raw = (envelope \ "bundle").asOpt[String].flatMap(b => Try(Base64.getDecoder.decode(b.trim)).toOption)
        raw match {
          case None        => Left("the envelope's bundle is not base64")
          case Some(bytes) =>
            val keys       = trustedKeys.flatMap(publicKey)
            val signatures = (envelope \ "signatures").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap { s =>
              (s \ "signature").asOpt[String].flatMap(sig => Try(Base64.getDecoder.decode(sig.trim)).toOption)
            }
            val signer     = keys.find(key => signatures.exists(sig => verify(key, bytes, sig))).map(fingerprint)
            if (signer.isEmpty && !allowUnsigned) {
              if (keys.isEmpty) Left("the feed has no trusted key to check the bundle's signature with")
              else Left("no signature of the bundle verifies with a trusted key")
            } else parse(new String(bytes, StandardCharsets.UTF_8)).map(b => VerifiedBundle(b, signer, new String(bytes, StandardCharsets.UTF_8)))
        }
      case Some(_) if allowUnsigned                                 => parse(served).map(b => VerifiedBundle(b, None, served))
      case Some(_)                                                  => Left("the feed served an unsigned bundle, and only signed ones are accepted")
    }
  }

  /** A bundle's JSON, every pack checked for what an installed pack needs. */
  def parse(raw: String): Either[String, RuleBundle] =
    Try(Json.parse(raw)).toOption.toRight("the bundle is not JSON").flatMap { json =>
      val packs = (json \ "packs").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { p =>
        RulePack(
          id = (p \ "id").asOpt[String].getOrElse(""),
          name = (p \ "name").asOpt[String].getOrElse((p \ "id").asOpt[String].getOrElse("")),
          kind = (p \ "kind").asOpt[String].filter(Set("pack", "virtual_patch")).getOrElse("pack"),
          description = (p \ "description").asOpt[String].getOrElse(""),
          references = (p \ "references").asOpt[Seq[String]].getOrElse(Seq.empty),
          requires = (p \ "requires").asOpt[Seq[String]].getOrElse(Seq.empty),
          rules = (p \ "rules").asOpt[Seq[String]].getOrElse(Seq.empty),
          tests = (p \ "tests").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { t =>
            val request = (t \ "request").asOpt[JsObject].getOrElse(Json.obj())
            RulePackTest(
              name = (t \ "name").asOpt[String].getOrElse("unnamed"),
              method = (request \ "method").asOpt[String].getOrElse("GET"),
              uri = (request \ "uri").asOpt[String].getOrElse("/"),
              headers = (request \ "headers").asOpt[JsObject].map(_.value.toMap.map {
                case (k, JsArray(vs)) => k -> vs.toSeq.flatMap(_.asOpt[String])
                case (k, v)           => k -> v.asOpt[String].toSeq
              }).getOrElse(Map.empty),
              body = (request \ "body").asOpt[String],
              expectBlock = (t \ "expect").asOpt[String].exists(_.trim.equalsIgnoreCase("block"))
            )
          }
        )
      }
      val version = (json \ "version").asOpt[String].map(_.trim).filter(_.nonEmpty)
      val bad     = packs.find(p => packId.findFirstIn(p.id).isEmpty)
      val dupe    = packs.groupBy(_.id).collectFirst { case (id, ps) if ps.size > 1 => id }
      if (version.isEmpty) Left("the bundle has no version")
      else if (packs.isEmpty) Left("the bundle carries no pack")
      else if (bad.isDefined) Left(s"pack id '${bad.get.id}' is not lowercase letters, digits, - and _")
      else if (dupe.isDefined) Left(s"pack id '${dupe.get}' appears twice")
      else
        Right(
          RuleBundle(
            feed = (json \ "feed").asOpt[String].getOrElse(""),
            version = version.get,
            publishedAt = (json \ "published_at").asOpt[Long].getOrElse(0L),
            packs = packs
          )
        )
    }

  /**
   * Builds each pack where it will run and runs its tests.
   *
   * `engine` builds an engine from rules, throwing on what does not parse or compile, the way the
   * extension's own factory does. A pack that does not build, or whose tests disagree with it, fails.
   */
  def check(packs: Seq[RulePack], engine: Seq[String] => SecLangEngine): Seq[PackCheck] =
    packs.map { pack =>
      // the engine is switched on after CRS is imported: CRS's own setup leaves it detection-only
      val head  = Option.when(pack.requires.exists(_.equalsIgnoreCase("crs")))("@import_preset crs").toSeq :+ "SecRuleEngine On"
      Try(engine(head ++ pack.rules)) match {
        case scala.util.Failure(e)     => PackCheck(pack.id, pack.name, Seq(s"does not compile: ${Option(e.getMessage).getOrElse(e.toString)}"), pack.tests.size, 0)
        case scala.util.Success(built) =>
          if (pack.rules.isEmpty) PackCheck(pack.id, pack.name, Seq("carries no rule"), pack.tests.size, 0)
          else {
            val results = pack.tests.map { test =>
              val blocked = Try(built.evaluate(test.request, List(1, 2)).disposition).toOption.exists {
                case _: Disposition.Block => true
                case _                    => false
              }
              Option.when(blocked != test.expectBlock)(
                s"test '${test.name}' expected the request to be ${if (test.expectBlock) "blocked" else "let through"}, it was ${if (blocked) "blocked" else "let through"}"
              )
            }
            PackCheck(pack.id, pack.name, results.flatten, pack.tests.size, results.count(_.isEmpty))
          }
      }
    }
}
