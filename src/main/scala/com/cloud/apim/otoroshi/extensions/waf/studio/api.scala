package com.cloud.apim.otoroshi.extensions.waf.studio

import com.cloud.apim.otoroshi.extensions.waf.analytics.{PostureReport, RouteGovernance}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.actions.ApiActionContextCapable
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Audit}
import otoroshi.models.ApiKey
import otoroshi.next.extensions.*
import otoroshi.next.models.{NgPlugins, NgRoute}
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.{RequestHeader, Result, Results}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try
import scala.util.control.NoStackTrace

final case class ThreatStudioApiError(status: Int, error: String, description: String, extra: JsObject = Json.obj())
    extends RuntimeException(description)
    with NoStackTrace {
  def result: Result = Results.Status(status)(Json.obj("error" -> error, "error_description" -> description) ++ extra)
}

object ThreatStudioApiError {
  def badRequest(description: String): ThreatStudioApiError = ThreatStudioApiError(400, "bad_request", description)
  def forbidden(description: String): ThreatStudioApiError  = ThreatStudioApiError(403, "forbidden", description)
  def notFound(description: String): ThreatStudioApiError   = ThreatStudioApiError(404, "not_found", description)
  def conflict(description: String, extra: JsObject = Json.obj()): ThreatStudioApiError =
    ThreatStudioApiError(409, "conflict", description, extra)
  def preconditionRequired(description: String): ThreatStudioApiError =
    ThreatStudioApiError(428, "precondition_required", description)
}

/** The person a call is made for, when the caller says so. It grants nothing: it is what the audit names. */
final case class StudioActor(email: String, name: String) {
  def json: JsValue = Json.obj("email" -> email, "name" -> name)
}

object StudioActor {
  val EmailHeader                = "Threat-Studio-User-Email"
  val NameHeader                 = "Threat-Studio-User-Name"
  private val Email              = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$".r
  def of(req: RequestHeader): Option[StudioActor] =
    req.headers.get(EmailHeader).map(_.trim).filter(_.nonEmpty).map { email =>
      if (!Email.matches(email)) throw ThreatStudioApiError.badRequest(s"the '$EmailHeader' header must be an email")
      val name = req.headers
        .get(NameHeader)
        .map(n => Try(URLDecoder.decode(n, StandardCharsets.UTF_8)).getOrElse(n).trim)
        .filter(_.nonEmpty)
        .getOrElse(email)
      StudioActor(email.toLowerCase, name)
    }
}

/**
 * A call of the studio api, with the rights of its caller.
 *
 * Otoroshi checks nothing on the admin api routes of an extension, so the rights are the ones the
 * generic admin api applies, read the same way: an api key has the rights of its
 * `otoroshi-access-rights` metadata (none at all means no restriction), a call relayed by the
 * backoffice (`/bo/api/proxy`) the rights of its user, and `Otoroshi-Tenant` is the tenant the caller
 * works in.
 */
final case class ThreatStudioApiRequest(
    ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute],
    req: RequestHeader,
    apikey: ApiKey,
    body: JsValue
) extends ApiActionContextCapable {
  override def apiKey: ApiKey         = apikey
  override def request: RequestHeader = req
  def param(name: String): String     = ctx.named(name).getOrElse("--")
  def form: JsObject                  = body match {
    case o: JsObject => o
    case JsNull      => Json.obj()
    case _           => throw ThreatStudioApiError.badRequest("the body must be a json object")
  }
  // read when the call is built, so a malformed header is refused before anything runs
  val actor: Option[StudioActor] = StudioActor.of(req)
  // the version a client read the table at, quoted or not, weak or not
  def ifMatch: Option[String] =
    req.headers.get("If-Match").map(_.trim.stripPrefix("W/").stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty)
}

/**
 * The admin api of Threat Studio, served under `/api/extensions/cloud-apim/extensions/waf/studio`.
 *
 * A workspace is a rule of the global preset table, and the table is one document on the global
 * configuration whose order decides which rule wins a route. So the rights here are about routes,
 * since that is what a table change ends up changing:
 *
 *   - a caller sees every rule, and the routes it may read: whatever lists routes (claims, matches,
 *     postures) leaves the others out, and says how many it left out;
 *   - a change is accepted when the caller may write every route whose governing rule or protection
 *     it changes, and every route the rules it edits claim. A change that reaches no route at all
 *     (editing a rule that claims nothing, for one) needs an admin of the tenant, because whatever
 *     route comes to match that rule later gets what was written.
 *
 * Every read returns the version of the table it was resolved from, and every write can be made
 * conditional on it with `If-Match`; writing the whole table requires it.
 */
