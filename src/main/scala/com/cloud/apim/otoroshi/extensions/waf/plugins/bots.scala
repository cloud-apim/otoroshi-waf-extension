package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.bots.BotVerdict
import com.cloud.apim.otoroshi.extensions.waf.security.*
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

final case class CloudApimBotConfig(policy: Option[String] = None) extends NgPluginConfig {
  override def json: JsValue = CloudApimBotConfig.format.writes(this)
}

object CloudApimBotConfig {
  val default: CloudApimBotConfig = CloudApimBotConfig()
  val format: Format[CloudApimBotConfig] = new Format[CloudApimBotConfig] {
    override def writes(o: CloudApimBotConfig): JsValue = Json.obj("policy" -> o.policy)
    override def reads(json: JsValue): JsResult[CloudApimBotConfig] = Try {
      CloudApimBotConfig(json.select("policy").asOpt[String].map(_.trim).filter(_.nonEmpty))
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
  }
  val configFlow: Seq[String] = Seq("policy")
  val configSchema: JsObject = Json.obj(
    "policy" -> Json.obj(
      "type"  -> "select",
      "label" -> "Bot policy",
      "props" -> Json.obj(
        "help"               -> "Leave empty to use the first enabled policy",
        "optionsFrom"        -> "/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/bot-policies",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
      )
    )
  )
}

/**
 * Identifies automated callers, proves or disproves what they claim, and applies the policy.
 *
 * The interesting outcome is not the crawler that is who it says it is — it is the one that is not.
 * "Googlebot" is the most forged user-agent on the web, and a failed forward-confirmed reverse DNS
 * check turns a suspicious string into a demonstrated lie, which is worth far more on the score
 * than any heuristic about the string itself.
 */
class CloudApimBotGuard extends NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Security Suite - Bot guard"
  override def description: Option[String]                 =
    "Recognises known crawlers, verifies the ones that publish a method, and applies a per-category policy".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimBotConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimBotConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimBotConfig.configSchema.some

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(CloudApimBotConfig.format).getOrElse(CloudApimBotConfig.default)
    ThreatSupport.module match {
      case None      => NgAccess.NgAllowed.vfuture
      case Some(mod) =>
        mod.botPolicy(config.policy) match {
          case None         => NgAccess.NgAllowed.vfuture
          case Some(policy) =>
            val userAgent = ctx.request.headers.get("User-Agent").getOrElse("")
            policy.signatureFor(userAgent) match {
              case None      => NgAccess.NgAllowed.vfuture
              case Some(sig) =>
                val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
                val verdict  = if (policy.verifyKnownBots) mod.botVerifier.check(identity.ip, sig) else BotVerdict.Unknown
                val rule     = policy.ruleFor(sig)

                if (verdict == BotVerdict.Impersonator) {
                  // a demonstrated lie, not a heuristic
                  ThreatBus.contribute(
                    ctx.attrs,
                    identity,
                    ThreatSignal(
                      source = "bots.identity",
                      kind = "bot",
                      weight = policy.impersonatorWeight,
                      tag = s"bot:impersonator:${sig.name}",
                      detail = Some(s"claims to be ${sig.name}, reverse dns does not confirm it")
                    )
                  )
                  if (policy.impersonatorAction.equalsIgnoreCase("deny")) {
                    ThreatSupport.deny(policy.denyStatus, ctx).map(NgAccess.NgDenied.apply)
                  } else NgAccess.NgAllowed.vfuture
                } else if (verdict == BotVerdict.Verified && policy.verifiedBypass) {
                  // proven to be who it says: record it and get out of its way
                  ThreatBus.contribute(
                    ctx.attrs,
                    identity,
                    ThreatSignal(
                      source = "bots.identity",
                      kind = "bot",
                      weight = 0,
                      tag = s"bot:verified:${sig.name}",
                      detail = Some(s"forward-confirmed reverse dns for ${sig.name}")
                    )
                  )
                  NgAccess.NgAllowed.vfuture
                } else {
                  rule.action.toLowerCase match {
                    case "allow" => NgAccess.NgAllowed.vfuture
                    case "deny"  =>
                      contributeRule(ctx, identity, sig.name, sig.category, rule.weight.max(50), "denied by policy")
                      ThreatSupport.deny(policy.denyStatus, ctx).map(NgAccess.NgDenied.apply)
                    case _       =>
                      contributeRule(ctx, identity, sig.name, sig.category, rule.weight, verdict.name)
                      NgAccess.NgAllowed.vfuture
                  }
                }
            }
        }
    }
  }

  private def contributeRule(
      ctx: NgAccessContext,
      identity: ClientIdentity,
      botName: String,
      category: String,
      weight: Int,
      detail: String
  ): Unit = {
    ThreatBus.contribute(
      ctx.attrs,
      identity,
      ThreatSignal(
        source = "bots.policy",
        kind = "bot",
        weight = weight,
        tag = s"bot:$category:$botName",
        detail = Some(detail)
      )
    )
  }
}

