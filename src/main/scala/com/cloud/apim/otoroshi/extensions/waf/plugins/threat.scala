package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.challenge.{Interstitial, Pow}
import com.cloud.apim.otoroshi.extensions.waf.entities.ChallengeProvider
import com.cloud.apim.otoroshi.extensions.waf.security.*
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.*
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import play.api.libs.json.*
import play.api.mvc.{Cookie, RequestHeader, Result, Results}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

final case class CloudApimThreatConfig(policy: Option[String] = None) extends NgPluginConfig {
  override def json: JsValue = CloudApimThreatConfig.format.writes(this)
}

object CloudApimThreatConfig {

  val default: CloudApimThreatConfig = CloudApimThreatConfig()

  val format: Format[CloudApimThreatConfig] = new Format[CloudApimThreatConfig] {
    override def writes(o: CloudApimThreatConfig): JsValue = Json.obj("policy" -> o.policy)
    override def reads(json: JsValue): JsResult[CloudApimThreatConfig] = Try {
      CloudApimThreatConfig(policy = json.select("policy").asOpt[String].map(_.trim).filter(_.nonEmpty))
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq("policy")

  val configSchema: JsObject = Json.obj(
    "policy" -> Json.obj(
      "type"  -> "select",
      "label" -> "Threat policy",
      "props" -> Json.obj(
        "help"               -> "Leave empty to run against a built-in dry-run policy that records but never enforces",
        "optionsFrom"        -> "/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/threat-policies",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
      )
    )
  )
}

private[plugins] object ThreatSupport {

  def module(using env: Env): Option[SecurityModule] =
    env.adminExtensions.extension[CloudApimWafExtension].map(_.security)

  def identityOf(request: RequestHeader, attrs: TypedMap)(using env: Env): ClientIdentity =
    ClientIdentity.from(request, attrs)

  /** Delays without holding a thread, so a tarpit costs the attacker a connection and us nothing. */
  def delay[A](duration: FiniteDuration, value: => A)(using env: Env, ec: ExecutionContext): Future[A] = {
    if (duration.toMillis <= 0L) Future.successful(value)
    else {
      val promise = Promise[A]()
      env.otoroshiScheduler.scheduleOnce(duration)(promise.trySuccess(value))
      promise.future
    }
  }

  def deny(status: Int, ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[Result] =
    Errors.craftResponseResult(
      message = "",
      status = Results.Status(status),
      req = ctx.request,
      maybeDescriptor = None,
      maybeCauseId = None,
      duration = ctx.report.getDurationNow(),
      overhead = ctx.report.getOverheadInNow(),
      attrs = ctx.attrs,
      maybeRoute = ctx.route.some,
      emptyBody = true
    )

  def denyT(status: Int, ctx: NgTransformerRequestContext)(using env: Env, ec: ExecutionContext): Future[Result] =
    Errors.craftResponseResult(
      message = "",
      status = Results.Status(status),
      req = ctx.request,
      maybeDescriptor = None,
      maybeCauseId = None,
      duration = ctx.report.getDurationNow(),
      overhead = ctx.report.getOverheadInNow(),
      attrs = ctx.attrs,
      maybeRoute = ctx.route.some,
      emptyBody = true
    )
}

/**
 * Refuses callers who are already banned, as early as the gateway allows.
 *
 * Deliberately separate from the response engine: a ban is a decision that was already taken, so
 * enforcing it should not wait for the rule engine to inspect a request nobody is going to serve.
 * The lookup is a node-local map read — no I/O, whatever the size of the ban list.
 */
class CloudApimThreatGate extends NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Security Suite - Threat gate"
  override def description: Option[String]                 =
    "Refuses callers that are already banned, before any inspection happens".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimThreatConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimThreatConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimThreatConfig.configSchema.some

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(CloudApimThreatConfig.format).getOrElse(CloudApimThreatConfig.default)
    ThreatSupport.module match {
      case None      => NgAccess.NgAllowed.vfuture
      case Some(mod) =>
        val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
        val policy   = mod.policyOrDefault(config.policy)
        ThreatBus.start(ctx.attrs, identity)
        // the allowlist already stops bans being *issued*; checking it here closes the window where
        // a ban issued a moment earlier is still in this node's cache waiting for the next refresh
        if (policy.isExempt(identity.ip) || mod.allowlist.check(identity).isDefined) {
          NgAccess.NgAllowed.vfuture
        } else {
          mod.bans.check(identity) match {
            case None      => NgAccess.NgAllowed.vfuture
            case Some(ban) =>
              val decision = ThreatDecision(
                action = ThreatAction.Ban,
                score = ban.score,
                tier = None,
                dryRun = policy.dryRun,
                reason = s"already banned: ${ban.reason}"
              )
              ctx.attrs.put(ThreatKeys.DecisionKey -> decision)
              mod.record(
                category = "ban",
                identity = identity,
                decision = decision,
                tags = ban.tags,
                signals = ban.signals,
                routeId = ctx.route.id.some,
                routeName = ctx.route.name.some,
                message = s"${ban.ref.key} is banned until ${ban.until}"
              )
              if (decision.enforced) ThreatSupport.deny(403, ctx).map(NgAccess.NgDenied.apply)
              else NgAccess.NgAllowed.vfuture
          }
        }
    }
  }
}

/**
 * Turns the accumulated threat score into one graded action.
 *
 * Runs as a request transformer so it sits **after** the detectors: reputation contributes during
 * access validation, and the WAF during request transformation. Place it last in the chain.
 *
 * Deliberately absent: `challenge` and `throttle`. Both appear in the roadmap and neither has a
 * module behind it yet, so offering them would let someone configure a tier that silently does
 * nothing.
 */
class CloudApimThreatResponse extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Security Suite - Threat response"
  override def description: Option[String]                 =
    "Reads the accumulated threat score and applies one graded action: log, tarpit, deny or ban".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimThreatConfig.default.some

