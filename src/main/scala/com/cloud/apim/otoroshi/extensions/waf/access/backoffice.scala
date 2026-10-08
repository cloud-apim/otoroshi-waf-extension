package com.cloud.apim.otoroshi.extensions.waf.access

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
 * later is closed by default. What a logged-in user may then do is each route's own business; this
 * only makes sure there is one.
 *
 * A write also has to be sent as json. The session cookie is `SameSite=Lax`, which keeps another
 * site's form from carrying it but not a page on a sibling subdomain; json is what such a page cannot
 * send without a preflight the backoffice never answers.
 */
object BackofficeAccess {

  /** Pages answer an anonymous caller themselves, with the redirect to the login page. */
  val pages: Set[String] = Set(
    "/extensions/cloud-apim/threat-studio",
    "/extensions/cloud-apim/threat-studio/*"
  )

  def isRead(method: String): Boolean = method == "GET" || method == "HEAD"

  private def isJson(req: RequestHeader): Boolean =
    req.contentType.exists(t => t.equalsIgnoreCase("application/json") || t.toLowerCase.endsWith("+json"))

  private def unauthorized: Future[Result] =
    Results.Unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> "you're not logged in")).vfuture

  private def notJson: Future[Result] =
    Results
      .UnsupportedMediaType(Json.obj("error" -> "unsupported_media_type", "error_description" -> "the body must be sent as application/json"))
      .vfuture

  def guard(routes: Seq[AdminExtensionBackofficeAuthRoute]): Seq[AdminExtensionBackofficeAuthRoute] =
    routes.map(guard)

  def guard(route: AdminExtensionBackofficeAuthRoute): AdminExtensionBackofficeAuthRoute = {
    val read = isRead(route.method)
    val page = pages.contains(route.path)
    route.copy(handle = (ctx, req, user, body) =>
      user match {
        case None if page                                        => route.handle(ctx, req, user, body)
        case None                                                => unauthorized
        case Some(_) if !read && route.wantsBody && !isJson(req) => notJson
        case Some(_)                                             => route.handle(ctx, req, user, body)
      }
    )
  }
}
