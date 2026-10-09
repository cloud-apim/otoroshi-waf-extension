package com.cloud.apim.otoroshi.extensions.waf.access

import otoroshi.env.Env
import otoroshi.next.extensions.AdminExtensionBackofficeAuthRoute
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.Future

/**
 * Who may call the extension's backoffice routes.
 *
 * Otoroshi hands a backoffice route every request that reaches the backoffice host, session or not:
 * the user is an `Option`, and `None` is a caller nobody logged in. So every route goes through here
 * when the extension declares it, rather than each handler remembering to check, and a route added
 * later is closed by default.
 *
 * Reading needs a logged-in user. Anything else needs a super admin, unless the route is a
 * computation: it answers from what it is sent and from the installed state, writes nothing, and
 * reaches no address the caller chose. A route that is not a read and is not listed below is a write.
 *
 * A write also has to be sent as json. The session cookie is `SameSite=Lax`, which keeps another
 * site's form from carrying it but not a page on a sibling subdomain; json is what such a page cannot
 * send without a preflight the backoffice never answers.
 */
object BackofficeAccess {

  private val root = "/extensions/cloud-apim/extensions/waf"

  val computations: Set[String] = Set(
    s"$root/utils/_compile",
    s"$root/utils/_test",
    s"$root/utils/_rules",
    s"$root/security/_contract_check",
    s"$root/security/_robots_txt",
    s"$root/security/_challenge_from_preset",
    s"$root/security/_simulate",
    // reads a score, and forgets one only for a super admin: the handler tells the two apart
    s"$root/security/_ledger",
    s"$root/tuning/_propose",
    s"$root/tuning/_preview",
    s"$root/learning/_report",
    // checks a bundle it is sent, and fetches nothing
    s"$root/feeds/_check",
    s"$root/reputation/_template",
    s"$root/reputation/_lookup",
    s"$root/reputation/_geo"
  )

  /** Pages answer an anonymous caller themselves, with the redirect to the login page. */
  val pages: Set[String] = Set(
    "/extensions/cloud-apim/threat-studio",
    "/extensions/cloud-apim/threat-studio/*"
  )

  def isRead(method: String): Boolean = method == "GET" || method == "HEAD"

  def needsSuperAdmin(route: AdminExtensionBackofficeAuthRoute): Boolean =
    !isRead(route.method) && !computations.contains(route.path)

  private def isJson(req: RequestHeader): Boolean =
    req.contentType.exists(t => t.equalsIgnoreCase("application/json") || t.toLowerCase.endsWith("+json"))

  private def unauthorized: Future[Result] =
    Results.Unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> "you're not logged in")).vfuture

  private def forbidden: Future[Result] =
    Results.Forbidden(Json.obj("error" -> "forbidden", "error_description" -> "this action requires a super admin")).vfuture

  private def notJson: Future[Result] =
    Results
      .UnsupportedMediaType(Json.obj("error" -> "unsupported_media_type", "error_description" -> "the body must be sent as application/json"))
      .vfuture

  def guard(routes: Seq[AdminExtensionBackofficeAuthRoute])(using env: Env): Seq[AdminExtensionBackofficeAuthRoute] =
    routes.map(guard)

  def guard(route: AdminExtensionBackofficeAuthRoute)(using env: Env): AdminExtensionBackofficeAuthRoute = {
    val read       = isRead(route.method)
    val superAdmin = needsSuperAdmin(route)
    val page       = pages.contains(route.path)
    route.copy(handle = (ctx, req, user, body) =>
      user match {
        case None if page                                        => route.handle(ctx, req, user, body)
        case None                                                => unauthorized
        case Some(u) if superAdmin && !u.rights.superAdmin       => forbidden
        case Some(_) if !read && route.wantsBody && !isJson(req) => notJson
        case Some(_)                                             => route.handle(ctx, req, user, body)
      }
    )
  }
}
