package com.cloud.apim.otoroshi.extensions.waf.challenge

import play.api.libs.json.*

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/**
 * The proof-of-work primitives.
 *
 * Deliberately small and free of any gateway concern, so the whole scheme can be reasoned about and
 * tested on its own: a puzzle is a string plus a difficulty, a solution is a nonce, and clearance is
 * an HMAC-signed token. Nothing here touches a request.
 */
object Pow {

  private def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  def sha256Hex(str: String): String =
    sha256(str.getBytes("UTF-8")).map("%02x".format(_)).mkString

  /** How many leading zero bits a hex digest starts with — the difficulty measure. */
  def leadingZeroBits(hexHash: String): Int = {
    var bits = 0
    var i    = 0
    var done = false
    while (i < hexHash.length && !done) {
      Try(Integer.parseInt(hexHash.charAt(i).toString, 16)).toOption match {
        case None       => done = true
        case Some(nib)  =>
          var j = 3
          while (j >= 0 && !done) {
            if (((nib >> j) & 1) == 0) bits += 1 else done = true
            j -= 1
          }
      }
      i += 1
    }
    bits
  }

  def hmacSha256(data: String, secret: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  def solves(challenge: String, nonce: String, difficulty: Int): Boolean =
    leadingZeroBits(sha256Hex(s"$challenge:$nonce")) >= difficulty

  /**
   * Clearance, granted once a puzzle is solved.
   *
   * Binding to the address and user-agent is what stops a solved token being handed around: a
   * scraper farm that solves once would otherwise share the cookie across every worker.
   */
  final case class Clearance(exp: Long, ip: Option[String], ua: Option[String], score: Int)

  object Clearance {
    def json(c: Clearance): JsValue = Json.obj("exp" -> c.exp, "ip" -> c.ip, "ua" -> c.ua, "score" -> c.score)
    def read(json: JsValue): Option[Clearance] = Try {
      Clearance(
        exp = (json \ "exp").as[Long],
        ip = (json \ "ip").asOpt[String],
        ua = (json \ "ua").asOpt[String],
        score = (json \ "score").asOpt[Int].getOrElse(0)
      )
    }.toOption
  }

  def signClearance(clearance: Clearance, secret: String): String = {
    val payload = Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(Json.stringify(Clearance.json(clearance)).getBytes("UTF-8"))
    s"$payload.${hmacSha256(payload, secret)}"
  }

  def verifyClearance(
      raw: String,
      secret: String,
      ip: Option[String],
      ua: Option[String],
      now: Long = System.currentTimeMillis() / 1000L
  ): Option[Clearance] = {
    raw.split("\\.") match {
      case Array(payload, signature) =>
        val expected = hmacSha256(payload, secret)
        // constant-time compare: a token check is exactly where a timing oracle would live
        if (!MessageDigest.isEqual(expected.getBytes("UTF-8"), signature.getBytes("UTF-8"))) None
        else {
          Try(new String(Base64.getUrlDecoder.decode(payload), "UTF-8")).toOption
            .flatMap(str => Try(Json.parse(str)).toOption)
            .flatMap(Clearance.read)
            .filter(_.exp > now)
            .filter(c => c.ip.forall(bound => ip.contains(bound)))
            .filter(c => c.ua.forall(bound => ua.contains(bound)))
        }
      case _                         => None
    }
  }

  /**
   * Difficulty scales with the threat score.
   *
   * A caller the fabric barely doubts pays the floor; one it nearly bans pays the ceiling. Each bit
   * doubles the expected work, so the range is deliberately narrow — 18 to 24 bits is roughly a
   * quarter of a second to a few seconds on a phone, and the difference between a nuisance and a
   * denial of service against your own users.
   */
  // inverted bounds collapse to the easier of the two on purpose: a misconfiguration should cost
  // protection, never serve an unsolvable puzzle to a legitimate visitor
  def difficultyFor(score: Int, floor: Int, ceiling: Int): Int = {
    val lo   = math.max(1, math.min(floor, ceiling))
    val hi   = math.max(lo, ceiling)
    val safe = math.max(0, math.min(100, score))
    lo + ((hi - lo) * safe) / 100
  }
}
