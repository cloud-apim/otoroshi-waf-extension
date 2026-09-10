package com.cloud.apim.otoroshi.extensions.waf.entities

import play.api.libs.json.*

import scala.util.Try

/**
 * The two dials that decide what CRS actually does, as typed fields.
 *
 * CRS is driven by a paranoia level and an anomaly threshold, and today setting either means
 * hand-writing a `SecAction` with `tx.` variables in the rule text — the single most common tuning
 * operation in the product, expressed as folklore. Nothing here is new capability: it is the same
 * directives, generated, with the numbers in a form and their cost written next to them.
 *
 * Every field is optional and **an empty settings object emits nothing at all**, so a configuration
 * that says nothing runs byte for byte what it ran before. That matters more than it looks: these
 * numbers change what a WAF blocks, and an upgrade must not move them.
 */
final case class CrsSettings(
    paranoiaLevel: Option[Int] = None,
    detectionParanoiaLevel: Option[Int] = None,
    inboundThreshold: Option[Int] = None,
    outboundThreshold: Option[Int] = None,
    earlyBlocking: Option[Boolean] = None
) {

  def isEmpty: Boolean =
    paranoiaLevel.isEmpty && detectionParanoiaLevel.isEmpty && inboundThreshold.isEmpty &&
      outboundThreshold.isEmpty && earlyBlocking.isEmpty

  def nonEmpty: Boolean = !isEmpty

  /**
   * What is wrong with these numbers, in words an operator can act on.
   *
   * Checked here rather than left to CRS: rule 901xxx does police the paranoia pair at request
   * time, but it does so by logging on every single request, which is a bad way to find out.
   */
  def errors: Seq[String] = {
    val out = Seq.newBuilder[String]
    // only values above the range can reach here: `read` treats 0 and below as "not set", because
    // that is what the form sends for an untouched field
    paranoiaLevel.filterNot(CrsSettings.paranoiaRange.contains).foreach { pl =>
      out += s"paranoia level must be between 1 and 4, got $pl"
    }
    detectionParanoiaLevel.filterNot(CrsSettings.paranoiaRange.contains).foreach { pl =>
      out += s"detection paranoia level must be between 1 and 4, got $pl"
    }
    for {
      blocking  <- paranoiaLevel
      detection <- detectionParanoiaLevel
      if detection < blocking
    } out += s"detection paranoia level ($detection) cannot be lower than the blocking level ($blocking) — " +
      "rules above the detection level never run at all, so the blocking level would have nothing to act on"
    out.result()
  }

  def valid: Boolean = errors.isEmpty

  private def setvars: Seq[String] = Seq(
    paranoiaLevel.map(v => s"setvar:'tx.blocking_paranoia_level=$v'"),
    detectionParanoiaLevel.map(v => s"setvar:'tx.detection_paranoia_level=$v'"),
    inboundThreshold.map(v => s"setvar:'tx.inbound_anomaly_score_threshold=$v'"),
    outboundThreshold.map(v => s"setvar:'tx.outbound_anomaly_score_threshold=$v'"),
    earlyBlocking.map(v => s"setvar:'tx.early_blocking=${if (v) 1 else 0}'")
  ).flatten

  /**
   * The SecLang this turns into, or nothing.
   *
   * One `SecAction` in phase 1, which has to be evaluated **before** the CRS initialisation rules:
   * those set the defaults with `SecRule &TX:x "@eq 0"`, meaning "only if nobody has said
   * otherwise". Being first is therefore the whole mechanism — see [[CrsSettings.preamble]] for
   * where it is placed.
   */
  def seclang: Option[String] =
    Option.when(setvars.nonEmpty) {
      // built by concatenation rather than in a triple-quoted string: those do not process escapes,
      // so a backslash pair written there would emit two and break every SecLang line continuation
      val actions   = Seq(s"id:${CrsSettings.preambleRuleId}", "phase:1", "nolog", "pass", "t:none") ++ setvars
      val continued = actions.mkString(",\\\n    ")
      "# generated from this config's CRS settings — change the fields, not this rule\n" +
        "SecAction \\\n    \"" + continued + "\""
    }

  def json: JsValue = Json.obj(
    "paranoia_level"            -> paranoiaLevel,
    "detection_paranoia_level"  -> detectionParanoiaLevel,
    "inbound_anomaly_threshold" -> inboundThreshold,
    "outbound_anomaly_threshold" -> outboundThreshold,
    "early_blocking"            -> earlyBlocking
  )
}

object CrsSettings {

  val empty: CrsSettings = CrsSettings()

  val paranoiaRange: Range = 1 to 4

  /**
   * The id the generated rule carries.
   *
   * Well below CRS's own space — its rules start at 901000 and its setup file at 900000 — and below
   * the band the tuning assistant allocates generated exclusions from, so the three never collide.
   */
  val preambleRuleId: Int = 40000

  /** What CRS falls back to when a configuration says nothing. Kept here so one place owns them. */
  val defaultParanoia: Int          = 1
  val defaultInboundThreshold: Int  = 5
  val defaultOutboundThreshold: Int = 4

  /**
   * Written first, ahead of every ruleset and inline rule.
   *
   * Not a stylistic choice. CRS sets its own defaults in `REQUEST-901-INITIALIZATION.conf` guarded
   * by `&TX:name "@eq 0"`, so whichever `setvar` runs first wins — and within a phase, SecLang runs
   * rules in declaration order. Emitted after the preset, this would do nothing at all.
   */
  def preamble(settings: CrsSettings): Seq[String] = settings.seclang.toSeq

  /**
   * Reads the fields, treating "zero" and "false" as "not set".
   *
   * Not a nicety — it is what makes the form work at all. Otoroshi's `number` component cannot
   * express an empty value: it renders an unset field as `0` and sends `0` back on save, and its
   * `bool` component does the same with `false`. Taking those literally would emit
   * `tx.inbound_anomaly_score_threshold=0` — a threshold that denies every request — for any
   * configuration whose form was merely opened and saved.
   *
   * Nothing is lost by it. Zero is not a meaningful threshold, and `early_blocking=0` is already
   * the CRS default, so "explicitly off" and "unset" compile to the same program. The existing
   * `input_body_limit` field solves the same problem the same way.
   */
  def read(json: JsValue): CrsSettings = Try {
    CrsSettings(
      paranoiaLevel = (json \ "paranoia_level").asOpt[Int].filter(_ > 0),
      detectionParanoiaLevel = (json \ "detection_paranoia_level").asOpt[Int].filter(_ > 0),
      inboundThreshold = (json \ "inbound_anomaly_threshold").asOpt[Int].filter(_ > 0),
      outboundThreshold = (json \ "outbound_anomaly_threshold").asOpt[Int].filter(_ > 0),
      earlyBlocking = (json \ "early_blocking").asOpt[Boolean].filter(identity)
    )
  }.getOrElse(empty)

  val format: Format[CrsSettings] = new Format[CrsSettings] {
    override def writes(o: CrsSettings): JsValue             = o.json
    override def reads(json: JsValue): JsResult[CrsSettings] = JsSuccess(read(json))
  }
}
