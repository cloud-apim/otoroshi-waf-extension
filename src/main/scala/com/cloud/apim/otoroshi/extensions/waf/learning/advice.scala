package com.cloud.apim.otoroshi.extensions.waf.learning

import com.cloud.apim.otoroshi.extensions.waf.tuning.{ExclusionProposal, PreviewResult}
import play.api.libs.json.*

/**
 * What the observed traffic says about the paranoia level.
 *
 * The most valuable thing a week of monitoring can tell you, and the one an operator working
 * exclusion-by-exclusion never finds out: if the noise is concentrated in rules above level 1, the
 * level was raised past what this traffic tolerates and one line replaces forty exclusions.
 */
final case class ParanoiaAdvice(
    byLevel: Map[Int, Long],
    current: Int,
    recommended: Option[Int],
    rationale: String
) {
  def json: JsValue = Json.obj(
    "by_level"    -> JsObject(byLevel.map { case (k, v) => k.toString -> JsNumber(v) }),
    "current"     -> current,
    "recommended" -> recommended,
    "rationale"   -> rationale
  )
}

/** How many of the sampled denials survive at each candidate anomaly threshold. */
final case class ThresholdAdvice(
    curve: Seq[(Int, Int)],
    sampled: Int,
    current: Int,
    recommended: Option[Int],
    rationale: String
) {
  def json: JsValue = Json.obj(
    "curve"       -> JsArray(curve.map { case (t, remaining) => Json.obj("threshold" -> t, "still_denied" -> remaining) }),
    "sampled"     -> sampled,
    "current"     -> current,
    "recommended" -> recommended,
    "rationale"   -> rationale
  )
}

/** One exclusion the run proposes, with the measurement that earned it a place. */
final case class ProposedExclusion(
    entry: LearningEntry,
    proposal: ExclusionProposal,
    preview: PreviewResult,
    accepted: Boolean,
    note: String
) {
  def json: JsValue = Json.obj(
    "entry"    -> entry.json,
    "proposal" -> proposal.json,
    "preview"  -> preview.json,
    "accepted" -> accepted,
    "note"     -> note
  )
}

/** What arming the configuration costs, before and after the proposal. */
final case class ArmingImpact(
    requests: Long,
    wouldBlock: Long,
    sampled: Int,
    resolved: Int,
    residual: Int,
    rationale: String
) {
  def wouldBlockRate: Double = if (requests <= 0) 0d else wouldBlock.toDouble / requests.toDouble
  def json: JsValue          = Json.obj(
    "requests"         -> requests,
    "would_block"      -> wouldBlock,
    "would_block_rate" -> wouldBlockRate,
    "sampled"          -> sampled,
    "resolved"         -> resolved,
    "residual"         -> residual,
    "rationale"        -> rationale
  )
}

object LearningAdvice {

  /** What the CRS defaults to when a configuration says nothing. */
  val defaultParanoia: Int  = 1
  val defaultThreshold: Int = 5

  private val paranoiaSetting  = """(?i)setvar\s*:\s*'?tx\.(?:blocking_)?paranoia_level\s*=\s*(\d+)'?""".r
  private val thresholdSetting = """(?i)setvar\s*:\s*'?tx\.inbound_anomaly_score_threshold\s*=\s*(\d+)'?""".r

  /** Read out of the configuration itself, so the advice is relative to what is actually running. */
  def currentParanoia(rules: Seq[String]): Int =
    paranoiaSetting.findAllMatchIn(rules.mkString("\n")).map(_.group(1).toInt).toSeq.lastOption.getOrElse(defaultParanoia)

  def currentThreshold(rules: Seq[String]): Int =
    thresholdSetting.findAllMatchIn(rules.mkString("\n")).map(_.group(1).toInt).toSeq.lastOption.getOrElse(defaultThreshold)

