package com.cloud.apim.otoroshi.extensions.waf.api

import play.api.libs.json.Json

/**
 * API-1 without a gateway: an OpenAPI 3.0 contract in YAML and a 3.1 one in JSON, compiled, and
 * what each says about requests and responses.
 */
class ContractSuite extends munit.FunSuite {

  private val petstore30 =
    """openapi: 3.0.3
      |info: { title: pets, version: "1" }
      |servers:
      |  - url: https://api.example.com/v1
      |paths:
      |  /pets:
      |    get:
      |      operationId: listPets
      |      parameters:
      |        - name: limit
      |          in: query
      |          schema: { type: integer, minimum: 1, maximum: 100 }
      |        - name: status
      |          in: query
      |          schema: { type: string, enum: [available, sold] }
      |        - name: tags
      |          in: query
      |          schema: { type: array, items: { type: string } }
      |      responses:
      |        "200":
      |          description: ok
      |          content:
      |            application/json:
      |              schema: { type: array, items: { $ref: "#/components/schemas/Pet" } }
      |    post:
      |      operationId: createPet
      |      parameters:
      |        - name: X-Request-Id
      |          in: header
      |          required: true
      |          schema: { type: string, format: uuid }
      |      requestBody:
      |        required: true
      |        content:
      |          application/json:
      |            schema: { $ref: "#/components/schemas/NewPet" }
      |      responses:
      |        "201": { description: created }
      |        4XX: { description: refused }
      |  /pets/mine:
      |    get:
      |      responses:
      |        "200": { description: ok }
      |  /pets/{petId}:
      |    parameters:
      |      - name: petId
      |        in: path
      |        required: true
      |        schema: { type: integer, format: int64 }
      |    get:
      |      responses:
      |        "200": { description: ok }
      |    delete:
      |      responses:
      |        default: { description: anything }
      |components:
      |  schemas:
      |    NewPet:
      |      type: object
      |      additionalProperties: false
      |      required: [name]
      |      properties:
      |        name: { type: string, minLength: 1 }
      |        tag: { type: string, nullable: true }
      |        born: { type: string, format: date }
      |    Pet:
      |      type: object
      |      required: [id, name]
      |      properties:
      |        id: { type: integer }
      |        name: { $ref: "#/components/schemas/Name" }
      |    Name: { type: string, minLength: 1 }
      |""".stripMargin

  private lazy val contract = ContractCompiler.compile(petstore30).fold(e => fail(e), identity)

  private def resolve(method: String, path: String) = contract.resolve(method, path)
  private def op(method: String, path: String)      = resolve(method, path).toOption.flatten.get

  test("a YAML 3.0 contract compiles, with the server's path as its base") {
    assertEquals(contract.version, "3.0.3")
    assertEquals(contract.basePath, "/v1")
    assertEquals(contract.operations.map(o => s"${o.method} ${o.path}").sorted, Seq("DELETE /pets/{petId}", "GET /pets", "GET /pets/mine", "GET /pets/{petId}", "POST /pets"))
    assertEquals(contract.warnings, Seq.empty[String])
  }

  test("a path outside the contract, or a method it does not declare, is told apart") {
    assertEquals(resolve("GET", "/v1/owners").left.map(_.kind), Left("unknown_path"))
    assertEquals(resolve("GET", "/pets").left.map(_.kind), Left("unknown_path"), "outside the base path")
    assertEquals(resolve("PUT", "/v1/pets").left.map(v => (v.kind, v.status)), Left(("method_not_allowed", 405)))
    assertEquals(contract.allowed("/v1/pets/12").sorted, Seq("DELETE", "GET"))
    assertEquals(resolve("OPTIONS", "/v1/pets"), Right(None), "a preflight is the browser asking")
    assertEquals(op("HEAD", "/v1/pets").operation.operationId, Some("listPets"), "a HEAD is a GET without its body")
    assertEquals(op("GET", "/v1/pets/").operation.path, "/pets")
  }

