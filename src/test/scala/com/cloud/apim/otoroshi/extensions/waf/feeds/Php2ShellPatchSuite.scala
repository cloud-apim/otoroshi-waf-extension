package com.cloud.apim.otoroshi.extensions.waf.feeds

import com.cloud.apim.seclang.impl.engine.SecLangEngine
import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang

import java.nio.file.{Files, Paths}

/** The virtual patches as the documentation gives them: one ruleset entry per block, as people paste them. */
object VirtualPatchPage {

  val page: String = new String(Files.readAllBytes(Paths.get("documentation/docs/waf/virtual-patches.mdx")), "UTF-8")

  def block(title: String): List[String] = {
    val fence = ("(?s)```text title=\"" + java.util.regex.Pattern.quote(title) + "\"\\n(.*?)\\n```").r
    val body  = fence.findFirstMatchIn(page).map(_.group(1)).getOrElse(throw new IllegalStateException(s"no $title block in the page"))
    body.split("\n\\s*\n").map(_.trim).filter(_.nonEmpty).toList
  }
}

/**
 * The PHP2Shell virtual patch (CVE-2026-109618, CVE-2026-109619), as documented.
 *
 * The rules are read from the documentation page people copy them from, so what is tested here is
 * what they paste: a fix to the rules is a fix to the page.
 */
class Php2ShellPatchSuite extends munit.FunSuite {

  private val factory = SecLang.factory(
    Map("crs" -> com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset.embedded),
    SecLangEngineConfig.default,
    DefaultNoCacheSecLangIntegration.default
  )

  private val patch     = VirtualPatchPage.block("php2shell.conf")
  private val uploads   = VirtualPatchPage.block("php2shell-uploads.conf")
  private val exception = """`(SecRule REQUEST_FILENAME "@streq /upload\.php"[^`]*)`""".r.findFirstMatchIn(VirtualPatchPage.page).map(_.group(1)).getOrElse(fail("no exception example in the page"))

  private lazy val engine = factory.engine("SecRuleEngine On" :: patch)

  private def post(body: String, ct: String, method: String = "POST", uri: String = "/index.php") = RequestContext(
    method = method,
    uri = uri,
    headers = Headers(Map("Host" -> List("example.com"), "Content-Type" -> List(ct), "Content-Length" -> List(body.getBytes("UTF-8").length.toString))),
    body = Some(ByteString(body)),
    remoteAddr = "203.0.113.10",
    protocol = "HTTP/1.1"
  )

  private def multipart(parts: Seq[String], ct: String = "multipart/form-data; boundary=XyZ", method: String = "POST", uri: String = "/index.php") =
    post(parts.map(p => s"--XyZ\r\n$p\r\n").mkString + "--XyZ--\r\n", ct, method, uri)

  private def form(body: String, ct: String = "application/x-www-form-urlencoded") = post(body, ct)

  /** The rule that refused the request, if one did. */
  private def refusedBy(req: RequestContext, rules: SecLangEngine = engine): Option[Int] =
    rules.evaluate(req, List(1, 2)).disposition match {
      case Disposition.Block(_, _, id) => id.orElse(Some(-1))
      case _                           => None
    }

  private def field(disposition: String, value: String = "x") = s"Content-Disposition: $disposition\r\n\r\n$value"
  private val progress = field("form-data; name=\"PHP_SESSION_UPLOAD_PROGRESS\"")
  private val avatar   = "Content-Disposition: form-data; name=\"avatar\"; filename=\"me.png\"\r\nContent-Type: image/png\r\n\r\n\u0089PNG...."

  test("the page carries the patch, and it compiles alone and with the CRS") {
    assertEquals(patch.size, 3)
    assertEquals(uploads.size, 1)
    engine
    factory.engine(patch ++ List("@import_preset crs", "SecRuleEngine On"))
  }

  test("the upload progress field is refused, however its name is written") {
    Seq(
      "form-data; name=\"PHP_SESSION_UPLOAD_PROGRESS\"",
      "form-data; name=\"PHP_SESSION_UPLOAD_PROGRESS\"; x=y",
      "form-data; name=PHP_SESSION_UPLOAD_PROGRESS",
      "form-data; name='PHP_SESSION_UPLOAD_PROGRESS'",
      "form-data;name=\"php_session_upload_progress\"",
      "form-data; name=\"PHP_SESSION_UPLOAD_PROGRESS\" "
    ).foreach { d =>
      assertEquals(refusedBy(multipart(Seq(field(d), avatar))), Some(9900001), d)
    }
  }

