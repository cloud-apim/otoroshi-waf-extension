package com.cloud.apim.otoroshi.extensions.waf.studio

import com.cloud.apim.otoroshi.extensions.waf.access.BackofficeAccess
import com.cloud.apim.otoroshi.extensions.waf.analytics.{PostureReport, RouteGovernance}
import com.cloud.apim.otoroshi.extensions.waf.api.ApiReport
import com.cloud.apim.otoroshi.extensions.waf.security.*
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.actions.ApiActionContextCapable
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Audit}
import otoroshi.models.{ApiKey, BackOfficeUser, UserRights}
import otoroshi.next.analytics.queries.{AnalyticsRuntime, Filters}
import otoroshi.next.extensions.*
import otoroshi.next.models.{NgPlugins, NgRoute}
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.{RequestHeader, Result, Results}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationLong
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
  // a call relayed by the backoffice (`/bo/api/proxy`), which signs the person it is made for
  val relayed: Boolean = req.headers.get("Otoroshi-BackOffice-User").isDefined
  // read when the call is built, so a malformed header is refused before anything runs; nobody renames
  // the person a relayed call is made for
  val actor: Option[StudioActor] = if (relayed) None else StudioActor.of(req)
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
 *
 * A person signed in to the backoffice uses the studio as the rest of the backoffice: their own rights
 * decide what they read and change, the extension's backoffice routes are guarded for them the same
 * way (BackofficeAccess), and what they do is done in their name. What keeps a workspace to what it
 * owns and saw (the entities of the other workspaces, the shared ones it may not change, the bans it
 * did not issue) is for the callers acting for the members of a workspace: an edition's service
 * account.
 */
class ThreatStudioApi(env: Env, ext: CloudApimWafExtension) {

  import ThreatStudioApiError.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext
  private given mat: Materializer    = env.otoroshiMaterializer
  private given ev: Env              = env

  private val logger = Logger("cloud-apim-threat-studio-api")

  private def studio: ThreatStudio      = ext.studio
  private def security: SecurityModule  = ext.security

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
    "actor",
    "workspace-analytics",
    "workspace-api-report",
    "workspace-incidents",
    "workspace-bans",
    "route-contract",
    "workspace-lookups",
    "module-routes",
    "workspace-entities",
    "entity-ownership",
    "secret-sentinel",
    "workspace-tuning",
    "workspace-learning",
    "entity-assign",
    "rule-preview"
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

  /** The person signed in to the backoffice, for a call it relays. */
  private def person(using call: ThreatStudioApiRequest): Option[BackOfficeUser] =
    if (call.relayed) call.backOfficeUser.toOption.flatten else None

  // a ban holds on every route of the gateway: for a person, it is a super admin's, as on the
  // extension's own backoffice routes
  private def requireGatewayWrite()(using call: ThreatStudioApiRequest): Unit =
    if (person.exists(!_.rights.superAdmin)) throw forbidden("this action requires a super admin")

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
    // with the contract each route names for itself, which the workspace's pages set on the routes the
    // admin api stores (`stored`): the ones an api or a service descriptor generates are not written here
    def postures(rs: Seq[NgRoute]): JsArray =
      JsArray(rs.filter(readable).map { r =>
        PostureReport.of(r, table.governanceOf.getOrElse(r.id, RouteGovernance.none)).json.as[JsObject] ++
        Json.obj("contract" -> Json.toJson(r.metadata.get(ContractMeta)), "stored" -> env.proxyState.rawRoute(r.id).isDefined)
      })
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

