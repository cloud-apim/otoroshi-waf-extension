package com.cloud.apim.otoroshi.extensions.waf.it

import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, PluginIndex}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimApiContract
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * API-2, API-3 and API-4 through a real gateway, read the way a CI reads them: traffic on a route
 * checked against its contract, then the report from the admin api, with an admin api key.
 */
class ApiReportIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private def mod = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.security

  private val spec =
    """openapi: 3.0.3
      |info: { title: inventory, version: "1" }
      |security: [ { key: [] } ]
      |paths:
      |  /items/{id}:
      |    get:
      |      parameters: [ { name: id, in: path, required: true, schema: { type: integer } } ]
      |      responses:
      |        "200":
      |          description: ok
      |          content:
      |            application/json:
      |              schema: { type: object, properties: { id: { type: integer } } }
      |  /reports:
      |    get:
      |      responses: { "200": { description: ok } }
      |components:
      |  securitySchemes:
      |    key: { type: apiKey, in: header, name: X-Api-Key }
      |""".stripMargin

  test("what a contract sees becomes a report: operations used and unused, a shadow endpoint, a drifting response, an unauthenticated endpoint") {
    val id      = s"api-contract_report_${java.util.UUID.randomUUID().toString.take(8)}"
    assert(Gateway.post("/apis/waf.extensions.cloud-apim.com/v1/api-contracts", Json.obj("id" -> id, "name" -> "inventory", "spec" -> spec)).status < 300)
    Await.result(mod.syncStates(), 30.seconds)
    val backend = new TestBackend(responseBody = ByteString("""{"id":1,"password":"hunter2"}"""))(using Gateway.system, Gateway.mat, Gateway.ec)
    val plugin  = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[CloudApimApiContract],
      config = NgPluginInstanceConfig(Json.obj("contract" -> id)),
      pluginIndex = PluginIndex(transformRequest = 0.5.some, transformResponse = 0.5.some).some
    )
    val route   = Gateway.createRoute("api234-report", backend.port, Seq(plugin))
    try {
      (1 to 3).foreach(i => assertEquals(Gateway.call(route, s"/items/$i").status, 200))
      assertEquals(Gateway.call(route, "/legacy/42/export").status, 200, "monitoring lets a path outside the contract through")
      val res     = Gateway.await(Gateway.admin(s"/api/extensions/cloud-apim/waf/api/_report?route_ids=${route.id}").get())
      assertEquals(res.status, 200, res.body)
      val report  = (res.json \ "routes").as[Seq[JsObject]].find(r => (r \ "route_id").as[String] == route.id).get
      def ops     = (report \ "operations").as[Seq[JsObject]].map(o => (o \ "path").as[String] -> o).toMap
      assertEquals((report \ "contract" \ "id").as[String], id)
      assertEquals(((ops("/items/{id}") \ "hits").as[Long], (ops("/items/{id}") \ "state").as[String]), (3L, "used"))
      assertEquals((ops("/items/{id}") \ "statuses" \ "2xx").as[Long], 3L)
      assertEquals((ops("/reports") \ "state").as[String], "unused", "never called, and observed for less than the zombie threshold")
      val shadow  = (report \ "shadows").as[Seq[JsObject]].head
      assertEquals(((shadow \ "path").as[String], (shadow \ "confirmed").as[Boolean]), ("/legacy/{id}/export", true))
      val drift   = (report \ "drift").as[Seq[JsObject]]
      assert(drift.exists(d => (d \ "where").as[String] == "response 200 $.password" && (d \ "sensitive").as[Boolean]), s"$drift")
      val auth    = (report \ "auth" \ "findings").as[Seq[JsValue]].map(f => ((f \ "path").as[String], (f \ "kind").as[String], (f \ "severity").as[String]))
      assertEquals(auth.toSet, Set(("/items/{id}", "unauthenticated", "high"), ("/reports", "unauthenticated", "high")))
      assertEquals((res.json \ "summary" \ "auth" \ "high").as[Int], 2)
      assertEquals((res.json \ "summary" \ "confirmed_shadows").as[Int], 1)
      assertEquals((res.json \ "summary" \ "sensitive_drift").as[Int], 1)
    } finally {
      Gateway.deleteRoute(route); backend.stop()
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/api-contracts/$id")
    }
  }
}
