package com.cloud.apim.otoroshi.extensions.waf.it

import otoroshi.models.{EntityLocation, TeamId, TenantId}
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*
import play.api.libs.ws.{WSAuthScheme, WSRequest, WSResponse}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String

/**
 * Calls of the studio's admin api with api keys that carry admin api rights of their own, the way a
 * team's key or an application's service account would.
 */
object StudioApiClient {

  val base = "/api/extensions/cloud-apim/extensions/waf/studio"

  final case class Key(id: String) {
    def secret: String = if (id == "admin-api-apikey-id") "admin-api-apikey-secret" else s"$id-secret"
  }

  /** The admin api key of a fresh install: no rights metadata, so no restriction. */
  val superKey: Key = Key("admin-api-apikey-id")

  def teamRights(team: String, write: Boolean = true): JsValue =
    Json.arr(Json.obj("tenant" -> s"default:${if (write) "rw" else "r"}", "teams" -> Json.arr(s"$team:${if (write) "rw" else "r"}")))

  val tenantAdminRights: JsValue = Json.arr(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("*:rw")))

  def createKey(key: Key, rights: JsValue): Unit = {
    val template = Gateway.await(Gateway.admin("/apis/apim.otoroshi.io/v1/apikeys/_template").get()).json.as[JsObject]
    val res      = Gateway.post(
      "/apis/apim.otoroshi.io/v1/apikeys",
      template ++ Json.obj(
        "clientId"           -> key.id,
        "clientSecret"       -> key.secret,
        "clientName"         -> key.id,
        "enabled"            -> true,
        "authorizedEntities" -> Json.arr("group_admin-api-group"),
        "metadata"           -> Json.obj("otoroshi-access-rights" -> Json.stringify(rights))
      )
    )
    if (res.status > 299) throw new RuntimeException(s"could not create the key ${key.id}: ${res.status} ${res.body}")
  }

  def deleteKey(key: Key): Unit = { Gateway.delete(s"/apis/apim.otoroshi.io/v1/apikeys/${key.id}"); () }

  /** A request on the admin api; `path` is under the studio api unless it starts with `/api/`. */
  def as(key: Key, path: String, headers: (String, String)*): WSRequest =
    Gateway.ws
      .url(s"http://127.0.0.1:${Gateway.port}${if (path.startsWith("/api/")) path else s"$base$path"}")
      .withHttpHeaders((Seq("Host" -> "otoroshi-api.oto.tools", "Content-Type" -> "application/json") ++ headers)*)
      .withAuth(key.id, key.secret, WSAuthScheme.BASIC)

  def get(key: Key, path: String): WSResponse = Gateway.await(as(key, path).get())

  def send(key: Key, method: String, path: String, body: JsValue, headers: (String, String)*): WSResponse =
    Gateway.await(as(key, path, headers*).withMethod(method).withBody(Json.stringify(body)).execute())

  def tagged(tag: String): CloudApimSecuritySuiteTarget =
    CloudApimSecuritySuiteTarget(path = Some("$.tags"), value = JsString(s"Contains($tag)"))

  def target(tag: String): JsValue = CloudApimSecuritySuiteTarget.format.writes(tagged(tag))

  def rule(id: String, tag: String): CloudApimSecuritySuiteGlobalRule =
    CloudApimSecuritySuiteGlobalRule(id = id, name = id, targets = Seq(tagged(tag)))

  def of(team: String): EntityLocation = EntityLocation(TenantId.default, Seq(TeamId(team)))
}