class ThreatStudioApi(env: Env, studio: ThreatStudio) {

  import ThreatStudioApiError.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext
  private given mat: Materializer    = env.otoroshiMaterializer
  private given ev: Env              = env

  private val logger = Logger("cloud-apim-threat-studio-api")

  val apiPath = "/api/extensions/cloud-apim/extensions/waf/studio"

  // the build of the extension, when it runs from its jar
  private val version: String = Option(getClass.getPackage).flatMap(p => Option(p.getImplementationVersion)).getOrElse("dev")

  // what this api offers, for a client to check the ones it needs before relying on them
  val features: Seq[String] = Seq(
    "caller-rights",
    "workspace-permissions",
    "table-version",
    "rule-writes",
    "table-preview",
    "actor"
  )

  // what a caller may do on a workspace, in the words of the studio front
  private val readPermissions  = Seq("workspace:read", "activity:read", "activity:details", "config:read")
  private val writePermissions = readPermissions ++ Seq("config:write", "incidents:respond")

  private val NewRuleId = "^[A-Za-z0-9_-]{1,64}$".r

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // the table as stored
  /////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * The table read from the datastore rather than from the cached global config, so a write is
   * checked against, and a read returns, what was last written.
   */
  final case class Stored(installed: Boolean, live: Boolean, config: CloudApimSecuritySuiteGlobalPresetConfig) {
    lazy val version: String = {
      val raw = Json.obj("installed" -> installed, "live" -> live, "table" -> config.json).stringify
      MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString.take(16)
    }
    def resolve(routes: Seq[NgRoute]): PostureReport.Table =
      PostureReport.table(routes, config, installed = installed, live = live)
    // what the rules would claim once the table applies, whether it does yet or not: the resolved
    // table itself when it already applies
    def potential(routes: Seq[NgRoute], resolved: PostureReport.Table): PostureReport.Table =
      if (live) resolved else PostureReport.table(routes, config, installed = true, live = true)
    def rule(id: String): CloudApimSecuritySuiteGlobalRule =
      config.rules.find(_.id == id).getOrElse(throw notFound("no such workspace"))
    def replace(id: String)(f: CloudApimSecuritySuiteGlobalRule => CloudApimSecuritySuiteGlobalRule): Stored = {
      rule(id)
      copy(config = config.copy(rules = config.rules.map(r => if (r.id == id) f(r) else r)))
    }
  }

  private def stored(): Future[Stored] =
    env.datastores.globalConfigDataStore.singleton().map { gc =>
      val slot   = NgPlugins.readFrom(gc.plugins.config.select("ng")).slots.find(_.plugin == CloudApimSecuritySuiteGlobalPreset.pluginId)
      val config = slot
        .flatMap(s => CloudApimSecuritySuiteGlobalPresetConfig.format.reads(s.config.raw).asOpt)
        .getOrElse(CloudApimSecuritySuiteGlobalPresetConfig.default)
      Stored(installed = slot.isDefined, live = slot.exists(_.enabled), config = config)
    }

  // one write of the table at a time on this node: each one reads the table, changes it and writes it
  // back whole, and two of them interleaved would lose one
  private val lastWrite = new AtomicReference[Future[Unit]](Future.unit)

