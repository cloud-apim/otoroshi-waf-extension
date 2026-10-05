package com.cloud.apim.otoroshi.extensions.waf.leakage

import com.cloud.apim.seclang.impl.engine.SecLangEngine
import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang
import play.api.libs.json.{JsValue, Json}

import scala.collection.concurrent.TrieMap
import scala.io.Source

/** One leak found in a response: the rule that saw it, what it says, and what kind of leak it is. */
final case class Leak(ruleId: Int, message: String, family: String) {
  def json: JsValue = Json.obj("rule_id" -> ruleId, "message" -> message, "family" -> family)
}

/**
 * What a response must not tell its caller (DLP-3).
 *
 * The Core Rule Set already maintains the signatures: SQL error text from a dozen engines, Java,
 * PHP, IIS and Ruby errors, directory listings, source code. Its RESPONSE-95x leakage rules run
 * here on their own, with their data files, so the signatures stay CRS's to keep up to date. What
 * CRS does not cover (Python tracebacks and debug pages, Node and .NET stack frames, Go panics,
 * Laravel's error page) is added next to them.
 *
 * 950100 is left out: it flags any 5xx status, and a status is not a leak, its body is.
 */
object LeakageDetector {

  private val crsFiles = Seq(
    "RESPONSE-950-DATA-LEAKAGES.conf",
    "RESPONSE-951-DATA-LEAKAGES-SQL.conf",
    "RESPONSE-952-DATA-LEAKAGES-JAVA.conf",
    "RESPONSE-953-DATA-LEAKAGES-PHP.conf",
    "RESPONSE-954-DATA-LEAKAGES-IIS.conf",
    "RESPONSE-956-DATA-LEAKAGES-RUBY.conf"
  )

  // \x22 is a double quote, \x27 a single one and \x5c a backslash: none of them can be written
  // as is inside a SecLang operator
  private val extraRules: String =
    """
      |SecRule RESPONSE_BODY "@rx Traceback \(most recent call last\):|\bFile \x22[^\x22\r\n]+\x22, line \d+, in \S" "id:1950100,phase:4,deny,msg:'Python traceback'"
      |SecRule RESPONSE_BODY "@rx You\x27re seeing this error because you have <code>DEBUG = True</code>|The debugger caught an exception in your WSGI application" "id:1950101,phase:4,deny,msg:'Python debug page'"
      |SecRule RESPONSE_BODY "@rx \bat (?:async )?[\w$.<>\[\]]+ \((?:file://)?(?:/|[A-Za-z]:\x5c)[^()\r\n]+\.(?:m?js|cjs|ts):\d+:\d+\)" "id:1950110,phase:4,deny,msg:'Node.js stack trace'"
      |SecRule RESPONSE_BODY "@rx goroutine \d+ \[running\]:|panic: runtime error:" "id:1950120,phase:4,deny,msg:'Go panic'"
      |SecRule RESPONSE_BODY "@rx \bat [\w.`<>]+\([^()\r\n]*\) in [^\r\n]+:line \d+|--- End of stack trace from previous location" "id:1950130,phase:4,deny,msg:'.NET stack trace'"
      |SecRule RESPONSE_BODY "@rx Whoops, looks like something went wrong|\bIlluminate(?:\x5c\w+)+Exception" "id:1950140,phase:4,deny,msg:'Laravel error page'"
      |""".stripMargin

  private def resource(path: String): String =
    Option(getClass.getClassLoader.getResourceAsStream(path))
      .map { in =>
        try Source.fromInputStream(in, "UTF-8").mkString
        finally in.close()
      }
      .getOrElse(throw new IllegalStateException(s"$path is missing from the Core Rule Set jar"))

  /** The whole program for one paranoia level: the levels the CRS files check, their rules, ours. */
  def rules(paranoia: Int): String = {
    val level = paranoia.max(1).min(4)
    val preamble =
      s"""SecRuleEngine On
         |SecAction "id:1950000,phase:3,pass,nolog,setvar:tx.detection_paranoia_level=$level,setvar:tx.blocking_paranoia_level=$level,setvar:tx.critical_anomaly_score=5,setvar:tx.error_anomaly_score=4,setvar:tx.warning_anomaly_score=3,setvar:tx.notice_anomaly_score=2"
         |""".stripMargin
    // ours first: they are the more specific, and the first leak found is the one reported, so a
    // Node stack trace is not filed as Ruby for carrying a `TypeError:` that Ruby's list also has
    ((preamble +: extraRules +: crsFiles.map(f => resource(s"crs/rules/$f"))) :+ "SecRuleRemoveById 950100").mkString("\n")
  }

  // the data files under the names the rules use, see CrsPreset
  private lazy val files: Map[String, String] = com.cloud.apim.otoroshi.extensions.waf.rules.CrsPreset.embedded.files

  private val engines = new TrieMap[Int, SecLangEngine]()

  private def engine(paranoia: Int): SecLangEngine = {
    val level = paranoia.max(1).min(4)
    engines.getOrElseUpdate(
      level, {
        val configuration = SecLang.parse(rules(level)).fold(err => throw err.throwable, identity)
        SecLang.engine(
          SecLang.compile(configuration),
          SecLangEngineConfig.default,
          files,
          None,
          new NoLogSecLangIntegration()
        )
      }
    )
  }

  /** The first leak in a response, if there is one. */
  def detect(response: RequestContext, paranoia: Int): Option[Leak] =
    engine(paranoia).evaluate(response, List(3, 4), Some(new TrieMap[String, String]())).disposition match {
      case Disposition.Block(_, msg, Some(id)) => Some(Leak(id, msg.getOrElse("leak"), familyOf(id)))
      case _                                   => None
    }

  def familyOf(id: Int): String = id match {
    case 950130                          => "directory_listing"
    case 950140                          => "source_code"
    case 950150                          => "dotnet"
    case i if i / 1000 == 951            => "sql"
    case i if i / 1000 == 952            => "java"
    case i if i / 1000 == 953            => "php"
    case i if i / 1000 == 954            => "iis"
    case i if i / 1000 == 956            => "ruby"
    case 1950100 | 1950101               => "python"
    case 1950110                         => "node"
    case 1950120                         => "go"
    case 1950130                         => "dotnet"
    case 1950140                         => "php"
    case _                               => "other"
  }
}