  test("the content type is matched whatever its case, and PUT is covered") {
    assertEquals(refusedBy(multipart(Seq(progress, avatar), ct = "MULTIPART/FORM-DATA; boundary=XyZ")), Some(9900001))
    assertEquals(refusedBy(multipart(Seq(progress, avatar), method = "PUT")), Some(9900001))
  }

  test("the field is seen behind padding that stays inside the inspection window") {
    val padding = field("form-data; name=\"pad\"", "A" * (1024 * 1024))
    assertEquals(refusedBy(multipart(Seq(padding, progress, avatar))), Some(9900001))
  }

  test("a deeply nested field name is refused, in a form and in a multipart body") {
    val deep = "a" + "[x]" * 20
    assertEquals(refusedBy(form(s"$deep=1")), Some(9900002))
    assertEquals(refusedBy(form(s"${deep.replace("[", "%5B").replace("]", "%5D")}=1")), Some(9900002))
    assertEquals(refusedBy(multipart(Seq(field(s"form-data; name=\"$deep\"")))), Some(9900002))
    // a file field's name is in FILES_NAMES, not ARGS_NAMES
    assertEquals(refusedBy(multipart(Seq(s"Content-Disposition: form-data; name=\"$deep\"; filename=\"a\"\r\n\r\nz"))), Some(9900002))
    // an engine that does not name this part leaves it to the rule on the raw body
    assert(refusedBy(multipart(Seq(field(s"form-data; name=$deep")))).exists(Set(9900002, 9900003)))
  }

  // the rule that does not depend on how the engine parses multipart, checked on its own
  test("on the raw body, the nesting is seen however the field name is written") {
    val rawOnly = factory.engine(List("SecRuleEngine On", patch(2)))
    val deep    = "a" + "[x]" * 20
    Seq(s"name=\"$deep\"", s"name=$deep", s"name='$deep'", s"name=\"$deep\"; filename=\"a\"", s"filename=\"a\"; NAME=\"$deep\"").foreach { p =>
      assertEquals(refusedBy(multipart(Seq(field(s"form-data; $p"))), rawOnly), Some(9900003), p)
    }
    assertEquals(refusedBy(multipart(Seq(field("form-data; name=\"a[x][x][x]\"", "[x]" * 20))), rawOnly), None)
  }

  test("ordinary traffic goes through") {
    assertEquals(refusedBy(multipart(Seq(field("form-data; name=\"title\"", "holidays"), avatar))), None)
    assertEquals(refusedBy(multipart(Seq(field("form-data; name=\"field_p[0][subform][field_q][0][subform][field_text][0][value]\"", "hi")))), None)
    assertEquals(refusedBy(form("user[address][city]=Tours&user[tags][]=a&q=PHP_SESSION_UPLOAD_PROGRESS")), None)
    assertEquals(refusedBy(form("""{"note":"PHP_SESSION_UPLOAD_PROGRESS"}""", ct = "application/json")), None)
  }

  test("listed before the CRS, the patch is what refuses the request") {
    val withCrs = factory.engine(patch ++ List("@import_preset crs", "SecRuleEngine On"))
    assertEquals(refusedBy(multipart(Seq(progress, avatar)), withCrs), Some(9900001))
    assertEquals(refusedBy(multipart(Seq(field("form-data; name=\"title\"", "holidays"), avatar)), withCrs), None)
  }

  test("the optional upload paths rule refuses multipart elsewhere, and only multipart") {
    val strict = factory.engine("SecRuleEngine On" :: uploads ++ patch)
    assertEquals(refusedBy(multipart(Seq(avatar), uri = "/wp-admin/async-upload.php"), strict), None)
    assertEquals(refusedBy(multipart(Seq(avatar), uri = "/wp-admin/async-upload.php?type=image"), strict), None)
    assertEquals(refusedBy(multipart(Seq(avatar), uri = "/index.php"), strict), Some(9900004))
    assertEquals(refusedBy(form("q=1"), strict), None)
  }

  test("the documented exception takes one path out of the upload progress rule") {
    val excepted = factory.engine("SecRuleEngine On" :: exception :: patch)
    assertEquals(refusedBy(multipart(Seq(progress, avatar), uri = "/upload.php"), excepted), None)
    assertEquals(refusedBy(multipart(Seq(progress, avatar), uri = "/index.php"), excepted), Some(9900001))
  }
}