  /**
   * Whether dropping a level would remove most of the noise.
   *
   * Deliberately conservative: it only recommends a drop when a clear majority of what fired lives
   * above the target level, because lowering paranoia gives up whole classes of detection and is
   * not a decision to nudge someone into over a handful of events.
   */
  def paranoia(entries: Seq[LearningEntry], current: Int): ParanoiaAdvice = {
    val byLevel = entries.groupBy(_.paranoia.getOrElse(1)).view.mapValues(_.map(_.count).sum).toMap
    val total   = byLevel.values.sum
    if (total == 0) {
      ParanoiaAdvice(byLevel, current, None, "Nothing matched, so there is nothing to say about the level.")
    } else {
      val candidates = (1 until current).map { level =>
        val removed = byLevel.filter(_._1 > level).values.sum
        (level, removed.toDouble / total.toDouble)
      }
      candidates.find(_._2 >= 0.6d) match {
        case Some((level, share)) =>
          ParanoiaAdvice(
            byLevel,
            current,
            Some(level),
            f"${share * 100}%.0f%% of what matched comes from rules above paranoia level $level. " +
              f"Dropping to $level removes them in one line instead of ${entries.count(_.paranoia.exists(_ > level))} exclusions — " +
              "and gives up everything those levels detect, which is a real trade, not a tidy-up."
          )
        case None                 =>
          ParanoiaAdvice(
            byLevel,
            current,
            None,
            s"The noise is not concentrated above level $current, so lowering it would give up detection without " +
              "removing much. Tune with exclusions instead."
          )
      }
    }
  }

  /** The candidate thresholds, and what each would still have denied. */
  def threshold(samples: Seq[WouldBlockSample], current: Int): ThresholdAdvice = {
    val scored = samples.flatMap(_.score)
    if (scored.isEmpty) {
      ThresholdAdvice(
        Seq.empty,
        0,
        current,
        None,
        "No anomaly scores were observed. Either nothing reached a deny, or the ruleset does not use anomaly scoring."
      )
    } else {
      val candidates = Seq(current, current + 5, current + 10, current + 15, current + 20, current + 30).distinct.sorted
      val curve      = candidates.map(t => (t, scored.count(_ >= t)))
      // the smallest threshold that clears all but a twentieth of what was seen
      val target     = math.max(1, (scored.size * 0.05d).ceil.toInt)
      val recommended = curve.find { case (t, remaining) => t > current && remaining <= target }.map(_._1)
      ThresholdAdvice(
        curve,
        scored.size,
        current,
        recommended,
        recommended match {
          case Some(t) =>
            s"Raising the inbound threshold from $current to $t would have let all but ${curve.toMap.getOrElse(t, 0)} " +
              s"of the ${scored.size} sampled denials through. It is blunter than an exclusion — it weakens every rule " +
              "at once — so prefer it as a stopgap while the exclusions are reviewed, not instead of them."
          case None    =>
            s"No candidate threshold clears the observed denials without going absurdly high, which means the scores " +
              "are genuinely spread out. Exclusions are the answer here, not the threshold."
        }
      )
    }
  }

  /**
   * How much of the breakage the proposed exclusions actually account for.
   *
   * A denial under anomaly scoring is usually several rules agreeing, so the number of noisy rules
   * says nothing about the number of requests that stop breaking. This walks the sampled
   * combinations instead: a sample is resolved only when *every* excludable rule that fired on it is
   * covered. That understates the improvement — a partial exclusion can still drop a request under
   * the threshold — which is the direction an estimate offered before arming should err in.
   */
  def impact(run: LearningRun, samples: Seq[WouldBlockSample], accepted: Seq[ProposedExclusion]): ArmingImpact = {
    val covered  = accepted.map(_.entry.key).toSet
    val resolved = samples.count(s => s.keys.nonEmpty && s.keys.forall(covered.contains))
    ArmingImpact(
      requests = run.requests,
      wouldBlock = run.wouldBlock,
      sampled = samples.size,
      resolved = resolved,
      residual = samples.size - resolved,
      rationale =
        if (samples.isEmpty)
          "Nothing reached a deny during the window, so arming this configuration should change nothing. " +
            "Check the window actually covered a representative traffic cycle before trusting that."
        else
          s"Of ${samples.size} sampled requests the ruleset would have denied, $resolved are fully accounted for by the " +
            s"exclusions above and ${samples.size - resolved} are not. A request counts as resolved only when every rule " +
            "that fired on it is excluded, so the real figure is at least this good."
    )
  }
}