  override def isTransformRequestAsync: Boolean = true
  override def transformsRequest: Boolean       = true
  override def transformsResponse: Boolean      = false
  override def transformsError: Boolean         = false

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimThreatConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimThreatConfig.configSchema.some

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(CloudApimThreatConfig.format).getOrElse(CloudApimThreatConfig.default)
    ThreatSupport.module match {
      case None      => ctx.otoroshiRequest.rightf
      case Some(mod) =>
        // a challenge answer comes back as a post to the very same url, so it is picked up before
        // anything else — the caller is mid-verification, not making a fresh request
        ctx.request.headers.get(Interstitial.submissionHeader) match {
          case Some(raw) => handleSubmission(mod, ctx, config, raw)
          case None      => evaluate(mod, ctx, config)
        }
    }
  }

  private def evaluate(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      config: CloudApimThreatConfig
  )(using env: Env, ec: ExecutionContext): Future[Either[Result, NgPluginHttpRequest]] = {
    // the gate already acted on this request; acting twice would double-count and double-log
    if (ctx.attrs.get(ThreatKeys.DecisionKey).isDefined) {
      ctx.otoroshiRequest.rightf
    } else {
      // richer than at access time: the apikey plugin has run by now
      val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
      val policy   = mod.policyOrDefault(config.policy)
      if (policy.isExempt(identity.ip)) {
        ctx.otoroshiRequest.rightf
      } else {
        val score = ThreatBus.start(ctx.attrs, identity)
        policy.tierFor(score.score) match {
          case None if score.isEmpty => ctx.otoroshiRequest.rightf
          case None                  =>
            // below every tier: still worth recording, that is what tuning reads
            record(
              mod, ctx, identity, score,
              ThreatDecision(ThreatAction.Allow, score.score, None, policy.dryRun, "below every tier"),
              policy
            )
            ctx.otoroshiRequest.rightf
          case Some((index, tier))   =>
            val decision = ThreatDecision(
              action = tier.resolvedAction,
              score = score.score,
              tier = Some(index),
              dryRun = policy.dryRun,
              reason = s"score ${score.score} reached tier ${tier.minScore} (${tier.action})"
            )
            ctx.attrs.put(ThreatKeys.DecisionKey -> decision)
            apply(mod, ctx, identity, score, decision, tier, policy)
        }
      }
    }
  }

  /**
   * Verifies a challenge answer and grants clearance.
   *
   * Answers with 204 and the cookie: the interstitial reloads, and the reloaded request carries the
   * cookie and sails through. A failed answer is refused rather than retried silently, so a broken
   * or hostile solver cannot spin here for free.
   */
  private def handleSubmission(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      config: CloudApimThreatConfig,
      raw: String
  )(using env: Env, ec: ExecutionContext): Future[Either[Result, NgPluginHttpRequest]] = {
    val policy   = mod.policyOrDefault(config.policy)
    val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
    val ua       = ctx.request.headers.get("User-Agent")
    mod.challengeProvider(policy.challengeProvider) match {
      case None           => ThreatSupport.denyT(403, ctx).map(Left.apply)
      case Some(provider) =>
        val submission = Try(Json.parse(raw)).getOrElse(Json.obj())
        mod.challenges.verify(provider, submission, Some(identity.ip), ua).map {
          case Left(reason)     =>
            mod.record(
              category = "challenge",
              identity = identity,
              decision = ThreatDecision(ThreatAction.Deny, 0, None, policy.dryRun, s"challenge failed: $reason"),
              tags = Seq("challenge:failed"),
              signals = JsArray(Seq.empty),
              routeId = ctx.route.id.some,
              routeName = ctx.route.name.some,
              message = s"challenge answer refused: $reason"
            )
            Left(Results.Forbidden(""))
          case Right(clearance) =>
            mod.record(
              category = "challenge",
              identity = identity,
              decision = ThreatDecision(ThreatAction.Allow, clearance.score, None, policy.dryRun, "challenge passed"),
              tags = Seq("challenge:passed"),
              signals = JsArray(Seq.empty),
              routeId = ctx.route.id.some,
              routeName = ctx.route.name.some,
              message = "challenge passed, clearance granted"
            )
            Left(
              Results.NoContent.withCookies(
                Cookie(
                  name = provider.cookieName,
                  value = Pow.signClearance(clearance, mod.challengeSecret(provider)),
                  maxAge = Some(provider.clearanceTtl.toSeconds.toInt),
                  path = "/",
                  secure = ctx.request.theSecured,
                  httpOnly = true,
                  sameSite = Some(Cookie.SameSite.Lax)
                )
              )
            )
        }
    }
  }

  /** True when this caller already holds valid clearance, so the tier is satisfied. */
  private def hasClearance(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      provider: ChallengeProvider,
      identity: ClientIdentity
  )(using env: Env): Boolean = {
    ctx.request.cookies
      .get(provider.cookieName)
      .flatMap { cookie =>
        Pow.verifyClearance(
          cookie.value,
          mod.challengeSecret(provider),
          Some(identity.ip),
          ctx.request.headers.get("User-Agent")
        )
      }
      .isDefined
  }

  private def record(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      identity: ClientIdentity,
      score: ThreatScore,
      decision: ThreatDecision,
      policy: ThreatPolicy
  ): Unit = {
    mod.record(
      category = "threat",
      identity = identity,
      decision = decision,
      tags = score.tags,
      signals = JsArray(score.signals.map(_.json)),
      routeId = ctx.route.id.some,
      routeName = ctx.route.name.some,
      message = decision.reason,
      // only enforced denials feed the cross-request memory: a dry run must not accumulate towards
      // a ban it was explicitly told not to issue
      ledgerWeight = if (decision.enforced) score.score else 0
    )
  }

  private def apply(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      identity: ClientIdentity,
      score: ThreatScore,
      decision: ThreatDecision,
      tier: ThreatTier,
      policy: ThreatPolicy
  )(using env: Env, ec: ExecutionContext): Future[Either[Result, NgPluginHttpRequest]] = {
    record(mod, ctx, identity, score, decision, policy)
    if (decision.action == ThreatAction.Challenge) {
      // a challenge is neither an allow nor a deny: it is a question, and it is skipped entirely
      // when the caller has already answered one recently
      mod.challengeProvider(policy.challengeProvider) match {
        case None                                              => ctx.otoroshiRequest.rightf
        case Some(_) if policy.dryRun                          => ctx.otoroshiRequest.rightf
        case Some(provider) if hasClearance(mod, ctx, provider, identity) => ctx.otoroshiRequest.rightf
        case Some(provider)                                    =>
          mod.challenges.issue(provider, score.score).map { issued =>
            Left(
              Results
                .Ok(Interstitial.render(provider, issued))
                .as("text/html; charset=utf-8")
                .withHeaders("Cache-Control" -> "no-store")
            )
          }
      }
    } else if (!decision.enforced) {
      // dry run, or a non-denying action: the request goes through either way
      decision.action match {
        case ThreatAction.Tarpit if !policy.dryRun =>
          ThreatSupport.delay(tier.tarpit, ctx.otoroshiRequest).map(Right.apply)
        case _                                     => ctx.otoroshiRequest.rightf
      }
    } else {
      decision.action match {
        case ThreatAction.Ban =>
          policy.banRef(identity).foreach { ref =>
            mod.bans.ban(
              ref = ref,
              duration = tier.banFor,
              reason = decision.reason,
              tags = score.tags,
              score = score.score,
              signals = JsArray(score.signals.map(_.json)),
              // the signals say why this request scored; the timeline says what the caller has been
              // doing up to now, and outlives the incident that holds it
              timeline = mod.incidents.byKey(ref.key).toSeq.flatMap(_.timeline)
            )
          }
          ThreatSupport.denyT(tier.status, ctx).map(Left.apply)
        case _                =>
          ThreatSupport.denyT(tier.status, ctx).map(Left.apply)
      }
    }
  }
}
