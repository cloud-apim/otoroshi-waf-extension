package com.cloud.apim.otoroshi.extensions.waf.rules

import com.cloud.apim.seclang.model.{Configuration, SecRule}
import com.cloud.apim.seclang.scaladsl.SecLang
import play.api.libs.json.*

/** One problem in a composed rule list, located on the element that carries it. */
final case class RuleProblem(index: Int, origin: String, message: String) {
  def text: String  = s"$origin: $message"
  def json: JsValue = Json.obj("index" -> index, "origin" -> origin, "message" -> message)
}

/**
 * What the engine will make of a composed rule list, checked the way the engine reads it.
 *
 * The factory takes the list element by element: an element that starts with `@import_preset` names
 * a preset, every other one is parsed and compiled on its own. Checking the list joined into one
 * text, presets left out, validated something that never runs — a chain could continue across two
 * elements there and not at runtime, and a preset name with a typo was simply dropped.
 */
object RuleCheck {

  private val presetPrefix = "@import_preset "

  def check(rules: Seq[(String, String)], presetExists: String => Boolean): Seq[RuleProblem] =
    rules.zipWithIndex.flatMap { case ((origin, rule), index) =>
      def problem(message: String) = Some(RuleProblem(index, origin, message))
      if (rule.trim.startsWith(presetPrefix)) {
        // the same reading as the factory's, so the name checked is the name it will look up
        val name = rule.replaceFirst(presetPrefix, "").trim
        // the factory skips a preset it does not know, so a typo would run without the rules it named
        if (presetExists(name)) None else problem(s"unknown preset '$name', it would be skipped")
      } else {
        SecLang.parse(rule) match {
          case Left(err)   => problem(s"does not parse: ${message(err.msg)}")
          case Right(conf) =>
            SecLang.compileSafe(conf) match {
              case Left(err) => problem(s"does not compile: ${message(err.msg)}")
              case Right(_)  =>
                danglingChain(conf).flatMap { rule =>
                  problem(
                    s"ends on a chained rule${rule.id.map(id => s" ($id)").getOrElse("")}. Each element is compiled " +
                    "on its own, so the chain stops here and this rule acts without the ones meant to follow it: " +
                    "keep the whole chain in one element"
                  )
                }
            }
        }
      }
    }

  /** The last rule of the element, when it asks to be chained to a rule that is not there. */
  private def danglingChain(conf: Configuration): Option[SecRule] =
    conf.statements.collect { case rule: SecRule => rule }.lastOption.filter(_.isChain)

  private def message(raw: String): String = Option(raw).map(_.trim).filter(_.nonEmpty).getOrElse("unknown error")
}
