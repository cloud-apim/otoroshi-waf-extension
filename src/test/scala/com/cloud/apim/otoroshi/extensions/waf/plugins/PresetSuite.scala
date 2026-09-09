package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.libs.json.Json

/**
 * The preset exists to make the chain order structural instead of documented, so the order is what
 * these tests are about. Everything else it does is bookkeeping.
 */
class PresetSuite extends munit.FunSuite {

  private val preset = new CloudApimSecuritySuitePreset()

  private def chain(config: CloudApimSecuritySuitePresetConfig): Seq[NgPluginInstance] =
    preset.instances(config)

  private def names(instances: Seq[NgPluginInstance]): Seq[String] =
    instances.map(_.plugin.stripPrefix("cp:").split('.').last)

  private val full = CloudApimSecuritySuitePresetConfig(wafConfig = Some("waf-config_x"))

  test("the full fabric expands into five slots") {
    assertEquals(
      names(chain(full)),
      Seq("CloudApimThreatGate", "CloudApimBotGuard", "CloudApimIpReputation", "CloudApimWaf", "CloudApimThreatResponse")
    )
  }

  test("fail2ban is off by default — it is the one detector your own clients can trip") {
    assertEquals(CloudApimSecuritySuitePresetConfig.default.fail2ban, false)
    assertEquals(names(chain(full)).contains("CloudApimFail2Ban"), false)
  }

  test("switching fail2ban on inserts it after reputation and before the waf") {
    assertEquals(
      names(chain(full.copy(fail2ban = true))),
      Seq(
        "CloudApimThreatGate", "CloudApimBotGuard", "CloudApimIpReputation",
        "CloudApimFail2Ban", "CloudApimWaf", "CloudApimThreatResponse"
      )
    )
  }

  test("the preset arms fail2ban in dry run, and can arm it for real") {
    def dryRunOf(cfg: CloudApimSecuritySuitePresetConfig) = chain(cfg)
      .find(_.plugin == NgPluginHelper.pluginId[CloudApimFail2Ban])
      .map(i => (i.config.raw \ "dry_run").as[Boolean])
    assertEquals(dryRunOf(full.copy(fail2ban = true)), Some(true))
    assertEquals(dryRunOf(full.copy(fail2ban = true, fail2banDryRun = false)), Some(false))
  }

  test("the response runs after the WAF — the property the preset exists for") {
    val transform = chain(full).flatMap(i => i.pluginIndex.flatMap(_.transformRequest).map(i.plugin -> _))
    val waf       = transform.toMap.apply(NgPluginHelper.pluginId[CloudApimWaf])
    val response  = transform.toMap.apply(NgPluginHelper.pluginId[CloudApimThreatResponse])
    assert(waf < response, s"the WAF must contribute before the score is read ($waf vs $response)")
  }

  test("the gate runs before anything that could let a banned caller through") {
    val access = chain(full).flatMap(i => i.pluginIndex.flatMap(_.validateAccess))
    assertEquals(access, access.sorted, "validate_access indices must be emitted in increasing order")
    assertEquals(access.headOption, Some(1.0))
  }

  test("every emitted slot carries an index, or the ordering guarantee is void") {
    chain(full).foreach { i =>
      val indexed = i.pluginIndex.exists(p => p.validateAccess.isDefined || p.transformRequest.isDefined)
      assert(indexed, s"${i.plugin} was emitted without a plugin index")
    }
  }

  test("a section switched off expands into nothing, not into a disabled slot") {
    val chained = chain(full.copy(bots = false, reputation = false))
    assertEquals(names(chained), Seq("CloudApimThreatGate", "CloudApimWaf", "CloudApimThreatResponse"))
    assert(chained.forall(_.enabled), "the slots that remain are all live")
  }

  test("the WAF is skipped when no config entity is selected") {
    assertEquals(names(chain(full.copy(wafConfig = None))).contains("CloudApimWaf"), false)
    assertEquals(names(chain(full.copy(waf = false))).contains("CloudApimWaf"), false)
  }

  test("scoping is propagated to every slot, because Otoroshi drops the preset's own") {
    val scoped = chain(full.copy(include = Seq("/api/.*"), exclude = Seq("/api/health")))
    assert(scoped.nonEmpty)
    scoped.foreach { i =>
      assertEquals(i.include, Seq("/api/.*"))
      assertEquals(i.exclude, Seq("/api/health"))
    }
  }

  test("the threat policy reaches both the gate and the response") {
    val chained = chain(full.copy(threatPolicy = Some("threat-policy_x")))
    val carried = chained
      .filter(i => names(Seq(i)).head.startsWith("CloudApimThreat"))
      .map(i => (i.config.raw \ "policy").as[String])
    assertEquals(carried, Seq("threat-policy_x", "threat-policy_x"))
  }

  test("the reputation mode reaches the reputation slot") {
    val monitored = chain(full.copy(reputationMode = "monitor"))
      .find(i => i.plugin == NgPluginHelper.pluginId[CloudApimIpReputation])
      .get
    assertEquals((monitored.config.raw \ "mode").as[String], "monitor")
  }

  test("an empty config yields the documented defaults") {
    val parsed = CloudApimSecuritySuitePresetConfig.format.reads(Json.obj()).get
    assertEquals(parsed, CloudApimSecuritySuitePresetConfig.default)
    assertEquals(parsed.gate, true)
    assertEquals(parsed.response, true)
    assertEquals(parsed.reputationMode, "block")
  }

  test("the config round-trips") {
    val cfg  = CloudApimSecuritySuitePresetConfig(
      threatPolicy = Some("tp"),
      botPolicy = Some("bp"),
      wafConfig = Some("wc"),
      bots = false,
      fail2ban = true,
      reputationMode = "monitor",
      fail2banDryRun = false,
      include = Seq("/a"),
      exclude = Seq("/b")
    )
    val back = CloudApimSecuritySuitePresetConfig.format.reads(cfg.json).get
    assertEquals(back, cfg)
  }

  test("every field of the flow is described by the schema, or the form renders an empty row") {
    val described = CloudApimSecuritySuitePresetConfig.configSchema.keys
    assertEquals(CloudApimSecuritySuitePresetConfig.configFlow.filterNot(described.contains), Seq.empty[String])
  }
}
