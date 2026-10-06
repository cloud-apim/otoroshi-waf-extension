package com.cloud.apim.otoroshi.extensions.waf.api

import com.cloud.apim.otoroshi.extensions.waf.body.MediaType
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.path.PathType
import com.networknt.schema.{InputFormat, Schema, SchemaLocation, SchemaRegistry, SchemaRegistryConfig}
import play.api.libs.json.*

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Something a request or a response does that its contract does not allow (API-1). */
final case class Violation(kind: String, where: String, detail: String) {

  /** What a refusal answers: the contract has no such path, no such method, no such media type. */
  def status: Int = kind match {
    case "unknown_path"           => 404
    case "method_not_allowed"     => 405
    case "unsupported_media_type" => 415
    case "body_too_large"         => 413
    case _                        => 400
  }

  def json: JsValue = Json.obj("kind" -> kind, "where" -> where, "detail" -> detail)
}

/** A schema of the contract, compiled, the types its values are read as, and the form it was written in. */
final class ContractSchema(val raw: JsValue, val types: Seq[String], val itemTypes: Seq[String], compiled: Option[Schema]) {

  /** What is wrong with a JSON document, at most `max` things. Never fails: a schema that cannot run checks nothing. */
  def check(json: String, max: Int = 5): Seq[String] = compiled match {
    case None         => Seq.empty
    case Some(schema) =>
      Try(schema.validate(json, InputFormat.JSON).asScala.toSeq) match {
        case scala.util.Success(errors) =>
          errors.take(max).map { e =>
            val at = Option(e.getInstanceLocation).map(_.toString).filter(l => l.nonEmpty && l != "/").map(l => s"$l: ").getOrElse("")
            s"$at${e.getMessage}"
          }
        case scala.util.Failure(_)      => Seq("not valid JSON")
      }
  }

  def check(value: JsValue): Seq[String] = check(Json.stringify(value))
}

/** One media type an operation accepts or answers, and its schema. */
final case class ContractMedia(range: String, schema: Option[ContractSchema]) {

  /** Whether a body of `contentType` that falls under this one is JSON its schema can judge. */
  def checks(contentType: String): Boolean = schema.isDefined && (ContractMedia.json(range) || ContractMedia.json(contentType))
}

object ContractMedia {

  def json(mediaType: String): Boolean = {
    val mt = MediaType.of(mediaType)
    mt.endsWith("/json") || mt.endsWith("+json")
  }

  /** The most specific of the declared media types a content type falls under. */
  def find(media: Seq[ContractMedia], contentType: String): Option[ContractMedia] = {
    val ct = MediaType.of(contentType)
    media
      .find(_.range == ct)
      .orElse(media.find(m => m.range.endsWith("/*") && m.range != "*/*" && ct.startsWith(m.range.dropRight(1))))
      .orElse(media.find(_.range == "*/*"))
  }
}

final case class ContractParam(name: String, in: String, required: Boolean, schema: Option[ContractSchema], explode: Boolean) {

  /** A parameter arrives as text: read it as what its schema says it is, so the schema can judge it. */
  def coerce(values: Seq[String]): JsValue = schema match {
    case Some(s) if s.types.contains("array") =>
      val items = if (explode) values else values.headOption.toSeq.flatMap(_.split(',').toSeq)
      JsArray(items.map(ContractParam.scalar(_, s.itemTypes)))
    case Some(s)                              => ContractParam.scalar(values.headOption.getOrElse(""), s.types)
    case None                                 => JsString(values.headOption.getOrElse(""))
  }
}

object ContractParam {
  def scalar(value: String, types: Seq[String]): JsValue = {
    def number: Option[JsValue] = Try(BigDecimal(value.trim)).toOption.filter(_ => value.trim.nonEmpty).map(JsNumber(_))
    def bool: Option[JsValue]   = value.trim.toLowerCase match {
      case "true"  => Some(JsBoolean(true))
      case "false" => Some(JsBoolean(false))
      case _       => None
    }
    types.iterator
      .map {
        case "integer" | "number" => number
        case "boolean"            => bool
        case "null"               => Option.when(value.isEmpty)(JsNull)
        case _                    => None
      }
      .collectFirst { case Some(v) => v }
      .getOrElse(JsString(value))
  }
}