/**
 * Paths and values that nobody legitimate ever asks for.
 *
 * A **global** validator on purpose: `/wp-login.php` and `/.env` match no route, so a route-level
 * plugin would never see the requests this exists to catch. Configured in the global configuration
 * alongside the other incoming request validators.
 */
class IncomingRequestValidatorCloudApimHoneypot extends NgIncomingRequestValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Security Suite - Honeypot (Incoming Request Validator)"
  override def description: Option[String]                 =
    "Treats a request for a path nobody legitimate asks for as near-certain evidence".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimBotConfig.default.some

  override def configFlow: Seq[String]        = CloudApimBotConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimBotConfig.configSchema.some

  override def access(ctx: NgIncomingRequestValidatorContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = CloudApimBotConfig.format.reads(ctx.config).asOpt.getOrElse(CloudApimBotConfig.default)
    ThreatSupport.module match {
      case None      => NgAccess.NgAllowed.vfuture
      case Some(mod) =>
        mod.honeypotPolicy(config.policy) match {
          case None         => NgAccess.NgAllowed.vfuture
          case Some(policy) =>
            val hit = policy
              .matchingPath(ctx.request.path)
              .map(p => (s"honeypot:path", s"requested $p, which exists for nobody"))
              .orElse(
                policy
                  .matchingCanary(ctx.request)
                  .map(c => (s"honeypot:canary", s"presented a canary value${if (c.description.isEmpty) "" else s" (${c.description})"}"))
              )
            hit match {
              case None                  => NgAccess.NgAllowed.vfuture
              case Some((tag, detail))   =>
                val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
                ThreatBus.contribute(
                  ctx.attrs,
                  identity,
                  ThreatSignal(source = "honeypot", kind = "deception", weight = policy.weight, tag = tag, detail = Some(detail))
                )
                val decision = ThreatDecision(
                  action = if (policy.bans) ThreatAction.Ban else if (policy.denies) ThreatAction.Deny else ThreatAction.Log,
                  score = policy.weight,
                  tier = None,
                  dryRun = false,
                  reason = detail
                )
                mod.record(
                  category = "honeypot",
                  identity = identity,
                  decision = decision,
                  tags = Seq(tag),
                  signals = JsArray(Seq.empty),
                  routeId = None,
                  routeName = None,
                  message = detail,
                  ledgerWeight = policy.weight
                )
                if (policy.bans) {
                  identity.refs.headOption.foreach { ref =>
                    mod.bans.ban(ref, policy.banFor, detail, Seq(tag), policy.weight)
                  }
                }
                // 404 by default: a 403 would tell the scanner that something is there
                if (policy.denies) NgAccess.NgDenied(Results.Status(policy.status)("")).vfuture
                else NgAccess.NgAllowed.vfuture
            }
        }
    }
  }
}
