package com.cloud.apim.otoroshi.extensions.waf.tuning

import play.api.libs.json.*

/**
 * How much a proposed exclusion gives up, from least to most.
 *
 * Ordered on purpose: the assistant offers them in this order and the UI marks anything past
 * `OneInput` as a concession. Presenting four options as equals is how an operator picks the one
 * that makes the alert go away, which is reliably the widest one.
 */
enum Reach(val rank: Int, val label: String) {
  case OneInputOnePath  extends Reach(1, "this parameter, on this path")
  case OneInput         extends Reach(2, "this parameter, everywhere this config runs")
  case OneRuleOnePath   extends Reach(3, "the whole rule, on this path")
  case OneRuleEverywhere extends Reach(4, "the whole rule, everywhere this config runs")
}

/**
 * Where in the composed configuration a directive has to sit to do anything.
 *
 * This is not cosmetic and it is not symmetric. `ctl:` forms are ordinary rules that run in phase 1
 * and set state the target rule reads afterwards, so they have to be evaluated *before* it — placed
 * after the CRS they are simply too late for anything the CRS does in phase 1, and they fail
 * silently. `SecRuleUpdateTargetById` and `SecRuleRemoveById` are declarations collected across the
 * whole program, so they work from anywhere; they go last to match the CRS's own convention of
 * `REQUEST-900-EXCLUSION-RULES-BEFORE-CRS` for the runtime kind and
 * `RESPONSE-999-EXCLUSION-RULES-AFTER-CRS` for the declarative kind.
 */
enum Placement(val name: String) {
  case Before extends Placement("before")
  case After  extends Placement("after")
}

object Placement {
  def of(kind: String): Placement = kind match {
    case "scoped_target" | "scoped_remove" => Before
    case _                                 => After
  }
}

/**
 * One way to stop a rule objecting, with what it costs.
 *
 * `stillCaught` and `noLongerCaught` are written as plain sentences rather than left implicit,
 * because the choice between these is a security decision and the person making it is usually not
 * the person who wrote the rule.
 */
final case class ExclusionProposal(
    kind: String,
    title: String,
    seclang: String,
    reach: Reach,
    rationale: String,
    stillCaught: String,
    noLongerCaught: String,
    recommended: Boolean
) {

  def placement: Placement = Placement.of(kind)

  def json: JsValue = Json.obj(
    "kind"             -> kind,
    "placement"        -> placement.name,
    "title"            -> title,
    "seclang"          -> seclang,
    "reach"            -> reach.label,
    "reach_rank"       -> reach.rank,
    "rationale"        -> rationale,
    "still_caught"     -> stillCaught,
    "no_longer_caught" -> noLongerCaught,
    "recommended"      -> recommended
  )
}

object ExclusionBuilder {

  /**
   * Where generated rule ids come from.
   *
   * ModSecurity reserves 1–99,999 for local rules and the CRS lives in 900,000–999,999, so this
   * band collides with neither. Ids are allocated by looking at what the destination ruleset
   * already uses rather than by counting, so re-running the assistant after someone hand-edited the
   * ruleset does not reissue an id that is already taken.
   */
  val generatedIdBase: Int = 50000
  val generatedIdMax: Int  = 59999

  private val idPattern = """\bid\s*:\s*'?(\d+)'?""".r

  /** The next free id in the generated band, given everything the destination already contains. */
  def nextId(existing: Seq[String]): Int = {
    val used = existing.flatMap(rule => idPattern.findAllMatchIn(rule).map(_.group(1).toInt))
      .filter(id => id >= generatedIdBase && id <= generatedIdMax)
    if (used.isEmpty) generatedIdBase else (used.max + 1).min(generatedIdMax)
  }

  /** SecLang string literals are double-quoted, so a quote in a path would end the argument. */
  private def escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  /**
   * A path a `@beginsWith` can be written against.
   *
   * The query string is dropped: it is per-request, so scoping on it would produce an exclusion
   * that matches the one request in the sample and nothing else the endpoint ever receives.
   */
  def normalizePath(raw: String): Option[String] = {
    val path = raw.trim.takeWhile(_ != '?').takeWhile(_ != '#')
    Option.when(path.startsWith("/") && path.length <= 512)(path)
  }

  /**
   * Every exclusion that would silence this rule, narrowest first.
   *
   * A target the engine cannot exclude — the `TX` scratch space, or a rule that logged no parameter
   * — leaves only the rule-level forms, and the caller is told why rather than offered a surgical
   * option that would not work.
   */
  def proposals(ruleId: Int, target: Option[MatchedTarget], path: Option[String], nextRuleId: Int): Seq[ExclusionProposal] = {
    val scopePath = path.flatMap(normalizePath)
    val excludable = target.filter(_.excludable)
    val targeted = excludable.toSeq.flatMap { t =>
      val onPath = scopePath.map { p =>
        ExclusionProposal(
          kind = "scoped_target",
          title = s"Stop inspecting ${t.full} under $p",
          seclang =
            s"""SecRule REQUEST_URI "@beginsWith ${escape(p)}" "id:$nextRuleId,phase:1,pass,nolog,ctl:ruleRemoveTargetById=$ruleId;${t.full}"""",
          reach = Reach.OneInputOnePath,
          rationale =
            s"Rule $ruleId keeps running on every request and on every other input. It stops looking at " +
              s"${t.full}, and only for requests whose path starts with $p.",
          stillCaught = s"An attack in any other parameter, and an attack in ${t.full} on any other path.",
          noLongerCaught = s"Whatever rule $ruleId detects, when it arrives in ${t.full} under $p.",
          recommended = true
        )
      }
      val everywhere = ExclusionProposal(
        kind = "update_target",
        title = s"Stop inspecting ${t.full}",
        seclang = s"""SecRuleUpdateTargetById $ruleId "!${t.full}"""",
        reach = Reach.OneInput,
        rationale =
          s"Rule $ruleId keeps running on every other input, on every route using this config. It stops " +
            s"looking at ${t.full} entirely.",
        stillCaught = s"An attack in any parameter other than ${t.full}.",
        noLongerCaught = s"Whatever rule $ruleId detects, whenever it arrives in ${t.full}.",
        recommended = onPath.isEmpty
      )
      onPath.toSeq :+ everywhere
    }
    val ruleLevel = scopePath.map { p =>
      ExclusionProposal(
        kind = "scoped_remove",
        title = s"Turn rule $ruleId off under $p",
        seclang = s"""SecRule REQUEST_URI "@beginsWith ${escape(p)}" "id:$nextRuleId,phase:1,pass,nolog,ctl:ruleRemoveById=$ruleId"""",
        reach = Reach.OneRuleOnePath,
        rationale = s"Rule $ruleId no longer runs at all for requests whose path starts with $p.",
        stillCaught = s"Whatever rule $ruleId detects, on every other path.",
        noLongerCaught = s"Whatever rule $ruleId detects, anywhere under $p — in any parameter, header or cookie.",
        recommended = false
      )
    }.toSeq :+ ExclusionProposal(
      kind = "remove_rule",
      title = s"Turn rule $ruleId off",
      seclang = s"SecRuleRemoveById $ruleId",
      reach = Reach.OneRuleEverywhere,
      rationale = s"Rule $ruleId is removed from this config. Nothing it detects is detected any more.",
      stillCaught = "Nothing this rule was responsible for.",
      noLongerCaught = s"Everything rule $ruleId detects, on every route using this config.",
      recommended = false
    )
    (targeted ++ ruleLevel).sortBy(_.reach.rank)
  }
}
