package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.tuning.*
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimWaf
import play.api.libs.json.Json

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * OPS-2 through a real gateway, on the whole loop.
 *
 * The unit tests prove each piece; this proves the one thing they cannot, which is that the loop
 * closes: a request is refused, the assistant is pointed at that refusal, what it writes reaches
 * the datastore, the running engine picks it up, and the same request now succeeds — while the
 * attack the rule exists for is still refused.
 */
class TuningIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private def ext = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get

  private def wafPlugin(ref: String) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimWaf],
    config = NgPluginInstanceConfig(Json.obj("ref" -> ref))
  )

  private def config(): String = Gateway.createWafConfig(
    Json.obj(
      "id"                   -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"                 -> "tuning-it",
      "description"          -> "",
      "enabled"              -> true,
      "block"                -> true,
      "inspect_input_body"   -> false,
      "inspect_output_body"  -> false,
      "oversize_body_action" -> "inspect_prefix",
      "rulesets"             -> Json.arr(),
      // a rule that objects to a perfectly ordinary value, so the "false positive" is unambiguous
      "rules"                -> Json.arr(
        "SecRuleEngine On",
        // the CRS logdata convention, which is what carries the input name *and* the value the
        // assistant replays — a rule that omits it can still be tuned, but not verified
        """SecRule ARGS "@rx (?i)(select|union)" "id:7100,phase:1,deny,status:403,log,msg:'sqli-ish',logdata:'Matched Data: %{TX.0} found within %{MATCHED_VAR_NAME}: %{MATCHED_VAR}'""""
      )
    )
  )

  test("a false positive becomes a verified exclusion, and the attack stays blocked") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = config()
    val route   = Gateway.createRoute("ops2", backend.port, Seq(wafPlugin(ref)))
    try {
      ext.tuning.store.clear()

      // 1. the legitimate request an editorial tool would send, refused by the rule
      assertEquals(Gateway.call(route, "/api/posts?comment=select%20a%20plan").status, 403)
      // and a real attack in another parameter, also refused
      assertEquals(Gateway.call(route, "/api/posts?id=1%20union%20select%20null").status, 403)

      // 2. the assistant saw both, and knows which input each landed in
      val groups = ext.tuning.store.groups
      assert(groups.nonEmpty, "the match was not recorded as a tuning candidate")
      val sample = groups.map(_.sample).find(_.target.exists(_.full == "ARGS:comment")).getOrElse {
        fail(s"no sample on ARGS:comment, got ${groups.map(_.sample.target)}")
      }
      assertEquals(sample.ruleId, 7100)
      assertEquals(sample.path, "/api/posts")

      // 3. the narrowest proposal, run before it is offered
      val cfg       = ext.states.config(ref).get
      val proposals = ExclusionBuilder.proposals(
        sample.ruleId,
        sample.target,
        Some(sample.path),
        ExclusionBuilder.nextId(ext.states.rulesetsFor(cfg).flatMap(_.rules))
      )
      val chosen    = proposals.head
      assertEquals(chosen.kind, "scoped_target")

      val preview = ExclusionPreview.run(
        rules = ext.states.rulesFor(cfg),
        exclusion = chosen.seclang,
        ruleId = sample.ruleId,
        target = sample.target,
        sampleValue = sample.matchedValue,
        method = sample.method,
        path = sample.path,
        placement = chosen.placement,
        engineOf = rs => ext.factory.engine(rs.toList)
      )
      assert(preview.effective, "the proposal does not actually stop the rule firing")

      // 4. written the way the assistant writes it
      val plan = ExclusionWriter.plan(
        config = cfg,
        placement = chosen.placement,
        all = ext.states.allRulesets(),
        entry = ExclusionWriter.entry(chosen.seclang, sample.ruleId, sample.target, "editorial copy mentions plans", "it", None),
        newRulesetId = s"waf-ruleset_${java.util.UUID.randomUUID().toString.take(8)}"
      )
      // through the assistant's own write path, not the admin api, so the persistence and the
      // state refresh it performs are what the test exercises
      Await.result(ext.tuning.states.saveRuleset(plan.ruleset), 10.seconds)
      plan.config.foreach(c => Await.result(ext.tuning.states.saveConfig(c), 10.seconds))
      // the running engine rebuilds from state on the next sync tick
      Thread.sleep(2000L)

      // 5. the loop closes
      assertEquals(
        Gateway.call(route, "/api/posts?comment=select%20a%20plan").status,
        200,
        "the false positive is still blocked after the exclusion was applied"
      )
      assertEquals(
        Gateway.call(route, "/api/posts?id=1%20union%20select%20null").status,
        403,
        "the exclusion gave up more than the one parameter it named"
      )
      assertEquals(
        Gateway.call(route, "/other?comment=select%20a%20plan").status,
        403,
        "the exclusion was scoped to a path and leaked outside it"
      )
    } finally {
      Gateway.deleteRoute(route)
      Gateway.deleteWafConfig(ref)
      backend.stop()
    }
  }
}