final case class ContractBody(required: Boolean, media: Seq[ContractMedia])

final case class ContractResponse(code: String, media: Seq[ContractMedia])

final case class ContractOperation(
    method: String,
    path: String,
    operationId: Option[String],
    params: Seq[ContractParam],
    body: Option[ContractBody],
    responses: Seq[ContractResponse],
    // API-4: the credentials the contract asks for, as alternatives of scheme families (`apiKey`,
    // `http:bearer`, `oauth2`…). None: it says nothing; an empty list: public on purpose
    security: Option[Seq[Set[String]]] = None
) {

  val segments: Seq[String] = ContractPaths.split(path)
  val literals: Int         = segments.count(s => !ContractPaths.isParam(s))
  val queryNames: Set[String] = params.filter(_.in == "query").map(_.name).toSet

  /** The path parameters, decoded, when `parts` is this operation's path. */
  def matchPath(parts: Seq[String]): Option[Map[String, String]] =
    if (parts.size != segments.size) None
    else
      segments.zip(parts).foldLeft(Option(Map.empty[String, String])) {
        case (None, _)                                              => None
        case (Some(acc), (seg, part)) if ContractPaths.isParam(seg) => Some(acc + (seg.drop(1).dropRight(1) -> ContractPaths.decode(part)))
        case (Some(acc), (seg, part))                               => Option.when(seg == part || seg == ContractPaths.decode(part))(acc)
      }

  /** The exact status first, then its class (`2XX`), then `default`. */
  def responseFor(status: Int): Option[ContractResponse] =
    responses
      .find(_.code == status.toString)
      .orElse(responses.find(_.code.equalsIgnoreCase(s"${status / 100}XX")))
      .orElse(responses.find(_.code == "default"))

  def summary: JsValue = Json.obj(
    "method"       -> method,
    "path"         -> path,
    "operation_id" -> operationId,
    "parameters"   -> params.map(p => s"${p.in} ${p.name}${if (p.required) "" else "?"}"),
    "body"         -> body.map(b => Json.obj("required" -> b.required, "media" -> b.media.map(_.range))),
    "responses"    -> responses.map(_.code),
    "security"     -> security.map(alternatives => JsArray(alternatives.map(a => JsArray(a.toSeq.sorted.map(JsString.apply)))))
  )
}

/** A request matched to the operation it calls, and its path parameters. */
final case class ContractMatch(operation: ContractOperation, params: Map[String, String]) {

  /** The object the request is about, for the object guard: its path parameters, in order. */
  def objectId: Option[String] = {
    val ids = operation.segments.filter(ContractPaths.isParam).flatMap(s => params.get(s.drop(1).dropRight(1)))
    Option.when(ids.nonEmpty)(ids.mkString("/"))
  }
}

object ContractPaths {

  def split(path: String): Seq[String] = path.takeWhile(_ != '?').split('/').toSeq.filter(_.nonEmpty)

  def isParam(seg: String): Boolean = seg.length > 2 && seg.startsWith("{") && seg.endsWith("}")

  /** A path segment's percent-encoding undone, a `+` kept as itself. */
  def decode(segment: String): String =
    if (!segment.contains('%')) segment else Try(URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)).getOrElse(segment)
}

/**
 * An OpenAPI 3 document, compiled into what a request is checked against (API-1).
 *
 * Every schema is compiled once, here, by the JSON schema dialect of the document's version: 3.0
 * with its `nullable` and draft 4 numbers, 3.1 as JSON Schema 2020-12. Formats are asserted, not
 * only annotated. Nothing is fetched: a reference outside the document is reported and its schema
 * checks nothing.
 */
