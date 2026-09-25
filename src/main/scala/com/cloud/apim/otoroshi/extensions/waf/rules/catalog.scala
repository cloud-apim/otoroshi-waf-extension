package com.cloud.apim.otoroshi.extensions.waf.rules

import com.cloud.apim.otoroshi.extensions.waf.entities.{CloudApimWafConfig, WafRuleset}
import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang
import play.api.Logger
import play.api.libs.json.*

import java.io.InputStream
import java.net.JarURLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * What a rule id means, for a human reading a trail.
 *
 * A match event carries the id of the rule and, when the rule has one, its `msg`. Most of what a CRS
 * trail shows has none: the setup and initialization rules, the paranoia-level gates that skip a
 * whole file, the anomaly evaluation, and every rule after the first of a chain (it reports the id of
 * the chain it belongs to, without its message). So the message alone leaves most ids bare.
 *
 * The engine does not keep a catalog, and it concatenates every file of a preset before parsing, so
 * which file a rule came from is lost too. This parses the sources again, one file at a time, and
 * keeps for each id the message, tags, severity, phase and the file it lives in — which is enough to
 * name every rule, message or not.
 */
final case class RuleInfo(
    id: Int,
    msg: Option[String],
    source: String,
    category: String,
    tags: Seq[String],
    severity: Option[String],
    phase: Int,
    paranoiaLevel: Option[Int],
    // the machinery of a ruleset rather than a detection — present on every request, and what drowns
    // the rules that actually matter. A detection always says what it detected: a rule with no message
    // (a gate, a variable, a performance switch) is machinery
    plumbing: Boolean,
    // what to show: the message, or what the rule is when it has none
    label: String
) {
  def json: JsValue = Json.obj(
    "id"             -> id,
    "msg"            -> msg,
    "source"         -> source,
    "category"       -> category,
    "tags"           -> tags,
    "severity"       -> severity,
    "phase"          -> phase,
    "paranoia_level" -> paranoiaLevel,
    "plumbing"       -> plumbing,
    "label"          -> label
  )
}

object RuleCatalog {

  private val logger = Logger("cloud-apim-waf-rule-catalog")

  // CRS files are numbered: the three digits name the family, whatever the file is called
  private val crsFamilies: Map[Int, String] = Map(
    900 -> "CRS setup",
    901 -> "CRS initialization",
    905 -> "Common exceptions",
    911 -> "Method enforcement",
    913 -> "Scanner detection",
    920 -> "Protocol enforcement",
    921 -> "Protocol attack",
    922 -> "Multipart attack",
    930 -> "Local file inclusion",
    931 -> "Remote file inclusion",
    932 -> "Remote code execution",
    933 -> "PHP injection",
    934 -> "Generic attack",
    941 -> "Cross-site scripting",
    942 -> "SQL injection",
    943 -> "Session fixation",
    944 -> "Java attack",
    949 -> "Inbound anomaly evaluation",
    950 -> "Data leakage",
    951 -> "SQL data leakage",
    952 -> "Java data leakage",
    953 -> "PHP data leakage",
    954 -> "IIS data leakage",
    955 -> "Web shell",
    956 -> "Ruby data leakage",
    959 -> "Outbound anomaly evaluation",
    980 -> "Correlation",
    999 -> "Common exceptions"
  )

  private val crsFile = """(?:REQUEST|RESPONSE)-(\d{3})-.*""".r
  private val paranoia = """paranoia-level/(\d)""".r

  private def familyOf(id: Int, source: String): String = source match {
    case crsFile(n)                         => crsFamilies.getOrElse(n.toInt, source)
    case s if s.startsWith("crs-setup")     => crsFamilies(900)
    case _ if crsFamilies.contains(id / 1000) && source == "crs" => crsFamilies(id / 1000)
    case other                              => other
  }

  // setup, initialization and the score log: machinery, whether or not a rule of theirs has a message
  private val plumbingFamilies = Seq(900, 901, 980)

  /**
   * `%{TX.…}` is only known at runtime, so out of context it is noise. A message that is mostly
   * placeholders (the score summaries) is cut to what it announces.
   */
  private def labelOf(msg: String): String = {
    val bare = msg.replaceAll("""%\{[^}]+\}""", "…")
    if (bare.count(_ == '…') <= 2) bare
    else bare.split("""[:(]""").headOption.map(_.trim).filter(_.nonEmpty).getOrElse(bare)
  }

  private def describe(id: Int, msgs: Seq[String], tags: Seq[String], severity: Option[String], phase: Int, raw: String, source: String): RuleInfo = {
    val category = familyOf(id, source)
    val pl       = tags.collectFirst { case paranoia(n) => n.toInt }
    // a message split over lines keeps its `\` continuations in the source
    val msg      = msgs.headOption.map(_.replaceAll("""\\\s*\n\s*""", " ").replaceAll("""\s+""", " ").trim).filter(_.nonEmpty)
    // the paranoia-level gates are all alike: skip the rest of the file below a level
    val gate     = raw.contains("PARANOIA_LEVEL") && raw.contains("skipAfter")
    val label    = msg.map(labelOf).getOrElse {
      if (gate) s"$category · paranoia level gate"
      else if (raw.contains("setvar")) s"$category · sets variables"
      else category
    }
    RuleInfo(
      id = id,
      msg = msg,
      source = source,
      category = category,
      tags = tags,
      severity = severity,
      phase = phase,
      paranoiaLevel = pl,
      plumbing = msg.isEmpty || plumbingFamilies.exists(f => category == crsFamilies(f)),
      label = label
    )
  }

