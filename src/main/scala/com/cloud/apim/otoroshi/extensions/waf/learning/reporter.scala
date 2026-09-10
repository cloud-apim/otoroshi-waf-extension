package com.cloud.apim.otoroshi.extensions.waf.learning

import com.cloud.apim.otoroshi.extensions.waf.entities.CrsSettings
import com.cloud.apim.otoroshi.extensions.waf.tuning.*
import com.cloud.apim.seclang.impl.engine.SecLangEngine
import com.cloud.apim.seclang.model.EngineMode
import play.api.libs.json.*

/**
 * A window of observation, turned into a configuration someone can decide about.
 *
 * The output is a proposal and never an action. Everything in it has been run — the exclusions
 * through the same preview the tuning assistant uses, the impact against the sampled denials — so
 * the reviewer is choosing between measured options rather than reading advice.
 */
final case class LearningReport(
    run: LearningRun,
    nodes: Int,
    mode: LearningReporter.ModeAdvice,
    entries: Seq[LearningEntry],
    exclusions: Seq[ProposedExclusion],
    paranoia: ParanoiaAdvice,
    threshold: ThresholdAdvice,
    impact: ArmingImpact,
    verdict: String
) {
  def json: JsValue = Json.obj(
    "run"             -> run.json,
    "nodes"           -> nodes,
    "mode"            -> mode.json,
    "entries"         -> JsArray(entries.map(_.json)),
    "exclusions"      -> JsArray(exclusions.map(_.json)),
    "paranoia"        -> paranoia.json,
    "threshold"       -> threshold.json,
    "impact"          -> impact.json,
    "verdict"         -> verdict
  )
}

object LearningReporter {

  /** Below this, a group is noise about noise — one match in a week decides nothing. */
  val defaultMinCount: Long = 3L

  /** Enough to cover a real tuning backlog, few enough that the page stays readable. */
  val defaultMaxProposals: Int = 25

  /**
   * What the engine mode does and does not let a window measure.
   *
   * Neither mode is simply better, and pretending otherwise would produce a confident number built
   * on the wrong one.
   *
   * With `SecRuleEngine On` — which is what a monitoring rollout normally runs, with Otoroshi told
   * not to act on the verdict — evaluation stops at the first rule that reaches a deny. The arming
   * estimate is then exact, because it is literally what production will do; the rule inventory is
   * "the first objection to each request", so tuning proceeds one layer at a time.
   *
   * With `SecRuleEngine DetectionOnly` every rule runs, so the inventory is complete in one pass.
   * But the engine keeps only the last phase's verdict, so an earlier deny is lost and the arming
   * estimate cannot be trusted — it would read as zero, which is the most dangerous number this
   * report could print. So it is withheld rather than shown.
   */
  final case class ModeAdvice(
      mode: Option[EngineMode],
      armingEstimateReliable: Boolean,
      ruleCountsComplete: Boolean,
      note: String
  ) {
    def json: JsValue = Json.obj(
      "mode"                     -> mode.map(_.toString),
      "arming_estimate_reliable" -> armingEstimateReliable,
      "rule_counts_complete"     -> ruleCountsComplete,
      "note"                     -> note
    )
  }

  def modeAdvice(mode: Option[EngineMode], configBlocking: Boolean): ModeAdvice = mode match {
    case Some(m) if m.isDetectionOnly =>
      ModeAdvice(
        mode,
        armingEstimateReliable = false,
        ruleCountsComplete = true,
        "The ruleset runs in `SecRuleEngine DetectionOnly`, so every rule is evaluated and the inventory below is " +
          "complete. The engine keeps only the last phase's verdict in this mode, so what would have been denied " +
          "cannot be counted — that figure is withheld rather than reported as zero. Switch the ruleset to " +
          "`SecRuleEngine On` and leave the configuration in monitoring to measure the cost of arming."
      )
    case Some(m) if m.isOff           =>
      ModeAdvice(mode, armingEstimateReliable = false, ruleCountsComplete = false,
        "The ruleset is `SecRuleEngine Off`. Nothing is being evaluated, so there is nothing to learn from.")
    case _                            =>
      ModeAdvice(
        mode,
        armingEstimateReliable = true,
        ruleCountsComplete = false,
        "The ruleset runs in `SecRuleEngine On`" +
          (if (configBlocking) ", and the configuration is armed."
           else ", with the configuration in monitoring — the setup this measurement is most accurate on.") +
          " Evaluation stops at the first rule that reaches a deny, so the counts below are the *first* objection to " +
          "each request rather than all of them. Expect to tune in rounds: excluding what is at the top brings the " +
          "next layer into view."
      )
  }