  private def serialized[A](f: => Future[A]): Future[A] = {
    val done = Promise[Unit]()
    val prev = lastWrite.getAndSet(done.future)
    prev.recover { case _ => () }.flatMap(_ => f).andThen { case _ => done.success(()) }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // rights
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def readable(route: NgRoute)(using call: ThreatStudioApiRequest): Boolean = call.canUserRead(route)
  private def writable(route: NgRoute)(using call: ThreatStudioApiRequest): Boolean = call.canUserWrite(route)

  private def tenantAdmin(using call: ThreatStudioApiRequest): Boolean = call.backOfficeUser match {
    case Left(_)        => false
    case Right(None)    => true
    case Right(Some(u)) => u.rights.superAdmin || u.rights.tenantAdmin(call.currentTenant)
  }

  private def claimedBy(table: PostureReport.Table, route: NgRoute, ruleId: String): Boolean =
    table.governanceOf.get(route.id).flatMap(_.workspaceId).contains(ruleId)

  private def permissionsOf(rule: CloudApimSecuritySuiteGlobalRule, potential: PostureReport.Table, routes: Seq[NgRoute])(using
      call: ThreatStudioApiRequest
  ): Seq[String] = {
    val claimed = routes.filter(r => claimedBy(potential, r, rule.id))
    val writes  = if (claimed.isEmpty) tenantAdmin else claimed.forall(writable)
    if (writes) writePermissions else readPermissions
  }

  private def governanceChanged(before: PostureReport.Table, after: PostureReport.Table, routes: Seq[NgRoute]): Seq[NgRoute] =
    routes.filter { r =>
      val b = before.governanceOf.getOrElse(r.id, RouteGovernance.none)
      val a = after.governanceOf.getOrElse(r.id, RouteGovernance.none)
      b.workspaceId != a.workspaceId || b.preset != a.preset || b.selfManaged != a.selfManaged
    }

  // the rules a change adds, removes or edits
  private def editedRules(before: Stored, after: Stored): Set[String] = {
    val b = before.config.rules.map(r => r.id -> CloudApimSecuritySuiteGlobalRule.format.writes(r)).toMap
    val a = after.config.rules.map(r => r.id -> CloudApimSecuritySuiteGlobalRule.format.writes(r)).toMap
    (b.keySet ++ a.keySet).filter(id => b.get(id) != a.get(id))
  }

  /**
   * Every route a change reaches: the ones whose rule or protection changes now, the ones whose rule
   * or protection would change once the table applies (a change to a disabled table is still a
   * change to what it will do), and the ones the edited rules claim, before or after.
   *
   * Resolved with no request in flight, like everything the studio shows: for a table whose
   * selectors read the request, this is the same approximation the page already warns about.
   */
  private def reached(before: Stored, after: Stored, routes: Seq[NgRoute]): Seq[NgRoute] = {
    val bNow   = before.resolve(routes)
    val aNow   = after.resolve(routes)
    val bLater = before.potential(routes, bNow)
    val aLater = after.potential(routes, aNow)
    val edited = editedRules(before, after)
    val claims = routes.filter(r => edited.exists(id => claimedBy(bLater, r, id) || claimedBy(aLater, r, id)))
    (governanceChanged(bNow, aNow, routes) ++ governanceChanged(bLater, aLater, routes) ++ claims).distinctBy(_.id)
  }

  /** Why the caller may not write this change, if it may not. */
  private def refusal(before: Stored, after: Stored, routes: Seq[NgRoute])(using call: ThreatStudioApiRequest): Option[String] =
    if (before == after) None
    else {
      val concerned = reached(before, after, routes)
      if (concerned.isEmpty) {
        Option.when(!tenantAdmin)("this change reaches no route yet, so it needs an admin of the tenant")
      } else {
        val denied = concerned.count(r => !writable(r))
        Option.when(denied > 0)(s"this change would alter the protection of $denied route(s) you cannot write")
      }
    }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // reading
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def tableJson(s: Stored, routes: Seq[NgRoute])(using call: ThreatStudioApiRequest): JsObject = {
    val resolved  = s.resolve(routes)
    val potential = s.potential(routes, resolved)
    studio.tableJson(
      resolved,
      routes,
      Some(readable),
      rule => Json.obj("permissions" -> permissionsOf(rule, potential, routes))
    ) ++ Json.obj("version" -> s.version)
  }

  private def workspaceJson(s: Stored, id: String, routes: Seq[NgRoute])(using call: ThreatStudioApiRequest): JsObject = {
    s.rule(id)
    val table = tableJson(s, routes)
    (table \ "workspaces").as[Seq[JsObject]].find(w => (w \ "id").asOpt[String].contains(id)).get ++
    Json.obj("version" -> s.version)
  }

  private def routesJson(s: Stored, id: String, routes: Seq[NgRoute])(using call: ThreatStudioApiRequest): JsObject = {
    s.rule(id)
    val table   = s.resolve(routes)
    val claimed = routes.filter(r => claimedBy(table, r, id))
    val also    = routes.filter(r => table.governanceOf.get(r.id).exists(_.alsoMatched.contains(id)))
    def postures(rs: Seq[NgRoute]): JsArray =
      JsArray(rs.filter(readable).map(r => PostureReport.of(r, table.governanceOf.getOrElse(r.id, RouteGovernance.none)).json))
    Json.obj(
      "routes"        -> postures(claimed),
      "also_matched"  -> postures(also),
      "hidden_claims" -> claimed.count(r => !readable(r)),
      "version"       -> s.version
    )
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // writing
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def audit(action: String, message: String, meta: JsObject)(using call: ThreatStudioApiRequest): Unit =
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(call.apikey),
        // the backoffice user, for a call relayed by the backoffice
        call.user,
        action,
        message,
        call.req.theIpAddress,
        call.req.theUserAgent,
        meta ++ Json.obj("actor" -> Json.toJson(call.actor.map(_.json)))
      )
    )

