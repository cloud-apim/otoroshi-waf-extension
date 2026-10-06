package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.feeds.TestSigner
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimWaf
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

/**
 * WAF-2 and WAF-3 through a real gateway: a signed bundle served over HTTP, checked and installed
 * as a managed ruleset a WAF config blocks with, a newer version replacing it, a rollback putting
 * the old one back and holding there, and what is refused is never installed.
 */
class RuleFeedIT extends munit.FunSuite {

  import TestSigner.*

  override val munitTimeout = 5.minutes

  private def ext                     = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get
  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)

  private val signer = keys()

  private def serve(body: String) =
    new TestBackend(contentType = "application/json", responseBody = ByteString(body))(using Gateway.system, Gateway.mat, Gateway.ec)

  private def feed(id: String, url: String, extra: JsObject = Json.obj()) = {
    val body  = Json.obj("id" -> id, "name" -> id, "enabled" -> true, "url" -> url, "trusted_keys" -> Json.arr(publicKey(signer))) ++ extra
    val saved =
      if (ext.states.ruleFeed(id).isEmpty) Gateway.post("/apis/waf.extensions.cloud-apim.com/v1/rule-feeds", body)
      else Gateway.await(Gateway.admin(s"/apis/waf.extensions.cloud-apim.com/v1/rule-feeds/$id").put(Json.stringify(body)))
    assert(saved.status < 300, saved.body)
    await(ext.syncStates())
    ext.states.ruleFeed(id).get
  }

  test("a signed bundle is installed as a managed ruleset a WAF config blocks with, and replaced by a newer one") {
    val id      = s"rule-feed_it_${System.nanoTime()}"
    val v1      = serve(envelope(bundle("2026.10.06-1", 1000L, lfiPack()), signer))
    val v2      = serve(envelope(bundle("2026.10.06-2", 2000L, lfiPack(Seq("""SecRule ARGS "@contains /etc/shadow" "id:9100002,phase:2,deny,status:403""""))), signer))
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    var route: Option[otoroshi.next.models.NgRoute] = None
    var config: Option[String]                      = None
    try {
      val state1  = await(ext.feeds.refresh(feed(id, s"http://127.0.0.1:${v1.port}/bundle")))
      assertEquals(state1.active.map(_.version), Some("2026.10.06-1"), s"${state1.lastError}")
      val managed = ext.feeds.managedId(id, "lfi-guard")
      val rs      = ext.states.ruleset(managed)
      assertEquals(rs.map(_.metadata.get("feed_version")), Some(Some("2026.10.06-1")))
      assertEquals(rs.map(_.rules.size), Some(1))

      config = Some(Gateway.createWafConfig(Json.obj(
        "id" -> s"waf-config_feed_${System.nanoTime()}", "name" -> "feed-it", "description" -> "", "enabled" -> true, "block" -> true,
        "inspect_input_body" -> true, "inspect_output_body" -> false, "input_body_limit" -> Json.toJson(Option.empty[Long]),
        "output_body_limit" -> Json.toJson(Option.empty[Long]), "output_body_mimetypes" -> Json.arr(),
        "oversize_body_action" -> "inspect_prefix", "rulesets" -> Json.arr(managed), "rules" -> Json.arr("SecRuleEngine On")
      )))
      await(ext.syncStates())
      route = Some(Gateway.createRoute("feed-it", backend.port, Seq(NgPluginInstance(NgPluginHelper.pluginId[CloudApimWaf], config = NgPluginInstanceConfig(Json.obj("ref" -> config.get))))))
      assertEquals(Gateway.call(route.get, "/file?f=../../etc/passwd").status, 403)
      assertEquals(Gateway.call(route.get, "/file?f=report.pdf").status, 200)

      val state2 = await(ext.feeds.refresh(feed(id, s"http://127.0.0.1:${v2.port}/bundle")))
      assertEquals(state2.active.map(_.version), Some("2026.10.06-2"))
      assertEquals(state2.previous.map(_.version), Some("2026.10.06-1"))
      assertEquals(ext.states.ruleset(managed).map(_.rules.size), Some(2), "the same ruleset id carries the new version")

      val back = await(ext.feeds.rollback(ext.states.ruleFeed(id).get))
      assertEquals(back.map(_.active.map(_.version)), Right(Some("2026.10.06-1")))
      assertEquals(ext.states.ruleset(managed).map(_.rules.size), Some(1))
      val again = await(ext.feeds.refresh(ext.states.ruleFeed(id).get))
      assertEquals(again.active.map(_.version), Some("2026.10.06-1"), "a version rolled back from is not installed again")
      assert(again.lastError.exists(_.contains("rolled back")), s"${again.lastError}")
    } finally {
      route.foreach(Gateway.deleteRoute)
      config.foreach(Gateway.deleteWafConfig)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/rule-feeds/$id")
      v1.stop(); v2.stop(); backend.stop()
    }
  }

  test("a bundle signed by a stranger, an older one, or one whose pack tests fail, is never installed") {
    val id       = s"rule-feed_it_${System.nanoTime()}"
    val good     = serve(envelope(bundle("v5", 5000L, lfiPack()), signer))
    val stranger = serve(envelope(bundle("v6", 6000L, lfiPack()), keys()))
    val older    = serve(envelope(bundle("v4", 4000L, lfiPack()), signer))
    val failing  = serve(envelope(bundle("v7", 7000L, lfiPack(expectOnTraversal = "pass")), signer))
    try {
      assertEquals(await(ext.feeds.refresh(feed(id, s"http://127.0.0.1:${good.port}/b"))).active.map(_.version), Some("v5"))
      Seq(stranger -> "no signature", older -> "older than", failing -> "refused: pack 'lfi-guard'").foreach { case (server, why) =>
        val state = await(ext.feeds.refresh(feed(id, s"http://127.0.0.1:${server.port}/b")))
        assertEquals(state.active.map(_.version), Some("v5"), why)
        assert(state.lastError.exists(_.contains(why)), s"$why: ${state.lastError}")
      }
    } finally {
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/rule-feeds/$id")
      Seq(good, stranger, older, failing).foreach(_.stop())
    }
  }

  test("a promotion delay stages a version until it is promoted") {
    val id     = s"rule-feed_it_${System.nanoTime()}"
    val server = serve(envelope(bundle("v1", 1000L, lfiPack()), signer))
    try {
      val staged = await(ext.feeds.refresh(feed(id, s"http://127.0.0.1:${server.port}/b", Json.obj("promotion_delay_seconds" -> 3600))))
      assertEquals((staged.active.map(_.version), staged.pending.map(_.version)), (None, Some("v1")))
      assertEquals(ext.states.ruleset(ext.feeds.managedId(id, "lfi-guard")), None, "nothing is installed while it waits")
      val promoted = await(ext.feeds.promote(ext.states.ruleFeed(id).get))
      assertEquals(promoted.map(_.active.map(_.version)), Right(Some("v1")))
      assert(ext.states.ruleset(ext.feeds.managedId(id, "lfi-guard")).isDefined)
    } finally {
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/rule-feeds/$id")
      server.stop()
    }
  }
}
