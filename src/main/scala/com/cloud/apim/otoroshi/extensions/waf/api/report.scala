package com.cloud.apim.otoroshi.extensions.waf.api

import com.cloud.apim.otoroshi.extensions.waf.analytics.{PostureReport, RouteGovernance}
import com.cloud.apim.otoroshi.extensions.waf.entities.ApiContract
import com.cloud.apim.otoroshi.extensions.waf.security.SecurityModule
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{CloudApimApiContract, CloudApimSecuritySuitePreset}
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** A credential check on a route: which plugin, what it is, which contract schemes it satisfies, where it applies. */
final case class AuthCheck(plugin: String, label: String, schemes: Set[String], include: Seq[String], exclude: Seq[String], enforcing: Boolean) {

  def covers(path: String): Boolean =
    (include.isEmpty || include.exists(p => Try(otoroshi.utils.RegexPool.regex(p).matches(path)).getOrElse(false))) &&
      !exclude.exists(p => Try(otoroshi.utils.RegexPool.regex(p).matches(path)).getOrElse(false))

  def json: JsValue = Json.obj(
    "plugin"    -> plugin,
    "label"     -> label,
    "schemes"   -> schemes.toSeq.sorted,
    "include"   -> include,
    "exclude"   -> exclude,
    "enforcing" -> enforcing
  )
}

/** One endpoint of the auth posture matrix, and what is wrong with it if anything. */
final case class AuthFinding(severity: String, kind: String, method: String, path: String, detail: String, checks: Seq[String], declared: Option[Seq[Set[String]]]) {
  def json: JsValue = Json.obj(
    "severity" -> severity,
    "kind"     -> kind,
    "method"   -> method,
    "path"     -> path,
    "detail"   -> detail,
    "checks"   -> checks,
    "declared" -> declared.map(alternatives => JsArray(alternatives.map(a => JsArray(a.toSeq.sorted.map(JsString.apply)))))
  )
}

/**
 * Which credentials apply where, as deployed (API-4).
 *
 * Read off the route's plugin chain — the plugins that refuse a request without a credential, and the
 * paths they are scoped to — and compared with what the contract declares for each operation. Only
 * the route's own plugins are read: a credential checked by a global plugin is not seen here.
 */
object AuthPosture {

  // the plugins that refuse a request with no credential, and the contract schemes each one satisfies
  private val known: Map[String, (String, Set[String])] = {
    val apikey = ("api key", Set("apiKey", "http:basic", "http:bearer"))
    val module = ("authentication module", Set("oauth2", "openIdConnect", "http:basic"))
    val jwt    = ("JWT", Set("http:bearer", "oauth2", "openIdConnect", "apiKey"))
    val oidc   = ("OAuth2 / OIDC token", Set("http:bearer", "oauth2", "openIdConnect"))
    val cert   = ("client certificate", Set("mutualTLS"))
    Map(
      "ApikeyCalls"                          -> apikey,
      "NgLegacyApikeyCall"                   -> apikey,
      "ApikeyAuthModule"                     -> ("api key", Set("apiKey", "http:basic")),
      "NgCertificateAsApikey"                -> cert,
      "AuthModule"                           -> module,
      "NgLegacyAuthModuleCall"               -> module,
      "MultiAuthModule"                      -> module,
      "BasicAuthWithAuthModule"              -> ("basic auth", Set("http:basic")),
      "NgAuthModuleExpectedUser"             -> ("authenticated user", Set("oauth2", "openIdConnect")),
      "JwtVerification"                      -> jwt,
      "JwtVerificationOnly"                  -> jwt,
      "OIDCJwtVerifier"                      -> oidc,
      "OIDCAccessTokenValidator"             -> oidc,
      "OIDCAccessTokenAsApikey"              -> oidc,
      "NgBiscuitValidator"                   -> ("biscuit", Set("http:bearer", "apiKey")),
      "NgHasClientCertValidator"             -> cert,
      "NgHasClientCertMatchingValidator"     -> cert,
      "NgHasClientCertMatchingApikeyValidator" -> cert,
      "NgHasClientCertMatchingHttpValidator" -> cert,
      "HMACValidator"                        -> ("HMAC signature", Set("http:signature", "apiKey")),
      "HttpSignatureVerifyRequest"           -> ("HTTP signature", Set("http:signature")),
      "SimpleBasicAuth"                      -> ("basic auth", Set("http:basic"))
    )
  }

  def checksOf(route: NgRoute): Seq[AuthCheck] =
    route.plugins.slots.filter(_.enabled).flatMap { slot =>
      val name = slot.plugin.split('.').last
      known.get(name).map { case (label, schemes) =>
        // an api key that is not mandatory, or not validated, lets a request without one through
        val enforcing = name match {
          case "ApikeyCalls" | "NgLegacyApikeyCall" =>
            (slot.config.raw \ "mandatory").asOpt[Boolean].getOrElse(true) && (slot.config.raw \ "validate").asOpt[Boolean].getOrElse(true)
          case _                                    => true
        }
        AuthCheck(name, label, schemes, slot.include, slot.exclude, enforcing)
      }
    }

