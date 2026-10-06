package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.entities.ApiContract
import com.cloud.apim.otoroshi.extensions.waf.security.IdentityRef
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute, PluginIndex}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimApiContract, CloudApimObjectGuard}
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * API-1 through a real gateway: a contract enforced refuses what it does not declare with the
 * status that says why, a contract monitored through a route's metadata only reports, a response
 * the contract does not declare is reported, and the object guard reads its objects from the
 * contract's path parameters.
 */
class ContractIT extends munit.FunSuite {

  override val munitTimeout = 5.minutes

  private def mod = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.security

  private val spec =
    """openapi: 3.0.3
      |info: { title: pets, version: "1" }
      |servers: [ { url: "https://api.example.com/v1" } ]
      |paths:
      |  /pets:
      |    get:
      |      parameters:
      |        - { name: limit, in: query, schema: { type: integer, maximum: 100 } }
      |      responses:
      |        "200":
      |          description: ok
      |          content:
      |            application/json:
      |              schema: { type: array }
      |    post:
      |      requestBody:
      |        required: true
      |        content:
      |          application/json:
      |            schema:
      |              type: object
      |              additionalProperties: false
      |              required: [name]
      |              properties: { name: { type: string } }
      |      responses:
      |        "201": { description: created }
      |  /pets/{petId}:
      |    get:
      |      parameters:
      |        - { name: petId, in: path, required: true, schema: { type: integer } }
      |      responses:
      |        "200": { description: ok }
      |""".stripMargin

  private def caller(): IdentityRef = {
    val r = new scala.util.Random()
    IdentityRef("ip", s"198.18.${r.nextInt(250)}.${1 + r.nextInt(250)}")
  }

  private def contract(): String = {
    val id      = s"api-contract_it_${java.util.UUID.randomUUID().toString.take(8)}"
    val created = Gateway.post("/apis/waf.extensions.cloud-apim.com/v1/api-contracts", Json.obj("id" -> id, "name" -> "pets", "spec" -> spec))
    assert(created.status < 300, created.body)
    Await.result(mod.syncStates(), 30.seconds)
    id
  }

  private def plugin(config: JsObject) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimApiContract],
    config = NgPluginInstanceConfig(config),
    pluginIndex = PluginIndex(transformRequest = 0.5.some, transformResponse = 0.5.some).some
  )

  private def call(route: NgRoute, who: IdentityRef, path: String, method: String = "GET", body: Option[String] = None, contentType: String = "application/json"): WSResponse =
    Gateway.call(route, path = path, method = method, body = body.map(ByteString(_)), contentType = contentType, headers = Seq("X-Forwarded-For" -> who.value))

  private def kinds(r: WSResponse): Seq[String] = (r.json \ "violations").as[Seq[JsObject]].map(v => (v \ "kind").as[String])

  test("an enforced contract refuses what it does not declare, with the status that says why") {
    val id      = contract()
    val who     = caller()
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute("api1-enforce", backend.port, Seq(plugin(Json.obj("contract" -> id, "mode" -> "enforce", "expose_errors" -> true))))
    try {
      assertEquals(call(route, who, "/v1/pets?limit=10").status, 200)
      val unknown = call(route, who, "/v1/owners")
      assertEquals((unknown.status, kinds(unknown)), (404, Seq("unknown_path")))
      val method  = call(route, who, "/v1/pets", method = "PUT", body = Some("{}"))
      assertEquals(method.status, 405)
      assertEquals(method.header("Allow").map(_.split(",").map(_.trim).toSet), Some(Set("GET", "POST")))
      val param   = call(route, who, "/v1/pets?limit=1000")
      assertEquals((param.status, kinds(param)), (400, Seq("invalid_parameter")))
      val path    = call(route, who, "/v1/pets/rex")
      assertEquals((path.status, kinds(path)), (400, Seq("invalid_parameter")))
      assertEquals(call(route, who, "/v1/pets", "POST", Some("""{"name":"rex"}""")).status, 200)
      val body    = call(route, who, "/v1/pets", "POST", Some("""{"name":"rex","admin":true}"""))
      assertEquals((body.status, kinds(body)), (400, Seq("invalid_body")))
      assertEquals(call(route, who, "/v1/pets", "POST", Some("name=rex"), "application/x-www-form-urlencoded").status, 415)
      assertEquals(call(route, who, "/v1/pets", "POST", None).status, 400, "a body is required")
      assertEquals(backend.calls.get(), 2L, "nothing refused reached the backend")
      assert(mod.incidents.byKey(who.key).exists(_.tags.contains("api:unknown_path")))
    } finally {
      Gateway.deleteRoute(route); backend.stop()
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/api-contracts/$id")
    }
  }

  test("a contract named by the route's metadata, monitored, only reports, and so does a response it does not declare") {
    val id      = contract()
    val who     = caller()
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val route   = Gateway.createRoute(
      "api1-monitor",
      backend.port,
      Seq(plugin(Json.obj("validate_responses" -> true))),
      metadata = Map(ApiContract.RouteMetadataKey -> id)
    )
    try {
      assertEquals(call(route, who, "/v1/owners").status, 200, "monitoring lets it through")
      val listed = call(route, who, "/v1/pets")
      assertEquals((listed.status, (listed.json \ "ok").asOpt[Boolean]), (200, Some(true)), "the response goes through as it came")
      val tags   = mod.incidents.byKey(who.key).toSeq.flatMap(_.tags)
      assert(tags.contains("api:unknown_path"), s"$tags")
      assert(tags.contains("api:invalid_response"), s"the backend answers an object where the contract says an array: $tags")
    } finally {
      Gateway.deleteRoute(route); backend.stop()
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/api-contracts/$id")
    }
  }

  test("the object guard counts the objects the contract's path parameters name") {
    val id      = contract()
    val who     = caller()
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val guard   = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[CloudApimObjectGuard],
      config = NgPluginInstanceConfig(Json.obj("auto_detect" -> false, "budget" -> 2)),
      pluginIndex = PluginIndex(transformRequest = 4.0.some, transformResponse = 4.0.some).some
    )
    val route   = Gateway.createRoute("api1-objects", backend.port, Seq(plugin(Json.obj("contract" -> id)), guard))
    try {
      assertEquals(call(route, who, "/v1/pets/1").status, 200)
      assertEquals(call(route, who, "/v1/pets/2").status, 200)
      assertEquals(call(route, who, "/v1/pets/3").status, 429, "a third pet, with no template declared nor identifier detected")
      assertEquals(call(route, who, "/v1/pets").status, 200, "a collection is not an object")
    } finally {
      Gateway.deleteRoute(route); backend.stop()
      Gateway.delete(s"/apis/waf.extensions.cloud-apim.com/v1/api-contracts/$id")
    }
  }
}
