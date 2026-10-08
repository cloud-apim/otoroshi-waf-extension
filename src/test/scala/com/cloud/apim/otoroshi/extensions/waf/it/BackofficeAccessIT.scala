package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.access.BackofficeAccess
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models.*
import otoroshi.next.extensions.AdminExtensionBackofficeAuthRoute
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import play.api.libs.json.Json
import play.api.libs.ws.{WSCookie, WSResponse}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Who may call the extension's backoffice routes, asked of the gateway over http.
 *
 * Otoroshi hands a backoffice route every request that reaches the backoffice host, with or without a
 * session, so each route of the extension is called by nobody, then by a logged-in admin of one
 * tenant. The routes are read off the extension rather than listed here, so a route added later is
 * covered without touching this suite.
 */
class BackofficeAccessIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private given otoroshi.env.Env                 = Gateway.instance.env
  private given scala.concurrent.ExecutionContext = Gateway.ec

  private val password = "backoffice-access-it"

  private def routes: Seq[AdminExtensionBackofficeAuthRoute] =
    Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.backofficeAuthRoutes()

  private def route(suffix: String): AdminExtensionBackofficeAuthRoute = routes.find(_.path.endsWith(suffix)).get

  /** A path the route answers: its parameters filled with something that names nothing. */
  private def concrete(route: AdminExtensionBackofficeAuthRoute): String =
    route.path.split("/").map(s => if (s.startsWith(":") || s == "*") "nothing" else s).mkString("/")

  private def call(route: AdminExtensionBackofficeAuthRoute, cookies: Seq[WSCookie], contentType: String = "application/json"): WSResponse = {
    val req = Gateway.ws
      .url(s"http://127.0.0.1:${Gateway.port}${concrete(route)}")
      .withHttpHeaders("Host" -> Gateway.instance.env.backOfficeHost, "Accept" -> "application/json")
      .withCookies(cookies*)
      .withFollowRedirects(false)
      .withMethod(route.method)
    Gateway.await(
      if (route.wantsBody) req.addHttpHeaders("Content-Type" -> contentType).withBody("{}").execute()
      else req.execute()
    )
  }

  private val admin = "tenant-admin@backoffice-access.it"

  private var cookies: Seq[WSCookie] = Seq.empty

  override def beforeAll(): Unit = {
    val user = SimpleOtoroshiAdmin(
      username = admin,
      password = BCrypt.hashpw(password, BCrypt.gensalt()),
      label = admin,
      createdAt = DateTime.now(),
      typ = OtoroshiAdminType.SimpleAdmin,
      metadata = Map.empty,
      rights = UserRights(Seq(UserRight(TenantAccess("default", true, true), Seq(TeamAccess("*", true, true))))),
      adminEntityValidators = Map.empty
    )
    Await.result(Gateway.instance.env.datastores.simpleAdminDataStore.registerUser(user), 10.seconds)
    val res = Gateway.await(
      Gateway.ws
        .url(s"http://127.0.0.1:${Gateway.port}/bo/simple/login")
        .withHttpHeaders("Host" -> Gateway.instance.env.backOfficeHost, "Content-Type" -> "application/json")
        .post(Json.stringify(Json.obj("username" -> admin, "password" -> password)))
    )
    assertEquals(res.status, 200, s"could not log in: ${res.body}")
    cookies = res.cookies.toSeq
  }

  override def afterAll(): Unit = {
    Await.result(Gateway.instance.env.datastores.simpleAdminDataStore.deleteUser(admin), 10.seconds)
    ()
  }

  test("the extension declares backoffice routes, and they all go through the guard") {
    assert(routes.size > 40, s"only ${routes.size} routes")
  }

  test("nobody logged in gets 401 on every route, and the pages send them to the login") {
    routes.foreach { route =>
      val res = call(route, Seq.empty)
      if (BackofficeAccess.pages.contains(route.path)) {
        assert(res.status == 303 || res.status == 302, s"${route.method} ${route.path}: ${res.status}")
      } else {
        assertEquals(res.status, 401, s"${route.method} ${route.path}: ${res.body}")
      }
    }
  }

  test("a logged-in admin reads every route as before") {
    routes.filter(r => BackofficeAccess.isRead(r.method) && !BackofficeAccess.pages.contains(r.path)).foreach { route =>
      val res = call(route, cookies)
      assert(res.status != 401 && res.status != 415, s"${route.method} ${route.path}: ${res.status} ${res.body}")
    }
  }

  test("a logged-in admin writes as before, as long as the write is sent as json") {
    // an unban that names nobody: it goes through and changes nothing
    val unban = route("/security/_unban")
    assertEquals(call(unban, cookies).status, 200)
    assertEquals(call(unban, cookies, contentType = "text/plain").status, 415)
    assertEquals(call(unban, cookies, contentType = "application/x-www-form-urlencoded").status, 415)
  }

  test("the studio still answers its own rule on the table") {
    // writing the table was already reserved to a super admin by the studio itself
    val save = routes.find(r => r.method == "PUT" && r.path.endsWith("/studio/workspaces")).get
    assertEquals(call(save, cookies).status, 403)
  }
}