  /** A concrete path for an operation's template, for the scoping patterns to be tried on. */
  def samplePath(basePath: String, template: String): String =
    basePath + ContractPaths.split(template).map(s => if (ContractPaths.isParam(s)) "1" else s).mkString("/", "/", "")

  def findings(route: NgRoute, contract: Option[CompiledContract]): (Seq[AuthCheck], Seq[AuthFinding]) = {
    val checks = checksOf(route)
    (checks, findingsOf(checks, route.frontend.domains.map(_.path).distinct, contract))
  }

  /** The matrix itself: what `checks` cover, endpoint by endpoint, against what `contract` declares. */
  def findingsOf(checks: Seq[AuthCheck], routePaths: Seq[String], contract: Option[CompiledContract]): Seq[AuthFinding] = {
    def covering(path: String) = checks.filter(c => c.enforcing && c.covers(path))
    contract match {
      case None    =>
        routePaths.flatMap { p =>
          val path = if (p.isEmpty) "/" else p
          val on   = covering(path)
          Option.when(on.isEmpty)(
            AuthFinding("low", "no_credential", "*", path, "nothing on the route checks a credential, and there is no contract to say whether that is meant", Seq.empty, None)
          )
        }
      case Some(c) =>
        c.operations.flatMap { op =>
          val path     = samplePath(c.basePath, op.path)
          val on       = covering(path)
          val schemes  = on.flatMap(_.schemes).toSet
          val labels   = on.map(_.label).distinct
          val endpoint = c.basePath + op.path
          (op.security, on.isEmpty) match {
            case (Some(alternatives), true) if alternatives.exists(_.nonEmpty) =>
              Some(AuthFinding("high", "unauthenticated", op.method, endpoint, s"the contract asks for ${describe(alternatives)}, and nothing on the route checks a credential", labels, op.security))
            case (Some(_), true)                                               =>
              Some(AuthFinding("info", "public", op.method, endpoint, "reachable with no credential, as the contract says", labels, op.security))
            case (None, true)                                                  =>
              Some(AuthFinding("low", "no_credential", op.method, endpoint, "reachable with no credential, and the contract says nothing about it", labels, None))
            case (Some(alternatives), false) if alternatives.forall(_.isEmpty) =>
              Some(AuthFinding("info", "undeclared_auth", op.method, endpoint, s"checked by ${labels.mkString(", ")}, while the contract says it is public", labels, op.security))
            case (Some(alternatives), false) if !alternatives.exists(_.exists(schemes.contains)) =>
              Some(AuthFinding("medium", "scheme_mismatch", op.method, endpoint, s"the contract asks for ${describe(alternatives)}, the route checks ${labels.mkString(", ")}", labels, op.security))
            case _                                                             => None
          }
        }
    }
  }

  private def describe(alternatives: Seq[Set[String]]): String =
    alternatives.filter(_.nonEmpty).map(_.toSeq.sorted.mkString(" and ")).mkString(" or ")
}

/**
 * The API reports of a set of routes (API-2, API-3, API-4): which contract governs each, what traffic
 * its operations get, the paths outside it, how its traffic drifts from it, and which credentials
 * apply where. Read from configuration and from the inventory, never from analytics.
 */
object ApiReport {

  private val contractPluginId = NgPluginHelper.pluginId[CloudApimApiContract]
  private val presetPluginId   = NgPluginHelper.pluginId[CloudApimSecuritySuitePreset]

  /** The contract a route is checked against, read the way its plugins would read it. */
  def contractOf(route: NgRoute, governance: RouteGovernance, mod: SecurityModule): Option[ApiContract] = {
    val slots                                 = route.plugins.slots.filter(_.enabled)
    def named(raw: JsValue, field: String)    = (raw \ field).asOpt[String].map(_.trim).filter(_.nonEmpty)
    val fromPlugin                            = slots.find(_.plugin == contractPluginId).map(s => named(s.config.raw, "contract"))
    val presets                               = slots.find(_.plugin == presetPluginId).map(_.config.raw).toSeq ++ governance.preset.toSeq
    val fromPreset                            = presets.find(p => (p \ "api_contract").asOpt[Boolean].contains(true)).map(named(_, "api_contract_id"))
    fromPlugin.orElse(fromPreset).flatMap(id => mod.apiContractOf(id, route.metadata))
  }