final class CompiledContract(
    val version: String,
    val basePath: String,
    val operations: Seq[ContractOperation],
    val warnings: Seq[String],
    // follows a schema's local reference within the document, for what reads a schema by hand
    val resolve: JsValue => JsValue = identity
) {

  private val byPath: Seq[(String, Seq[ContractOperation])] =
    operations.groupBy(_.path).toSeq.sortBy { case (_, ops) => -ops.head.literals }

  /**
   * The operation a request calls. `None` for a preflight the contract does not declare: it is the
   * browser asking, not the client calling.
   */
  def resolve(method: String, rawPath: String): Either[Violation, Option[ContractMatch]] = {
    val path     = rawPath.takeWhile(_ != '?')
    val m        = method.toUpperCase
    val relative =
      if (basePath.isEmpty) Some(path)
      else if (path == basePath || path == basePath + "/") Some("/")
      else Option.when(path.startsWith(basePath + "/"))(path.drop(basePath.length))
    relative.map(ContractPaths.split) match {
      case None        => Left(Violation("unknown_path", path, s"outside the contract's base path $basePath"))
      case Some(parts) =>
        val candidates = byPath.flatMap { case (_, ops) => ops.head.matchPath(parts).map(params => (ops, params)) }
        val wanted     = if (m == "HEAD") Seq("HEAD", "GET") else Seq(m)
        candidates.iterator
          .flatMap { case (ops, params) => wanted.iterator.flatMap(w => ops.find(_.method == w)).map(op => ContractMatch(op, params)) }
          .nextOption() match {
          case Some(found)              => Right(Some(found))
          case None if candidates.isEmpty => Left(Violation("unknown_path", path, "no operation of the contract has this path"))
          case None if m == "OPTIONS"   => Right(None)
          case None                     =>
            Left(Violation("method_not_allowed", s"$m $path", s"allowed: ${candidates.head._1.map(_.method).mkString(", ")}"))
        }
    }
  }

  /** What the methods allowed on a path are, for the `Allow` of a 405. */
  def allowed(rawPath: String): Seq[String] = {
    val path  = rawPath.takeWhile(_ != '?')
    val parts = ContractPaths.split(if (basePath.nonEmpty && path.startsWith(basePath)) path.drop(basePath.length) else path)
    byPath.collectFirst { case (_, ops) if ops.head.matchPath(parts).isDefined => ops.map(_.method) }.getOrElse(Seq.empty)
  }

  def checkParameters(
      m: ContractMatch,
      query: Map[String, Seq[String]],
      headers: Map[String, String],
      rejectUnknownQuery: Boolean
  ): Seq[Violation] = {
    val declared = m.operation.params.flatMap { p =>
      val values: Seq[String] = p.in match {
        case "path"   => m.params.get(p.name).toSeq
        case "query"  => query.getOrElse(p.name, Seq.empty)
        case "header" => headers.get(p.name.toLowerCase).toSeq
        case _        => Seq.empty
      }
      if (p.in == "cookie") Seq.empty
      else if (values.isEmpty) {
        if (p.required) Seq(Violation("missing_parameter", s"${p.in} ${p.name}", "required")) else Seq.empty
      } else p.schema.toSeq.flatMap(_.check(p.coerce(values))).map(msg => Violation("invalid_parameter", s"${p.in} ${p.name}", msg))
    }
    val unknown  =
      if (!rejectUnknownQuery) Seq.empty
      else query.keys.toSeq.sorted.filterNot(m.operation.queryNames.contains).map(n => Violation("unknown_parameter", s"query $n", "not in the contract"))
    declared ++ unknown
  }

  /**
   * Whether the body is acceptable as far as its presence and media type go, and the media type it
   * is then checked against, when that one is JSON with a schema.
   */
  def bodyMedia(m: ContractMatch, contentType: Option[String], hasBody: Boolean): Either[Violation, Option[ContractMedia]] =
    m.operation.body match {
      case None                                => Right(None)
      case Some(b) if !hasBody                 =>
        if (b.required) Left(Violation("missing_body", s"${m.operation.method} ${m.operation.path}", "a body is required")) else Right(None)
      case Some(b) if b.media.isEmpty          => Right(None)
      case Some(b)                             =>
        val ct = contentType.map(MediaType.of).filter(_.nonEmpty).getOrElse("application/octet-stream")
        ContractMedia.find(b.media, ct) match {
          case None        => Left(Violation("unsupported_media_type", ct, s"expected ${b.media.map(_.range).mkString(", ")}"))
          case Some(media) => Right(Option.when(media.checks(ct))(media))
        }
    }

  def checkBody(media: ContractMedia, json: String): Seq[Violation] =
    media.schema.toSeq.flatMap(_.check(json)).map(Violation("invalid_body", "body", _))

  /** Whether the status and the media type of a response are declared, and what its body is checked against. */
  def responseMedia(m: ContractMatch, status: Int, contentType: Option[String], hasBody: Boolean): Either[Violation, Option[ContractMedia]] = {
    val op = m.operation
    if (op.responses.isEmpty) Right(None)
    else
      op.responseFor(status) match {
        case None                                         => Left(Violation("undeclared_status", s"${op.method} ${op.path}", s"$status is not a declared response"))
        case Some(r) if !hasBody || r.media.isEmpty       => Right(None)
        case Some(r)                                      =>
          val ct = contentType.map(MediaType.of).filter(_.nonEmpty).getOrElse("application/octet-stream")
          ContractMedia.find(r.media, ct) match {
            case None        => Left(Violation("undeclared_media_type", s"${op.method} ${op.path} $status", s"$ct, expected ${r.media.map(_.range).mkString(", ")}"))
            case Some(media) => Right(Option.when(media.checks(ct))(media))
          }
      }
  }

  def checkResponseBody(m: ContractMatch, status: Int, media: ContractMedia, json: String): Seq[Violation] =
    media.schema.toSeq.flatMap(_.check(json)).map(Violation("invalid_response", s"${m.operation.method} ${m.operation.path} $status", _))

  def summary: JsValue = Json.obj(
    "version"    -> version,
    "base_path"  -> basePath,
    "operations" -> operations.map(_.summary),
    "warnings"   -> warnings
  )
}

