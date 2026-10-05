package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.reputation.GeoFixtures
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimWaf
import play.api.libs.json.Json

import scala.concurrent.duration.*

/**
 * REP-6 through a real gateway: the database is downloaded by the module's own schedule, over the
 * production http client, and a WAF rule locates the client Otoroshi resolved.
 */
class GeoIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private def extension = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get

  private def waitUntil(what: String, timeout: FiniteDuration = 90.seconds)(cond: => Boolean): Unit = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(500L)
    assert(cond, s"timed out waiting for $what")
  }

  private def call(route: NgRoute, forwardedFor: Option[String]) = Gateway.await(
    Gateway.ws
      .url(s"http://127.0.0.1:${Gateway.port}/")
      .withHttpHeaders(Seq("Host" -> route.frontend.domains.head.domain) ++ forwardedFor.map("X-Forwarded-For" -> _).toSeq*)
      .withRequestTimeout(30.seconds)
      .get()
  )

  test("a database served over http is loaded on schedule, and @geoLookup blocks on the client's country") {
    val database = new TestBackend(contentType = "application/octet-stream", responseBody = ByteString(GeoFixtures.gzip(GeoFixtures.city)))(using
      Gateway.system,
      Gateway.mat,
      Gateway.ec
    )
    val backend  = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val dbId     = s"geo-database_${java.util.UUID.randomUUID().toString.take(8)}"
    val created  = Gateway.post(
      "/apis/waf.extensions.cloud-apim.com/v1/geo-databases",
      Json.obj(
        "id"          -> dbId,
        "name"        -> "it-geo",
        "description" -> "",
        "enabled"     -> true,
        "url"         -> s"http://127.0.0.1:${database.port}/GeoLite2-City.mmdb.gz",
        "attribution" -> "Test data by MaxMind"
      )
    )
    assert(created.status < 300, s"could not create the geolocation database: ${created.status} ${created.body}")
    val ref   = Gateway.createWafConfig(
      Json.obj(
        "id"                   -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
        "name"                 -> "it-geo",
        "description"          -> "",
        "enabled"              -> true,
        "block"                -> true,
        "inspect_input_body"   -> false,
        "inspect_output_body"  -> false,
        "oversize_body_action" -> "inspect_prefix",
        "rulesets"             -> Seq.empty[String],
        // one element: each element of `rules` is compiled on its own, and a chain cannot span two
        "rules"                -> Seq(
          """SecRule REMOTE_ADDR "@geoLookup" "id:9301,phase:1,deny,status:403,msg:'from %{GEO.COUNTRY_CODE}',chain"
            |    SecRule GEO:COUNTRY_CODE "@within GB SE" "t:none"""".stripMargin,
          "SecRuleEngine On"
        )
      )
    )
    val route = Gateway.createRoute(
      "geo-lookup",
      backend.port,
      Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[CloudApimWaf], config = NgPluginInstanceConfig(Json.obj("ref" -> ref))))
    )
    try {
      waitUntil("the database to be loaded by the module") {
        extension.reputation.states.registry.geoSnapshot(dbId).exists(_.loaded)
      }
      val snapshot = extension.reputation.states.registry.geoSnapshot(dbId).get
      assertEquals(snapshot.databaseType, Some("GeoLite2-City"))
      assertEquals(extension.reputation.geolocate("81.2.69.142").flatMap(_.city), Some("London"))

      assertEquals(call(route, Some("81.2.69.142")).status, 403, "a london client is refused")
      assertEquals(call(route, Some("89.160.20.115")).status, 403, "so is one from sweden")
      assertEquals(call(route, Some("216.160.83.56")).status, 200, "an american one is not")
      assertEquals(call(route, None).status, 200, "an address the database does not hold is not a match")
    } finally {
      Gateway.deleteRoute(route)
      Gateway.deleteWafConfig(ref)
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/geo-databases/$dbId")
      database.stop()
      backend.stop()
    }
  }
}
