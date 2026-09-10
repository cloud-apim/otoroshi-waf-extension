package com.cloud.apim.otoroshi.extensions.waf.tuning

import play.api.libs.json.*

/**
 * The one input a rule objected to.
 *
 * Tuning lives or dies on this distinction. "Rule 942100 fired on this route" only supports turning
 * 942100 off; "rule 942100 fired on `ARGS:comment`" supports leaving it armed everywhere except one
 * parameter of one endpoint. The second is a tuning session, the first is how people end up
 * disabling the WAF.
 */
final case class MatchedTarget(collection: String, member: Option[String]) {

  /** The name SecLang uses for it, and the one an exclusion has to be written against. */
  def full: String = member.map(m => s"$collection:$m").getOrElse(collection)

  /**
   * Whether excluding it means anything.
   *
   * `TX` and the `MATCHED_*` family are the engine's own scratch space, not something a caller
   * sent — an exclusion aimed at one of them would be written, would compile, and would protect
   * nothing. Rules that report a match there are saying "an earlier rule matched", and the tuning
   * belongs on that earlier rule.
   */
  def excludable: Boolean =
    member.isDefined && !MatchedTarget.internalCollections.contains(collection)

  def json: JsValue = Json.obj(
    "collection" -> collection,
    "member"     -> member,
    "full"       -> full,
    "excludable" -> excludable
  )
}

object MatchedTarget {

  /** Scratch space rather than caller input — see [[MatchedTarget.excludable]]. */
  val internalCollections: Set[String] =
    Set("TX", "MATCHED_VAR", "MATCHED_VARS", "MATCHED_VAR_NAME", "MATCHED_VARS_NAMES", "ENV", "GEO", "RULE")

  /**
   * The collections a request actually carries.
   *
   * Used to sanity-check what was recovered from a log line: everything on that line after the
   * rule id comes from the request, so a caller can put `found within ` inside a payload and steer
   * the parse. A name that is not a real collection is therefore treated as "could not tell" rather
   * than trusted, and the preview re-checks the result regardless.
   */
  val knownCollections: Set[String] = Set(
    "ARGS", "ARGS_NAMES", "ARGS_GET", "ARGS_GET_NAMES", "ARGS_POST", "ARGS_POST_NAMES",
    "REQUEST_HEADERS", "REQUEST_HEADERS_NAMES", "REQUEST_COOKIES", "REQUEST_COOKIES_NAMES",
    "REQUEST_BODY", "REQUEST_URI", "REQUEST_URI_RAW", "REQUEST_LINE", "REQUEST_FILENAME",
    "REQUEST_BASENAME", "REQUEST_METHOD", "REQUEST_PROTOCOL", "QUERY_STRING", "PATH_INFO",
    "RESPONSE_BODY", "RESPONSE_HEADERS", "RESPONSE_HEADERS_NAMES", "RESPONSE_STATUS",
    "FILES", "FILES_NAMES", "MULTIPART_PART_HEADERS", "XML"
  ) ++ internalCollections

  /** `ARGS:comment` or `REQUEST_URI`. */
  def parse(name: String): Option[MatchedTarget] = {
    val trimmed = name.trim
    if (trimmed.isEmpty) None
    else {
      val (col, rest) = trimmed.indexOf(':') match {
        case -1  => (trimmed, None)
        case idx => (trimmed.substring(0, idx), Some(trimmed.substring(idx + 1)))
      }
      val collection = col.toUpperCase
      // a keyless collection is reported as `ARGS:` by rules that name one explicitly, and an empty
      // member is not something an exclusion can be written against
      Option.when(knownCollections.contains(collection))(
        MatchedTarget(collection, rest.map(_.trim).filter(_.nonEmpty))
      )
    }
  }

  private val marker = " found within "

  /**
   * What a CRS log line says the rule matched on.
   *
   * The convention is `logdata:'Matched Data: %{TX.0} found within %{MATCHED_VAR_NAME}: %{MATCHED_VAR}'`,
   * carried by nearly every CRS rule — which is why this reads the rendered line rather than asking
   * the engine: the same line is what the analytics row already stores, so an assistant built on it
   * can work on an event from last Tuesday and not only on one still in memory.
   *
   * A rule with no `logdata` yields nothing, and the caller is then limited to rule-level options.
   */
  def fromLogdata(log: String): Option[MatchedTarget] = {
    val idx = log.indexOf(marker)
    if (idx < 0) None
    else {
      val rest = log.substring(idx + marker.length)
      // `ARGS:comment: <value>` — the value is what follows the first colon-space
      val name = rest.indexOf(": ") match {
        case -1  => rest
        case sep => rest.substring(0, sep)
      }
      parse(name)
    }
  }

  /** The first target any of these lines names. */
  def fromLogs(logs: Seq[String]): Option[MatchedTarget] = logs.iterator.flatMap(fromLogdata).nextOption()

  /**
   * The value the rule objected to, as the same log line reports it.
   *
   * Capped, because it is caller-controlled and ends up in a store, an api response and a preview.
   * It carries no more than the event already emits today, and it is what lets the preview replay
   * the actual false positive rather than a stand-in.
   */
  def valueFromLogdata(log: String, max: Int = 200): Option[String] = {
    val idx = log.indexOf(marker)
    if (idx < 0) None
    else {
      val rest = log.substring(idx + marker.length)
      rest.indexOf(": ") match {
        case -1  => None
        case sep => Some(rest.substring(sep + 2).trim).filter(_.nonEmpty).map(v => v.take(max))
      }
    }
  }

  def valueFromLogs(logs: Seq[String]): Option[String] = logs.iterator.flatMap(l => valueFromLogdata(l)).nextOption()
}