  // a type test rather than the JsBoolean extractor, whose signature differs between play-json
  // versions: a gateway built on another one would throw a NoSuchMethodError, fatal to its actor system
  private def bool(form: JsObject, key: String): Option[Boolean] = form.value.get(key).map {
    case b: JsBoolean => b.value
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

  /** `targets`, `enabled` and `skip` of a rule, any of them. */
  private def scoped(before: Stored, id: String, form: JsObject): Stored =
    before.replace(id) { rule =>
      rule.copy(
        targets = form.value.get("targets").map(targetsFrom).getOrElse(rule.targets),
        enabled = bool(form, "enabled").getOrElse(rule.enabled),
        skip = bool(form, "skip").getOrElse(rule.skip)
      )
    }

  private def without(before: Stored, id: String): Stored = {
    before.rule(id)
    before.copy(config = before.config.copy(rules = before.config.rules.filterNot(_.id == id)))
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
  // what one workspace sees of the gateway
  /////////////////////////////////////////////////////////////////////////////////////////////////

  // asked for when a workspace claims no route the caller may read: an empty list of route ids means
  // every route to the queries and the report, and this one means none, in the shape each one answers
  private val NoRoute = "__threat_studio_no_route__"

  /** A workspace with the routes it claims now. */
  private final case class Scope(stored: Stored, rule: CloudApimSecuritySuiteGlobalRule, routes: Seq[NgRoute], table: PostureReport.Table) {
    val claims: Seq[NgRoute] = routes.filter(r => claimedBy(table, r, rule.id))
    val claimIds: Set[String] = claims.map(_.id).toSet
    def visible(using call: ThreatStudioApiRequest): Seq[NgRoute] = claims.filter(readable)
    def visibleIds(using call: ThreatStudioApiRequest): Set[String] = visible.map(_.id).toSet
    // what the route ids sent to a query or a report become
    def routeIds(using call: ThreatStudioApiRequest): Seq[String] = visibleIds.toSeq.sorted match {
      case Seq() => Seq(NoRoute)
      case ids   => ids
    }
    // an incident lists its routes by name, or by id for a route without one
    def visibleKeys(using call: ThreatStudioApiRequest): Set[String] = visible.flatMap(r => Seq(r.id, r.name)).toSet
  }

  private def scope(id: String): Future[Scope] = stored().map { s =>
    val routes = env.proxyState.allRoutes()
    Scope(s, s.rule(id), routes, s.resolve(routes))
  }

  private def canRespond(sc: Scope)(using call: ThreatStudioApiRequest): Boolean =
    permissionsOf(sc.rule, sc.stored.potential(sc.routes, sc.table), sc.routes).contains("incidents:respond")

  private def requireRespond(sc: Scope)(using call: ThreatStudioApiRequest): Unit =
    if (!canRespond(sc)) throw forbidden("acting on the callers of a workspace needs the right to write every route it claims")

  private def leaderOnly(): Unit =
    if (!(env.clusterConfig.mode.isOff || env.clusterConfig.mode.isLeader)) throw notFound("leader-only endpoint")

  private def runQuery(queryId: String, filters: Filters, params: JsObject, bucket: Option[String], compare: Boolean, nocache: Boolean)
      : Future[Either[String, JsObject]] =
    AnalyticsRuntime.executor match {
      // what the front reads as "no user analytics exporter", which is what it means here
      case None           => Future.successful(Left("no active analytics on this gateway"))
      case Some(executor) => executor.run(queryId, filters, params, bucket, compare, nocache)
    }

  /**
   * One of the extension's queries, narrowed to the routes the workspace claims and the caller may
   * read, in the caller's tenant. Nothing the caller sends chooses the routes: a route filter of its
   * own only narrows further.
   */
  private def analytics(sc: Scope, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] = {
    val queryId = form.select("query").asOpt[String].getOrElse(throw badRequest("'query' is required"))
    if (!queryId.startsWith("cloudapim_security_") && !queryId.startsWith("cloudapim_waf_"))
      throw badRequest("only the security and waf queries of the extension (cloudapim_security_*, cloudapim_waf_*) run on a workspace")
    leaderOnly()
    val params  = form.select("params").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj("route_ids" -> sc.routeIds)
    val filters = Filters.fromJson(form.select("filters").asOpt[JsObject].getOrElse(Json.obj())).copy(tenant = Some(call.currentTenant.value))
    runQuery(
      queryId,
      filters,
      params,
      form.select("bucket").asOpt[String],
      form.select("compare").asOpt[Boolean].getOrElse(false),
      form.select("nocache").asOpt[Boolean].getOrElse(false)
    ).map {
      case Left(err) if err.contains("no active") => Results.PreconditionFailed(Json.obj("error" -> "precondition_failed", "error_description" -> err))
      case Left(err) if err.startsWith("unknown") => Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> err))
      case Left(err)                              => Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> err))
      case Right(res)                             => Results.Ok(res)
    }
  }

  private def apiReport(sc: Scope)(using call: ThreatStudioApiRequest): Future[Result] = {
    val zombieDays = call.req.getQueryString("zombie_days").flatMap(_.toIntOption).filter(_ > 0).getOrElse(90)
    // what this node saw is published first, so a report asked right after traffic includes it
    security.apiInventory
      .publish()
      .recover { case _ => 0 }
      .flatMap(_ => ApiReport.json(security, sc.routeIds.toSet, zombieDays))
      .map(Results.Ok(_))
  }

  // ---------------------------------------------------------------------------------------------
  // incidents and bans, which are about callers and not about routes
  // ---------------------------------------------------------------------------------------------

  private def onRoutes(timeline: Seq[IncidentEvent], ids: Set[String]): Seq[IncidentEvent] =
    timeline.filter(_.routeId.exists(ids.contains))

  private def touches(incident: Incident, ids: Set[String], keys: Set[String]): Boolean =
    onRoutes(incident.timeline, ids).nonEmpty || incident.routes.exists(keys.contains)

  /** A timeline narrowed to the workspace's routes, with how much of it happened elsewhere. */
  private def narrowed(timeline: Seq[IncidentEvent], ids: Set[String]): JsObject = {
    val mine = onRoutes(timeline, ids)
    Json.obj("timeline" -> JsArray(mine.map(_.json)), "timeline_elsewhere" -> (timeline.size - mine.size))
  }

  private def banJson(ban: BanEntry, ids: Set[String]): JsObject =
    ban.json.as[JsObject] ++ narrowed(ban.timeline, ids)

  private def incidentJson(view: IncidentView, ids: Set[String], keys: Set[String]): JsObject = {
    val mine = onRoutes(view.incident.timeline, ids)
    view.json.as[JsObject] ++ narrowed(view.incident.timeline, ids) ++ Json.obj(
      "routes"       -> view.incident.routes.filter(keys.contains).toSeq.sorted,
      // the latest thing seen on the workspace's routes rather than on someone else's
      "last_message" -> mine.headOption.map(_.message).getOrElse[String](""),
      "ban"          -> Json.toJson(view.ban.map(b => banJson(b, ids)))
    )
  }

  private def incidentsOf(sc: Scope)(using call: ThreatStudioApiRequest): Future[Seq[IncidentView]] = {
    val ids  = sc.visibleIds
    val keys = sc.visibleKeys
    for {
      views  <- security.board.all()
      states <- security.board.workspaceStates(sc.rule.id)
    } yield views.filter(v => touches(v.incident, ids, keys)).map(v => v.copy(state = states.get(v.key)))
  }

  private def bansOf(sc: Scope)(using call: ThreatStudioApiRequest): Seq[BanEntry] = {
    val ids = sc.visibleIds
    security.bans.all.filter(b => b.workspace.contains(sc.rule.id) || onRoutes(b.timeline, ids).nonEmpty)
  }

  private def refOf(form: JsObject): IdentityRef =
    form
      .select("ref")
      .asOpt[String]
      .flatMap(IdentityRef.parse)
      .orElse(for {
        kind  <- form.select("kind").asOpt[String]
        value <- form.select("value").asOpt[String]
      } yield IdentityRef(kind, value))
      .orElse(form.select("ip").asOpt[String].map(IdentityRef(IdentityRef.Ip, _)))
      .getOrElse(throw badRequest("'ref' is required, as 'kind:value'"))

  private def durationOf(form: JsObject): Long =
    form.select("duration_seconds").asOpt[Long].filter(_ > 0).getOrElse(throw badRequest("'duration_seconds' must be a positive number"))

  /**
   * Who is acting, the way the security module names its operators.
   *
   * A person signed in to the backoffice, as they are. Anyone else is named after the call, and is
   * only ever handed to a module's handler once this api has checked the call: an admin of the tenant
   * for the module routes, a reader of the workspace for its lookups. The rights a handler checks on
   * its user are the backoffice's, so this one carries all of them.
   */
  private def operator(using call: ThreatStudioApiRequest): BackOfficeUser = person.getOrElse {
    val via = call.apikey.clientId
    BackOfficeUser(
      randomId = IdGenerator.token,
      name = call.actor.map(_.name).getOrElse(call.apikey.clientName),
      email = call.actor.map(a => s"${a.email} via $via").getOrElse(via),
      profile = Json.obj(),
      authConfigId = "apikey",
      simpleLogin = false,
      tags = Seq.empty,
      metadata = Map.empty,
      rights = UserRights.superAdmin,
      location = otoroshi.models.EntityLocation.default,
      adminEntityValidators = Map.empty
    )
  }

  private def securityAudit(sc: Scope, action: String, ref: IdentityRef, detail: JsObject)(using call: ThreatStudioApiRequest): Unit =
    CloudApimWafSecurityAudit(Some(operator), action, Some(ref), detail ++ Json.obj("workspace" -> sc.rule.id)).toAnalytics()

  /**
   * What a workspace has against a caller before banning it: an incident on its routes, or, for an
   * address, a decision on its routes in the last seven days. A workspace bans for what happened to
   * it, not for what it was told about.
   */
  private def evidenceOf(sc: Scope, ref: IdentityRef)(using call: ThreatStudioApiRequest): Future[Option[Seq[IncidentEvent]]] = {
    val ids = sc.visibleIds
    security.board.get(ref.key).flatMap {
      case Some(view) if onRoutes(view.incident.timeline, ids).nonEmpty => Some(onRoutes(view.incident.timeline, ids)).vfuture
      case _ if ref.kind == IdentityRef.Ip && ids.nonEmpty && AnalyticsRuntime.executor.isDefined &&
            (env.clusterConfig.mode.isOff || env.clusterConfig.mode.isLeader) =>
        val now = Instant.now()
        runQuery(
          "cloudapim_security_decisions_log",
          Filters(from = now.minusSeconds(7L * 24L * 3600L), to = now, tenant = Some(call.currentTenant.value)),
          Json.obj("source" -> ref.value, "route_ids" -> ids.toSeq.sorted, "limit" -> 1),
          None,
          compare = false,
          nocache = true
        ).map {
          case Right(res) if (res \ "data" \ "items").asOpt[JsArray].exists(_.value.nonEmpty) => Some(Seq.empty)
          case _                                                                              => None
        }.recover { case _ => None }
      case _ => Option.empty[Seq[IncidentEvent]].vfuture
    }
  }

  private def ban(sc: Scope, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] = {
    requireRespond(sc)
    requireGatewayWrite()
    val ref      = refOf(form)
    val duration = durationOf(form)
    val reason   = form.select("reason").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse(throw badRequest("'reason' is required"))
    // a person bans whoever they choose, as on the backoffice; what the workspace saw goes on the ban
    evidenceOf(sc, ref).map(_.orElse(person.map(_ => Seq.empty))).flatMap {
      case None           => throw forbidden(s"nothing on the routes of this workspace was seen from ${ref.key}")
      case Some(evidence) =>
        security.bans
          .ban(
            ref,
            duration.seconds,
            reason,
            Seq("manual", s"workspace:${sc.rule.id}"),
            timeline = evidence,
            issuedBy = Some(operator.email),
            workspace = Some(sc.rule.id)
          )
          .map { outcome =>
            securityAudit(sc, if (outcome.issued) "ban" else "ban-refused", ref, Json.obj("duration_seconds" -> duration, "reason" -> reason))
            if (outcome.issued) Results.Created(outcome.json) else Results.Conflict(outcome.json)
          }
    }
  }

  private def banOfWorkspace(sc: Scope, ref: IdentityRef)(using call: ThreatStudioApiRequest): BanEntry =
    bansOf(sc).find(_.ref.key == ref.key).getOrElse(throw notFound(s"no ban of ${ref.key} for this workspace"))

  private def extendBan(sc: Scope, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] = {
    requireRespond(sc)
    requireGatewayWrite()
    val ref      = refOf(form)
    val duration = durationOf(form)
    val current  = banOfWorkspace(sc, ref)
    if (person.isEmpty && !current.workspace.contains(sc.rule.id)) throw forbidden("a workspace extends the bans it issued, and only those")
    security.bans.extend(ref, duration.seconds, operator.email).map {
      case None       => throw notFound(s"the ban of ${ref.key} has lapsed")
      case Some(next) =>
        securityAudit(sc, "extend", ref, Json.obj("duration_seconds" -> duration))
        Results.Ok(banJson(next, sc.visibleIds))
    }
  }

  private def unban(sc: Scope, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] = {
    requireRespond(sc)
    requireGatewayWrite()
    val ref     = refOf(form)
    val current = banOfWorkspace(sc, ref)
    // its own bans, and the ones the fabric issued for what happened on its routes alone
    val ownsIt  = current.workspace.contains(sc.rule.id) ||
      (current.workspace.isEmpty && current.timeline.nonEmpty && current.timeline.forall(_.routeId.exists(sc.claimIds.contains)))
    if (person.isEmpty && !ownsIt) throw forbidden("this ban was issued for more than this workspace: lifting it is an administrator's call")
    security.bans.unban(ref).map { done =>
      securityAudit(sc, "unban", ref, Json.obj())
      Results.Ok(Json.obj("done" -> done, "ref" -> ref.json))
    }
  }

  private def triage(sc: Scope, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] = {
    requireRespond(sc)
    val key   = form.select("key").asOpt[String].getOrElse(throw badRequest("'key' is required"))
    val state = form.select("state").asOpt[String].flatMap(IncidentState.parse).getOrElse(throw badRequest("'state' must be open, acknowledged or resolved"))
    incidentsOf(sc).flatMap { views =>
      if (!views.exists(_.key == key)) throw notFound("no such incident on this workspace")
      security.board
        .setWorkspaceState(sc.rule.id, key, state, operator.email, form.select("note").asOpt[String])
        .map { entry =>
          IdentityRef.parse(key).foreach(ref => securityAudit(sc, s"incident-$state", ref, Json.obj()))
          Results.Ok(entry.json)
        }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // the contract a route names
  // ---------------------------------------------------------------------------------------------

  private lazy val RouteEntities    = new StudioEntities("proxy.otoroshi.io", "routes")
  private lazy val ContractEntities = new StudioEntities("waf.extensions.cloud-apim.com", "api-contracts")

  private val ContractMeta = "cloud-apim-api-contract"

  private def setRouteContract(sc: Scope, routeId: String, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] = {
    if (!sc.claimIds.contains(routeId)) throw notFound("no such route in this workspace")
    val contract = form.value.get("contract_id") match {
      case Some(JsString(id)) if id.trim.nonEmpty => Some(id.trim)
      case None | Some(JsNull) | Some(JsString(_)) => None
      case Some(_)                                => throw badRequest("'contract_id' must be a string or null")
    }
    for {
      _     <- contract match {
                 case None     => ().vfuture
                 case Some(id) => ContractEntities.get(id).map(_.getOrElse(throw notFound(s"no such contract '$id'")))
               }
      route <- RouteEntities.get(routeId).map(_.getOrElse(throw notFound("this route is not one the admin api can write")))
      meta   = route.select("metadata").asOpt[JsObject].getOrElse(Json.obj())
      saved <- RouteEntities.update(route ++ Json.obj("metadata" -> contract.fold(meta - ContractMeta)(id => meta ++ Json.obj(ContractMeta -> id))))
    } yield Results.Ok(Json.obj("route_id" -> RouteEntities.idOf(saved), "contract_id" -> Json.toJson(contract)))
  }

  // ---------------------------------------------------------------------------------------------
  // the module routes, on the admin api
  // ---------------------------------------------------------------------------------------------

  private val modulePrefix = "/extensions/cloud-apim/extensions/waf/"
  private val studioPrefix = "/extensions/cloud-apim/extensions/waf/studio"

  private lazy val moduleRoutes: Seq[AdminExtensionBackofficeAuthRoute] =
    ext.rawBackofficeRoutes.filter(r => r.path.startsWith(modulePrefix) && !r.path.startsWith(studioPrefix))

  private def moduleRoute(method: String, suffix: String): AdminExtensionBackofficeAuthRoute =
    moduleRoutes
      .find(r => r.method == method && r.path == s"$modulePrefix$suffix")
      .getOrElse(throw ThreatStudioApiError(500, "internal_error", s"no module route $method $suffix"))

  /** Runs a module's own handler, as the operator the call names: for a person, behind the guard of the backoffice. */
  private def delegate(route: AdminExtensionBackofficeAuthRoute, body: JsValue)(using call: ThreatStudioApiRequest): Future[Result] = {
    val handler = if (person.isDefined) BackofficeAccess.guard(route) else route
    val ctx     = new AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute](
      new org.bigtesting.routd.Route(route.path),
      route,
      route.method,
      route.path,
      call.req.path
    )
    val source = body match {
      case JsNull => None
      case json   => Some(Source.single(ByteString(Json.stringify(json))))
    }
    handler.handle(ctx, call.req, Some(operator), source)
  }

  /**
   * Every module route of the backoffice, served on the admin api under `/api`, for an admin of the
   * tenant: the bans, the allowlist, the feeds, the reputation sources and the rest are the gateway's
   * and not any one workspace's. A person reaches them as on the backoffice, guarded the same way.
   */
  private def mirrored(route: AdminExtensionBackofficeAuthRoute): AdminExtensionAdminApiRoute =
    this.route(route.method, "", wantsBody = route.wantsBody, absolute = Some(s"/api${route.path}")) {
      if (person.isEmpty && !tenantAdmin) throw forbidden("the routes of the whole gateway need an admin of the tenant")
      delegate(route, call.body)
    }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // the entities of a workspace
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private lazy val kindEntities: Map[String, StudioEntities] =
    StudioKind.all.map(k => k.plural -> new StudioEntities(StudioKind.Group, k.plural)).toMap

  private def entitiesOf(kind: StudioKind): StudioEntities = kindEntities(kind.plural)

  private def kindOf(name: String): StudioKind =
    StudioKind.of(name).filter(_.referenceable).getOrElse(throw notFound(s"'$name' is not something a workspace has"))

  private val Rulesets           = StudioKind.of("waf-rulesets").get
  private val ChallengeProviders = StudioKind.of("challenge-providers").get

  /** Every entity of the suite and who names it, as of now. */
  private def graph(sc: Scope): Future[EntityGraph] = graphOf(sc.stored, sc.routes, sc.table)

  private def graphOf(s: Stored, routes: Seq[NgRoute], resolved: PostureReport.Table): Future[EntityGraph] = {
    val potential = s.potential(routes, resolved)
    val claims    = s.config.rules
      .map(r => r.id -> routes.filter(route => claimedBy(potential, route, r.id)).map(_.id).toSet)
      .toMap
    Future
      .sequence(StudioKind.all.map(k => entitiesOf(k).everything().map(_.map(k -> _))))
      .map(all => new EntityGraph(all.flatten, s.config.rules, routes, ws => claims.getOrElse(ws, Set.empty)))
  }

  /**
   * Who each entity a workspace may own belongs to, for the administrators of the table: the
   * workspace its mark names, and whether it holds there or the entity is shared after all. A mark
   * naming no rule of the table is an orphan: no workspace sees the entity, and nobody but an
   * administrator changes it.
   */
  private def ownershipJson(s: Stored, g: EntityGraph, kind: StudioKind, entity: JsObject): JsObject = {
    val id   = EntityGraph.idOf(entity)
    val mark = EntityGraph.markOf(entity)
    Json.obj(
      "kind"    -> kind.plural,
      "id"      -> id,
      "name"    -> Json.toJson(entity.select("name").asOpt[String]),
      "mark"    -> Json.toJson(mark),
      "owner"   -> Json.toJson(g.ownerOf(id)),
      "orphan"  -> mark.exists(m => !s.config.rules.exists(_.id == m)),
      "used_by" -> g.referencersOf(id).size,
      "managed" -> entity.select("metadata").select("managed_by").asOpt[String].isDefined
    )
  }

  /**
   * An entity given to a workspace, or to none: the mark is set or removed, and the entity belongs
   * to the workspace once nothing outside of it names it. What it decides is who else may see and
   * change the entity, so it is an administrator's call.
   */
  private def assignEntity(kind: StudioKind, id: String, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] = {
    if (!tenantAdmin) throw forbidden("giving an entity to a workspace needs an admin of the tenant")
    val target = form.value.get("workspace") match {
      case Some(JsString(ws)) if ws.trim.nonEmpty => Some(ws.trim)
      case Some(JsNull) | Some(JsString(_))       => None
      case _                                      => throw badRequest("'workspace' is the id of a workspace, or null for none")
    }
    stored().flatMap { s =>
      target.foreach(s.rule)
      entitiesOf(kind).get(id).flatMap {
        case None          => throw notFound(s"no such ${kind.plural} '$id'")
        case Some(current) =>
          val meta = current.select("metadata").asOpt[JsObject].getOrElse(Json.obj()) - EntityGraph.Mark - EntityGraph.KindMark
          val next = current ++ Json.obj(
            "metadata" -> target.fold(meta)(ws => meta ++ Json.obj(EntityGraph.Mark -> ws, EntityGraph.KindMark -> kind.plural))
          )
          for {
            saved <- entitiesOf(kind).update(next)
            routes = env.proxyState.allRoutes()
            g     <- graphOf(s, routes, s.resolve(routes))
          } yield Results.Ok(ownershipJson(s, g, kind, saved))
      }
    }
  }

  private def itemJson(g: EntityGraph, sc: Scope, entity: JsObject): JsObject = {
    val id = EntityGraph.idOf(entity)
    Json.obj(
      "entity"    -> entity,
      "ownership" -> (if (g.owns(sc.rule.id, id)) "workspace" else "shared"),
      "usage"     -> g.usage(sc.rule.id, id),
      // installed by a rule feed: whatever is written here, the next pack replaces
      "managed"   -> entity.select("metadata").select("managed_by").asOpt[String].isDefined
    )
  }

  /** What a workspace sees of an entity: for a person, whatever their rights let them read. */
  private def sees(g: EntityGraph, ws: String, kind: StudioKind, id: String)(using call: ThreatStudioApiRequest): Boolean =
    if (person.isDefined) kind.referenceable && g.kindOf(id).contains(kind) && g.entity(id).exists(e => call.canUserReadJson(e))
    else g.visible(ws, kind, id)

  /** Whether a workspace changes an entity it sees: a person, when their rights let them write it. */
  private def changes(g: EntityGraph, ws: String, id: String)(using call: ThreatStudioApiRequest): Boolean =
    person.isDefined || g.owns(ws, id)

  private def visibleEntity(g: EntityGraph, sc: Scope, kind: StudioKind, id: String)(using call: ThreatStudioApiRequest): Future[JsObject] =
    if (!sees(g, sc.rule.id, kind, id)) Future.failed(notFound(s"no such ${kind.plural} '$id'"))
    else entitiesOf(kind).get(id).map(_.getOrElse(throw notFound(s"no such ${kind.plural} '$id'")))

  private def ownEntity(g: EntityGraph, sc: Scope, kind: StudioKind, id: String)(using call: ThreatStudioApiRequest): Future[JsObject] =
    visibleEntity(g, sc, kind, id).map { e =>
      if (!changes(g, sc.rule.id, id))
        throw conflict(s"this ${kind.plural} is shared with what is outside the workspace: copy it into the workspace to change it", Json.obj("shared_entity" -> id))
      e
    }

  /** What an entity of the workspace names has to be visible to the workspace. */
  private def checkRefs(g: EntityGraph, sc: Scope, kind: StudioKind, entity: JsObject, current: Option[JsObject])(using
      call: ThreatStudioApiRequest
  ): Unit = {
    def changed(field: String): Boolean = current.forall(c => c.select(field).asOpt[JsValue] != entity.select(field).asOpt[JsValue])
    val refs: Seq[(StudioKind, String)] = kind.plural match {
      case "waf-configs" if changed("rulesets")               =>
        entity.select("rulesets").asOpt[Seq[String]].getOrElse(Seq.empty).map(Rulesets -> _)
      case "threat-policies" if changed("challenge_provider") =>
        entity.select("challenge_provider").asOpt[String].filter(_.nonEmpty).toSeq.map(ChallengeProviders -> _)
      case _                                                  => Seq.empty
    }
    refs.foreach { case (k, id) => if (!sees(g, sc.rule.id, k, id)) throw notFound(s"no such ${k.plural} '$id'") }
  }

  /** The entities a preset names, when it changes them, have to be visible to the workspace. */
  private def checkPresetRefs(g: EntityGraph, ws: String, before: CloudApimSecuritySuitePresetConfig, after: CloudApimSecuritySuitePresetConfig)(using
      call: ThreatStudioApiRequest
  ): Unit = {
    val b = before.json
    val a = after.json
    StudioKind.referenceable.flatMap(k => k.presetField.map(k -> _)).foreach { case (kind, field) =>
      val ref = a.select(field).asOpt[String].filter(_.nonEmpty)
      if (ref != b.select(field).asOpt[String].filter(_.nonEmpty)) ref.foreach { id =>
        if (!sees(g, ws, kind, id)) throw notFound(s"no such ${kind.plural} '$id'")
      }
    }
  }

  // where the entities a caller creates go: for a caller limited to some teams, the teams it may write,
  // so it still sees what it created; anyone else keeps the location of the template
  private def locationFor(using call: ThreatStudioApiRequest): Option[JsObject] = call.backOfficeUser match {
    case Right(Some(user)) if !(user.rights.superAdmin || user.rights.tenantAdmin(call.currentTenant)) =>
      val tenant = call.currentTenant.value
      val teams  = user.rights.rights
        .filter(r => r.tenant.value == "*" || r.tenant.value == tenant)
        .flatMap(_.teams)
        .filter(t => t.canWrite && !t.value.startsWith("*"))
        .map(_.value)
        .distinct
      Some(Json.obj("tenant" -> tenant, "teams" -> teams))
    case _ => None
  }

  private def marked(sc: Scope, kind: StudioKind, entity: JsObject): JsObject =
    entity ++ Json.obj(
      "metadata" -> (entity.select("metadata").asOpt[JsObject].getOrElse(Json.obj()) ++
        Json.obj(EntityGraph.Mark -> sc.rule.id, EntityGraph.KindMark -> kind.plural))
    )

  private def createEntity(sc: Scope, kind: StudioKind, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] =
    graph(sc).flatMap { g =>
      val seeded = entitiesOf(kind).template() ++ (form - "id")
      checkRefs(g, sc, kind, seeded, None)
      val located = if (form.value.contains("_loc")) seeded else locationFor.fold(seeded)(loc => seeded ++ Json.obj("_loc" -> loc))
      entitiesOf(kind).create(marked(sc, kind, located)).map(saved => Results.Created(Json.obj("entity" -> saved, "ownership" -> "workspace")))
    }

  private def updateEntity(sc: Scope, kind: StudioKind, id: String, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] =
    graph(sc).flatMap { g =>
      ownEntity(g, sc, kind, id).flatMap { current =>
        val next = SecretSentinel.unmasked(form, current).as[JsObject] ++ Json.obj("id" -> id)
        checkRefs(g, sc, kind, next, Some(current))
        // what a person changes without the workspace owning it stays whoever's it was
        val own  = g.owns(sc.rule.id, id)
        entitiesOf(kind)
          .update(if (own) marked(sc, kind, next) else next)
          .map(saved => Results.Ok(Json.obj("entity" -> saved, "ownership" -> (if (own) "workspace" else "shared"))))
      }
    }

  private def deleteEntity(sc: Scope, kind: StudioKind, id: String)(using call: ThreatStudioApiRequest): Future[Result] =
    graph(sc).flatMap { g =>
      ownEntity(g, sc, kind, id).flatMap { _ =>
        // a person deletes as on the backoffice, where what still names it is theirs to mend
        if (person.isEmpty && g.referencersOf(id).nonEmpty) throw conflict(s"this ${kind.plural} is still used", Json.obj("usage" -> g.usage(sc.rule.id, id)))
        entitiesOf(kind).delete(id).map(_ => Results.NoContent)
      }
    }

  /**
   * A copy of an entity the workspace may see, made its own, and in place of the original in the
   * preset when asked: how a workspace changes something it shares.
   */
  private def forkEntity(sc: Scope, kind: StudioKind, id: String, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] =
    graph(sc).flatMap { g =>
      visibleEntity(g, sc, kind, id).flatMap { source =>
        val template = entitiesOf(kind).template()
        val name     = form.select("name").asOpt[String].map(_.trim).filter(_.nonEmpty)
          .getOrElse(s"${source.select("name").asOpt[String].getOrElse(id)} (copy)")
        val copy     = (source - "_loc" - "metadata") ++ Json.obj(
          "id"       -> EntityGraph.idOf(template),
          "name"     -> name,
          "metadata" -> Json.obj(),
          "_loc"     -> locationFor.orElse(source.select("_loc").asOpt[JsObject]).getOrElse(Json.obj())
        )
        entitiesOf(kind).create(marked(sc, kind, copy)).flatMap { saved =>
          val newId = EntityGraph.idOf(saved)
          (kind.presetField, form.select("use").asOpt[Boolean].getOrElse(false)) match {
            case (Some(field), true) =>
              mutate("fork_into_preset") { before =>
                before.replace(sc.rule.id)(rule => rule.copy(preset = presetFrom(rule.preset.json.asObject ++ Json.obj(field -> newId))))
              }.map(_ => Results.Created(Json.obj("entity" -> saved, "ownership" -> "workspace", "used" -> true)))
            case _                   => Results.Created(Json.obj("entity" -> saved, "ownership" -> "workspace", "used" -> false)).vfuture
          }
        }
      }
    }

  // ---------------------------------------------------------------------------------------------
  // tuning, learning and compilation, on what the workspace may change
  // ---------------------------------------------------------------------------------------------

  private val WafConfigs = StudioKind.of("waf-configs").get

  private def delegateJson(route: AdminExtensionBackofficeAuthRoute, body: JsValue)(using call: ThreatStudioApiRequest): Future[(Int, JsValue)] =
    delegate(route, body).flatMap { result =>
      result.body.consumeData.map(bytes => (result.header.status, Try(Json.parse(bytes.utf8String)).getOrElse(JsNull)))
    }

  /** The false positive candidates of the workspace's routes: a person gets every one, as on the backoffice. */
  private def matchesOf(sc: Scope)(using call: ThreatStudioApiRequest): Future[(Int, JsObject, Seq[JsObject])] = {
    val ids = sc.visibleIds
    delegateJson(moduleRoute("GET", "tuning/_matches"), JsNull).map { case (status, json) =>
      val all  = (json \ "matches").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      val mine = if (person.isDefined) all else all.filter(m => (m \ "route_id").asOpt[String].exists(ids.contains))
      (status, json.asOpt[JsObject].getOrElse(Json.obj()), mine)
    }
  }

  private def sampleOf(sc: Scope, form: JsObject)(using call: ThreatStudioApiRequest): Future[Option[JsObject]] =
    form.select("sample_id").asOpt[String] match {
      case None     => None.vfuture
      case Some(id) =>
        matchesOf(sc).map { case (_, _, mine) =>
          Some(mine.find(m => (m \ "id").asOpt[String].contains(id) || (m \ "key").asOpt[String].contains(id)).getOrElse(throw notFound("no such sample on this workspace")))
        }
    }

  private def configRefOf(form: JsObject, sample: Option[JsObject]): String =
    form.select("config_ref").asOpt[String].filter(_.nonEmpty)
      .orElse(sample.flatMap(s => (s \ "config_ref").asOpt[String]))
      .getOrElse(throw badRequest("'config_ref' is required"))

  /** What tuning and learning write for a config of the workspace is the workspace's too. */
  private def markManagedRulesets(sc: Scope, configRef: String)(using call: ThreatStudioApiRequest): Future[Unit] =
    entitiesOf(WafConfigs).get(configRef).flatMap {
      case None         => ().vfuture
      case Some(config) =>
        val ids = config.select("rulesets").asOpt[Seq[String]].getOrElse(Seq.empty)
        Future
          .sequence(ids.map(id => entitiesOf(Rulesets).get(id)))
          .map(_.flatten.filter(rs => EntityGraph.markOf(rs).isEmpty && rs.select("metadata").select("cloud-apim.tuning.config").asOpt[String].contains(configRef)))
          .flatMap(rulesets => Future.sequence(rulesets.map(rs => entitiesOf(Rulesets).update(marked(sc, Rulesets, rs)))))
          .map(_ => ())
    }

  private def tuning(sc: Scope, action: String, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] =
    for {
      g      <- graph(sc)
      sample <- sampleOf(sc, form)
      ref     = configRefOf(form, sample)
      _       = if (action == "_apply") { if (!changes(g, sc.rule.id, ref)) throw conflict("this waf config is shared: copy it into the workspace to tune it", Json.obj("shared_entity" -> ref)) }
                else if (!sees(g, sc.rule.id, WafConfigs, ref)) throw notFound(s"no such waf-configs '$ref'")
      result <- delegate(moduleRoute("POST", s"tuning/$action"), form ++ Json.obj("config_ref" -> ref))
      _      <- if (action == "_apply" && result.header.status < 300 && g.owns(sc.rule.id, ref)) markManagedRulesets(sc, ref) else ().vfuture
    } yield result

  private def learning(sc: Scope, action: String, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] =
    for {
      g      <- graph(sc)
      ref     = configRefOf(form, None)
      // a window counts every route the config runs on: a config shared with others would report on them
      _       = if (!changes(g, sc.rule.id, ref)) throw conflict("this waf config is shared: copy it into the workspace to learn on it", Json.obj("shared_entity" -> ref))
      result <- delegate(moduleRoute("POST", s"learning/$action"), form ++ Json.obj("config_ref" -> ref))
      _      <- if (action == "_apply" && result.header.status < 300 && g.owns(sc.rule.id, ref)) markManagedRulesets(sc, ref) else ().vfuture
    } yield result

  private def compile(sc: Scope, form: JsObject)(using call: ThreatStudioApiRequest): Future[Result] =
    graph(sc).flatMap { g =>
      form.select("rulesets").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty).foreach { id =>
        if (!sees(g, sc.rule.id, Rulesets, id)) throw notFound(s"no such waf-rulesets '$id'")
      }
      delegate(moduleRoute("POST", "utils/_compile"), form)
    }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // routes
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def route(method: String, path: String, wantsBody: Boolean = false, absolute: Option[String] = None)(
      handle: ThreatStudioApiRequest ?=> Future[Result]
  ): AdminExtensionAdminApiRoute =
    AdminExtensionAdminApiRoute(
      method = method,
      path = absolute.getOrElse(s"$apiPath$path"),
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
      scope(id).flatMap(graph).flatMap { g =>
        mutate("save_preset") { before =>
          // fields left out keep their value: a page sends what it edits and nothing else
          before.replace(id) { rule =>
            val next = presetFrom(rule.preset.json.asObject ++ call.form)
            checkPresetRefs(g, id, rule.preset, next)
            rule.copy(preset = next)
          }
        }
      }.map(s => withEtag(workspaceJson(s, id, env.proxyState.allRoutes()), s.version))
    },
    route("PUT", "/workspaces/:id/scope", wantsBody = true) {
      val id = call.param("id")
      mutate("save_scope")(before => scoped(before, id, call.form))
        .map(s => withEtag(workspaceJson(s, id, env.proxyState.allRoutes()), s.version))
    },
    route("POST", "/workspaces/:id/_move", wantsBody = true) {
      val id = call.param("id")
      mutate("move_workspace") { before =>
        move(before, id, call.form.select("to").asOpt[Int].getOrElse(throw badRequest("'to' is required")))
      }.map(s => withEtag(tableJson(s, env.proxyState.allRoutes()), s.version))
    },
    route("DELETE", "/workspaces/:id") {
      val id = call.param("id")
      mutate("delete_workspace")(before => without(before, id)).map(_ => Results.NoContent)
    },

    // what a change of one rule would do to the routes, before it is made: the same change as the write
    route("POST", "/workspaces/:id/scope/_preview", wantsBody = true) {
      val id = call.param("id")
      stored().map(before => Results.Ok(previewJson(before, scoped(before, id, call.form), env.proxyState.allRoutes())))
    },
    route("POST", "/workspaces/:id/_move/_preview", wantsBody = true) {
      val id = call.param("id")
      val to = call.form.select("to").asOpt[Int].getOrElse(throw badRequest("'to' is required"))
      stored().map(before => Results.Ok(previewJson(before, move(before, id, to), env.proxyState.allRoutes())))
    },
    route("POST", "/workspaces/:id/_delete/_preview") {
      val id = call.param("id")
      stored().map(before => Results.Ok(previewJson(before, without(before, id), env.proxyState.allRoutes())))
    },

    // who the entities belong to

    route("GET", "/entities/_ownership") {
      stored().flatMap { s =>
        val routes = env.proxyState.allRoutes()
        for {
          g        <- graphOf(s, routes, s.resolve(routes))
          readable <- Future.sequence(StudioKind.referenceable.map(k => entitiesOf(k).all().map(_.map(k -> _))))
        } yield Results.Ok(JsArray(readable.flatten.map { case (k, e) => ownershipJson(s, g, k, e) }))
      }
    },
    route("POST", "/entities/:kind/:eid/_assign", wantsBody = true) {
      assignEntity(kindOf(call.param("kind")), call.param("eid"), call.form)
    },

    // what the workspace sees of its traffic

    route("POST", "/workspaces/:id/analytics/_query", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => analytics(sc, call.form))
    },
    route("GET", "/workspaces/:id/api-report") {
      scope(call.param("id")).flatMap(sc => apiReport(sc))
    },

    // its callers

    route("GET", "/workspaces/:id/incidents") {
      scope(call.param("id")).flatMap { sc =>
        val ids  = sc.visibleIds
        val keys = sc.visibleKeys
        incidentsOf(sc).map(views => Results.Ok(Json.obj("incidents" -> JsArray(views.map(v => incidentJson(v, ids, keys))))))
      }
    },
    route("POST", "/workspaces/:id/incidents/_state", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => triage(sc, call.form))
    },
    route("GET", "/workspaces/:id/bans") {
      scope(call.param("id")).map { sc =>
        val ids = sc.visibleIds
        Results.Ok(Json.obj("bans" -> JsArray(bansOf(sc).map(b => banJson(b, ids)))))
      }
    },
    route("POST", "/workspaces/:id/bans", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => ban(sc, call.form))
    },
    route("POST", "/workspaces/:id/bans/_extend", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => extendBan(sc, call.form))
    },
    route("POST", "/workspaces/:id/bans/_unban", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => unban(sc, call.form))
    },

    // the contract each route of the workspace names

    route("PUT", "/workspaces/:id/routes/:rid/contract", wantsBody = true) {
      // writing the route checks the caller may write it
      scope(call.param("id")).flatMap(sc => setRouteContract(sc, call.param("rid"), call.form))
    },

    // the lookups and computations its pages make, which read nothing of another workspace

    route("POST", "/workspaces/:id/reputation/_lookup", wantsBody = true) {
      scope(call.param("id")).flatMap(_ => delegate(moduleRoute("POST", "reputation/_lookup"), call.form - "feeds" - "crowdsec"))
    },
    route("POST", "/workspaces/:id/reputation/_geo", wantsBody = true) {
      scope(call.param("id")).flatMap(_ => delegate(moduleRoute("POST", "reputation/_geo"), call.form))
    },
    route("POST", "/workspaces/:id/rules/_describe", wantsBody = true) {
      scope(call.param("id")).flatMap(_ => delegate(moduleRoute("POST", "utils/_rules"), call.form))
    },
    route("POST", "/workspaces/:id/bots/_robots_txt", wantsBody = true) {
      scope(call.param("id")).flatMap(_ => delegate(moduleRoute("POST", "security/_robots_txt"), call.form))
    },
    route("GET", "/workspaces/:id/bots/catalog") {
      scope(call.param("id")).flatMap(_ => delegate(moduleRoute("GET", "security/_bot_catalog"), JsNull))
    },
    route("GET", "/workspaces/:id/challenge-presets") {
      scope(call.param("id")).flatMap(_ => delegate(moduleRoute("GET", "security/_challenge_presets"), JsNull))
    },
    route("POST", "/workspaces/:id/challenge-presets/_build", wantsBody = true) {
      scope(call.param("id")).flatMap(_ => delegate(moduleRoute("POST", "security/_challenge_from_preset"), call.form))
    },
    route("POST", "/workspaces/:id/contracts/_check", wantsBody = true) {
      scope(call.param("id")).flatMap { _ =>
        // a stored contract is checked only when the caller may read it
        call.form.select("id").asOpt[String] match {
          case None     => delegate(moduleRoute("POST", "security/_contract_check"), call.form)
          case Some(id) =>
            ContractEntities.get(id).flatMap {
              case None    => throw notFound(s"no such contract '$id'")
              case Some(_) => delegate(moduleRoute("POST", "security/_contract_check"), call.form)
            }
        }
      }
    },

    // its entities

    route("GET", "/workspaces/:id/entities/:kind") {
      val kind = kindOf(call.param("kind"))
      scope(call.param("id")).flatMap { sc =>
        for {
          g        <- graph(sc)
          readable <- entitiesOf(kind).all()
        } yield Results.Ok(JsArray(readable.filter(e => sees(g, sc.rule.id, kind, EntityGraph.idOf(e))).map(e => itemJson(g, sc, e))))
      }
    },
    route("GET", "/workspaces/:id/entities/:kind/_template") {
      val kind = kindOf(call.param("kind"))
      scope(call.param("id")).map(sc => Results.Ok(marked(sc, kind, entitiesOf(kind).template())))
    },
    route("POST", "/workspaces/:id/entities/:kind", wantsBody = true) {
      val kind = kindOf(call.param("kind"))
      scope(call.param("id")).flatMap(sc => createEntity(sc, kind, call.form))
    },
    route("GET", "/workspaces/:id/entities/:kind/:eid") {
      val kind = kindOf(call.param("kind"))
      scope(call.param("id")).flatMap(sc => graph(sc).flatMap(g => visibleEntity(g, sc, kind, call.param("eid")).map(e => Results.Ok(itemJson(g, sc, e)))))
    },
    route("PUT", "/workspaces/:id/entities/:kind/:eid", wantsBody = true) {
      val kind = kindOf(call.param("kind"))
      scope(call.param("id")).flatMap(sc => updateEntity(sc, kind, call.param("eid"), call.form))
    },
    route("DELETE", "/workspaces/:id/entities/:kind/:eid") {
      val kind = kindOf(call.param("kind"))
      scope(call.param("id")).flatMap(sc => deleteEntity(sc, kind, call.param("eid")))
    },
    route("POST", "/workspaces/:id/entities/:kind/:eid/_fork", wantsBody = true) {
      val kind = kindOf(call.param("kind"))
      scope(call.param("id")).flatMap(sc => forkEntity(sc, kind, call.param("eid"), call.form))
    },
    route("GET", "/workspaces/:id/entities/:kind/:eid/_usage") {
      val kind = kindOf(call.param("kind"))
      val eid  = call.param("eid")
      scope(call.param("id")).flatMap { sc =>
        graph(sc).flatMap { g =>
          visibleEntity(g, sc, kind, eid).map { _ =>
            // who else uses it is the gateway's business: a workspace is told how many
            val details = if (tenantAdmin) Json.obj("referencers" -> JsArray(g.referencersOf(eid).map(_.json))) else Json.obj()
            Results.Ok(g.usage(sc.rule.id, eid) ++ details)
          }
        }
      }
    },

    // tuning, learning and compilation

    route("GET", "/workspaces/:id/tuning/matches") {
      scope(call.param("id")).flatMap(sc => matchesOf(sc).map { case (status, json, mine) => Results.Status(status)(json ++ Json.obj("matches" -> mine)) })
    },
    route("POST", "/workspaces/:id/tuning/_propose", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => tuning(sc, "_propose", call.form))
    },
    route("POST", "/workspaces/:id/tuning/_preview", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => tuning(sc, "_preview", call.form))
    },
    route("POST", "/workspaces/:id/tuning/_apply", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => tuning(sc, "_apply", call.form))
    },
    route("GET", "/workspaces/:id/learning") {
      scope(call.param("id")).flatMap { sc =>
        for {
          g             <- graph(sc)
          (status, json) <- delegateJson(moduleRoute("GET", "learning/_running"), JsNull)
        } yield {
          val running = (json \ "running").asOpt[Seq[String]].getOrElse(Seq.empty).filter(id => sees(g, sc.rule.id, WafConfigs, id))
          Results.Status(status)(json.asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj("running" -> running))
        }
      }
    },
    route("POST", "/workspaces/:id/learning/:action", wantsBody = true) {
      val action = call.param("action")
      if (!Seq("_start", "_stop", "_discard", "_report", "_apply").contains(action)) throw notFound("no such learning action")
      scope(call.param("id")).flatMap(sc => learning(sc, action, call.form))
    },
    route("POST", "/workspaces/:id/waf/_compile", wantsBody = true) {
      scope(call.param("id")).flatMap(sc => compile(sc, call.form))
    }
  ) ++ moduleRoutes.map(mirrored)
}