  /**
   * Reads the table, applies the change, checks the caller may make it, and writes it back.
   *
   * `requireVersion` is for the writes that send a whole table: replacing it from a copy read before
   * someone else's change is exactly the lost update the version is there for.
   */
  private def mutate(action: String, requireVersion: Boolean = false)(change: Stored => Stored)(using
      call: ThreatStudioApiRequest
  ): Future[Stored] = serialized {
    stored().flatMap { before =>
      call.ifMatch match {
        case None if requireVersion         =>
          throw preconditionRequired("writing the whole table needs the version it was read at, in If-Match")
        case Some(v) if v != before.version =>
          throw conflict("the table changed since it was read", Json.obj("version" -> before.version))
        case _                              => ()
      }
      val after  = change(before)
      val routes = env.proxyState.allRoutes()
      refusal(before, after, routes).foreach(reason => throw forbidden(reason))
      if (after == before) before.vfuture
      else
        studio.writeTable(after.config, Some(after.live)).map { _ =>
          audit(
            s"THREAT_STUDIO_${action.toUpperCase}",
            s"Threat Studio api: $action",
            Json.obj(
              "rules"  -> JsArray(editedRules(before, after).toSeq.sorted.map(JsString.apply)),
              "before" -> before.version,
              "after"  -> after.version
            )
          )
          after
        }
    }
  }

  private def presetFrom(json: JsValue): CloudApimSecuritySuitePresetConfig =
    CloudApimSecuritySuitePresetConfig.format.reads(json) match {
      case JsSuccess(p, _) => p
      case JsError(errors) => throw badRequest(s"invalid preset: ${errors.map(_._1.toString).mkString(", ")}")
    }

  private def targetsFrom(json: JsValue): Seq[CloudApimSecuritySuiteTarget] = json match {
    case JsArray(values) =>
      values.toSeq.map { v =>
        CloudApimSecuritySuiteTarget.format.reads(v) match {
          case JsSuccess(t, _) if t.path.isDefined || t.expression.isDefined => t
          // the plugin reads such a target as matching nothing, which is never what was meant here
          case _                                                             => throw badRequest(s"invalid target: ${v.stringify}")
        }
      }
    case _               => throw badRequest("'targets' must be an array")
  }

  private def bool(form: JsObject, key: String): Option[Boolean] = form.value.get(key).map {
    case JsBoolean(b) => b
    case _            => throw badRequest(s"'$key' must be a boolean")
  }

  private def tableFrom(form: JsObject, before: Stored): Stored = {
    val config = CloudApimSecuritySuiteGlobalPresetConfig.format.reads(form) match {
      case JsSuccess(c, _) => c
      case JsError(_)      => throw badRequest("invalid table")
    }
    val rules = studio.withIds(config.rules)
    val known = before.config.rules.map(_.id).toSet
    rules.filterNot(r => known.contains(r.id)).find(r => !NewRuleId.matches(r.id)).foreach { r =>
      throw badRequest(s"'${r.id}' is not a valid workspace id: letters, digits, '_' and '-', 64 at most")
    }
    before.copy(
      installed = true,
      live = bool(form, "slot_enabled").getOrElse(if (before.installed) before.live else true),
      config = config.copy(rules = rules)
    )
  }

  private val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray.map(_.toString)

  private def newRuleId(form: JsObject): String = form.value.get("id") match {
    case None | Some(JsNull)                              => s"ws_${IdGenerator.token(alphabet, 10)}"
    case Some(JsString(id)) if NewRuleId.matches(id.trim) => id.trim
    case Some(_)                                          => throw badRequest("'id' must be letters, digits, '_' and '-', 64 at most")
  }