  /** Every rule with an id in `text`, attributed to `source`. Chain links carry no id and are skipped. */
  def parse(text: String, source: String): Seq[RuleInfo] =
    SecLang.parse(text, includeRawRule = true) match {
      case Left(err)   =>
        logger.debug(s"could not parse rules of '$source' for the catalog: ${err.msg}")
        Seq.empty
      case Right(conf) =>
        conf.statements.collect {
          case r: SecRule if r.id.isDefined =>
            val actions  = r.actions.toList.flatMap(_.actions)
            val severity = actions.collectFirst { case Action.Severity(v) => (v.json \ "value").asOpt[String] }.flatten
            describe(r.id.get, r.msgs.toSeq.sorted, r.tags.toSeq.sorted, severity, r.phase, r.raw, source)
          case a: SecAction if a.id.isDefined =>
            val severity = a.actions.actions.collectFirst { case Action.Severity(v) => (v.json \ "value").asOpt[String] }.flatten
            describe(a.id.get, a.msgs.toSeq.sorted, a.tags.toSeq.sorted, severity, a.phase, a.raw, source)
        }
    }

  /**
   * The CRS bundled with the engine, read file by file from the classpath the same way the preset
   * scans it. Built once: it never changes for the life of the process.
   */
  lazy val crs: Map[Int, RuleInfo] = {
    val files = Try(crsFiles()).getOrElse {
      logger.warn("could not read the CRS files for the rule catalog")
      Seq.empty
    }
    val all   = files.flatMap { case (name, text) => parse(text, name.stripSuffix(".conf")) }
    logger.info(s"rule catalog: ${all.size} CRS rules from ${files.size} files")
    all.map(r => r.id -> r).toMap
  }

  private def crsFiles(): Seq[(String, String)] = {
    // `.conf` only: the `.conf.example` files the preset also picks up are templates of the others
    val wanted = (name: String) => name.endsWith(".conf")
    Option(getClass.getClassLoader.getResource("crs")).toSeq.flatMap { url =>
      url.getProtocol match {
        case "file" =>
          val root = Paths.get(url.toURI)
          Files.walk(root).iterator().asScala.toSeq
            .filter(p => Files.isRegularFile(p) && wanted(p.getFileName.toString))
            .map(p => p.getFileName.toString -> Files.readString(p, StandardCharsets.UTF_8))
        case "jar"  =>
          val conn = url.openConnection().asInstanceOf[JarURLConnection]
          val jar  = conn.getJarFile
          val root = conn.getEntryName
          jar.entries().asScala.toSeq
            .filter(e => !e.isDirectory && e.getName.startsWith(root) && wanted(e.getName.split("/").last))
            .map { e =>
              val is: InputStream = jar.getInputStream(e)
              try e.getName.split("/").last -> Source.fromInputStream(is, StandardCharsets.UTF_8.name()).mkString
              finally is.close()
            }
        case _      => Seq.empty
      }
    }
  }

  /**
   * The rules written in the install's own rulesets and configs, attributed to the entity they come
   * from. Parsed again only when that text changes, which is rarely: the key is the text itself.
   */
  private val custom = new AtomicReference[(Int, Map[Int, RuleInfo])]((0, Map.empty))

  def customRules(rulesets: Seq[WafRuleset], configs: Seq[CloudApimWafConfig]): Map[Int, RuleInfo] = {
    val sources = rulesets.map(r => r.name -> r.rules) ++ configs.map(c => s"${c.name} (inline)" -> c.rules)
    // `@import_preset` is not SecLang: the preset it names is the CRS above
    val texts   = sources.map { case (name, rules) =>
      name -> rules.filterNot(_.trim.startsWith("@import_preset")).mkString("\n")
    }.filter(_._2.trim.nonEmpty)
    val key     = texts.hashCode()
    val cached  = custom.get()
    if (cached._1 == key) cached._2
    else {
      val built = texts.flatMap { case (name, text) => parse(text, name) }.map(r => r.id -> r).toMap
      custom.set((key, built))
      built
    }
  }

  /** The install's own rules win over CRS: an id redefined locally is the one that runs. */
  def describe(ids: Seq[Int], rulesets: Seq[WafRuleset], configs: Seq[CloudApimWafConfig]): Map[Int, RuleInfo] = {
    val own = customRules(rulesets, configs)
    ids.distinct.flatMap(id => own.get(id).orElse(crs.get(id)).map(id -> _)).toMap
  }
}
