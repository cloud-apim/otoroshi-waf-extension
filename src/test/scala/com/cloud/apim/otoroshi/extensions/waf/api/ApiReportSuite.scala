package com.cloud.apim.otoroshi.extensions.waf.api

import com.cloud.apim.otoroshi.extensions.waf.security.InMemorySharedStateStore
import play.api.libs.json.Json

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * API-2, API-3 and API-4 without a gateway: a payload against its declared shape, an inventory
 * counted on two nodes and merged, and the auth posture of a contract against the checks deployed.
 */
class ApiReportSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val contract = ContractCompiler
    .compile(
      """openapi: 3.0.3
        |info: { title: t, version: "1" }
        |servers: [ { url: /v1 } ]
        |security: [ { key: [] } ]
        |paths:
        |  /users/{id}:
        |    get:
        |      responses:
        |        "200":
        |          description: ok
        |          content:
        |            application/json:
        |              schema: { $ref: "#/components/schemas/User" }
        |  /open/stats:
        |    get:
        |      responses: { "200": { description: ok } }
        |  /public/ping:
        |    get:
        |      security: []
        |      responses: { "200": { description: ok } }
        |  /admin/reports:
        |    get:
        |      security: [ { sso: [] } ]
        |      responses: { "200": { description: ok } }
        |components:
        |  securitySchemes:
        |    key: { type: apiKey, in: header, name: X-Api-Key }
        |    sso: { type: openIdConnect, openIdConnectUrl: https://id.example.com/.well-known/openid-configuration }
        |  schemas:
        |    User:
        |      allOf:
        |        - type: object
        |          properties:
        |            id: { type: integer }
        |            name: { type: string, nullable: true }
        |        - type: object
        |          properties:
        |            tags: { type: array, items: { type: object, properties: { label: { type: string } } } }
        |            attributes: { type: object, additionalProperties: { type: string } }
        |            extra: { type: object }
        |""".stripMargin
    )
    .fold(e => fail(e), identity)

  private val user = contract.operations.find(_.path == "/users/{id}").get
  private def shapeOf(json: String) =
    ShapeDiff.diff(Json.parse(json), user.responses.head.media.head.schema.get.raw, contract.resolve)

  test("a payload matching its shape, compositions folded together, drifts from nothing") {
    assertEquals(shapeOf("""{"id":1,"name":null,"tags":[{"label":"a"}],"attributes":{"k":"v"},"extra":{"anything":true}}"""), Seq.empty)
  }

  test("an undeclared field is reported where it is, and a sensitive one says so") {
    val found = shapeOf("""{"id":1,"passwordHash":"x","tags":[{"label":"a","color":"red"},{"label":"b","color":"blue"}],"company":"acme"}""")
    assertEquals(
      found.map(f => (f.kind, f.where, f.sensitive)).sortBy(_._2),
      Seq(("undeclared_field", "$.company", false), ("undeclared_field", "$.passwordHash", true), ("undeclared_field", "$.tags[].color", false))
    )
  }

  test("a type the contract does not declare is a mismatch, and an integer is a number") {
    assertEquals(shapeOf("""{"id":"1"}""").map(f => (f.kind, f.where)), Seq(("type_mismatch", "$.id")))
    assertEquals(shapeOf("""{"attributes":{"k":3}}""").map(f => (f.kind, f.where)), Seq(("type_mismatch", "$.attributes.*")))
    assertEquals(ShapeDiff.diff(Json.parse("3"), Json.obj("type" -> "number"), identity), Seq.empty)
  }

  test("sensitive names are read by their words") {
    assertEquals(Seq("card_number", "apiKey", "ssn", "refreshToken", "company", "panel", "syntax", "keyboard").filter(ShapeDiff.sensitive), Seq("card_number", "apiKey", "ssn", "refreshToken"))
  }

  test("two nodes count on their own, and a report merges them") {
    val store = new InMemorySharedStateStore()
    val one   = new ApiInventory("inv", "node-1", store)
    val two   = new ApiInventory("inv", "node-2", store)
    val k1    = one.operation("r1", "c1", "GET", "/users/{id}", 1000L)
    one.operation("r1", "c1", "GET", "/users/{id}", 2000L)
    one.status(k1, 200)
    val k2    = two.operation("r1", "c1", "GET", "/users/{id}", 5000L)
    two.status(k2, 503)
    val s     = two.shadow("r1", "get", "/v1/legacy/42/export?x=1", 6000L)
    assertEquals(s, "shadow|r1|GET /v1/legacy/{id}/export")
    two.status(s, 200)
    two.drift("r1", "c1", "GET", "/users/{id}", DriftFinding("undeclared_field", "response 200 $.ssn", "string", sensitive = true), 7000L)
    assertEquals(await(one.publish()), 1)
    assertEquals(await(two.publish()), 3)
    assertEquals(await(two.publish()), 0, "nothing changed since")
    val merged = await(one.merged())
    val op     = merged(k1)
    assertEquals((op.hits, op.first, op.last), (3L, 1000L, 5000L))
    assertEquals(op.statusesJson, Json.obj("refused" -> 0, "1xx" -> 0, "2xx" -> 1, "3xx" -> 0, "4xx" -> 0, "5xx" -> 1))
    assert(merged(s).statuses(2) == 1L, "a shadow the backend answered")
    assert(merged.keys.exists(k => k.startsWith("drift|r1|c1|GET /users/{id}|undeclared_field|") && merged(k).sensitive))
  }

  test("a scanner walking random paths fills one bucket, not the store") {
    val inv  = new ApiInventory("inv", "n", new InMemorySharedStateStore(), maxShadowsPerRoute = 3)
    val keys = (1 to 10).map(i => inv.shadow("r", "GET", s"/probe-$i", i.toLong)).distinct
    assertEquals(keys.size, 4)
    assert(keys.contains("shadow|r|GET (other paths)"))
  }

  test("the auth posture compares the checks deployed with what the contract declares, endpoint by endpoint") {
    val apikey   = AuthCheck("ApikeyCalls", "api key", Set("apiKey", "http:basic", "http:bearer"), include = Seq.empty, exclude = Seq("/v1/open/.*", "/v1/public/.*"), enforcing = true)
    val found    = AuthPosture.findingsOf(Seq(apikey), Seq("/"), Some(contract)).map(f => (f.path, f.kind, f.severity)).sortBy(_._1)
    assertEquals(
      found,
      Seq(
        ("/v1/admin/reports", "scheme_mismatch", "medium"),
        ("/v1/open/stats", "unauthenticated", "high"),
        ("/v1/public/ping", "public", "info")
      )
    )
    val optional = apikey.copy(exclude = Seq.empty, enforcing = false)
    assert(AuthPosture.findingsOf(Seq(optional), Seq("/"), Some(contract)).exists(f => f.path == "/v1/users/{id}" && f.kind == "unauthenticated"), "an api key that is not mandatory checks nothing")
    assertEquals(AuthPosture.findingsOf(Seq.empty, Seq("/api"), None).map(f => (f.kind, f.path)), Seq(("no_credential", "/api")))
    assertEquals(AuthPosture.findingsOf(Seq(apikey.copy(exclude = Seq.empty)), Seq("/api"), None), Seq.empty)
  }
}