  def json(mod: SecurityModule, routeIds: Set[String], zombieDays: Int)(using env: Env, ec: ExecutionContext): Future[JsValue] =
    mod.apiInventory.merged().map { inventory =>
      val now      = System.currentTimeMillis()
      val all      = env.proxyState.allRoutes()
      val routes   = if (routeIds.isEmpty) all else all.filter(r => routeIds.contains(r.id))
      val table    = PostureReport.table(all)
      val zombieMs = zombieDays.toLong * 24L * 3600L * 1000L
      // every route asked for, every route of the router when none is: an audit that skips the routes
      // nobody configured anything on would skip exactly the ones it exists for
      val rows     = routes.flatMap { route =>
        val governance = table.governanceOf.getOrElse(route.id, RouteGovernance.none)
        val contract   = contractOf(route, governance, mod)
        val compiled   = contract.flatMap(c => mod.apiContracts.get(c).toOption)
        val records    = inventory.filter { case (k, _) => k.split('|').lift(1).contains(route.id) }
        val since      = records.values.map(_.first).filter(_ > 0L).minOption
        val (checks, auth) = AuthPosture.findings(route, compiled)
        val operations = compiled.toSeq.flatMap(_.operations).map { op =>
          val seen  = contract.flatMap(c => inventory.get(mod.apiInventory.operationKey(route.id, c.id, op.method, op.path)))
          val state = seen match {
            case Some(s) if now - s.last < zombieMs                         => "used"
            case _ if since.exists(now - _ >= zombieMs)                      => "zombie"
            case _                                                           => "unused"
          }
          Json.obj(
            "method"       -> op.method,
            "path"         -> op.path,
            "operation_id" -> op.operationId,
            "state"        -> state,
            "hits"         -> seen.map(_.hits).getOrElse(0L),
            "last_seen"    -> seen.map(_.last),
            "statuses"     -> seen.map(_.statusesJson)
          )
        }
        val shadows    = records.toSeq.collect { case (k, s) if k.startsWith("shadow|") =>
          val endpoint = k.split('|').drop(2).mkString("|")
          Json.obj(
            "method"    -> endpoint.takeWhile(_ != ' '),
            "path"      -> endpoint.dropWhile(_ != ' ').trim,
            "hits"      -> s.hits,
            "last_seen" -> s.last,
            "statuses"  -> s.statusesJson,
            // the backend answered it: an endpoint that exists and that nobody documented
            "confirmed" -> (s.statuses.lift(2).getOrElse(0L) > 0L)
          )
        }.sortBy(j => -(j \ "hits").as[Long])
        val drift      = records.toSeq.collect { case (k, s) if k.startsWith("drift|") =>
          val parts = k.split('|')
          Json.obj(
            "operation" -> parts.lift(3),
            "kind"      -> parts.lift(4),
            "where"     -> parts.drop(5).mkString("|"),
            "observed"  -> s.observed,
            "sensitive" -> s.sensitive,
            "count"     -> s.hits,
            "last_seen" -> s.last
          )
        }.sortBy(j => (!(j \ "sensitive").as[Boolean], -(j \ "count").as[Long]))
        Some(
          Json.obj(
            "route_id"       -> route.id,
            "route_name"     -> route.name,
            "contract"       -> contract.map(c => Json.obj("id" -> c.id, "name" -> c.name)),
            "compiles"       -> contract.map(c => mod.apiContracts.get(c).isRight),
            "observed_since" -> since,
            "operations"     -> operations,
            "shadows"        -> shadows,
            "drift"          -> drift,
            "auth"           -> Json.obj("checks" -> checks.map(_.json), "findings" -> auth.map(_.json))
          )
        )
      }
      def count(field: String, p: JsValue => Boolean) = rows.map(r => (r \ field).asOpt[Seq[JsValue]].getOrElse(Seq.empty).count(p)).sum
      def authCount(severity: String) = rows.map(r => (r \ "auth" \ "findings").as[Seq[JsValue]].count(f => (f \ "severity").as[String] == severity)).sum
      Json.obj(
        "zombie_after_days" -> zombieDays,
        "routes"            -> rows,
        "summary"           -> Json.obj(
          "routes"            -> rows.size,
          "with_contract"     -> rows.count(r => (r \ "contract").toOption.exists(_ != JsNull)),
          "operations"        -> count("operations", _ => true),
          "zombies"           -> count("operations", o => (o \ "state").as[String] == "zombie"),
          "unused"            -> count("operations", o => (o \ "state").as[String] == "unused"),
          "shadows"           -> count("shadows", _ => true),
          "confirmed_shadows" -> count("shadows", s => (s \ "confirmed").as[Boolean]),
          "drift"             -> count("drift", _ => true),
          "sensitive_drift"   -> count("drift", d => (d \ "sensitive").as[Boolean]),
          "auth"              -> Json.obj("high" -> authCount("high"), "medium" -> authCount("medium"), "low" -> authCount("low"), "info" -> authCount("info"))
        )
      )
    }
}