  private def createRule(before: Stored, form: JsObject, id: String): Stored = {
    if (before.config.rules.exists(_.id == id)) throw conflict(s"the workspace '$id' already exists")
    val rule         = CloudApimSecuritySuiteGlobalRule(
      id = id,
      name = form.select("name").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse(throw badRequest("'name' is required")),
      enabled = bool(form, "enabled").getOrElse(true),
      skip = bool(form, "skip").getOrElse(false),
      targets = form.value.get("targets").map(targetsFrom).getOrElse(Seq.empty),
      preset = form.value.get("preset").map(presetFrom).getOrElse(CloudApimSecuritySuitePresetConfig.default)
    )
    val rules        = before.config.rules
    // a new rule lands above the first catch-all unless told otherwise: below it, it could never win
    val catchAll     = rules.indexWhere(r => r.enabled && r.targets.isEmpty)
    val at           = form.select("position").asOpt[Int] match {
      case Some(p) if p >= 0 && p <= rules.size => p
      case Some(_)                              => throw badRequest(s"'position' must be between 0 and ${rules.size}")
      case None                                 => if (catchAll < 0) rules.size else catchAll
    }
    val (head, tail) = rules.splitAt(at)
    before.copy(
      installed = true,
      live = if (before.installed) before.live else true,
      config = before.config.copy(rules = head ++ (rule +: tail))
    )
  }

  private def move(before: Stored, id: String, to: Int): Stored = {
    val rules = before.config.rules
    val from  = rules.indexWhere(_.id == id)
    if (from < 0) throw notFound("no such workspace")
    if (to < 0 || to >= rules.size) throw badRequest(s"'to' must be between 0 and ${rules.size - 1}")
    val rule  = rules(from)
    val rest  = rules.patch(from, Nil, 1)
    before.copy(config = before.config.copy(rules = rest.patch(to, Seq(rule), 0)))
  }

  /** The routes a proposed table would move, and whether the caller could write it. */
  private def previewJson(before: Stored, after: Stored, routes: Seq[NgRoute])(using call: ThreatStudioApiRequest): JsObject = {
    val b       = before.resolve(routes)
    val a       = after.resolve(routes)
    val changed = governanceChanged(b, a, routes)
    def ruleRef(g: RouteGovernance): JsValue =
      Json.toJson(g.workspaceId.map(id => Json.obj("id" -> id, "name" -> g.workspaceName.getOrElse[String](id))))
    val reason  = refusal(before, after, routes)
    Json.obj(
      "changes" -> JsArray(changed.filter(readable).map { r =>
        val bg = b.governanceOf.getOrElse(r.id, RouteGovernance.none)
        val ag = a.governanceOf.getOrElse(r.id, RouteGovernance.none)
        Json.obj(
          "route"             -> studio.routeRef(r),
          "before"            -> ruleRef(bg),
          "after"             -> ruleRef(ag),
          "workspace_changed" -> (bg.workspaceId != ag.workspaceId),
          "self_managed"      -> ag.selfManaged
        )
      }),
      "hidden"  -> changed.count(r => !readable(r)),
      "allowed" -> reason.isEmpty,
      "reason"  -> Json.toJson(reason),
      "version" -> before.version
    )
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // routes
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def route(method: String, path: String, wantsBody: Boolean = false)(
      handle: ThreatStudioApiRequest ?=> Future[Result]
  ): AdminExtensionAdminApiRoute =
    AdminExtensionAdminApiRoute(
      method = method,
      path = s"$apiPath$path",
      wantsBody = wantsBody,
      handle = (
          ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute],
          req: RequestHeader,
          apikey: ApiKey,
          body: Option[Source[ByteString, ?]]
      ) => {
        val fuBody: Future[JsValue] = body match {
          case None      => JsNull.vfuture
          case Some(src) =>
            src.runFold(ByteString.empty)(_ ++ _).map { bytes =>
              if (bytes.isEmpty) JsNull else Try(Json.parse(bytes.utf8String)).getOrElse(throw badRequest("the body is not valid json"))
            }
        }
        fuBody
          .flatMap { json =>
            given call: ThreatStudioApiRequest = ThreatStudioApiRequest(ctx, req, apikey, json)
            if (call.backOfficeUser.isLeft) throw forbidden("you're not authorized here")
            handle
          }
          .recover {
            case e: ThreatStudioApiError => e.result
            case e: Throwable            =>
              logger.error(s"error while handling threat studio api call $method ${req.path}", e)
              Results.InternalServerError(Json.obj("error" -> "internal_error", "error_description" -> e.getMessage))
          }
      }
    )

