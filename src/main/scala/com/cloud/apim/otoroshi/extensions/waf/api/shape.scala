package com.cloud.apim.otoroshi.extensions.waf.api

import play.api.libs.json.*

import scala.collection.mutable.ListBuffer

/**
 * Where an observed payload departs from its declared shape (API-3).
 *
 * `where` is a path such as `$.owner.ssn` or `$.items[].price`, array elements folded together, so
 * that the same departure seen on a thousand responses is one finding. `sensitive` says the field's
 * name is one a response should think twice about carrying.
 */
final case class DriftFinding(kind: String, where: String, observed: String, sensitive: Boolean = false)

/**
 * A payload against its schema, by hand rather than by a validator: a validator says whether the
 * payload is allowed, and a schema that does not forbid additional properties allows any. This says
 * what is there that the contract never mentions, which is what drifts.
 *
 * Compositions are read generously: the properties of every member of `allOf`, `oneOf` and `anyOf`
 * count as declared, so a payload is never reported for matching one branch rather than another.
 */
object ShapeDiff {

  private val words  = Set("password", "passwd", "pwd", "secret", "token", "ssn", "iban", "cvv", "cvc", "pan", "salary", "dob", "birthdate", "birthday", "tax", "private")
  private val joined = Seq("apikey", "creditcard", "cardnumber", "nationalid", "socialsecurity", "accesstoken", "refreshtoken", "privatekey")

  /** By the words of the name, `passwordHash` and `card_number` alike, so that `company` is not a `pan`. */
  def sensitive(name: String): Boolean = {
    val tokens = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase.split("[^a-z0-9]+").toSeq.filter(_.nonEmpty)
    tokens.exists(words.contains) || joined.exists(tokens.mkString.contains)
  }

  def diff(value: JsValue, schema: JsValue, resolve: JsValue => JsValue, max: Int = 20): Seq[DriftFinding] = {
    val out = ListBuffer.empty[DriftFinding]
    walk(value, schema, resolve, "$", 0, out, max)
    out.distinct.toSeq
  }

  private def typeOf(v: JsValue): String = v match {
    case _: JsObject                    => "object"
    case _: JsArray                     => "array"
    case JsNull                         => "null"
    case _: JsString                    => "string"
    case JsNumber(n) if n.isWhole       => "integer"
    case _: JsNumber                    => "number"
    case _: JsBoolean                   => "boolean"
  }

  /** The schema with its references followed and its compositions folded in, as far as shape goes. */
  private final case class Shape(types: Set[String], nullable: Boolean, properties: Map[String, JsValue], additional: Option[JsValue], items: Option[JsValue])

  private def shapeOf(schema: JsValue, resolve: JsValue => JsValue, depth: Int = 0): Shape = resolve(schema) match {
    case o: JsObject if depth < 6 =>
      val own     = (o \ "type").asOpt[String].toSet ++ (o \ "type").asOpt[Seq[String]].getOrElse(Seq.empty).toSet
      val members = Seq("allOf", "oneOf", "anyOf").flatMap(k => (o \ k).asOpt[Seq[JsValue]].getOrElse(Seq.empty)).map(shapeOf(_, resolve, depth + 1))
      Shape(
        types = own ++ (if (own.isEmpty) members.flatMap(_.types) else Set.empty),
        nullable = (o \ "nullable").asOpt[Boolean].getOrElse(false) || members.exists(_.nullable),
        properties = members.flatMap(_.properties).toMap ++ (o \ "properties").asOpt[JsObject].map(_.value.toMap).getOrElse(Map.empty),
        additional = (o \ "additionalProperties").toOption.orElse(members.flatMap(_.additional).headOption),
        items = (o \ "items").toOption.orElse(members.flatMap(_.items).headOption)
      )
    case _                        => Shape(Set.empty, nullable = false, Map.empty, None, None)
  }

  private def fits(observed: String, shape: Shape): Boolean =
    shape.types.isEmpty || shape.types.contains(observed) ||
      (observed == "integer" && shape.types.contains("number")) ||
      (observed == "null" && shape.nullable)

  private def walk(value: JsValue, schema: JsValue, resolve: JsValue => JsValue, where: String, depth: Int, out: ListBuffer[DriftFinding], max: Int): Unit =
    if (out.size < max && depth <= 10) {
      val shape    = shapeOf(schema, resolve)
      val observed = typeOf(value)
      if (!fits(observed, shape)) out += DriftFinding("type_mismatch", where, s"$observed, declared ${shape.types.toSeq.sorted.mkString(" or ")}")
      else
        value match {
          case o: JsObject =>
            o.value.foreach { case (name, field) =>
              val at = s"$where.$name"
              shape.properties.get(name) match {
                case Some(declared) => walk(field, declared, resolve, at, depth + 1, out, max)
                case None           =>
                  shape.additional match {
                    case Some(map: JsObject)                    => walk(field, map, resolve, s"$where.*", depth + 1, out, max)
                    case _ if shape.properties.nonEmpty || shape.additional.contains(JsBoolean(false)) =>
                      if (out.size < max) out += DriftFinding("undeclared_field", at, typeOf(field), sensitive(name))
                    case _                                      => ()
                  }
              }
            }
          case a: JsArray  => shape.items.foreach(items => a.value.take(3).foreach(walk(_, items, resolve, s"$where[]", depth + 1, out, max)))
          case _           => ()
        }
    }
}
