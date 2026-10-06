package com.cloud.apim.otoroshi.extensions.waf.it

import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimUploadGuard
import play.api.libs.json.{JsValue, Json}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * OPS-6 through a real gateway: an alert rule saved through the admin api, an attack on a route,
 * and the one message a webhook receives for it.
 */
class AlertIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private def ext = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get

  private final class Hook {
    val received = new ConcurrentLinkedQueue[JsValue]()
    private val binding = Await.result(
      Http()(using Gateway.system)
        .newServerAt("127.0.0.1", 0)
        .bind { request =>
          request.entity
            .toStrict(5.seconds)(using Gateway.mat)
            .map { entity =>
              received.add(Json.parse(entity.data.utf8String))
              HttpResponse(StatusCodes.OK)
            }(using Gateway.ec)
        },
      10.seconds
    )
    def url: String  = s"http://127.0.0.1:${binding.localAddress.getPort}/hook"
    def stop(): Unit = { Await.result(binding.unbind(), 10.seconds); () }
  }

  private def waitUntil(what: String, timeout: FiniteDuration = 30.seconds)(cond: => Boolean): Unit = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(200)
    assert(cond, s"timed out waiting for $what")
  }

  test("an attack on a route is one webhook message, carrying the incident") {
    val hook    = new Hook()
    val ruleId  = s"alert-rule_it_${System.nanoTime()}"
    val created = Gateway.post(
      "/apis/waf.extensions.cloud-apim.com/v1/alert-rules",
      Json.obj(
        "id"               -> ruleId,
        "name"             -> "Uploads refused",
        "trigger"          -> "incident",
        "min_score"        -> 50,
        "categories"       -> Json.arr("upload"),
        "cooldown_seconds" -> 600,
        "channel"          -> Json.obj("kind" -> "webhook", "url" -> hook.url, "headers" -> Json.obj("X-Token" -> "secret"))
      )
    )
    assert(created.status < 300, created.body)
    Await.result(ext.security.syncStates(), 30.seconds)
    waitUntil("the rule to reach the state")(ext.security.states.alertRule(ruleId).isDefined)

    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute(
      "ops6-upload",
      backend.port,
      Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[CloudApimUploadGuard], config = NgPluginInstanceConfig(Json.obj())))
    )
    try {
      val body = ByteString(
        "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shell.php\"\r\n\r\n<?php echo 1;\r\n--b--\r\n"
      )
      (1 to 5).foreach { _ =>
        assertEquals(Gateway.call(route, "/upload", "POST", Some(body), "multipart/form-data; boundary=b").status, 415)
      }
      waitUntil("the webhook to receive the alert")(!hook.received.isEmpty)
      Thread.sleep(1500)
      val alerts = hook.received.asScala.toSeq
      assertEquals(alerts.size, 1, s"five refusals of one attacker: $alerts")
      val alert  = alerts.head
      assertEquals((alert \ "trigger").as[String], "incident")
      assertEquals((alert \ "rule" \ "id").as[String], ruleId)
      assertEquals((alert \ "identity" \ "kind").as[String], "ip")
      assert((alert \ "details" \ "categories").as[Seq[String]].contains("upload"), alert.toString)
    } finally {
      Gateway.deleteRoute(route)
      backend.stop()
      hook.stop()
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/alert-rules/$ruleId")
    }
  }
}
