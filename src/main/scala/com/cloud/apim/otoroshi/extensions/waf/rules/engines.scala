package com.cloud.apim.otoroshi.extensions.waf.rules

import com.cloud.apim.otoroshi.extensions.waf.entities.ComposedRules
import com.cloud.apim.seclang.impl.engine.SecLangEngine
import com.cloud.apim.seclang.model.{EngineResult, RequestContext}

import scala.collection.concurrent.TrieMap
import scala.util.Try

/**
 * The engine a config compiles to, built once per change of its rules and shared by every request.
 *
 * A SecLang engine keeps no state between evaluations other than its transaction map, and an
 * evaluation can bring its own. Evaluating only through an exchange makes that the only possible
 * use, so no request ever reads the TX another one wrote.
 */
final class SharedWafEngine(engine: SecLangEngine) {
  def exchange(): WafExchange = new WafExchange(engine)
}

/** One exchange's view of a shared engine: its request and response halves share a TX, nothing else does. */
final class WafExchange(engine: SecLangEngine) {
  private val tx = new TrieMap[String, String]()
  def evaluate(ctx: RequestContext, phases: List[Int]): EngineResult = engine.evaluate(ctx, phases, Some(tx))
}

/**
 * The shared engines, one per config, each checked against the composition it was built from.
 *
 * The check is identity rather than equality: a composition is a new instance exactly when it
 * changes (see `WafExtensionState.recompose`), so a request pays a reference comparison where it
 * used to re-hash every rule and rebuild the program. A composition that does not compile is
 * remembered as such, so it is reported once per change rather than rebuilt on every request.
 */
final class EngineCache(
    build: Seq[String] => SecLangEngine,
    presetExists: String => Boolean,
    onBroken: (String, String) => Unit
) {

  private val engines = new TrieMap[String, (ComposedRules, Either[String, SharedWafEngine])]()

  def engineFor(key: String, composed: ComposedRules): Either[String, SharedWafEngine] =
    engines.get(key) match {
      case Some((built, cached)) if built eq composed => cached
      case _                                          =>
        val engine = Try(build(composed.rules)).toEither match {
          case Right(engine) => Right(new SharedWafEngine(engine))
          case Left(err)     =>
            // what the factory throws is often a bare `None.get`: the check says which rule and why
            val reason = RuleCheck
              .check(composed.labelled, presetExists)
              .headOption
              .map(_.text)
              .getOrElse(Option(err.getMessage).getOrElse(err.getClass.getSimpleName))
            onBroken(key, reason)
            Left(reason)
        }
        engines.put(key, (composed, engine))
        engine
    }

  /** Forgets the engines of the configs that are gone. */
  def retain(keys: Set[String]): Unit = engines.keySet.filterNot(keys.contains).foreach(engines.remove)
}