  test("a literal path wins over a template, and path parameters are decoded and typed") {
    assertEquals(op("GET", "/v1/pets/mine").operation.path, "/pets/mine")
    val m = op("GET", "/v1/pets/42")
    assertEquals(m.params, Map("petId" -> "42"))
    assertEquals(contract.checkParameters(m, Map.empty, Map.empty, false), Seq.empty)
    val bad = op("GET", "/v1/pets/forty%20two")
    assertEquals(bad.params, Map("petId" -> "forty two"))
    assertEquals(contract.checkParameters(bad, Map.empty, Map.empty, false).map(_.kind), Seq("invalid_parameter"))
  }

  test("query parameters are read as their schema says: numbers, enums, arrays") {
    val m = op("GET", "/v1/pets")
    def check(query: Map[String, Seq[String]], unknown: Boolean = false) = contract.checkParameters(m, query, Map.empty, unknown).map(v => (v.kind, v.where))
    assertEquals(check(Map("limit" -> Seq("10"), "status" -> Seq("sold"), "tags" -> Seq("a", "b"))), Seq.empty)
    assertEquals(check(Map("limit" -> Seq("1000"))), Seq(("invalid_parameter", "query limit")))
    assertEquals(check(Map("limit" -> Seq("ten"))), Seq(("invalid_parameter", "query limit")))
    assertEquals(check(Map("status" -> Seq("lost"))), Seq(("invalid_parameter", "query status")))
    assertEquals(check(Map("debug" -> Seq("1"))), Seq.empty, "unknown parameters are let through unless asked")
    assertEquals(check(Map("debug" -> Seq("1")), unknown = true), Seq(("unknown_parameter", "query debug")))
  }

  test("a required header is required, and its format asserted") {
    val m = op("POST", "/v1/pets")
    assertEquals(contract.checkParameters(m, Map.empty, Map.empty, false).map(_.kind), Seq("missing_parameter"))
    assertEquals(contract.checkParameters(m, Map.empty, Map("x-request-id" -> "nope"), false).map(_.kind), Seq("invalid_parameter"))
    assertEquals(contract.checkParameters(m, Map.empty, Map("x-request-id" -> "3f2b8c1e-9a4d-4e2f-8b1a-0c9d8e7f6a5b"), false), Seq.empty)
  }

  test("a body is required, of a declared media type, and matching its schema through references") {
    val m = op("POST", "/v1/pets")
    assertEquals(contract.bodyMedia(m, None, hasBody = false).left.map(_.kind), Left("missing_body"))
    assertEquals(contract.bodyMedia(m, Some("text/plain"), hasBody = true).left.map(v => (v.kind, v.status)), Left(("unsupported_media_type", 415)))
    val media = contract.bodyMedia(m, Some("application/json; charset=utf-8"), hasBody = true).toOption.flatten.get
    assertEquals(contract.checkBody(media, """{"name":"rex","tag":null,"born":"2020-02-03"}"""), Seq.empty, "nullable, a date")
    def kinds(json: String) = contract.checkBody(media, json).map(_.kind)
    assertEquals(kinds("""{"tag":"x"}"""), Seq("invalid_body"), "name is required")
    assertEquals(kinds("""{"name":"rex","admin":true}"""), Seq("invalid_body"), "additionalProperties: false")
    assertEquals(kinds("""{"name":"rex","born":"yesterday"}"""), Seq("invalid_body"), "formats are asserted")
    assertEquals(kinds("""{"name":"""), Seq("invalid_body"), "not JSON")
    assert(contract.checkBody(media, """{"tag":"x"}""").head.detail.contains("name"), contract.checkBody(media, """{"tag":"x"}""").toString)
  }

