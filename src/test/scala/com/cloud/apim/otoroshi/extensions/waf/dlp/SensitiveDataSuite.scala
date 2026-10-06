package com.cloud.apim.otoroshi.extensions.waf.dlp

import com.cloud.apim.otoroshi.extensions.waf.body.MediaType
import org.apache.pekko.util.ByteString
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimSensitiveDataConfig
import play.api.libs.json.Json

/**
 * DLP-1 and DLP-2 without a gateway: what each detector takes for a real value and what it does
 * not, what masking leaves behind, and that a stream cut anywhere is read the same as a whole body.
 *
 * Secrets are assembled from pieces, so this file never holds a token a secret scanner would stop.
 */
class SensitiveDataSuite extends munit.FunSuite {

  private def rules(actions: (Detector, DlpAction)*): Seq[(Detector, DlpAction)] = actions

  private def all(action: DlpAction): Seq[(Detector, DlpAction)] =
    Detectors.all.map(d => d -> (if (d.volume && action == DlpAction.Mask) DlpAction.Log else action))

  private def masked(body: String, rs: Seq[(Detector, DlpAction)] = all(DlpAction.Mask), threshold: Int = 50): (String, SensitiveScanner) = {
    val scanner = new SensitiveScanner(rs, rewrite = true, threshold)
    (scanner.scanAll(ByteString(body)).utf8String, scanner)
  }

  private def found(body: String, detector: Detector): Int =
    masked(body, rules(detector -> DlpAction.Log))._2.hits.find(_.detector == detector).map(_.count).getOrElse(0)

  // ---------------------------------------------------------------- cards

  test("a card number is masked, its first and last four digits kept") {
    assertEquals(masked("""{"pan":"4111 1111 1111 1111"}""")._1, """{"pan":"4111 **** **** 1111"}""")
    assertEquals(masked("""{"pan":"4111111111111111"}""")._1, """{"pan":"4111********1111"}""")
    assertEquals(masked("card 5555-5555-5555-4444 ok")._1, "card 5555-****-****-4444 ok")
  }

  test("every network is recognised, and only valid numbers") {
    Seq("378282246310005", "6011111111111117", "3530111333300000", "5555555555554444").foreach { n =>
      assertEquals(found(s"x $n y", Detectors.Card), 1, n)
    }
    assertEquals(found("x 4111111111111112 y", Detectors.Card), 0, "bad Luhn")
    assertEquals(found("""{"ts":1696600000000000}""", Detectors.Card), 0, "a timestamp is no card")
    assertEquals(found("4111 1111-1111 1111", Detectors.Card), 0, "two kinds of separator")
    assertEquals(found("41111111111111111111", Detectors.Card), 0, "twenty digits")
  }

  test("a card written as a bare JSON number is masked with zeros, and the JSON still parses") {
    val (out, _) = masked("""{"n":4111111111111111,"m":[5555555555554444]}""")
    assertEquals(out, """{"n":4111000000001111,"m":[5555000000004444]}""")
    Json.parse(out)
  }

  // ---------------------------------------------------------------- IBANs

  test("an IBAN is masked, its country, check digits and last four kept") {
    assertEquals(masked("FR76 3000 6000 0112 3456 7890 189")._1, "FR76 **** **** **** **** ***0 189")
    assertEquals(masked("DE89370400440532013000")._1, "DE89**************3000")
  }

  test("an IBAN with a bad check is no IBAN") {
    assertEquals(found("FR76 3000 6000 0112 3456 7890 188", Detectors.Iban), 0)
    assertEquals(found("ZZ76 3000 6000 0112 3456 7890 189", Detectors.Iban), 0)
  }

  test("a spaced IBAN ends where its country says, not at the word after it") {
    assertEquals(masked("BE68 5390 0754 7034 FROM")._1, "BE68 **** **** 7034 FROM")
    assertEquals(masked("GB82 WEST 1234 5698 7654 32 PAID")._1, "GB82 **** **** **** **54 32 PAID")
  }

  // ---------------------------------------------------------------- national identifiers

  test("a NIR is recognised by its key, Corsica included") {
    assertEquals(masked("nir 1 84 12 76 451 089 46.")._1, "nir * ** ** ** *** *89 46.")
    assertEquals(found("184127645108946", Detectors.FrenchNir), 1)
    assertEquals(found("1 85 05 2A 006 084 35", Detectors.FrenchNir), 1)
    assertEquals(found("1 85 05 2B 006 084 62", Detectors.FrenchNir), 1)
    assertEquals(found("184127645108947", Detectors.FrenchNir), 0, "wrong key")
  }

