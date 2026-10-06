package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.body.BodyReader
import com.cloud.apim.otoroshi.extensions.waf.login.*
import com.cloud.apim.otoroshi.extensions.waf.security.*
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Which requests are logins, and what the login guard counts in them (BEH-3).
 *
 * `login_paths` are exact paths, or prefixes ending in `*`; empty means every request of the route
 * with one of `methods`. A login failed when its status is one of `failure_statuses`, or, for an
 * application that answers `200` either way, when its body contains `failure_marker`. Routes that
 * share an account base share counters through `realm`.
 */
final case class CloudApimLoginGuardConfig(
    loginPaths: Seq[String] = Seq.empty,
    methods: Seq[String] = Seq("POST"),
    usernameFields: Seq[String] = Credentials.defaultUsernameFields,
    passwordFields: Seq[String] = Credentials.defaultPasswordFields,
    failureStatuses: Seq[Int] = Seq(401, 403),
    failureMarker: Option[String] = None,
    realm: Option[String] = None,
    windowSeconds: Long = 900L,
    thresholds: LoginThresholds = LoginThresholds(),
    takeoverAccounts: Int = 3,
    takeoverWeight: Int = 80,
    breachedPasswords: Boolean = false,
    breachedWeight: Int = 20,
    bodyLimit: Long = 64L * 1024L
) extends NgPluginConfig {
  override def json: JsValue = CloudApimLoginGuardConfig.format.writes(this)

  def windowMillis: Long = windowSeconds.max(10L) * 1000L

  def isLogin(method: String, path: String): Boolean =
    methods.exists(_.equalsIgnoreCase(method)) && (loginPaths.isEmpty || loginPaths.exists { p =>
      val pattern = p.trim
      if (pattern.endsWith("*")) path.startsWith(pattern.dropRight(1)) else path == pattern
    })
}

object CloudApimLoginGuardConfig {

  val default: CloudApimLoginGuardConfig = CloudApimLoginGuardConfig()

  private def strings(json: JsValue, name: String): Option[Seq[String]] =
    json.select(name).asOpt[Seq[String]].map(_.map(_.trim).filter(_.nonEmpty))

