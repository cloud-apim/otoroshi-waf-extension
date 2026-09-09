package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, PluginIndex}
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.util.{Failure, Success, Try}

final case class CloudApimSecuritySuitePresetConfig(
    threatPolicy: Option[String] = None,
    botPolicy: Option[String] = None,
    wafConfig: Option[String] = None,
    gate: Boolean = true,
    bots: Boolean = true,
    reputation: Boolean = true,
    waf: Boolean = true,
    fail2ban: Boolean = false,
    response: Boolean = true,
    reputationMode: String = "block",
    fail2banDryRun: Boolean = true,
    include: Seq[String] = Seq.empty,
    exclude: Seq[String] = Seq.empty
) extends NgPluginConfig {
  override def json: JsValue = CloudApimSecuritySuitePresetConfig.format.writes(this)
}

object CloudApimSecuritySuitePresetConfig {

  val default: CloudApimSecuritySuitePresetConfig = CloudApimSecuritySuitePresetConfig()

  private def refOf(json: JsValue, name: String): Option[String] =
    json.select(name).asOpt[String].map(_.trim).filter(_.nonEmpty)

  val format: Format[CloudApimSecuritySuitePresetConfig] = new Format[CloudApimSecuritySuitePresetConfig] {
    override def writes(o: CloudApimSecuritySuitePresetConfig): JsValue = Json.obj(
      "threat_policy"   -> o.threatPolicy,
      "bot_policy"      -> o.botPolicy,
      "waf_config"      -> o.wafConfig,
      "gate"            -> o.gate,
      "bots"            -> o.bots,
      "reputation"      -> o.reputation,
      "waf"             -> o.waf,
      "fail2ban"        -> o.fail2ban,
      "response"        -> o.response,
      "reputation_mode" -> o.reputationMode,
      "fail2ban_dry_run" -> o.fail2banDryRun,
      "include"         -> o.include,
      "exclude"         -> o.exclude
    )
    override def reads(json: JsValue): JsResult[CloudApimSecuritySuitePresetConfig] = Try {
      CloudApimSecuritySuitePresetConfig(
        threatPolicy = refOf(json, "threat_policy"),
        botPolicy = refOf(json, "bot_policy"),
        wafConfig = refOf(json, "waf_config"),
        gate = json.select("gate").asOpt[Boolean].getOrElse(true),
        bots = json.select("bots").asOpt[Boolean].getOrElse(true),
        reputation = json.select("reputation").asOpt[Boolean].getOrElse(true),
        waf = json.select("waf").asOpt[Boolean].getOrElse(true),
        fail2ban = json.select("fail2ban").asOpt[Boolean].getOrElse(false),
        response = json.select("response").asOpt[Boolean].getOrElse(true),
        reputationMode = json.select("reputation_mode").asOpt[String].getOrElse("block"),
        fail2banDryRun = json.select("fail2ban_dry_run").asOpt[Boolean].getOrElse(true),
        include = json.select("include").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty),
        exclude = json.select("exclude").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "threat_policy",
    "gate",
    "bots",
    "bot_policy",
    "reputation",
    "reputation_mode",
    "waf",
    "waf_config",
    "fail2ban",
    "fail2ban_dry_run",
    "response",
    "include",
    "exclude"
  )

  val configSchema: JsObject = Json.obj(
    "threat_policy"   -> Json.obj(
      "type"  -> "select",
      "label" -> "Threat policy",
      "props" -> Json.obj(
        "help"               -> "Shared by the gate and the response. Leave empty for the built-in dry-run policy that records but never enforces.",
        "optionsFrom"        -> "/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/threat-policies",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
      )
    ),
    "gate"            -> Json.obj(
      "type"  -> "bool",
      "label" -> "Threat gate",
      "props" -> Json.obj("help" -> "Refuse callers that are already banned, before anything else runs")
    ),
    "bots"            -> Json.obj(
      "type"  -> "bool",
      "label" -> "Bot guard",
      "props" -> Json.obj("help" -> "Identify crawlers and verify the ones that publish a method")
    ),
    "bot_policy"      -> Json.obj(
      "type"  -> "select",
      "label" -> "Bot policy",
      "props" -> Json.obj(
        "help"               -> "Leave empty to use the first enabled policy",
        "optionsFrom"        -> "/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/bot-policies",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
      )
    ),
    "reputation"      -> Json.obj(
      "type"  -> "bool",
      "label" -> "IP reputation",
      "props" -> Json.obj("help" -> "Score the caller against every enabled threat feed and CrowdSec bouncer")
    ),
    "reputation_mode" -> Json.obj(
      "type"  -> "select",
      "label" -> "IP reputation mode",
      "props" -> Json.obj(
        "help"    -> "'monitor' scores and reports without ever denying on its own, leaving the decision to the threat response",
        "options" -> Json.arr(
          Json.obj("label" -> "Block", "value"   -> "block"),
          Json.obj("label" -> "Monitor", "value" -> "monitor")
        )
      )
    ),
    "waf"             -> Json.obj(
      "type"  -> "bool",
      "label" -> "WAF",
      "props" -> Json.obj("help" -> "Run the rule engine. Ignored when no WAF config is selected below.")
    ),
    "waf_config"      -> Json.obj(
      "type"  -> "select",
      "label" -> "WAF config.",
      "props" -> Json.obj(
        "optionsFrom"        -> "/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/waf-configs",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
      )
    ),
    "fail2ban"        -> Json.obj(
      "type"  -> "bool",
      "label" -> "Fail2ban",
      "props" -> Json.obj(
        "help" -> "Ban callers that keep producing failed responses. Off by default: it is the one detector here your own clients can trip."
      )
    ),
    "fail2ban_dry_run" -> Json.obj(
      "type"  -> "bool",
      "label" -> "Fail2ban dry run",
      "props" -> Json.obj(
        "help" -> "Count and report without banning. Leave it on until the failure statuses have been checked against real traffic."
      )
    ),
    "response"        -> Json.obj(
      "type"  -> "bool",
      "label" -> "Threat response",
      "props" -> Json.obj("help" -> "Read the accumulated score and apply one graded action. Without it nothing enforces the score.")
    ),
    "include"         -> Json.obj(
      "type"  -> "array",
      "label" -> "Apply on paths",
      "props" -> Json.obj(
        "help" -> "Set the scope here, not on the plugin slot: Otoroshi drops a preset's own include/exclude when it expands."
      )
    ),
    "exclude"         -> Json.obj(
      "type"  -> "array",
      "label" -> "Except paths",
      "props" -> Json.obj("help" -> "Same remark as above")
    )
  )
}

/**
 * Lays down the whole detection fabric on a route, in the one order that makes it work.
 *
 * The fabric is a chain of plugins that talk to each other through a shared score, and the chain
 * only means anything if the response runs last: put it before the WAF and it reads a score the WAF
 * has not contributed to yet, silently, with every request still flowing. Composing the five slots
 * by hand is therefore a correctness problem, not a convenience one — this preset expands into them
 * with explicit `plugin_index` values, so the order stops being something a reader has to know.
 *
 * A section switched off expands into nothing at all, rather than into a disabled instance: a
 * plugin that is present but inert is exactly the kind of thing one finds six months later while
 * wondering why it never fired.
 *
 * Not covered, and deliberately: the honeypot and the incoming-request-validator variants of the
 * WAF and of IP reputation. Those run before routing, off the global config, so no route-level
 * preset can reach them.
 */
class CloudApimSecuritySuitePreset extends NgPresetPlugin {