  test("a US SSN is masked to its last four, and numbers never issued are left alone") {
    assertEquals(masked("ssn: 123-45-6789")._1, "ssn: ***-**-6789")
    Seq("000-12-3456", "666-12-3456", "912-12-3456", "123-00-4567", "123-45-0000", "123-45-67890").foreach { n =>
      assertEquals(found(n, Detectors.UsSsn), 0, n)
    }
  }

  // ---------------------------------------------------------------- secrets

  test("a private key in a JSON string is masked between its markers, its escapes intact") {
    val body     = """{"key":"-----BEGIN RSA PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\nBKcwggSjAgEAAoIBAQC7\n-----END RSA PRIVATE KEY-----"}"""
    val (out, s) = masked(body)
    val key      = (Json.parse(out) \ "key").as[String]
    assert(key.startsWith("-----BEGIN RSA PRIVATE KEY-----\n"), key)
    assert(key.endsWith("\n-----END RSA PRIVATE KEY-----"), key)
    assert(!key.contains("MIIE") && !key.contains("BKcw"), key)
    assertEquals(s.hits.map(_.detector), Seq(Detectors.PrivateKey))
  }

  test("an unterminated private key is masked all the same") {
    val (out, _) = masked("-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC")
    assert(!out.contains("MIIE"), out)
  }

  test("a private key refuses the response when its action is block") {
    val (_, s) = masked("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----")
    assertEquals(s.blocked, None, "masked, not blocked, when its action is mask")
    val blocking = new SensitiveScanner(rules(Detectors.PrivateKey -> DlpAction.Block), rewrite = true, 50)
    blocking.scanAll(ByteString("x -----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY----- y"))
    assertEquals(blocking.blocked, Some(Detectors.PrivateKey))
  }

  private val jwt =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" + "." + "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ" + "." +
      "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

