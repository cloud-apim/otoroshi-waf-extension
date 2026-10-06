package com.cloud.apim.otoroshi.extensions.waf.it

import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimSecuritySuitePreset, CloudApimUploadGuard}
import play.api.libs.json.{JsObject, Json}

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.concurrent.duration.*
import scala.util.Try

/**
 * WAF-4 through a real gateway: a clean upload reaches the backend whole, a disguised file never
 * does, a refusal past the head cuts the upload, monitoring lets everything through, and the preset
 * lays the guard down.
 */
class UploadIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private val boundary    = "----cloudapimit9f3a"
  private val contentType = s"multipart/form-data; boundary=$boundary"

  private val png = ByteString(Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)) ++ ByteString(Array.fill(4000)(3.toByte))
  private val gif = ByteString("GIF89a;<?php system($_GET['c']); ?>")

  private def multipart(files: (String, ByteString)*): ByteString =
    files.foldLeft(ByteString.empty) { case (acc, (name, body)) =>
      acc ++ ByteString(
        s"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n"
      ) ++ body ++ ByteString("\r\n")
    } ++ ByteString(s"--$boundary--\r\n")

  private def bomb: ByteString = {
    val out = new ByteArrayOutputStream()
    val z   = new ZipOutputStream(out)
    z.putNextEntry(new ZipEntry("zeros.bin"))
    z.write(new Array[Byte](20 * 1024 * 1024))
    z.closeEntry()
    z.close()
    ByteString(out.toByteArray)
  }

  private def guard(config: JsObject = Json.obj()) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimUploadGuard],
    config = NgPluginInstanceConfig(config)
  )

  private def withRoute[A](name: String, plugins: Seq[NgPluginInstance])(f: (NgRoute, TestBackend) => A): A = {
    val b     = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route = Gateway.createRoute(name, b.port, plugins)
    try f(route, b)
    finally {
      Gateway.deleteRoute(route); b.stop()
    }
  }

  private def upload(route: NgRoute, body: ByteString) = Gateway.call(route, "/upload", "POST", Some(body), contentType)

  test("a clean upload reaches the backend whole") {
    withRoute("waf4-clean", Seq(guard())) { (route, backend) =>
      val body = multipart("avatar.png" -> png, "notes.txt" -> ByteString("hello"))
      assertEquals(upload(route, body).status, 200)
      assertEquals(backend.received.get(), body.size.toLong)
    }
  }

  test("a disguised file is refused, with why, and never reaches the backend") {
    withRoute("waf4-refuse", Seq(guard())) { (route, backend) =>
      Seq("shell.php" -> ByteString("<?php echo 1;") -> 415, "avatar.gif" -> gif -> 415, "bomb.zip" -> bomb -> 413).foreach {
        case ((name, content), status) =>
          val res = upload(route, multipart(name -> content))
          assertEquals(res.status, status, name)
          assertEquals((res.json \ "error").as[String], "upload_refused")
          assert((res.json \ "reference").as[String].nonEmpty)
      }
      assertEquals((upload(route, multipart("avatar.gif" -> gif)).json \ "reason").as[String], "polyglot")
      assertEquals(backend.calls.get(), 0L)
    }
  }

  test("a file refused past the head cuts the upload on its way") {
    withRoute("waf4-cut", Seq(guard(Json.obj("body_limit" -> 1024)))) { (route, backend) =>
      val body = multipart("big.png" -> (png ++ ByteString(Array.fill(300 * 1024)(5.toByte))), "shell.php" -> ByteString("<?php echo 1;"))
      val res  = Try(upload(route, body))
      assert(res.fold(_ => true, r => r.status >= 400), s"the upload went through: ${res.map(_.status)}")
      assert(backend.received.get() < body.size.toLong, "the backend received the whole upload")
    }
  }

  test("in monitor mode every upload goes through") {
    withRoute("waf4-monitor", Seq(guard(Json.obj("mode" -> "monitor")))) { (route, backend) =>
      val body = multipart("shell.php" -> ByteString("<?php echo 1;"))
      assertEquals(upload(route, body).status, 200)
      assertEquals(backend.received.get(), body.size.toLong)
    }
  }

  test("a body that is not multipart is not the guard's") {
    withRoute("waf4-json", Seq(guard())) { (route, _) =>
      assertEquals(Gateway.call(route, "/", "POST", Some(ByteString("""{"file":"shell.php"}""")), "application/json").status, 200)
    }
  }

  test("the preset lays the guard down when uploads is on, with its allowed extensions") {
    def preset(on: Boolean) = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[CloudApimSecuritySuitePreset],
      config = NgPluginInstanceConfig(
        Json.obj(
          "gate"                       -> false,
          "bots"                       -> false,
          "reputation"                 -> false,
          "waf"                        -> false,
          "response"                   -> false,
          "uploads"                    -> on,
          "uploads_allowed_extensions" -> Json.arr("png")
        )
      )
    )
    val pdf = multipart("report.pdf" -> ByteString("%PDF-1.7\n"))
    withRoute("waf4-preset-on", Seq(preset(true))) { (route, _) =>
      val res = upload(route, pdf)
      assertEquals(res.status, 415)
      assertEquals((res.json \ "reason").as[String], "extension_not_allowed")
      assertEquals(upload(route, multipart("avatar.png" -> png)).status, 200)
    }
    withRoute("waf4-preset-off", Seq(preset(false))) { (route, _) =>
      assertEquals(upload(route, pdf).status, 200)
    }
  }
}