  val format: Format[CloudApimLoginGuardConfig] = new Format[CloudApimLoginGuardConfig] {
    override def writes(o: CloudApimLoginGuardConfig): JsValue = Json.obj(
      "login_paths"           -> o.loginPaths,
      "methods"               -> o.methods,
      "username_fields"       -> o.usernameFields,
      "password_fields"       -> o.passwordFields,
      "failure_statuses"      -> o.failureStatuses,
      "failure_marker"        -> o.failureMarker,
      "realm"                 -> o.realm,
      "window_seconds"        -> o.windowSeconds,
      "source_failures"       -> o.thresholds.sourceFailures,
      "source_accounts"       -> o.thresholds.sourceAccounts,
      "account_failures"      -> o.thresholds.accountFailures,
      "account_sources"       -> o.thresholds.accountSources,
      "stuffing_weight"       -> o.thresholds.stuffingWeight,
      "spraying_weight"       -> o.thresholds.sprayingWeight,
      "account_attack_weight" -> o.thresholds.accountAttackWeight,
      "takeover_accounts"     -> o.takeoverAccounts,
      "takeover_weight"       -> o.takeoverWeight,
      "breached_passwords"    -> o.breachedPasswords,
      "breached_weight"       -> o.breachedWeight,
      "body_limit"            -> o.bodyLimit
    )
    override def reads(json: JsValue): JsResult[CloudApimLoginGuardConfig] = Try {
      val d = CloudApimLoginGuardConfig.default
      val t = d.thresholds
      def int(name: String, default: Int) = json.select(name).asOpt[Int].filter(_ >= 0).getOrElse(default)
      CloudApimLoginGuardConfig(
        loginPaths = strings(json, "login_paths").getOrElse(d.loginPaths),
        methods = strings(json, "methods").filter(_.nonEmpty).getOrElse(d.methods),
        usernameFields = strings(json, "username_fields").filter(_.nonEmpty).getOrElse(d.usernameFields),
        passwordFields = strings(json, "password_fields").filter(_.nonEmpty).getOrElse(d.passwordFields),
        failureStatuses = json.select("failure_statuses").asOpt[Seq[Int]].getOrElse(d.failureStatuses),
        failureMarker = json.select("failure_marker").asOpt[String].map(_.trim).filter(_.nonEmpty),
        realm = json.select("realm").asOpt[String].map(_.trim).filter(_.nonEmpty),
        windowSeconds = json.select("window_seconds").asOpt[Long].filter(_ > 0L).getOrElse(d.windowSeconds),
        thresholds = LoginThresholds(
          sourceFailures = int("source_failures", t.sourceFailures),
          sourceAccounts = int("source_accounts", t.sourceAccounts),
          accountFailures = int("account_failures", t.accountFailures),
          accountSources = int("account_sources", t.accountSources),
          stuffingWeight = int("stuffing_weight", t.stuffingWeight),
          sprayingWeight = int("spraying_weight", t.sprayingWeight),
          accountAttackWeight = int("account_attack_weight", t.accountAttackWeight)
        ),
        takeoverAccounts = int("takeover_accounts", d.takeoverAccounts),
        takeoverWeight = int("takeover_weight", d.takeoverWeight),
        breachedPasswords = json.select("breached_passwords").asOpt[Boolean].getOrElse(d.breachedPasswords),
        breachedWeight = int("breached_weight", d.breachedWeight),
        bodyLimit = json.select("body_limit").asOpt[Long].filter(_ > 0L).getOrElse(d.bodyLimit)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "login_paths", "methods", "username_fields", "password_fields", "failure_statuses", "failure_marker", "realm", "window_seconds",
    "source_failures", "source_accounts", "account_failures", "account_sources", "stuffing_weight", "spraying_weight",
    "account_attack_weight", "takeover_accounts", "takeover_weight", "breached_passwords", "breached_weight", "body_limit"
  )

  private def number(label: String, help: String) = Json.obj("type" -> "number", "label" -> label, "props" -> Json.obj("help" -> help))
  private def array(label: String, help: String)  = Json.obj("type" -> "array", "label" -> label, "props" -> Json.obj("help" -> help))

  val configSchema: JsObject = Json.obj(
    "login_paths"           -> array("Login paths", "Exact paths, or prefixes ending in *. Empty means every request of the route with one of the methods"),
    "methods"               -> array("Methods", "The methods a login is sent with"),
    "username_fields"       -> array("Username fields", "Where the username is, in a JSON or form body. A dotted field reaches into JSON"),
    "password_fields"       -> array("Password fields", "Where the password is, read only to check it against known breaches"),
    "failure_statuses"      -> Json.obj("type" -> "array", "label" -> "Failure statuses", "props" -> Json.obj("help" -> "The statuses a failed login answers with")),
    "failure_marker"        -> Json.obj(
      "type"  -> "string",
      "label" -> "Failure marker",
      "props" -> Json.obj("help" -> "For an application that answers 200 either way: a text its failed logins contain")
    ),
    "realm"                 -> Json.obj(
      "type"  -> "string",
      "label" -> "Realm",
      "props" -> Json.obj("help" -> "Routes sharing an account base share counters through the same realm. Empty means this route alone")
    ),
    "window_seconds"        -> number("Window", "How long failures are counted together, in seconds"),
    "source_failures"       -> number("Stuffing: failures per source", "Failed logins from one source past which it is credential stuffing. 0 turns it off"),
    "source_accounts"       -> number("Spraying: accounts per source", "Accounts failed from one source past which it is password spraying. 0 turns it off"),
    "account_failures"      -> number("Account attack: failures per account", "Failed logins on one account. 0 turns it off"),
    "account_sources"       -> number("Account attack: sources per account", "From at least this many sources"),
    "stuffing_weight"       -> number("Stuffing weight", "What credential stuffing contributes to the threat score"),
    "spraying_weight"       -> number("Spraying weight", "What password spraying contributes"),
    "account_attack_weight" -> number("Account attack weight", "What trying an account under attack contributes"),
    "takeover_accounts"     -> number("Takeover: accounts failed first", "A successful login from a source that failed against this many accounts is a likely takeover"),
    "takeover_weight"       -> number("Takeover weight", "What a likely takeover charges the caller's ledger"),
    "breached_passwords"    -> Json.obj(
      "type"  -> "bool",
      "label" -> "Check breached passwords",
      "props" -> Json.obj("help" -> "Ask Have I Been Pwned whether the password is known, sending only five characters of its SHA-1")
    ),
    "breached_weight"       -> number("Breached password weight", "What a known password contributes"),
    "body_limit"            -> number("Body limit", "Bytes of a login request read to find its credentials")
  )
}

/** One login attempt, carried from the request to its response. */
final case class LoginAttempt(realm: String, source: String, account: String, masked: String)

object CloudApimLoginGuard {
  val AttemptKey: TypedKey[LoginAttempt] = TypedKey[LoginAttempt]("cloud-apim.waf.login.attempt")
}

/**
 * Credential stuffing, password spraying and account takeover, seen on the login endpoints (BEH-3).
 *
 * On the way in, a login's username is read and what the counters say about its source and its
 * account becomes signals on the threat bus: the threat response turns them into a challenge, a
 * tarpit, a refusal or a ban, as the policy's tiers say, so an attack that goes on climbs them. On
 * the way back, a failed login is counted; a successful one from a source that has just failed
 * against several accounts is reported as a likely takeover and charged to the caller's ledger.
 *
 * Nothing here refuses a request on its own, and nothing ever refuses an account: its owner is at
 * worst challenged while an attack on it lasts.
 */
class CloudApimLoginGuard extends NgRequestTransformer {

  private val logger = Logger("cloud-apim-waf-login")

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Threat Protection - Login guard"
  override def description: Option[String]                 =
    "Sees credential stuffing, password spraying and likely account takeovers on login endpoints, and scores them".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimLoginGuardConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimLoginGuardConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimLoginGuardConfig.configSchema.some

  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  private def config(ctx: NgCachedConfigContext): CloudApimLoginGuardConfig =
    ctx.cachedConfig(internalName)(CloudApimLoginGuardConfig.format).getOrElse(CloudApimLoginGuardConfig.default)

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val cfg     = config(ctx)
    val request = ctx.otoroshiRequest
    (ThreatSupport.module, cfg.isLogin(ctx.request.method, ctx.request.path)) match {
      case (Some(mod), true) =>
        BodyReader.prefix(request.body, cfg.bodyLimit).flatMap { prefix =>
          val forwarded = request.copy(body = prefix.resume)
          Credentials.extract(prefix.bytes, request.contentType, request.header("Authorization"), cfg.usernameFields, cfg.passwordFields) match {
            case None              => Right(forwarded).vfuture
            case Some(credentials) =>
              val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
              // the address, not the api key: on a login endpoint the key belongs to the app, the attempts to someone
              val source   = identity.ip
              val realm    = cfg.realm.getOrElse(ctx.route.id)
              val account  = mod.logins.account(realm, credentials.username)
              ctx.attrs.put(CloudApimLoginGuard.AttemptKey -> LoginAttempt(realm, source, account, credentials.masked))
              val breached =
                if (cfg.breachedPasswords) credentials.password.map(mod.breachedPasswords.breached).getOrElse(Future.successful(None))
                else Future.successful(None)
              (for {
                state  <- mod.logins.state(realm, source, account, cfg.windowMillis)
                broken <- breached
              } yield {
                val signals = cfg.thresholds.signals(state) ++
                  broken.filter(known => known).map(_ => ("breached_password", cfg.breachedWeight, "the password is in a known breach"))
                signals.foreach { case (kind, weight, detail) =>
                  ThreatBus.contribute(
                    ctx.attrs,
                    identity,
                    ThreatSignal(source = "login", kind = kind, weight = weight, tag = s"login:$kind", detail = Some(s"$detail, ${credentials.masked}"))
                  )
                  observe(mod, ctx, identity, cfg, realm, if (kind == "account_under_attack") account else source, kind, weight, detail, credentials.masked)
                }
                Right(forwarded)
              }).recover { case e =>
                logger.error("could not read the login counters, the login goes through unscored", e)
                Right(forwarded)
              }
          }
        }
      case _                 => request.rightf
    }
  }

  /** A pattern seen for the first time in its window is reported once; the bus carries every one. */
  private def observe(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      identity: ClientIdentity,
      cfg: CloudApimLoginGuardConfig,
      realm: String,
      key: String,
      kind: String,
      weight: Int,
      detail: String,
      masked: String
  )(using env: Env, ec: ExecutionContext): Unit =
    mod.logins.first(realm, s"$kind:$key", cfg.windowMillis).foreach { first =>
      if (first)
        mod.record(
          category = "login",
          identity = identity,
          decision = ThreatDecision(action = ThreatAction.Log, score = weight, tier = None, dryRun = true, reason = detail),
          tags = Seq(s"login:$kind"),
          signals = Json.arr(Json.obj("pattern" -> kind, "detail" -> detail, "account" -> masked, "realm" -> realm)),
          routeId = ctx.route.id.some,
          routeName = ctx.route.name.some,
          message = s"${kind.replace('_', ' ')}: $detail, $masked"
        )
    }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val cfg      = config(ctx)
    val response = ctx.otoroshiResponse
    (ThreatSupport.module, ctx.attrs.get(CloudApimLoginGuard.AttemptKey)) match {
      case (Some(mod), Some(attempt)) =>
        def settle(failed: Boolean): Unit = {
          val done =
            if (failed) mod.logins.failed(attempt.realm, attempt.source, attempt.account, cfg.windowMillis)
            else
              mod.logins.state(attempt.realm, attempt.source, attempt.account, cfg.windowMillis).map { state =>
                if (cfg.takeoverAccounts > 0 && state.sourceAccounts >= cfg.takeoverAccounts) takeover(mod, ctx, cfg, attempt, state)
              }
          done.failed.foreach(e => logger.error("could not count a login", e))
        }
        val byStatus = cfg.failureStatuses.contains(response.status)
        cfg.failureMarker match {
          case None         =>
            settle(byStatus)
            response.rightf
          case Some(marker) =>
            BodyReader.prefix(response.body, 16L * 1024L).map { prefix =>
              val (bytes, _) = RequestBodies.readResponse(prefix, response.headers)
              settle(byStatus || bytes.utf8String.contains(marker))
              Right(response.copy(body = prefix.resume))
            }
        }
      case _                          => response.rightf
    }
  }

  /** A login that succeeded from a source that was failing against other accounts a moment ago. */
  private def takeover(mod: SecurityModule, ctx: NgTransformerResponseContext, cfg: CloudApimLoginGuardConfig, attempt: LoginAttempt, state: LoginState)(using
      env: Env
  ): Unit = {
    val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
    val detail   = s"${attempt.masked} logged in from ${attempt.source}, which failed against ${state.sourceAccounts} accounts"
    mod.record(
      category = "login",
      identity = identity,
      decision = ThreatDecision(action = ThreatAction.Log, score = cfg.takeoverWeight, tier = None, dryRun = false, reason = detail),
      tags = Seq("login:account_takeover"),
      signals = Json.arr(Json.obj("pattern" -> "account_takeover", "account" -> attempt.masked, "failed_accounts" -> state.sourceAccounts, "realm" -> attempt.realm)),
      routeId = ctx.route.id.some,
      routeName = ctx.route.name.some,
      message = s"likely account takeover: $detail",
      ledgerWeight = cfg.takeoverWeight
    )
  }
}