object ContractCompiler {

  private val methods = Seq("get", "put", "post", "delete", "options", "head", "patch", "trace")

  // a header parameter by these names is ignored, the specification says: they are the transport's
  private val transportHeaders = Set("accept", "content-type", "authorization")

  /** JSON or YAML, into a document. */
  def parse(spec: String): Either[String, JsObject] = {
    val text = spec.trim
    if (text.isEmpty) Left("the contract is empty")
    else
      Try {
        if (text.startsWith("{")) Json.parse(text)
        else {
          val yaml = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
          Json.parse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(yaml.readTree(text)))
        }
      }.toEither.left.map(e => s"neither JSON nor YAML: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName).linesIterator.nextOption().getOrElse("")}").flatMap {
        case o: JsObject => Right(o)
        case _           => Left("the contract is not an object")
      }
  }

  def compile(spec: String, basePath: String = ""): Either[String, CompiledContract] = parse(spec).flatMap(compile(_, basePath))

  def compile(doc: JsObject, basePathOverride: String): Either[String, CompiledContract] =
    (doc \ "openapi").asOpt[String] match {
      case None if (doc \ "swagger").isDefined => Left("a Swagger 2 document: convert it to OpenAPI 3 first")
      case None                                => Left("not an OpenAPI document: it has no openapi version")
      case Some(v) if !v.startsWith("3.")      => Left(s"OpenAPI $v is not supported, only 3.0 and 3.1")
      case Some(version)                       => Right(new Compilation(doc, version, basePathOverride).result())
    }

  /** A local reference, `#/components/schemas/Pet`, followed into the document. */
  def pointer(doc: JsValue, ref: String): Option[JsValue] =
    if (!ref.startsWith("#")) None
    else {
      val tokens = ref.drop(1).split('/').toSeq.filter(_.nonEmpty).map(t => ContractPaths.decode(t.replace("~1", "/").replace("~0", "~")))
      tokens.foldLeft(Option(doc)) {
        case (Some(o: JsObject), t) => o.value.get(t)
        case (Some(a: JsArray), t)  => t.toIntOption.flatMap(i => a.value.lift(i))
        case _                      => None
      }
    }

  /** Follows local references within `doc`, a few levels deep at most; an unresolvable one is an empty schema. */
  def resolver(doc: JsValue): JsValue => JsValue = {
    def follow(node: JsValue, depth: Int): JsValue = node match {
      case o: JsObject if depth < 16 =>
        (o \ "$ref").asOpt[String] match {
          case None      => o
          case Some(ref) => pointer(doc, ref).map(follow(_, depth + 1)).getOrElse(JsObject.empty)
        }
      case other                     => other
    }
    follow(_, 0)
  }

  /** The path of the first server, when it has one: what the contract's paths are relative to. */
  def serverPath(doc: JsObject): String =
    (doc \ "servers" \ 0 \ "url").asOpt[String].map(_.trim).filterNot(_.contains("{")).flatMap { url =>
      if (url.startsWith("/")) Some(url) else Try(Option(new URI(url).getPath)).toOption.flatten
    }.map(_.stripSuffix("/")).filter(_.nonEmpty).getOrElse("")

  private final class Compilation(doc: JsObject, version: String, basePathOverride: String) {

    private val warnings = ListBuffer.empty[String]
    private val pending  = ListBuffer.empty[JsValue]
    private val types    = ListBuffer.empty[(Seq[String], Seq[String])]

    private def resolve(node: JsValue, depth: Int = 0): JsValue = node match {
      case o: JsObject if depth < 16 =>
        (o \ "$ref").asOpt[String] match {
          case None      => o
          case Some(ref) =>
            pointer(doc, ref) match {
              case Some(target) => resolve(target, depth + 1)
              case None         =>
                warnings += s"$ref cannot be resolved within the document"
                JsObject.empty
            }
        }
      case other                     => other
    }

    /** The types a schema's values are, looking through references and compositions. */
    private def typesOf(node: JsValue, depth: Int = 0): Seq[String] = resolve(node) match {
      case o: JsObject if depth < 8 =>
        val own = (o \ "type").asOpt[String].toSeq ++ (o \ "type").asOpt[Seq[String]].getOrElse(Seq.empty)
        if (own.nonEmpty) own.filterNot(_ == "null") ++ own.filter(_ == "null")
        else Seq("allOf", "oneOf", "anyOf").flatMap(k => (o \ k).asOpt[Seq[JsValue]].getOrElse(Seq.empty)).flatMap(typesOf(_, depth + 1)).distinct
      case _                        => Seq.empty
    }

    /** Registers a schema to compile once the bundle is complete; returns where its compiled form lands. */
    private def schema(node: JsValue): Int = {
      pending += node
      val itemTypes = (resolve(node) \ "items").toOption.map(typesOf(_)).getOrElse(Seq.empty)
      types += ((typesOf(node), itemTypes))
      pending.size - 1
    }

    // what each security scheme of the contract is, as the family a deployed plugin is compared with
    private lazy val schemes: Map[String, String] =
      (doc \ "components" \ "securitySchemes").asOpt[JsObject].map(_.value.toMap).getOrElse(Map.empty).map { case (name, raw) =>
        val s = resolve(raw)
        name -> ((s \ "type").asOpt[String].getOrElse("") match {
          case "http" => s"http:${(s \ "scheme").asOpt[String].getOrElse("").toLowerCase}"
          case other  => other
        })
      }

    private def security(node: JsValue): Option[Seq[Set[String]]] =
      node.asOpt[Seq[JsObject]].map(_.map(requirement => requirement.keys.toSet.map(name => schemes.getOrElse(name, name))))

    private def media(content: JsValue): Seq[(String, Option[Int])] =
      content.asOpt[JsObject].map(_.value.toSeq).getOrElse(Seq.empty).map { case (range, m) =>
        (MediaType.of(range), (resolve(m) \ "schema").toOption.map(schema))
      }

    def result(): CompiledContract = {
      val securities = ListBuffer.empty[Option[Seq[Set[String]]]]
      val draft = ListBuffer.empty[(String, String, Option[String], Seq[(String, String, Boolean, Option[Int], Boolean)], Option[(Boolean, Seq[(String, Option[Int])])], Seq[(String, Seq[(String, Option[Int])])])]
      (doc \ "paths").asOpt[JsObject].map(_.value.toSeq).getOrElse(Seq.empty).foreach { case (path, rawItem) =>
        val item       = resolve(rawItem)
        val pathParams = (item \ "parameters").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(resolve(_))
        methods.foreach { method =>
          (item \ method).toOption.map(resolve(_)).foreach { op =>
            val opParams = (op \ "parameters").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(resolve(_))
            def key(p: JsValue) = ((p \ "name").asOpt[String].getOrElse(""), (p \ "in").asOpt[String].getOrElse(""))
            val merged = (pathParams.filterNot(p => opParams.exists(o => key(o) == key(p))) ++ opParams).filter(p => key(p)._1.nonEmpty)
            val params = merged
              .filterNot(p => key(p)._2 == "header" && transportHeaders.contains(key(p)._1.toLowerCase))
              .map { p =>
                val (name, in) = key(p)
                val style      = (p \ "style").asOpt[String].getOrElse(if (in == "query" || in == "cookie") "form" else "simple")
                (name, in, in == "path" || (p \ "required").asOpt[Boolean].getOrElse(false), (p \ "schema").toOption.map(schema), (p \ "explode").asOpt[Boolean].getOrElse(style == "form"))
              }
            val body = (op \ "requestBody").toOption.map(resolve(_)).map { b =>
              ((b \ "required").asOpt[Boolean].getOrElse(false), media((b \ "content").getOrElse(JsObject.empty)))
            }
            val responses = (op \ "responses").asOpt[JsObject].map(_.value.toSeq).getOrElse(Seq.empty).map { case (code, r) =>
              (code, media((resolve(r) \ "content").getOrElse(JsObject.empty)))
            }
            draft += ((method.toUpperCase, path, (op \ "operationId").asOpt[String], params, body, responses))
            securities += ((op \ "security").toOption.flatMap(security).orElse((doc \ "security").toOption.flatMap(security)))
          }
        }
      }
      val compiled = compileAll()
      def at(i: Option[Int]): Option[ContractSchema] = i.map(n => new ContractSchema(pending(n), types(n)._1, types(n)._2, compiled(n)))
      val operations = draft.toSeq.zip(securities).map { case ((method, path, operationId, params, body, responses), security) =>
        ContractOperation(
          method = method,
          path = path,
          operationId = operationId,
          params = params.map { case (name, in, required, s, explode) => ContractParam(name, in, required, at(s), explode) },
          body = body.map { case (required, ms) => ContractBody(required, ms.map { case (range, s) => ContractMedia(range, at(s)) }) },
          responses = responses.map { case (code, ms) => ContractResponse(code, ms.map { case (range, s) => ContractMedia(range, at(s)) }) },
          security = security
        )
      }
      if (operations.isEmpty) warnings += "the contract declares no operation: every request is outside it"
      val basePath = Option(basePathOverride.trim).filter(_.nonEmpty).map(p => ("/" + p.stripPrefix("/")).stripSuffix("/")).getOrElse(serverPath(doc))
      new CompiledContract(version, basePath, operations, warnings.distinct.toSeq, resolver(doc))
    }

    /**
     * Every schema of the contract, in one document beside the contract's own, so that a reference
     * anywhere in it resolves the way it does in the contract, and the components exist once.
     */
    private def compileAll(): IndexedSeq[Option[Schema]] =
      if (pending.isEmpty) IndexedSeq.empty
      else {
        val iri      = s"https://contracts.cloud-apim.local/${java.util.UUID.randomUUID()}.json"
        val bundle   = doc ++ Json.obj("x-cloud-apim-schemas" -> JsObject(pending.zipWithIndex.map { case (s, i) => s"s$i" -> s }))
        val dialect  = if (version.startsWith("3.1")) Dialects.getOpenApi31 else Dialects.getOpenApi30
        // messages in English whatever the JVM's locale: they end up in events and in refusals
        val config   = SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER).formatAssertionsEnabled(true).locale(java.util.Locale.ENGLISH).build()
        val registry = SchemaRegistry.withDefaultDialect(
          dialect,
          b => { b.schemaRegistryConfig(config).schemas(java.util.Map.of(iri, Json.stringify(bundle))); () }
        )
        pending.indices.map { i =>
          Try {
            val s = registry.getSchema(SchemaLocation.of(s"$iri#/x-cloud-apim-schemas/s$i"))
            s.initializeValidators()
            s
          }.fold(
            e => {
              warnings += s"a schema cannot be compiled: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
              None
            },
            Some(_)
          )
        }
      }
  }
}
