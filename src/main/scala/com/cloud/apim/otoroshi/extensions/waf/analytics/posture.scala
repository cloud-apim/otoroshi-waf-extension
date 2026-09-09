package com.cloud.apim.otoroshi.extensions.waf.analytics

import otoroshi.env.Env
import otoroshi.next.models.{NgPluginInstance, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.*
import play.api.libs.json.*

/** What one route is actually protected by, as opposed to what someone believes it is. */
final case class RoutePosture(
    routeId: String,
    routeName: String,
    viaPreset: Boolean,
    waf: Option[String],
    wafBlocking: Boolean,
    reputation: Option[String],
    bots: Boolean,
    fail2ban: Option[Boolean],
    gate: Boolean,
    response: Boolean,
    policy: Option[String],
    policyDryRun: Boolean
) {

  /** Anything at all, on this route. */
  def covered: Boolean = waf.isDefined || reputation.isDefined || bots || fail2ban.isDefined || gate || response

  /**
   * Whether a decision on this route can actually stop anything.
   *
   * A route can be covered and enforce nothing — the whole suite in dry run — and that distinction
   * is the reason this view exists. "Protected" without it means "configured".
   */
  def enforcing: Boolean =
    (waf.isDefined && wafBlocking) || (response && !policyDryRun) || reputation.contains("block")

  def json: JsValue = Json.obj(
    "route_id"       -> routeId,
    "route_name"     -> routeName,
    "via_preset"     -> viaPreset,
    "waf"            -> waf,
    "waf_blocking"   -> wafBlocking,
    "reputation"     -> reputation,
    "bots"           -> bots,
    "fail2ban"       -> fail2ban.map(dry => if (dry) "dry run" else "armed"),
    "gate"           -> gate,
    "response"       -> response,
    "policy"         -> policy,
    "policy_dry_run" -> policyDryRun,
    "covered"        -> covered,
    "enforcing"      -> enforcing
  )
}

/**
 * Which routes are protected, in which mode.
 *
 * It reads live state — the router's routes and the suite's own entities — and touches no analytics
 * at all: it answers a question about configuration, not about traffic, so it works before a single
 * event has been emitted and without the user-analytics exporter being set up.
 *
 * The row that earns the page is the one with nothing on it. Nobody goes looking for a route they
 * forgot to protect.
 */
object PostureReport {

  private val wafId        = NgPluginHelper.pluginId[CloudApimWaf]
  private val reputationId = NgPluginHelper.pluginId[CloudApimIpReputation]
  private val botsId       = NgPluginHelper.pluginId[CloudApimBotGuard]
  private val fail2banId   = NgPluginHelper.pluginId[CloudApimFail2Ban]
  private val gateId       = NgPluginHelper.pluginId[CloudApimThreatGate]
  private val responseId   = NgPluginHelper.pluginId[CloudApimThreatResponse]
  private val presetId     = NgPluginHelper.pluginId[CloudApimSecuritySuitePreset]

  def of(route: NgRoute)(using env: Env): RoutePosture = {
    val ext    = env.adminExtensions.extension[CloudApimWafExtension]
    val slots  = route.plugins.slots.filter(_.enabled)
    def slot(id: String): Option[NgPluginInstance] = slots.find(_.plugin == id)

    // the preset expands at request time, so its coverage is read from its flags rather than by
    // running the expansion — the answer has to be available with no request in flight
    val preset       = slot(presetId).map(_.config.raw)
    val presetOn     = (field: String, default: Boolean) =>
      preset.exists(c => (c \ field).asOpt[Boolean].getOrElse(default))
    val presetWafRef = preset.flatMap(c => (c \ "waf_config").asOpt[String]).filter(_.trim.nonEmpty)

    val wafRef = slot(wafId)
      .flatMap(i => (i.config.raw \ "ref").asOpt[String])
      .orElse(presetWafRef.filter(_ => presetOn("waf", true)))
      .filter(_.trim.nonEmpty)
    val wafCfg = wafRef.flatMap(r => ext.flatMap(_.states.config(r)))

    val reputationMode = slot(reputationId)
      .map(i => (i.config.raw \ "mode").asOpt[String].getOrElse("block"))
      .orElse(
        Option
          .when(preset.isDefined && presetOn("reputation", true))(
            preset.flatMap(c => (c \ "reputation_mode").asOpt[String]).getOrElse("block")
          )
      )

    val policyRef = slot(gateId)
      .orElse(slot(responseId))
      .flatMap(i => (i.config.raw \ "policy").asOpt[String])
      .orElse(preset.flatMap(c => (c \ "threat_policy").asOpt[String]))
      .filter(_.trim.nonEmpty)
    val policy    = policyRef.flatMap(r => ext.flatMap(_.security.policy(r)))

    RoutePosture(
      routeId = route.id,
      routeName = route.name,
      viaPreset = preset.isDefined,
      waf = wafCfg.map(_.name),
      wafBlocking = wafCfg.exists(_.block),
      reputation = reputationMode,
      bots = slot(botsId).isDefined || (preset.isDefined && presetOn("bots", true)),
      fail2ban = slot(fail2banId)
        .map(i => (i.config.raw \ "dry_run").asOpt[Boolean].getOrElse(true))
        .orElse(
          Option.when(preset.isDefined && presetOn("fail2ban", false))(
            preset.flatMap(c => (c \ "fail2ban_dry_run").asOpt[Boolean]).getOrElse(true)
          )
        ),
      gate = slot(gateId).isDefined || (preset.isDefined && presetOn("gate", true)),
      response = slot(responseId).isDefined || (preset.isDefined && presetOn("response", true)),
      policy = policy.map(_.name).orElse(policyRef),
      // no policy at all means the built-in one, which is dry run by definition
      policyDryRun = policy.forall(_.dryRun)
    )
  }

  def all(using env: Env): Seq[RoutePosture] =
    env.proxyState.allRoutes().map(of).sortBy(p => (p.covered, p.routeName))

  def json(using env: Env): JsValue = {
    val postures = all
    Json.obj(
      "routes"    -> JsArray(postures.map(_.json)),
      "summary"   -> Json.obj(
        "total"     -> postures.size,
        "covered"   -> postures.count(_.covered),
        "enforcing" -> postures.count(_.enforcing),
        "uncovered" -> postures.count(!_.covered)
      )
    )
  }
}
