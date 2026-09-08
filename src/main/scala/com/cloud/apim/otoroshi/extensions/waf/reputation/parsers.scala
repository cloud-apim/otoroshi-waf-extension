package com.cloud.apim.otoroshi.extensions.waf.reputation

import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.util.{Failure, Success, Try}

/**
 * Turns the payload published by a threat feed into a flat list of IP / CIDR strings.
 *
 * Parsers are deliberately lenient about junk lines (they are counted, not fatal) and strict
 * about the overall shape (a JSON feed that stops being JSON is an error, not an empty feed) —
 * a feed that silently degrades to zero entries is worse than one that reports a failure, because
 * the refresher keeps the last good snapshot when a refresh errors.
 */
object FeedParser {

  val formats: Seq[String] = Seq("cidr_lines", "csv", "json_array", "json_path", "misp")

  def parse(format: String, options: JsObject, body: String): Either[String, Vector[String]] = {
    format.trim.toLowerCase match {
      case "cidr_lines" | "cidr" | "lines" | "spamhaus_drop" => Right(parseLines(body))
      case "csv"                                             => Right(parseCsv(options, body))
      case "json_array"                                      => parseJsonArray(options, body)
      case "json_path"                                       => parseJsonPath(options, body)
      case "misp"                                            => parseMisp(body)
      case other                                             => Left(s"unknown feed format '$other', expected one of ${formats.mkString(", ")}")
    }
  }

  /**
   * One entry per line. Handles the two comment conventions used by public blocklists:
   * `# comment` (FireHOL, Emerging Threats) and `1.2.3.0/24 ; SBL123` (Spamhaus DROP).
   */
  private def parseLines(body: String): Vector[String] = {
    body
      .split("\n")
      .iterator
      .map { rawLine =>
        val noHash  = rawLine.indexOf('#') match {
          case -1  => rawLine
          case idx => rawLine.substring(0, idx)
        }
        val noSemi  = noHash.indexOf(';') match {
          case -1  => noHash
          case idx => noHash.substring(0, idx)
        }
        noSemi.trim.split("\\s+").headOption.getOrElse("").trim
      }
      .filter(_.nonEmpty)
      .toVector
  }

  private def parseCsv(options: JsObject, body: String): Vector[String] = {
    val column     = options.select("column").asOpt[Int].getOrElse(0)
    val separator  = options.select("separator").asOptString.getOrElse(",")
    val skipHeader = options.select("skip_header").asOpt[Boolean].getOrElse(false)
    val lines      = body.split("\n").iterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#"))
    val rows       = if (skipHeader) lines.drop(1) else lines
    rows
      .map { line =>
        val cells = line.split(java.util.regex.Pattern.quote(separator), -1)
        if (column < cells.length) cells(column).trim.stripPrefix("\"").stripSuffix("\"") else ""
      }
      .filter(_.nonEmpty)
      .toVector
  }

  private def parseJsonArray(options: JsObject, body: String): Either[String, Vector[String]] = {
    val field = options.select("field").asOptString
    readJson(body).flatMap {
      case JsArray(values) =>
        Right(values.iterator.flatMap(v => valueToEntry(v, field)).toVector)
      case other           =>
        Left(s"expected a json array at the root, got ${kindOf(other)}")
    }
  }

  private def parseJsonPath(options: JsObject, body: String): Either[String, Vector[String]] = {
    options.select("path").asOptString match {
      case None       => Left("format 'json_path' requires a 'path' option, e.g. 'prefixes[].ip_prefix'")
      case Some(path) =>
        readJson(body).map(json => resolvePath(json, path))
    }
  }

  /** MISP feed: `response[].Event.Attribute[]`, or a bare `Event`, keeping ip-src / ip-dst values. */
  private def parseMisp(body: String): Either[String, Vector[String]] = {
    val ipTypes = Set("ip-src", "ip-dst", "ip-src|port", "ip-dst|port")
    readJson(body).map { json =>
      val events = (json \ "response").asOpt[JsArray].map(_.value.toVector).getOrElse {
        (json \ "Event").asOpt[JsValue].map(v => Vector(Json.obj("Event" -> v))).getOrElse(Vector.empty)
      }
      events.flatMap { event =>
        (event \ "Event" \ "Attribute").asOpt[JsArray].map(_.value.toVector).getOrElse(Vector.empty).flatMap { attr =>
          val typ = (attr \ "type").asOptString.getOrElse("")
          if (ipTypes.contains(typ)) (attr \ "value").asOptString.map(_.split('|').head) else None
        }
      }
    }
  }

  /** Supports `a.b`, `a[].b` and a trailing `[]` for arrays of scalars. */
  private def resolvePath(root: JsValue, path: String): Vector[String] = {
    val segments = path.split('.').toVector.filter(_.nonEmpty)
    val resolved = segments.foldLeft(Vector(root)) { (current, segment) =>
      val isArray = segment.endsWith("[]")
      val name    = if (isArray) segment.dropRight(2) else segment
      val stepped = if (name.isEmpty) current else current.flatMap(v => (v \ name).asOpt[JsValue])
      if (isArray) stepped.flatMap {
        case JsArray(values) => values.toVector
        case _               => Vector.empty
      }
      else stepped
    }
    resolved.flatMap(v => valueToEntry(v, None))
  }

  private def valueToEntry(value: JsValue, field: Option[String]): Option[String] = {
    field match {
      case Some(f) => (value \ f).asOptString.map(_.trim).filter(_.nonEmpty)
      case None    =>
        value match {
          case JsString(s) => Some(s.trim).filter(_.nonEmpty)
          case _           => None
        }
    }
  }

  private def readJson(body: String): Either[String, JsValue] = Try(Json.parse(body)) match {
    case Success(json) => Right(json)
    case Failure(err)  => Left(s"invalid json payload: ${err.getMessage}")
  }

  private def kindOf(value: JsValue): String = value match {
    case _: JsObject => "an object"
    case _: JsArray  => "an array"
    case _: JsString => "a string"
    case _: JsNumber => "a number"
    case _           => "something else"
  }
}
