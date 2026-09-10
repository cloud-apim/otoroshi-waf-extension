package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.learning.*
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimWaf
import play.api.libs.json.Json

import com.cloud.apim.otoroshi.extensions.waf.it.Gateway.ec

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * OPS-3 through a real gateway.
 *
 * What the unit tests cannot show is that the window measures the traffic that actually happened:
 * that the denominator counts every request and not only the interesting ones, that a monitoring
 * rollout's would-be denials are counted without anything being denied, and that what the report
 * proposes still works once written.
 */
class LearningIT extends munit.FunSuite {

  override val munitTimeout = Duration(5, "min")

  private def ext = Gateway.instance.env.adminExtensions.extension[CloudApimWafExtension].get

  private def wafPlugin(ref: String) = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[CloudApimWaf],
    config = NgPluginInstanceConfig(Json.obj("ref" -> ref))
  )

  /** Monitoring mode: the engine reaches a deny, and Otoroshi is told not to act on it. */
  private def config(): String = Gateway.createWafConfig(
    Json.obj(
      "id"                   -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
      "name"                 -> "learning-it",
      "description"          -> "",
      "enabled"              -> true,
      "block"                -> false,
      "inspect_input_body"   -> false,
      "inspect_output_body"  -> false,
      "oversize_body_action" -> "inspect_prefix",
      "rulesets"             -> Json.arr(),
      "rules"                -> Json.arr(
        // a rule nobody's attack corpus will trip, so the proposal comes back clean
        """SecRule ARGS "@rx (?i)plan-[0-9]+" "id:7200,phase:1,deny,status:403,log,msg:'plan code',logdata:'Matched Data: %{MATCHED_VAR} found within %{MATCHED_VAR_NAME}: %{MATCHED_VAR}'"""",
        // the setup a monitoring rollout actually runs, and the only one where the cost of arming
        // can be measured: the engine reaches its verdict, Otoroshi is told not to act on it
        "SecRuleEngine On"
      )
    )
  )

  test("a window measures the traffic, then proposes what makes arming safe") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = config()
    val route   = Gateway.createRoute("ops3", backend.port, Seq(wafPlugin(ref)))
    try {
      Await.result(ext.learning.aggregator.start(ref), 10.seconds)

      // traffic: mostly innocuous, a handful tripping the rule on one parameter of one endpoint
      (1 to 10).foreach(i => assertEquals(Gateway.call(route, s"/api/posts?comment=hello$i").status, 200))
      (1 to 5).foreach(i =>
        assertEquals(
          Gateway.call(route, s"/api/posts?comment=upgrade%20to%20plan-$i").status,
          200,
          "monitoring mode must not deny anything"
        )
      )

      val report = Await.result(ext.learning.report(ref), 30.seconds).toOption.get

      assertEquals(report.run.requests, 15L, "every request counts, not only the ones that matched")
      assertEquals(
        report.run.matched,
        5L,
        "only requests where a rule objected to an input count as matched — not every request the engine emitted an event for"
      )
      assertEquals(report.run.wouldBlock, 5L, "the requests that break the day this is armed")
      assert(report.mode.armingEstimateReliable, s"the arming estimate should be measurable here: ${report.mode.note}")

      val entry = report.entries.find(_.ruleId == 7200).getOrElse(fail(s"rule 7200 not counted: ${report.entries}"))
      assertEquals(entry.count, 5L)
      assertEquals(entry.target.map(_.full), Some("ARGS:comment"))
      assertEquals(entry.path, "/api/posts")

      val proposal = report.exclusions.find(_.entry.ruleId == 7200).getOrElse(fail("nothing proposed for the noisy rule"))
      assert(proposal.preview.reproduced, "the report must rebuild the match before proposing anything")
      assert(proposal.accepted, s"a clean exclusion should be accepted: ${proposal.note}")
      assertEquals(proposal.entry.wouldBlock, 5L)

      assertEquals(report.impact.sampled, 5)
      assertEquals(report.impact.resolved, 5, "every sampled denial is accounted for by this one exclusion")
      assertEquals(report.impact.residual, 0)

      // applying it goes through the tuning assistant's own write path
      val applied = Await.result(
        ext.learning.states.config(ref) match {
          case Some(cfg) =>
            val plan = com.cloud.apim.otoroshi.extensions.waf.tuning.ExclusionWriter.plan(
              config = cfg,
              placement = proposal.proposal.placement,
              all = ext.states.allRulesets(),
              entry = com.cloud.apim.otoroshi.extensions.waf.tuning.ExclusionWriter
                .entry(proposal.proposal.seclang, 7200, proposal.entry.target, "learning run", "it", None),
              newRulesetId = s"waf-ruleset_${java.util.UUID.randomUUID().toString.take(8)}"
            )
            for {
              _ <- ext.learning.states.saveRuleset(plan.ruleset)
              _ <- plan.config.map(ext.learning.states.saveConfig).getOrElse(scala.concurrent.Future.successful(true))
            } yield plan
          case None      => fail("config vanished")
        },
        30.seconds
      )
      assert(applied.rulesetCreated)
      Thread.sleep(2000L)

      // the rule no longer objects to that parameter, and still objects everywhere else
      Await.result(ext.learning.aggregator.start(ref), 10.seconds)
      assertEquals(Gateway.call(route, "/api/posts?comment=upgrade%20to%20plan-9").status, 200)
      assertEquals(Gateway.call(route, "/api/posts?other=upgrade%20to%20plan-9").status, 200)
      val after = Await.result(ext.learning.report(ref), 30.seconds).toOption.get
      assertEquals(after.run.wouldBlock, 1L, "only the parameter named in the exclusion should have stopped matching")
    } finally {
      Await.result(ext.learning.aggregator.discard(ref), 10.seconds)
      Gateway.deleteRoute(route)
      Gateway.deleteWafConfig(ref)
      backend.stop()
    }
  }

  /**
   * A window outlives the node that started it.
   *
   * "Is this running" used to live in one node's memory, so a node restarting inside a week-long
   * window silently stopped contributing while the report kept working — with a denominator quietly
   * missing that node's traffic, which is worse than an error. A second aggregator over the same
   * shared store is what a restarted or newly-joined node looks like.
   */
  test("a node that was not there at the start still joins the open window") {
    val backend = new TestBackend()(using Gateway.system, Gateway.mat, Gateway.ec)
    val ref     = config()
    val route   = Gateway.createRoute("ops3-rejoin", backend.port, Seq(wafPlugin(ref)))
    try {
      Await.result(ext.learning.aggregator.start(ref), 10.seconds)
      Gateway.call(route, "/api/posts?comment=upgrade%20to%20plan-1")
      Await.result(ext.learning.aggregator.flush(ref), 10.seconds)

      val newcomer = new LearningAggregator(
        s"${Gateway.instance.env.storageRoot}:extensions:${ext.id.cleanup}",
        ext.security.sharedState,
        "another-node",
        play.api.Logger("learning-it")
      )(using Gateway.ec)

      assert(!newcomer.isRunning(ref), "precondition: it knows nothing yet")
      Await.result(newcomer.refreshRunning(), 10.seconds)
      assert(newcomer.isRunning(ref), "it must pick the open window up from the shared store")

      newcomer.observeRequest(ref)
      newcomer.observeRequest(ref)
      Await.result(newcomer.flush(ref), 10.seconds)

      val report = Await.result(ext.learning.report(ref), 30.seconds).toOption.get
      assertEquals(report.run.requests, 3L, "both nodes' traffic must be added, not replaced")
      assertEquals(report.nodes, 2)

      Await.result(ext.learning.aggregator.stop(ref), 10.seconds)
      Await.result(newcomer.refreshRunning(), 10.seconds)
      assert(!newcomer.isRunning(ref), "stopping the window must reach every node too")
    } finally {
      Await.result(ext.learning.aggregator.discard(ref), 10.seconds)
      Gateway.deleteRoute(route)
      Gateway.deleteWafConfig(ref)
      backend.stop()
    }
  }

  test("detection-only withholds the arming estimate instead of reporting zero") {
    val ref = Gateway.createWafConfig(
      Json.obj(
        "id"      -> s"waf-config_${java.util.UUID.randomUUID().toString.take(8)}",
        "name"    -> "learning-it-truncated",
        "enabled" -> true,
        "block"   -> false,
        "description" -> "",
        "rules"   -> Json.arr("""SecRule ARGS "@rx x" "id:7201,phase:1,deny"""", "SecRuleEngine DetectionOnly")
      )
    )
    try {
      // the extension refreshes its state on a tick; the report reads that state
      val deadline = System.currentTimeMillis() + 20000L
      while (ext.states.config(ref).isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(250L)
      Await.result(ext.learning.aggregator.start(ref), 10.seconds)
      val report = Await.result(ext.learning.report(ref), 30.seconds).toOption
        .getOrElse(fail(s"no report for $ref: the config never reached the extension state"))
      assert(!report.mode.armingEstimateReliable, "detection-only loses the verdict, so it cannot be counted")
      assert(report.mode.ruleCountsComplete, "but every rule does run, so the inventory is complete")
      // with no traffic the verdict rightly leads with that; the mode caveat is covered on its own
      assert(report.verdict.contains("Nothing was observed"), report.verdict)
    } finally {
      Await.result(ext.learning.aggregator.discard(ref), 10.seconds)
      Gateway.deleteWafConfig(ref)
    }
  }
}