  def build(
      snapshot: LearningSnapshot,
      rules: Seq[String],
      mode: Option[EngineMode],
      configBlocking: Boolean,
      firstRuleId: Int,
      engineOf: Seq[String] => SecLangEngine,
      minCount: Long = defaultMinCount,
      maxProposals: Int = defaultMaxProposals,
      crs: CrsSettings = CrsSettings.empty
  ): LearningReport = {
    val run       = snapshot.totals
    val entries   = snapshot.entries
    val candidates = entries.filter(_.count >= minCount).take(maxProposals)

    val proposals = candidates.zipWithIndex.map { case (entry, idx) =>
      val options = ExclusionBuilder.proposals(entry.ruleId, entry.target, Some(entry.path), firstRuleId + idx)
      options.headOption match {
        case None           =>
          ProposedExclusion(entry, ExclusionBuilder.proposals(entry.ruleId, None, None, firstRuleId + idx).head, unverifiable, false, "no exclusion could be generated")
        case Some(proposal) =>
          val preview = ExclusionPreview.run(
            rules = rules,
            exclusion = proposal.seclang,
            ruleId = entry.ruleId,
            target = entry.target,
            sampleValue = entry.samples.headOption,
            method = entry.method,
            path = entry.path,
            placement = proposal.placement,
            engineOf = engineOf
          )
          val (accepted, note) =
            if (!preview.compiles) (false, "does not compile")
            else if (!preview.reproduced)
              (false, "the match could not be rebuilt from what was recorded, so the exclusion cannot be verified")
            else if (!preview.effective) (false, "ran it — the rule still fires")
            else if (preview.regressions.nonEmpty)
              (false, s"gives up ${preview.regressions.map(_.name).mkString(", ")} in the same input — decide this one by hand")
            else (true, "verified: the rule stops firing and the attack corpus is still caught")
          ProposedExclusion(entry, proposal, preview, accepted, note)
      }
    }

    val accepted   = proposals.filter(_.accepted)
    val paranoia   = LearningAdvice.paranoia(entries, LearningAdvice.currentParanoia(rules, crs))
    val threshold  = LearningAdvice.threshold(snapshot.samples, LearningAdvice.currentThreshold(rules, crs))
    val impact     = LearningAdvice.impact(run, snapshot.samples, accepted)
    val modeInfo   = modeAdvice(mode, configBlocking)

    LearningReport(
      run = run,
      nodes = snapshot.contributions.size,
      mode = modeInfo,
      entries = entries,
      exclusions = proposals,
      paranoia = paranoia,
      threshold = threshold,
      impact = impact,
      verdict = verdictFor(run, impact, accepted.size, proposals.size - accepted.size, modeInfo)
    )
  }

  private val unverifiable = PreviewResult(false, Some("not attempted"), false, false, RuleVerdict(Set.empty, false), RuleVerdict(Set.empty, false), Seq.empty, Set.empty)

  /**
   * The sentence someone reads first.
   *
   * It exists because the honest answer is usually "not yet, and here is what is missing" — and a
   * report that only shows tables lets an optimistic reader conclude otherwise.
   */
  private def verdictFor(run: LearningRun, impact: ArmingImpact, accepted: Int, rejected: Int, mode: ModeAdvice): String = {
    val hours = run.durationMillis / 3600000L
    if (run.requests == 0)
      "Nothing was observed. Either the window has just started, or no traffic reached this configuration."
    else if (hours < 24)
      f"Only ${hours}h of traffic so far. A daily batch job or a weekend pattern has not been seen yet, so arming on " +
        "this would be arming on a guess."
    else if (!mode.armingEstimateReliable)
      f"${run.matched} matches over ${hours}h and ${run.requests} requests, and a complete inventory of what fired. " +
        "What arming would cost cannot be measured in this mode — see the note above — so treat the exclusions below " +
        "as the tuning backlog, not as a green light."
    else if (impact.wouldBlock == 0)
      f"Nothing would have been denied over ${hours}h and ${run.requests} requests. This configuration looks safe to arm."
    else if (accepted > 0 && impact.residual == 0)
      f"Arming today denies ${impact.wouldBlock} of ${run.requests} requests (${impact.wouldBlockRate * 100}%.2f%%). " +
        f"The $accepted verified exclusions below account for every sampled denial."
    else
      f"Arming today denies ${impact.wouldBlock} of ${run.requests} requests (${impact.wouldBlockRate * 100}%.2f%%). " +
        f"$accepted exclusions are verified, ${impact.residual} sampled denials remain unaccounted for" +
        (if (rejected > 0) s", and $rejected candidates need a human decision." else ".")
  }
}