  override def steps: Seq[NgStep]                          =
    Seq(NgStep.ValidateAccess, NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           =
    Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Security Suite - Preset"
  override def description: Option[String]                 =
    "Expands into the whole detection fabric — threat gate, bot guard, IP reputation, WAF and threat response — in the right order".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimSecuritySuitePresetConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimSecuritySuitePresetConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimSecuritySuitePresetConfig.configSchema.some

  private def slot(
      plugin: String,
      config: JsObject,
      index: PluginIndex,
      preset: CloudApimSecuritySuitePresetConfig
  ): NgPluginInstance = NgPluginInstance(
    plugin = plugin,
    include = preset.include,
    exclude = preset.exclude,
    config = NgPluginInstanceConfig(config),
    pluginIndex = index.some
  )

  override def expand(ctx: NgPresetPluginContext): Seq[NgPluginInstance] = instances(
    CloudApimSecuritySuitePresetConfig.format
      .reads(ctx.config)
      .getOrElse(CloudApimSecuritySuitePresetConfig.default)
  )

  /** The expansion itself, kept clear of the request context so the resulting chain can be asserted on. */
  def instances(config: CloudApimSecuritySuitePresetConfig): Seq[NgPluginInstance] = {
    val threat = CloudApimThreatConfig(policy = config.threatPolicy).json.asObject

    val gate = Option.when(config.gate) {
      slot(
        NgPluginHelper.pluginId[CloudApimThreatGate],
        threat,
        PluginIndex(validateAccess = 1.0.some),
        config
      )
    }

    val bots = Option.when(config.bots) {
      slot(
        NgPluginHelper.pluginId[CloudApimBotGuard],
        CloudApimBotConfig(policy = config.botPolicy).json.asObject,
        PluginIndex(validateAccess = 2.0.some),
        config
      )
    }

    val reputation = Option.when(config.reputation) {
      slot(
        NgPluginHelper.pluginId[CloudApimIpReputation],
        CloudApimIpReputationConfig(mode = config.reputationMode).json.asObject,
        PluginIndex(validateAccess = 3.0.some),
        config
      )
    }

    // the rule engine holds its own config entity, so an unset ref is not a WAF with defaults —
    // it is a WAF with nothing to run
    val waf = config.wafConfig.filter(_ => config.waf).map { ref =>
      slot(
        NgPluginHelper.pluginId[CloudApimWaf],
        CloudApimWafConfigRef(ref = ref).json.asObject,
        PluginIndex(transformRequest = 1.0.some),
        config
      )
    }

    // its own bans are enforced by the gate above; this slot is what counts the failures
    val fail2ban = Option.when(config.fail2ban) {
      slot(
        NgPluginHelper.pluginId[CloudApimFail2Ban],
        CloudApimFail2BanConfig(dryRun = config.fail2banDryRun).json.asObject,
        PluginIndex(validateAccess = 4.0.some),
        config
      )
    }

    // far to the right of anything that could still contribute a signal
    val response = Option.when(config.response) {
      slot(
        NgPluginHelper.pluginId[CloudApimThreatResponse],
        threat,
        PluginIndex(transformRequest = 900.0.some),
        config
      )
    }

    Seq(gate, bots, reputation, fail2ban, waf, response).flatten
  }
}
