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
 * session, so each route of the extension is called the three ways that matter: by nobody, by an
 * admin of one tenant, and by a super admin. The routes are read off the extension rather than
 * listed here, so a route added later is covered without touching this suite.
 */
class BackofficeAccessIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private given otoroshi.env.Env                 = Gateway.instance.env
  private given scala.concurrent.ExecutionContext = Gateway.ec

  private val password = "backoffice-access-it"

  private def routes: Seq[AdminExtensionBackofficeAuthRoute] =
    Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get.backofficeAuthRoutes()

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

  private def register(username: String, rights: UserRights): Unit = {
    val admin = SimpleOtoroshiAdmin(
      username = username,
      password = BCrypt.hashpw(password, BCrypt.gensalt()),
      label = username,
      createdAt = DateTime.now(),
      typ = OtoroshiAdminType.SimpleAdmin,
      metadata = Map.empty,
      rights = rights,
      adminEntityValidators = Map.empty
    )
    Await.result(Gateway.instance.env.datastores.simpleAdminDataStore.registerUser(admin), 10.seconds)
  }

  private def login(username: String): Seq[WSCookie] = {
    val res = Gateway.await(
      Gateway.ws
        .url(s"http://127.0.0.1:${Gateway.port}/bo/simple/login")
        .withHttpHeaders("Host" -> Gateway.instance.env.backOfficeHost, "Content-Type" -> "application/json")
        .post(Json.stringify(Json.obj("username" -> username, "password" -> password)))
    )
    assertEquals(res.status, 200, s"could not log in as $username: ${res.body}")
    res.cookies.toSeq
  }

  private val superAdmin  = "super-admin@backoffice-access.it"
  private val tenantAdmin = "tenant-admin@backoffice-access.it"

  private var superCookies: Seq[WSCookie]  = Seq.empty
  private var tenantCookies: Seq[WSCookie] = Seq.empty

  override def beforeAll(): Unit = {
    register(superAdmin, UserRights.superAdmin)
    register(tenantAdmin, UserRights(Seq(UserRight(TenantAccess("default", true, true), Seq(TeamAccess("*", true, true))))))
    superCookies = login(superAdmin)
    tenantCookies = login(tenantAdmin)
  }

  override def afterAll(): Unit = {
    val store = Gateway.instance.env.datastores.simpleAdminDataStore
    Await.result(store.deleteUser(superAdmin), 10.seconds)
    Await.result(store.deleteUser(tenantAdmin), 10.seconds)
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

  test("an admin of one tenant reads, computes, and is refused every write") {
    routes.filterNot(r => BackofficeAccess.pages.contains(r.path)).foreach { route =>
      val res = call(route, tenantCookies)
      if (BackofficeAccess.needsSuperAdmin(route)) {
        assertEquals(res.status, 403, s"${route.method} ${route.path}: ${res.body}")
      } else {
        assert(res.status != 401 && res.status != 403, s"${route.method} ${route.path}: ${res.status} ${res.body}")
      }
    }
  }

  test("writes are the routes that change state or reach an address the caller chose") {
    val writes = routes.filter(BackofficeAccess.needsSuperAdmin).map(_.path.split("/").last).toSet
    Seq(
      "_ban", "_unban", "_extend", "_allow", "_disallow", "_incident_state",
      "_apply", "_start", "_stop", "_discard", "_refresh", "_promote", "_rollback", "_crowdsec_sync",
      "_alert_test", "_scanner_test", "_contract_fetch", "workspaces"
    ).foreach(w => assert(writes.contains(w), s"$w is not treated as a write"))
  }

  test("forgetting a score needs a super admin, reading one does not") {
    val ledger = routes.find(_.path.endsWith("/security/_ledger")).get
    def ledgerCall(cookies: Seq[WSCookie], forget: Boolean): WSResponse = Gateway.await(
      Gateway.ws
        .url(s"http://127.0.0.1:${Gateway.port}${ledger.path}")
        .withHttpHeaders("Host" -> Gateway.instance.env.backOfficeHost, "Content-Type" -> "application/json")
        .withCookies(cookies*)
        .post(Json.stringify(Json.obj("ref" -> "ip:203.0.113.251", "forget" -> forget)))
    )
    assertEquals(ledgerCall(tenantCookies, forget = false).status, 200)
    assertEquals(ledgerCall(tenantCookies, forget = true).status, 403)
    assertEquals(ledgerCall(superCookies, forget = true).status, 200)
  }

  test("a super admin is let through, and a write has to be sent as json") {
    val unban = routes.find(_.path.endsWith("/security/_unban")).get
    assertEquals(call(unban, superCookies).status, 200)
    assertEquals(call(unban, superCookies, contentType = "text/plain").status, 415)
    assertEquals(call(unban, superCookies, contentType = "application/x-www-form-urlencoded").status, 415)
  }
}