  private def call(using r: ThreatStudioApiRequest): ThreatStudioApiRequest = r

  private def withEtag(json: JsObject, version: String): Result =
    Results.Ok(json).withHeaders("ETag" -> s"\"$version\"")

  val routes: Seq[AdminExtensionAdminApiRoute] = Seq(
    route("GET", "/_info") {
      Results
        .Ok(Json.obj("version" -> version, "features" -> features, "config" -> ThreatStudioConfig.current(env).frontendJson))
        .vfuture
    },

    // the table

    route("GET", "/workspaces") {
      stored().map(s => withEtag(tableJson(s, env.proxyState.allRoutes()), s.version))
    },
    route("PUT", "/workspaces", wantsBody = true) {
      mutate("save_table", requireVersion = true)(before => tableFrom(call.form, before))
        .map(s => withEtag(tableJson(s, env.proxyState.allRoutes()), s.version))
    },
    route("POST", "/table/_preview", wantsBody = true) {
      stored().map(before => Results.Ok(previewJson(before, tableFrom(call.form, before), env.proxyState.allRoutes())))
    },
    route("PUT", "/table/settings", wantsBody = true) {
      mutate("table_settings") { before =>
        val form = call.form
        before.copy(
          installed = true,
          live = bool(form, "enabled").getOrElse(if (before.installed) before.live else true),
          config = before.config.copy(skipProtectedRoutes = bool(form, "skip_protected_routes").getOrElse(before.config.skipProtectedRoutes))
        )
      }.map(s => withEtag(tableJson(s, env.proxyState.allRoutes()), s.version))
    },

    // one workspace

    route("POST", "/workspaces", wantsBody = true) {
      val id = newRuleId(call.form)
      mutate("create_workspace")(before => createRule(before, call.form, id))
        .map(s => Results.Created(workspaceJson(s, id, env.proxyState.allRoutes())))
    },
    route("GET", "/workspaces/:id") {
      stored().map(s => withEtag(workspaceJson(s, call.param("id"), env.proxyState.allRoutes()), s.version))
    },
    route("GET", "/workspaces/:id/routes") {
      stored().map(s => withEtag(routesJson(s, call.param("id"), env.proxyState.allRoutes()), s.version))
    },
    route("PATCH", "/workspaces/:id", wantsBody = true) {
      val id = call.param("id")
      mutate("rename_workspace") { before =>
        val name = call.form.select("name").asOpt[String].map(_.trim).getOrElse(throw badRequest("'name' is required"))
        before.replace(id)(_.copy(name = name))
      }.map(s => withEtag(workspaceJson(s, id, env.proxyState.allRoutes()), s.version))
    },
    route("PATCH", "/workspaces/:id/preset", wantsBody = true) {
      val id = call.param("id")
      mutate("save_preset") { before =>
        // fields left out keep their value: a page sends what it edits and nothing else
        before.replace(id)(rule => rule.copy(preset = presetFrom(rule.preset.json.asObject ++ call.form)))
      }.map(s => withEtag(workspaceJson(s, id, env.proxyState.allRoutes()), s.version))
    },
    route("PUT", "/workspaces/:id/scope", wantsBody = true) {
      val id = call.param("id")
      mutate("save_scope") { before =>
        val form = call.form
        before.replace(id) { rule =>
          rule.copy(
            targets = form.value.get("targets").map(targetsFrom).getOrElse(rule.targets),
            enabled = bool(form, "enabled").getOrElse(rule.enabled),
            skip = bool(form, "skip").getOrElse(rule.skip)
          )
        }
      }.map(s => withEtag(workspaceJson(s, id, env.proxyState.allRoutes()), s.version))
    },
    route("POST", "/workspaces/:id/_move", wantsBody = true) {
      val id = call.param("id")
      mutate("move_workspace") { before =>
        move(before, id, call.form.select("to").asOpt[Int].getOrElse(throw badRequest("'to' is required")))
      }.map(s => withEtag(tableJson(s, env.proxyState.allRoutes()), s.version))
    },
    route("DELETE", "/workspaces/:id") {
      val id = call.param("id")
      mutate("delete_workspace") { before =>
        before.rule(id)
        before.copy(config = before.config.copy(rules = before.config.rules.filterNot(_.id == id)))
      }.map(_ => Results.NoContent)
    }
  )
}