  test("a JWT keeps its header and loses its claims and signature") {
    val (out, _) = masked(s"""{"token":"$jwt"}""")
    val token    = (Json.parse(out) \ "token").as[String]
    val parts    = token.split('.')
    assertEquals(parts(0), "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
    assert(parts(1).forall(c => c == '*' || c == '_' || c == '-'), token)
    assertEquals(token.length, jwt.length)
  }

  test("something shaped like a JWT whose header says nothing is not one") {
    assertEquals(found("eyJmb29iYXJiYXo.eyJzdWIiOiIxMjM0.abc", Detectors.Jwt), 0)
  }

  test("prefixed secrets keep what says what they are") {
    val aws       = "AKIA" + "IOSFODNN7EXAMPLE"
    val github    = "ghp_" + "a" * 36
    val stripe    = "sk_" + "live_" + "b" * 24
    val anthropic = "sk-ant-" + "api03-" + "c" * 93 + "AA"
    val openai    = "sk-proj-" + "d" * 20 + "T3Blbk" + "FJ" + "e" * 20
    assertEquals(masked(aws)._1, "AKIA" + "*" * 16)
    assertEquals(masked(github)._1, "ghp_" + "*" * 36)
    assertEquals(masked(stripe)._1, "sk_live_" + "*" * 24)
    assertEquals(masked(anthropic)._1, "sk-ant-api03-" + "*" * 95)
    assert(masked(openai)._1.startsWith("sk-proj-****"), masked(openai)._1)
    assertEquals(found("task-ant-api03-" + "c" * 93 + "AA", Detectors.LlmKey), 0, "inside a longer token")
  }

  // ---------------------------------------------------------------- volume

  test("email addresses only count past the threshold, and once each") {
    def body(n: Int) = (1 to n).map(i => s""""user$i@example.com"""").mkString("[", ",", "]")
    def hit(b: String) = masked(b, rules(Detectors.EmailBulk -> DlpAction.Log), threshold = 50)._2.hits
    assertEquals(hit(body(49)), Seq.empty)
    assertEquals(hit(body(50)).map(_.count), Seq(50))
    assertEquals(hit(body(49) + "," + body(49)), Seq.empty, "the same addresses twice are still 49")
    assertEquals(masked(body(60), all(DlpAction.Mask))._1, body(60), "addresses are reported, never masked")
  }

  // ---------------------------------------------------------------- what masking keeps

  test("masking never touches an escape or a character reference") {
    assertEquals(masked("""{"a":"x\n4111111111111111\t"}""")._1, """{"a":"x\n4111********1111\t"}""")
    val xml = masked("<key>-----BEGIN PRIVATE KEY-----&#10;MIIEvQIBADAN&#10;-----END PRIVATE KEY-----</key>")._1
    assertEquals(xml, "<key>-----BEGIN PRIVATE KEY-----&#10;************&#10;-----END PRIVATE KEY-----</key>")
  }

  test("a masked body keeps its length, its non-ASCII text and its validity") {
    val body     = s"""{"name":"Zoé Müller","iban":"FR7630006000011234567890189","pan":"4111 1111 1111 1111","jwt":"$jwt"}"""
    val (out, _) = masked(body)
    assertEquals(ByteString(out).size, ByteString(body).size)
    assertEquals((Json.parse(out) \ "name").as[String], "Zoé Müller")
  }

  test("log reports and leaves the body as it is") {
    val body     = "pan 4111111111111111 iban FR7630006000011234567890189"
    val (out, s) = masked(body, all(DlpAction.Log))
    assertEquals(out, body)
    assertEquals(s.hits.map(h => h.detector.id -> h.count).toMap, Map("card" -> 1, "iban" -> 1))
    assertEquals(s.masked, 0)
  }

  // ---------------------------------------------------------------- streaming

  test("a body cut anywhere is read and masked exactly as a whole one") {
    val body  = (1 to 40).map { i =>
      s"""{"i":$i,"pan":"4111 1111 1111 1111","iban":"DE89 3704 0044 0532 0130 00","ssn":"123-45-6789","key":"${"AKIA" + "IOSFODNN7EXAMPLE"}"}"""
    }.mkString("[", ",", "]")
    val whole = masked(body)
    Seq(1, 3, 7, 16, 64, 500, 4096).foreach { size =>
      val scanner = new SensitiveScanner(all(DlpAction.Mask), rewrite = true, 50)
      val out     = ByteString(body).grouped(size).map(scanner.push).foldLeft(ByteString.empty)(_ ++ _) ++ scanner.finish()
      assertEquals(out.utf8String, whole._1, s"chunks of $size")
      assertEquals(scanner.hits.map(h => h.detector -> h.count), whole._2.hits.map(h => h.detector -> h.count), s"chunks of $size")
    }
    assertEquals(whole._2.hits.map(h => h.detector.id -> h.count).toMap, Map("card" -> 40, "iban" -> 40, "us_ssn" -> 40, "cloud_key" -> 40))
  }

  test("nothing past the window is held back") {
    val scanner = new SensitiveScanner(rules(Detectors.Card -> DlpAction.Mask), rewrite = true, 50)
    val out     = scanner.push(ByteString("a" * 1000))
    assertEquals(out.size, 1000 - scanner.window)
  }

  // ---------------------------------------------------------------- config

  test("a config keeps known detectors and actions, and a volume detector cannot mask") {
    val cfg = CloudApimSensitiveDataConfig.format
      .reads(Json.obj("detectors" -> Json.obj("card" -> "block", "email_bulk" -> "mask", "nope" -> "mask", "iban" -> "explode")))
      .get
    assertEquals(cfg.detectors, Map("card" -> "block", "email_bulk" -> "mask"))
    assertEquals(cfg.actionOf(Detectors.Card), DlpAction.Block)
    assertEquals(cfg.actionOf(Detectors.EmailBulk), DlpAction.Log)
    assertEquals(cfg.actionOf(Detectors.Iban), DlpAction.Mask)
    assertEquals(cfg.actionOf(Detectors.Jwt), DlpAction.Log)
    assertEquals(cfg.actionOf(Detectors.PrivateKey), DlpAction.Block)
    assertEquals(CloudApimSensitiveDataConfig.format.reads(cfg.json).get, cfg)
  }

  test("every field of the flow is described by the schema") {
    assertEquals(CloudApimSensitiveDataConfig.configFlow.filterNot(CloudApimSensitiveDataConfig.configSchema.keys.contains), Seq.empty[String])
  }

  test("server-sent events are never read, they would stall") {
    assert(!MediaType.textual("text/event-stream"))
    assert(MediaType.textual("text/html") && MediaType.textual("application/problem+json"))
    assert(!CloudApimSensitiveDataConfig.default.inspects(Some("text/event-stream; charset=utf-8")))
  }

  test("detector ids are unique, and every one holds back something") {
    assertEquals(Detectors.all.map(_.id).distinct.size, Detectors.all.size)
    assert(Detectors.all.forall(d => d.maxLength > 0))
  }
}
