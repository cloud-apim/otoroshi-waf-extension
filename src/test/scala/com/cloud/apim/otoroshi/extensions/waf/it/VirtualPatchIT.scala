package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.feeds.VirtualPatchPage
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimWaf
import play.api.libs.json.{JsObject, Json}

/**
 * The PHP2Shell virtual patch through a real gateway, set up the way its page says: a ruleset listed
 * first, a CRS baseline after it, and the body refused when it cannot be inspected whole.
 */
class VirtualPatchIT extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def ruleset(rules: Seq[String]): String = Gateway.createWafRuleset(
    Json.obj(
      "id"          -> s"waf-ruleset_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"        -> "PHP2Shell virtual patch",
      "description" -> "",
      "enabled"     -> true,
      "rules"       -> rules
    )
  )

  private def config(rulesets: Seq[String], extra: JsObject = Json.obj()): String = Gateway.createWafConfig(
    Json.obj(
      "id"                  -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"                -> "it-php",
      "description"         -> "",
      "enabled"             -> true,
      "block"               -> true,
      "inspect_input_body"  -> true,
      "inspect_output_body" -> false,
      "rulesets"            -> rulesets,
      "rules"               -> Seq("@import_preset crs", "SecRuleEngine On")
    ) ++ extra
  )

  private def wafPlugin(ref: String) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimWaf],
    config = NgPluginInstanceConfig(Json.obj("ref" -> ref))
  )

  private val ct = "multipart/form-data; boundary=XyZ"

  private def multipart(parts: String*): Option[ByteString] =
    Some(ByteString(parts.map(p => s"--XyZ\r\n$p\r\n").mkString + "--XyZ--\r\n"))

  private val progress = "Content-Disposition: form-data; name=\"PHP_SESSION_UPLOAD_PROGRESS\"; x=y\r\n\r\nx"
  private val title    = "Content-Disposition: form-data; name=\"title\"\r\n\r\nholidays"
  private val avatar   = "Content-Disposition: form-data; name=\"avatar\"; filename=\"me.png\"\r\nContent-Type: image/png\r\n\r\nPNG...."
  private val padding  = s"Content-Disposition: form-data; name=\"pad\"\r\n\r\n${"A" * (1536 * 1024)}"

  test("the upload progress field is refused, an ordinary upload goes through") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val rs      = ruleset(VirtualPatchPage.block("php2shell.conf"))
    val ref     = config(Seq(rs))
    val route   = Gateway.createRoute("php2shell", backend.port, Seq(wafPlugin(ref)))
    try {
      assertEquals(Gateway.call(route, "/upload.php", "POST", multipart(progress, avatar), ct).status, 403)
      assertEquals(Gateway.call(route, "/index.php", "POST", Some(ByteString(("a" + "[x]" * 20) + "=1")), "application/x-www-form-urlencoded").status, 403)
      assertEquals(Gateway.call(route, "/upload.php", "POST", multipart(title, avatar), ct).status, 200)
    } finally {
      Gateway.deleteRoute(route); Gateway.deleteWafConfig(ref); Gateway.deleteWafRuleset(rs); backend.stop()
    }
  }

  // why the page asks for `reject`: past the window the field is never seen
  test("padded past the inspection window, the field only stops at a body refused for its size") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val rs      = ruleset(VirtualPatchPage.block("php2shell.conf"))
    val lax     = config(Seq(rs), Json.obj("input_body_limit" -> 1048576, "oversize_body_action" -> "inspect_prefix"))
    val strict  = config(Seq(rs), Json.obj("input_body_limit" -> 1048576, "oversize_body_action" -> "reject"))
    val laxRoute    = Gateway.createRoute("php2shell-lax", backend.port, Seq(wafPlugin(lax)))
    val strictRoute = Gateway.createRoute("php2shell-strict", backend.port, Seq(wafPlugin(strict)))
    try {
      assertEquals(Gateway.call(laxRoute, "/upload.php", "POST", multipart(padding, progress), ct).status, 200)
      assertEquals(Gateway.call(strictRoute, "/upload.php", "POST", multipart(padding, progress), ct).status, 413)
      assertEquals(Gateway.call(strictRoute, "/upload.php", "POST", multipart(progress), ct).status, 403)
    } finally {
      Gateway.deleteRoute(laxRoute); Gateway.deleteRoute(strictRoute)
      Gateway.deleteWafConfig(lax); Gateway.deleteWafConfig(strict); Gateway.deleteWafRuleset(rs); backend.stop()
    }
  }
}
