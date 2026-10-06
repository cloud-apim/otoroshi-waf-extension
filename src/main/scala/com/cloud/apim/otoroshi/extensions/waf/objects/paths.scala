package com.cloud.apim.otoroshi.extensions.waf.objects

import play.api.libs.json.*

import scala.util.Try

/** One object a request touched: its kind, and which one. */
final case class ObjectRef(kind: String, id: String) {

  /** The identifier as a number, when its last part is one: what a sequential walk is seen on. */
  def number: Option[Long] =
    id.split('/').lastOption.filter(s => s.nonEmpty && s.length <= 18 && s.forall(_.isDigit)).flatMap(_.toLongOption)
}

/**
 * A declared object path (BEH-1).
 *
 * `/api/orders/{id}`: each `{name}` is one segment and part of the identifier, a trailing `*` is
 * anything after. The kind is the template's name, or the path itself; `methods` empty means all.
 * `budget`, when set, replaces the guard's own for this kind.
 */
final case class ObjectTemplate(path: String, name: String = "", methods: Seq[String] = Seq.empty, budget: Option[Long] = None) {

  private val segments = ObjectPaths.split(path)
  private val rest     = segments.lastOption.contains("*")
  private val fixed    = if (rest) segments.init else segments

  def kind: String = if (name.trim.nonEmpty) name.trim else path

  def json: JsValue = Json.obj("path" -> path, "name" -> name, "methods" -> methods, "budget" -> budget)

  def resolve(method: String, parts: Seq[String]): Option[ObjectRef] =
    if (methods.nonEmpty && !methods.exists(_.equalsIgnoreCase(method))) None
    else if (parts.size < fixed.size || (!rest && parts.size != fixed.size)) None
    else {
      val captured = fixed.zip(parts).foldLeft(Option(Vector.empty[String])) {
        case (None, _)                                            => None
        case (Some(ids), (seg, part)) if ObjectPaths.isParam(seg) => Some(ids :+ part)
        case (Some(ids), (seg, part))                             => Option.when(seg == part)(ids)
      }
      captured.filter(_.nonEmpty).map(ids => ObjectRef(kind, ids.mkString("/")))
    }
}

object ObjectTemplate {

  /** A plain string is a path; an object says more. */
  def read(json: JsValue): Option[ObjectTemplate] = json match {
    case JsString(path) if path.trim.nonEmpty => Some(ObjectTemplate(path.trim))
    case o: JsObject                          =>
      Try {
        ObjectTemplate(
          path = (o \ "path").as[String].trim,
          name = (o \ "name").asOpt[String].getOrElse(""),
          methods = (o \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim.toUpperCase).filter(_.nonEmpty),
          budget = (o \ "budget").asOpt[Long].filter(_ > 0L)
        )
      }.toOption.filter(_.path.nonEmpty)
    case _                                    => None
  }
}

/** Telling which object a request is about, from its path alone (BEH-1, until API-1 knows the contract). */
object ObjectPaths {

  def split(path: String): Seq[String] = path.takeWhile(_ != '?').split('/').toSeq.filter(_.nonEmpty)

  def isParam(seg: String): Boolean = seg.length > 2 && seg.startsWith("{") && seg.endsWith("}")

  private val Uuid     = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r
  private val ObjectId = "^[0-9a-fA-F]{24}$".r
  private val Ulid     = "^[0-9A-HJKMNP-TV-Z]{26}$".r

  /** A segment that names one object rather than a collection: a number, a UUID, an ObjectId, a ULID. */
  def idLike(seg: String): Boolean =
    (seg.nonEmpty && seg.length <= 18 && seg.forall(_.isDigit)) || Uuid.matches(seg) || ObjectId.matches(seg) || Ulid.matches(seg)

  /**
   * `/users/42/orders/7` is an object of kind `/users/{id}/orders/{id}`, identified by `42/7`. The
   * kind doubles as the template a contract would declare.
   */
  def auto(parts: Seq[String]): Option[ObjectRef] = {
    val ids = parts.filter(idLike)
    Option.when(ids.nonEmpty)(ObjectRef(parts.map(p => if (idLike(p)) "{id}" else p).mkString("/", "/", ""), ids.mkString("/")))
  }

  /**
   * Declared templates first, the first that matches; then the object the API contract says the
   * request is about, its path parameters; then, when on, what looks like an identifier.
   */
  def resolve(
      templates: Seq[ObjectTemplate],
      autoDetect: Boolean,
      method: String,
      path: String,
      contract: Option[ObjectRef] = None
  ): Option[(ObjectRef, Option[ObjectTemplate])] =
    if (method.equalsIgnoreCase("OPTIONS")) None
    else {
      val parts = split(path)
      templates.iterator.flatMap(t => t.resolve(method, parts).map(ref => (ref, Some(t)))).nextOption()
        .orElse(contract.map(ref => (ref, None)))
        .orElse(if (autoDetect) auto(parts).map(ref => (ref, None)) else None)
    }
}