  test("a response is checked against its status, its class, then default") {
    val list = op("GET", "/v1/pets")
    val media = contract.responseMedia(list, 200, Some("application/json"), hasBody = true).toOption.flatten.get
    assertEquals(contract.checkResponseBody(list, 200, media, """[{"id":1,"name":"rex"}]"""), Seq.empty)
    assertEquals(contract.checkResponseBody(list, 200, media, """[{"name":"rex"}]""").map(_.kind), Seq("invalid_response"))
    assert(contract.checkResponseBody(list, 200, media, """[{"name":"rex"}]""").head.detail.contains("required property 'id'"), "in English")
    assertEquals(contract.responseMedia(list, 500, None, hasBody = false).left.map(_.kind), Left("undeclared_status"))
    assertEquals(contract.responseMedia(list, 200, Some("text/html"), hasBody = true).left.map(_.kind), Left("undeclared_media_type"))
    assertEquals(contract.responseMedia(op("POST", "/v1/pets"), 422, None, hasBody = true), Right(None), "4XX")
    assertEquals(contract.responseMedia(op("DELETE", "/v1/pets/1"), 503, None, hasBody = true), Right(None), "default")
  }

  test("a 3.1 contract in JSON reads its types as JSON Schema 2020-12 does") {
    val spec = Json.obj(
      "openapi" -> "3.1.0",
      "info"    -> Json.obj("title" -> "t", "version" -> "1"),
      "paths"   -> Json.obj(
        "/items/{id}" -> Json.obj(
          "put" -> Json.obj(
            "parameters"  -> Json.arr(Json.obj("name" -> "id", "in" -> "path", "required" -> true, "schema" -> Json.obj("type" -> "string", "pattern" -> "^[a-z]+$"))),
            "requestBody" -> Json.obj(
              "content" -> Json.obj("application/merge-patch+json" -> Json.obj("schema" -> Json.obj("type" -> "object", "properties" -> Json.obj("note" -> Json.obj("type" -> Json.arr("string", "null"))))))
            ),
            "responses"   -> Json.obj("204" -> Json.obj("description" -> "done"))
          )
        )
      )
    )
    val c     = ContractCompiler.compile(Json.stringify(spec)).fold(e => fail(e), identity)
    assertEquals(c.basePath, "")
    val m     = c.resolve("PUT", "/items/abc").toOption.flatten.get
    assertEquals(c.checkParameters(m, Map.empty, Map.empty, false), Seq.empty)
    assertEquals(c.checkParameters(c.resolve("PUT", "/items/ABC").toOption.flatten.get, Map.empty, Map.empty, false).map(_.kind), Seq("invalid_parameter"))
    val media = c.bodyMedia(m, Some("application/merge-patch+json"), hasBody = true).toOption.flatten.get
    assertEquals(c.checkBody(media, """{"note":null}"""), Seq.empty)
    assertEquals(c.checkBody(media, """{"note":3}""").map(_.kind), Seq("invalid_body"))
    assertEquals(c.bodyMedia(m, Some("application/json"), hasBody = false), Right(None), "an optional body")
  }

  test("what is not an OpenAPI 3 contract says why, and a reference out of the document is a warning") {
    assertEquals(ContractCompiler.compile("").left.toOption.isDefined, true)
    assert(ContractCompiler.compile("""{"swagger":"2.0"}""").left.exists(_.contains("Swagger 2")))
    assert(ContractCompiler.compile("""{"openapi":"2.5"}""").left.exists(_.contains("not supported")))
    assert(ContractCompiler.compile("openapi: [unclosed").isLeft)
    val c = ContractCompiler.compile(
      """{"openapi":"3.0.0","paths":{"/a":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Missing"}}}},"responses":{}}}}}""",
      basePath = "api/"
    ).fold(e => fail(e), identity)
    assertEquals(c.basePath, "/api")
    assert(c.warnings.exists(_.contains("Missing")), s"${c.warnings}")
    val m = c.resolve("POST", "/api/a").toOption.flatten.get
    assertEquals(c.bodyMedia(m, Some("application/json"), hasBody = true).map(_.map(media => c.checkBody(media, "{}"))), Right(Some(Seq.empty)), "a schema that cannot run checks nothing")
  }
}
