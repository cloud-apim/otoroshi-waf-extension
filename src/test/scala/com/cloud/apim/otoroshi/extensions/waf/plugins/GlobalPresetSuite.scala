package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import otoroshi.env.Env
import otoroshi.next.models.{NgPluginInstance, NgPlugins, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.libs.json.{JsNull, JsString, Json}

/**
 * The route-level preset owns the shape of the chain, and `PresetSuite` owns the assertions on it.
 * What is new here is the table: which routes a rule claims, and what happens when several could.
 */
class GlobalPresetSuite extends munit.FunSuite {

  // Every selector below reads a plain dotted path, which `FastJsonPath` walks straight on the
  // JsValue without ever reaching jayway — the one road through `JsonPathValidator` that never
  // touches the Env. Selection itself needs none: that is why the expression language is injected
  // as a function rather than called.
  private given env: Env = null.asInstanceOf[Env]

  private val plugin = new CloudApimSecuritySuiteGlobalPreset()

  private def route(
      id: String = "route_a",
      tags: Seq[String] = Seq.empty,
      groups: Seq[String] = Seq("default"),
      metadata: Map[String, String] = Map.empty,
      slots: Seq[NgPluginInstance] = Seq.empty
  ): NgRoute =
    NgRoute.fake.copy(id = id, tags = tags, groups = groups, metadata = metadata, plugins = NgPlugins(slots))

  private def expand(
      config: CloudApimSecuritySuiteGlobalPresetConfig,
      r: NgRoute,
      resolve: String => String = _ => "unresolved"
  ): Seq[NgPluginInstance] = plugin.instances(config, r, resolve)

  private def names(instances: Seq[NgPluginInstance]): Seq[String] =
    instances.map(_.plugin.stripPrefix("cp:").split('.').last)

  private def protect(sections: CloudApimSecuritySuitePresetConfig => CloudApimSecuritySuitePresetConfig =
    identity): CloudApimSecuritySuitePresetConfig =
    sections(CloudApimSecuritySuitePresetConfig(wafConfig = Some("waf-config_x")))

  private def rule(
      name: String,
      targets: Seq[CloudApimSecuritySuiteTarget],
      preset: CloudApimSecuritySuitePresetConfig = CloudApimSecuritySuitePresetConfig.default,
      enabled: Boolean = true,
      skip: Boolean = false
  ): CloudApimSecuritySuiteGlobalRule =
    CloudApimSecuritySuiteGlobalRule(id = name, name = name, targets = targets, preset = preset, enabled = enabled, skip = skip)

  private def onPath(path: String, value: String): CloudApimSecuritySuiteTarget =
    CloudApimSecuritySuiteTarget(path = Some(path), value = JsString(value))

  private def onExpression(expr: String, value: String): CloudApimSecuritySuiteTarget =
    CloudApimSecuritySuiteTarget(expression = Some(expr), value = JsString(value))

  test("a route no rule claims expands into nothing at all") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(rule("public", Seq(onPath("$.tags", "Contains(public)")), protect()))
    )
    assertEquals(expand(config, route(tags = Seq("internal"))), Seq.empty[NgPluginInstance])
  }

  test("an empty table protects nobody — the plugin is inert until the table says otherwise") {
    assertEquals(expand(CloudApimSecuritySuiteGlobalPresetConfig.default, route()), Seq.empty[NgPluginInstance])
  }

  test("a rule with no target at all claims every route") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(Seq(rule("everything", Seq.empty, protect())))
    assert(names(expand(config, route())).contains("CloudApimWaf"))
    assert(names(expand(config, route(id = "route_z", tags = Seq("whatever")))).contains("CloudApimWaf"))
  }

  test("the first rule that matches decides, and the ones below it are never considered") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(
        rule("strict", Seq(onPath("$.tags", "Contains(public)")), protect(_.copy(fail2ban = true))),
        rule("catch all", Seq.empty, protect(_.copy(bots = false)))
      )
    )
    val chained = names(expand(config, route(tags = Seq("public"))))
    assert(chained.contains("CloudApimFail2Ban"), "the strict rule won")
    assert(chained.contains("CloudApimBotGuard"), "and the catch-all below it contributed nothing")
  }

  test("a route the narrow rules miss falls through to the catch-all") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(
        rule("strict", Seq(onPath("$.tags", "Contains(public)")), protect(_.copy(fail2ban = true))),
        rule("catch all", Seq.empty, protect(_.copy(reputationMode = "monitor")))
      )
    )
    val chained = expand(config, route(tags = Seq("internal")))
    assertEquals(names(chained).contains("CloudApimFail2Ban"), false)
    val mode = chained
      .find(_.plugin.endsWith("CloudApimIpReputation"))
      .map(i => (i.config.raw \ "mode").as[String])
    assertEquals(mode, Some("monitor"))
  }

  test("skip is the opt-out: it matches, lays down nothing and stops the search") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(
        rule("left alone", Seq(onPath("$.tags", "Contains(no-threat-protection)")), skip = true),
        rule("everything else", Seq.empty, protect())
      )
    )
    assertEquals(expand(config, route(tags = Seq("no-threat-protection"))), Seq.empty[NgPluginInstance])
    assert(names(expand(config, route(tags = Seq("api")))).nonEmpty, "the rule below still applies to the rest")
  }

  test("an empty preset block is the default protection, not an opt-out — that is what skip is for") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig.format
      .reads(Json.obj("rules" -> Json.arr(Json.obj("name" -> "oops", "preset" -> Json.obj()))))
      .get
    assert(expand(config, route()).nonEmpty, "an omitted preset arms the default sections")
  }

  test("a disabled rule is skipped entirely and the search carries on below it") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(
        rule("off", Seq.empty, protect(_.copy(fail2ban = true)), enabled = false),
        rule("on", Seq.empty, protect())
      )
    )
    assertEquals(names(expand(config, route())).contains("CloudApimFail2Ban"), false)
    assert(names(expand(config, route())).contains("CloudApimWaf"))
  }

  test("every target of a rule must match") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(
        rule(
          "prod public",
          Seq(onPath("$.tags", "Contains(public)"), onPath("$.metadata.env", "prod")),
          protect()
        )
      )
    )
    assert(expand(config, route(tags = Seq("public"), metadata = Map("env" -> "prod"))).nonEmpty)
    assertEquals(expand(config, route(tags = Seq("public"), metadata = Map("env" -> "dev"))), Seq.empty)
    assertEquals(expand(config, route(tags = Seq("internal"), metadata = Map("env" -> "prod"))), Seq.empty)
  }

  test("a JSONPath selector reads arrays as arrays — tags and groups") {
    val onTag   = CloudApimSecuritySuiteGlobalPresetConfig(Seq(rule("t", Seq(onPath("$.tags", "Contains(api)")), protect())))
    val onGroup = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(rule("g", Seq(onPath("$.groups", "Contains(grp_public)")), protect()))
    )
    assert(expand(onTag, route(tags = Seq("api", "v2"))).nonEmpty)
    assertEquals(expand(onTag, route(tags = Seq("v2"))), Seq.empty)
    assert(expand(onGroup, route(groups = Seq("grp_public"))).nonEmpty)
    assertEquals(expand(onGroup, route(groups = Seq("default"))), Seq.empty)
  }

  test("ContainedIn selects a named set of routes") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(rule("three of them", Seq(onPath("$.id", "ContainedIn(route_a, route_b, route_c)")), protect()))
    )
    assert(expand(config, route(id = "route_b")).nonEmpty)
    assertEquals(expand(config, route(id = "route_d")), Seq.empty)
  }

  test("an expression selector compares whatever the expression language resolved to") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(rule("prod", Seq(onExpression("${route.metadata.env}", "Regex(prod|staging)")), protect()))
    )
    assert(expand(config, route(), _ => "staging").nonEmpty)
    assertEquals(expand(config, route(), _ => "dev"), Seq.empty)
  }

  test("a target that selects on nothing selects nothing, rather than everything") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(rule("malformed", Seq(CloudApimSecuritySuiteTarget(value = JsString("whatever"))), protect()))
    )
    assertEquals(expand(config, route()), Seq.empty[NgPluginInstance])
  }

  test("a target with no value never matches either") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(rule("no value", Seq(CloudApimSecuritySuiteTarget(path = Some("$.id"), value = JsNull)), protect()))
    )
    assertEquals(expand(config, route()), Seq.empty[NgPluginInstance])
  }

  test("the chain handed back is the route-level preset's, verbatim") {
    val protection = protect(_.copy(fail2ban = true, reputationMode = "monitor", include = Seq("/api/.*")))
    val config     = CloudApimSecuritySuiteGlobalPresetConfig(Seq(rule("all", Seq.empty, protection)))
    assertEquals(expand(config, route()), new CloudApimSecuritySuitePreset().instances(protection))
  }

  test("a table that reads the route alone is cacheable, one that reads the request is not") {
    def cacheable(t: CloudApimSecuritySuiteTarget): Boolean =
      CloudApimSecuritySuiteGlobalPresetConfig(Seq(rule("r", Seq(t)))).routeOnly

    assert(cacheable(onPath("$.tags", "Contains(x)")), "a JSONPath reads the route by construction")
    assert(cacheable(onExpression("${route.id}", "x")))
    assert(cacheable(onExpression("${route.metadata.env:none}", "x")))
    assert(cacheable(onExpression("${route.metadata.a || route.metadata.b}", "x")))
    assert(!cacheable(onExpression("${req.host}", "x")), "the request can differ between two calls")
    assert(!cacheable(onExpression("${route.id}-${req.path}", "x")), "one request-bound token is enough")
    assert(!cacheable(onExpression("${apikey.metadata.tier}", "x")), "not resolved at expansion time anyway")
  }

  test("a route that already carries the fabric is left to the chain it was given") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(Seq(rule("all", Seq.empty, protect())))
    def carrying(plugin: String, enabled: Boolean = true): NgRoute =
      route(slots = Seq(NgPluginInstance(plugin = plugin, enabled = enabled)))

    assertEquals(expand(config, carrying(NgPluginHelper.pluginId[CloudApimSecuritySuitePreset])), Seq.empty)
    assertEquals(expand(config, carrying(NgPluginHelper.pluginId[CloudApimWaf])), Seq.empty)
    assertEquals(expand(config, carrying(NgPluginHelper.pluginId[CloudApimThreatResponse])), Seq.empty)
    assert(
      expand(config, carrying(NgPluginHelper.pluginId[CloudApimWaf], enabled = false)).nonEmpty,
      "a slot that is present but switched off protects nothing, so it does not hold the table back"
    )
    assert(expand(config, carrying("cp:otoroshi.next.plugins.ApikeyCalls")).nonEmpty, "unrelated plugins do not count")
  }

  test("the stand-down can be switched off, and the table then wins") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(rule("all", Seq.empty, protect())),
      skipProtectedRoutes = false
    )
    val carrying = route(slots = Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[CloudApimWaf])))
    assert(expand(config, carrying).nonEmpty)
  }

  test("the sub-routes of one composition are told apart, although they share an id") {
    // NgService.toRoutes hands out one NgRoute per frontend, all carrying the service id and so all
    // sharing cacheableId, while their own plugins and their frontend differ — the two things the
    // table looks at
    val composed = Map(
      "otoroshi-core-original-route-id"  -> "service_x",
      "otoroshi-core-cacheable-route-id" -> "service_x-0"
    )
    val first  = route(id = "service_x", metadata = composed)
    val second = route(id = "service_x", metadata = composed ++ Map("otoroshi-core-cacheable-route-id" -> "service_x-1"))

    assertEquals(first.cacheableId, second.cacheableId, "otoroshi collapses them, which is the trap")
    assertNotEquals(
      CloudApimSecuritySuiteGlobalPreset.cacheKeyOf(first),
      CloudApimSecuritySuiteGlobalPreset.cacheKeyOf(second)
    )
    assertEquals(CloudApimSecuritySuiteGlobalPreset.cacheKeyOf(route(id = "route_plain")), "route_plain")
  }

  test("the config round-trips") {
    val config = CloudApimSecuritySuiteGlobalPresetConfig(
      Seq(
        rule("left alone", Seq(onPath("$.tags", "Contains(no-waf)")), skip = true),
        rule(
          "public",
          Seq(onPath("$.groups", "Contains(grp_public)"), onExpression("${route.id}", "Not(route_x)")),
          protect(_.copy(fail2ban = true)),
          enabled = false
        ),
        rule("rest", Seq.empty, protect())
      ),
      skipProtectedRoutes = false
    )
    assertEquals(CloudApimSecuritySuiteGlobalPresetConfig.format.reads(config.json).get, config)
  }

  test("a rule written without an id is given one, derived from its position") {
    // a hand written table carries no id. one is enough for everything but attributing entities to a
    // rule, and that only happens from the studio, which always writes them
    val parsed = CloudApimSecuritySuiteGlobalPresetConfig.format
      .reads(Json.obj("rules" -> Json.arr(Json.obj("name" -> "first"), Json.obj("name" -> "second"))))
      .get
    assertEquals(parsed.rules.map(_.id), Seq("rule_0", "rule_1"))
    assertEquals(
      CloudApimSecuritySuiteGlobalPresetConfig.format.reads(parsed.json).get,
      parsed,
      "and once materialised it round-trips unchanged"
    )
  }

  test("an empty config yields an empty table rather than an error") {
    assertEquals(
      CloudApimSecuritySuiteGlobalPresetConfig.format.reads(Json.obj()).get,
      CloudApimSecuritySuiteGlobalPresetConfig.default
    )
  }

  test("every field of the flow is described by the schema, or the form renders an empty row") {
    val described = CloudApimSecuritySuiteGlobalPresetConfig.configSchema.keys
    assertEquals(CloudApimSecuritySuiteGlobalPresetConfig.configFlow.filterNot(described.contains), Seq.empty[String])
  }
}
