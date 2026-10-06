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
    chain(full.copy(fail2ban = true, errorLeakage = true, sensitiveData = true, uploads = true, login = true, traffic = true, objects = true, apiContract = true)).foreach { i =>
      val indexed = i.pluginIndex.exists(p => p.validateAccess.isDefined || p.transformRequest.isDefined || p.transformResponse.isDefined)
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
      errorLeakage = true,
      errorLeakageMode = "monitor",
      sensitiveData = true,
      sensitiveDataMode = "monitor",
      sensitiveDataDetectors = Map("card" -> "block", "jwt" -> "off"),
      uploads = true,
      uploadsMode = "monitor",
      uploadsAllowedExtensions = Seq("png", "pdf"),
      uploadsScanner = Some("malware-scanner_1"),
      uploadsScanFailureAction = "allow",
      login = true,
      loginPaths = Seq("/login", "/api/auth/*"),
      traffic = true,
      trafficSensitivity = "high",
      objects = true,
      objectsMode = "score",
      objectsPaths = Seq("/api/orders/{id}"),
      objectsBudget = 500L,
      apiContract = true,
      apiContractId = Some("api-contract_1"),
      apiContractMode = "enforce",
      include = Seq("/a"),
      exclude = Seq("/b")
    )
    val back = CloudApimSecuritySuitePresetConfig.format.reads(cfg.json).get
    assertEquals(back, cfg)
  }

  test("the error leakage guard is off by default, and comes last, on the response, once switched on") {
    assertEquals(CloudApimSecuritySuitePresetConfig.default.errorLeakage, false)
    assertEquals(names(chain(full)).contains("CloudApimErrorLeakageGuard"), false)
    val armed = chain(full.copy(errorLeakage = true))
    assertEquals(names(armed).last, "CloudApimErrorLeakageGuard")
    assert(armed.last.pluginIndex.exists(_.transformResponse.isDefined))
  }

  test("the leakage mode reaches the guard") {
    def modeOf(cfg: CloudApimSecuritySuitePresetConfig) = chain(cfg)
      .find(_.plugin == NgPluginHelper.pluginId[CloudApimErrorLeakageGuard])
      .map(i => (i.config.raw \ "mode").as[String])
    assertEquals(modeOf(full.copy(errorLeakage = true)), Some("mask"))
    assertEquals(modeOf(full.copy(errorLeakage = true, errorLeakageMode = "monitor")), Some("monitor"))
  }

  test("the sensitive data guard is off by default, and runs after the error leakage guard once switched on") {
    assertEquals(CloudApimSecuritySuitePresetConfig.default.sensitiveData, false)
    assertEquals(names(chain(full)).contains("CloudApimSensitiveDataGuard"), false)
    val armed = chain(full.copy(errorLeakage = true, sensitiveData = true))
    assertEquals(names(armed).takeRight(2), Seq("CloudApimErrorLeakageGuard", "CloudApimSensitiveDataGuard"))
    val response = armed.flatMap(i => i.pluginIndex.flatMap(_.transformResponse).map(i.plugin -> _)).toMap
    assert(
      response(NgPluginHelper.pluginId[CloudApimErrorLeakageGuard]) < response(NgPluginHelper.pluginId[CloudApimSensitiveDataGuard]),
      "a response the leakage guard replaced has nothing left to mask"
    )
  }

  test("the sensitive data mode and detectors reach the guard, unknown ones dropped") {
    val cfg = CloudApimSecuritySuitePresetConfig.format
      .reads(Json.obj(
        "sensitive_data"           -> true,
        "sensitive_data_mode"      -> "MONITOR",
        "sensitive_data_detectors" -> Json.obj("card" -> "block", "nope" -> "mask", "iban" -> "explode")
      ))
      .get
    val guard = chain(cfg).find(_.plugin == NgPluginHelper.pluginId[CloudApimSensitiveDataGuard]).get
    assertEquals((guard.config.raw \ "mode").as[String], "monitor")
    assertEquals((guard.config.raw \ "detectors").as[Map[String, String]], Map("card" -> "block"))
  }

  test("the upload guard is off by default, and runs between the WAF and the response once switched on") {
    assertEquals(CloudApimSecuritySuitePresetConfig.default.uploads, false)
    assertEquals(names(chain(full)).contains("CloudApimUploadGuard"), false)
    val armed     = chain(full.copy(uploads = true, uploadsMode = "monitor", uploadsAllowedExtensions = Seq("png"), uploadsScanner = Some("ms_1")))
    val transform = armed.flatMap(i => i.pluginIndex.flatMap(_.transformRequest).map(i.plugin -> _)).toMap
    val guard     = transform(NgPluginHelper.pluginId[CloudApimUploadGuard])
    assert(transform(NgPluginHelper.pluginId[CloudApimWaf]) < guard && guard < transform(NgPluginHelper.pluginId[CloudApimThreatResponse]))
    val config = armed.find(_.plugin == NgPluginHelper.pluginId[CloudApimUploadGuard]).get.config.raw
    assertEquals((config \ "mode").as[String], "monitor")
    assertEquals((config \ "allowed_extensions").as[Seq[String]], Seq("png"))
    assertEquals((config \ "scanner").as[String], "ms_1")
    assertEquals((config \ "scan_failure_action").as[String], "reject")
  }

  test("the login guard is off by default, and contributes before the response reads the score") {
    assertEquals(CloudApimSecuritySuitePresetConfig.default.login, false)
    assertEquals(names(chain(full)).contains("CloudApimLoginGuard"), false)
    val armed     = chain(full.copy(login = true, loginPaths = Seq("/login")))
    val transform = armed.flatMap(i => i.pluginIndex.flatMap(_.transformRequest).map(i.plugin -> _)).toMap
    assert(transform(NgPluginHelper.pluginId[CloudApimLoginGuard]) < transform(NgPluginHelper.pluginId[CloudApimThreatResponse]))
    val guard = armed.find(_.plugin == NgPluginHelper.pluginId[CloudApimLoginGuard]).get
    assert(guard.pluginIndex.exists(_.transformResponse.isDefined), "the guard counts failures on the way back")
    assertEquals((guard.config.raw \ "login_paths").as[Seq[String]], Seq("/login"))
  }

  test("the traffic guard is off by default, validates access, and its sensitivity sets the surge factor") {
    assertEquals(CloudApimSecuritySuitePresetConfig.default.traffic, false)
    assertEquals(names(chain(full)).contains("CloudApimTrafficGuard"), false)
    val guard = chain(full.copy(traffic = true, trafficSensitivity = "high")).find(_.plugin == NgPluginHelper.pluginId[CloudApimTrafficGuard]).get
    assert(guard.pluginIndex.exists(_.validateAccess.isDefined))
    assertEquals((guard.config.raw \ "surge_factor").as[Double], 2.0)
  }

  test("the object guard is off by default, sits between the login guard and the response, and reads the status") {
    assertEquals(CloudApimSecuritySuitePresetConfig.default.objects, false)
    assertEquals(names(chain(full)).contains("CloudApimObjectGuard"), false)
    val armed     = chain(full.copy(login = true, objects = true))
    val transform = armed.flatMap(i => i.pluginIndex.flatMap(_.transformRequest).map(i.plugin -> _)).toMap
    assert(transform(NgPluginHelper.pluginId[CloudApimLoginGuard]) < transform(NgPluginHelper.pluginId[CloudApimObjectGuard]))
    assert(transform(NgPluginHelper.pluginId[CloudApimObjectGuard]) < transform(NgPluginHelper.pluginId[CloudApimThreatResponse]))
    val guard     = armed.find(_.plugin == NgPluginHelper.pluginId[CloudApimObjectGuard]).get
    assert(guard.pluginIndex.exists(_.transformResponse.isDefined), "the guard reads what the backend answered")
    val alert     = CloudApimObjectGuardConfig.format.reads(guard.config.raw).get
    assertEquals((alert.contribute, alert.autoDetect, alert.budget), (false, true, 0L), "alert only, identifiers detected, no budget")
    val declared  = chain(full.copy(objects = true, objectsMode = "score", objectsPaths = Seq("/api/orders/{id}"), objectsBudget = 500L))
      .find(_.plugin == NgPluginHelper.pluginId[CloudApimObjectGuard])
      .map(i => CloudApimObjectGuardConfig.format.reads(i.config.raw).get)
      .get
    assertEquals((declared.contribute, declared.autoDetect, declared.budget, declared.paths.map(_.path)), (true, false, 500L, Seq("/api/orders/{id}")))
  }

  test("the API contract is off by default, runs before the WAF, and reads the route's metadata when no contract is named") {
    assertEquals(CloudApimSecuritySuitePresetConfig.default.apiContract, false)
    assertEquals(names(chain(full)).contains("CloudApimApiContract"), false)
    val armed     = chain(full.copy(apiContract = true))
    val transform = armed.flatMap(i => i.pluginIndex.flatMap(_.transformRequest).map(i.plugin -> _)).toMap
    assert(transform(NgPluginHelper.pluginId[CloudApimApiContract]) < transform(NgPluginHelper.pluginId[CloudApimWaf]))
    val guard     = armed.find(_.plugin == NgPluginHelper.pluginId[CloudApimApiContract]).get
    assert(guard.pluginIndex.exists(_.transformResponse.isDefined))
    val cfg       = CloudApimApiContractConfig.format.reads(guard.config.raw).get
    assertEquals((cfg.contract, cfg.mode), (None, "monitor"))
    val named     = chain(full.copy(apiContract = true, apiContractId = Some("api-contract_1"), apiContractMode = "enforce"))
      .find(_.plugin == NgPluginHelper.pluginId[CloudApimApiContract])
      .map(i => CloudApimApiContractConfig.format.reads(i.config.raw).get)
      .get
    assertEquals((named.contract, named.mode), (Some("api-contract_1"), "enforce"))
    assertEquals(CloudApimApiContractConfig.configFlow.filterNot(CloudApimApiContractConfig.configSchema.keys.contains), Seq.empty[String])
  }

  test("every field of the flow is described by the schema, or the form renders an empty row") {
    val described = CloudApimSecuritySuitePresetConfig.configSchema.keys
    assertEquals(CloudApimSecuritySuitePresetConfig.configFlow.filterNot(described.contains), Seq.empty[String])
  }
}
