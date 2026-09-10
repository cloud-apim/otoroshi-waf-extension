package com.cloud.apim.otoroshi.extensions.waf.tuning

import com.cloud.apim.seclang.impl.engine.SecLangEngine
import com.cloud.apim.seclang.model.{Disposition, RequestContext}
import play.api.libs.json.*

import scala.util.{Failure, Success, Try}

final case class RuleVerdict(ruleIds: Set[Int], blocked: Boolean) {
  def json: JsValue = Json.obj("rule_ids" -> ruleIds.toSeq.sorted, "blocked" -> blocked)
}

final case class CorpusOutcome(name: String, category: String, before: Boolean, after: Boolean) {
  def regression: Boolean = before && !after
  def json: JsValue       =
    Json.obj("name" -> name, "category" -> category, "caught_before" -> before, "caught_after" -> after)
}

/**
 * What an exclusion actually does, measured rather than described.
 *
 * The assistant never writes a line it has not run. Two things get checked, and they fail in
 * opposite directions:
 *
 *  - `effective` — the rule really does stop firing. An exclusion the engine ignores is the worst
 *    outcome available: it compiles, it is written to a ruleset, it is reviewed and approved, and
 *    the alert keeps arriving. Half of these directives silently did nothing before
 *    seclang-engine 2.2.0, which is why this is asserted rather than assumed.
 *  - `regressions` — attacks that were caught and now are not. This is the cost side, and it is the
 *    one the person clicking "this was a false positive" is least equipped to work out alone.
 */
final case class PreviewResult(
    compiles: Boolean,
    error: Option[String],
    reproduced: Boolean,
    effective: Boolean,
    sampleBefore: RuleVerdict,
    sampleAfter: RuleVerdict,
    corpus: Seq[CorpusOutcome],
    collateralRules: Set[Int]
) {
  def regressions: Seq[CorpusOutcome] = corpus.filter(_.regression)
  def safe: Boolean                   = compiles && effective && regressions.isEmpty && collateralRules.isEmpty

  def json: JsValue = Json.obj(
    "compiles"         -> compiles,
    "error"            -> error,
    "reproduced"       -> reproduced,
    "effective"        -> effective,
    "safe"             -> safe,
    "sample_before"    -> sampleBefore.json,
    "sample_after"     -> sampleAfter.json,
    "corpus"           -> JsArray(corpus.map(_.json)),
    "regressions"      -> JsArray(regressions.map(_.json)),
    "collateral_rules" -> collateralRules.toSeq.sorted
  )
}

object ExclusionPreview {

  private val requestPhases = List(1, 2, 5)

  private def verdict(engine: SecLangEngine, ctx: RequestContext): RuleVerdict = {
    val res = engine.evaluate(ctx, requestPhases)
    RuleVerdict(
      ruleIds = res.events.flatMap(_.ruleId).toSet,
      blocked = res.disposition match {
        case _: Disposition.Block => true
        case Disposition.Continue => false
      }
    )
  }

  /**
   * Run the ruleset with and without the candidate line.
   *
   * `engineOf` is passed in rather than built here so the preview runs on the very engine the route
   * runs — same presets, same factory, same compiled CRS. Replaying against a freshly assembled
   * engine would be measuring something adjacent to what is deployed.
   */
  def run(
      rules: Seq[String],
      exclusion: String,
      ruleId: Int,
      target: Option[MatchedTarget],
      sampleValue: Option[String],
      method: String,
      path: String,
      placement: Placement,
      engineOf: Seq[String] => SecLangEngine
  ): PreviewResult = {
    Try {
      val before = engineOf(rules)
      // composed at the position the directive will actually occupy once written. Previewing a
      // `ctl:` form appended last would measure a configuration nobody is going to deploy — and,
      // since that position makes it a no-op for phase 1, would report a working exclusion as broken
      val after  = engineOf(placement match {
        case Placement.Before => exclusion +: rules
        case Placement.After  => rules :+ exclusion
      })

      val probe = target
        .map(t => AttackCorpus.requestFor(t, method, path, sampleValue.getOrElse("' or 1=1--")))
        .getOrElse(
          AttackCorpus.requestFor(MatchedTarget("ARGS", Some("q")), method, path, sampleValue.getOrElse("' or 1=1--"))
        )

      val sampleBefore = verdict(before, probe)
      val sampleAfter  = verdict(after, probe)

      // "caught" cannot mean "blocked": the CRS scores anomalies and one payload rarely crosses the
      // threshold on its own, so blocking would report every payload as uncaught both before and
      // after, and the check would quietly always pass. It also cannot mean "some rule fired",
      // since setup and logging rules fire on everything. What it means is: this value triggers
      // something a harmless value in the same input does not — which needs no knowledge of how the
      // ruleset numbers itself, and so holds for a hand-written one too.
      val corpus = target.filter(_.excludable).toSeq.flatMap { t =>
        val benign         = AttackCorpus.requestFor(t, method, path, AttackCorpus.benignValue)
        val benignBefore   = verdict(before, benign).ruleIds
        val benignAfter    = verdict(after, benign).ruleIds
        def caught(v: RuleVerdict, benign: Set[Int]): Boolean = v.blocked || (v.ruleIds -- benign).nonEmpty
        AttackCorpus.requests(t, method, path).map { case (payload, ctx) =>
          CorpusOutcome(
            name = payload.name,
            category = payload.category,
            before = caught(verdict(before, ctx), benignBefore),
            after = caught(verdict(after, ctx), benignAfter)
          )
        }
      }

      PreviewResult(
        compiles = true,
        error = None,
        // whether the probe reproduced the match at all. Without it nothing can be concluded either
        // way, and saying "ineffective" would be a different claim than the one the evidence
        // supports — a rule with no `logdata` value leaves no way to rebuild what tripped it
        reproduced = sampleBefore.ruleIds.contains(ruleId),
        effective = sampleBefore.ruleIds.contains(ruleId) && !sampleAfter.ruleIds.contains(ruleId),
        sampleBefore = sampleBefore,
        sampleAfter = sampleAfter,
        corpus = corpus,
        // anything else that went quiet: an exclusion is meant to silence one rule, and silencing
        // three is a different decision than the one being approved
        collateralRules = (sampleBefore.ruleIds -- sampleAfter.ruleIds) - ruleId
      )
    } match {
      case Success(result) => result
      case Failure(err)    =>
        PreviewResult(
          compiles = false,
          error = Some(Option(err.getMessage).getOrElse(err.toString)),
          reproduced = false,
          effective = false,
          sampleBefore = RuleVerdict(Set.empty, false),
          sampleAfter = RuleVerdict(Set.empty, false),
          corpus = Seq.empty,
          collateralRules = Set.empty
        )
    }
  }
}
