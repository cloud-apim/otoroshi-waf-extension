package com.cloud.apim.otoroshi.extensions.waf.studio

import otoroshi.next.models.NgRoute
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimSecuritySuiteGlobalRule
import play.api.libs.json.*

import scala.annotation.tailrec

/** One kind of the suite's entities, as a workspace sees it. */
final case class StudioKind(plural: String, presetField: Option[String] = None) {
  def referenceable: Boolean = StudioKind.referenceable.exists(_.plural == plural)
}

object StudioKind {

  val Group = "waf.extensions.cloud-apim.com"

  /** What a preset reaches, directly or through another entity: what a workspace may own. */
  val referenceable: Seq[StudioKind] = Seq(
    StudioKind("waf-configs", Some("waf_config")),
    StudioKind("waf-rulesets"),
    StudioKind("threat-policies", Some("threat_policy")),
    StudioKind("challenge-providers"),
    StudioKind("bot-policies", Some("bot_policy")),
    StudioKind("malware-scanners", Some("uploads_scanner")),
    StudioKind("api-contracts", Some("api_contract_id"))
  )

  /** What no preset reaches: the gateway's, and its administrators'. */
  val global: Seq[StudioKind] = Seq(
    StudioKind("threat-feeds"),
    StudioKind("asn-databases"),
    StudioKind("geo-databases"),
    StudioKind("crowdsec-bouncers"),
    StudioKind("honeypot-policies"),
    StudioKind("rule-feeds"),
    StudioKind("alert-rules")
  )

  val all: Seq[StudioKind] = referenceable ++ global

  def of(plural: String): Option[StudioKind] = all.find(_.plural == plural)
}

/** Something that names an entity: a rule of the table, a route, or another entity. */
final case class Referencer(kind: String, id: String) {
  def json: JsValue = Json.obj("kind" -> kind, "id" -> id)
}

object Referencer {
  val Rule   = "rule"
  val Route  = "route"
  val Entity = "entity"
}

/**
 * Who names what among the suite's entities, and so which workspace each one belongs to.
 *
 * A reference is an entity id found anywhere in a preset, in a route's plugins or metadata, or in
 * another entity's fields. Ids are prefixed by kind and random, so finding one is naming it, and a
 * field the suite adds later is covered without listing it here. An entity's own metadata is left
 * out: it describes the entity, it does not use another one (the rulesets the tuning assistant
 * writes name their config there).
 *
 * An entity belongs to a workspace when it carries the workspace's mark and everything that names it
 * is inside that workspace: its rule, the routes it claims, and the entities that belong to it. That
 * last condition is circular, so it is computed down from every marked entity until nothing changes.
 * Nothing is stored: who owns what is read off the state of the moment, every time.
 */
final class EntityGraph(
    entities: Seq[(StudioKind, JsObject)],
    rules: Seq[CloudApimSecuritySuiteGlobalRule],
    routes: Seq[NgRoute],
    claimsOf: String => Set[String]
) {

  import EntityGraph.*

  private val byId: Map[String, (StudioKind, JsObject)] = entities.map { case (k, e) => idOf(e) -> (k, e) }.toMap
  private val ids: Set[String]                          = byId.keySet

  private def named(json: JsValue): Set[String] = strings(json).filter(ids.contains).toSet

  val referencers: Map[String, Seq[Referencer]] = {
    val fromRules    = rules.flatMap(r => named(r.preset.json).map(_ -> Referencer(Referencer.Rule, r.id)))
    val fromRoutes   = routes.flatMap { r =>
      named(Json.obj("plugins" -> r.plugins.json, "metadata" -> JsObject(r.metadata.map { case (k, v) => k -> JsString(v) })))
        .map(_ -> Referencer(Referencer.Route, r.id))
    }
    val fromEntities = entities.flatMap { case (_, e) =>
      val id = idOf(e)
      named(e - "metadata" - "id").filterNot(_ == id).map(_ -> Referencer(Referencer.Entity, id))
    }
    (fromRules ++ fromRoutes ++ fromEntities).groupBy(_._1).view.mapValues(_.map(_._2).distinct).toMap
  }

  def referencersOf(id: String): Seq[Referencer] = referencers.getOrElse(id, Seq.empty)

  /** Entity id to the workspace it belongs to. */
  val owners: Map[String, String] = {
    val marked = entities.collect {
      case (k, e) if k.referenceable && markOf(e).isDefined => (markOf(e).get, idOf(e))
    }
    marked.groupBy(_._1).toSeq.flatMap { case (ws, group) =>
      val claims = claimsOf(ws)
      @tailrec def settle(own: Set[String]): Set[String] = {
        val next = own.filter(id =>
          referencersOf(id).forall {
            case Referencer(Referencer.Rule, rule)     => rule == ws
            case Referencer(Referencer.Route, route)   => claims.contains(route)
            case Referencer(Referencer.Entity, entity) => own.contains(entity)
            case _                                     => false
          }
        )
        if (next == own) own else settle(next)
      }
      settle(group.map(_._2).toSet).toSeq.map(_ -> ws)
    }.toMap
  }

  def ownerOf(id: String): Option[String] = owners.get(id)

  def owns(ws: String, id: String): Boolean = owners.get(id).contains(ws)

  /** Whether something inside the workspace names the entity. */
  def usedBy(ws: String, id: String): Boolean = {
    val claims = claimsOf(ws)
    referencersOf(id).exists {
      case Referencer(Referencer.Rule, rule)     => rule == ws
      case Referencer(Referencer.Route, route)   => claims.contains(route)
      case Referencer(Referencer.Entity, entity) => owns(ws, entity)
      case _                                     => false
    }
  }

  /**
   * What a workspace sees of a kind: what belongs to it, and what belongs to nobody. An entity that
   * belongs to another workspace does not exist for it. A contract describes one api and may be
   * confidential, so of the shared ones a workspace sees only those it already uses.
   */
  def visible(ws: String, kind: StudioKind, id: String): Boolean =
    kind.referenceable && kindOf(id).contains(kind) &&
    (owns(ws, id) || (ownerOf(id).isEmpty && (kind.plural != "api-contracts" || usedBy(ws, id))))

  def kindOf(id: String): Option[StudioKind] = byId.get(id).map(_._1)

  def entity(id: String): Option[JsObject] = byId.get(id).map(_._2)

  def usage(ws: String, id: String): JsObject = {
    val claims = claimsOf(ws)
    val (here, elsewhere) = referencersOf(id).partition {
      case Referencer(Referencer.Rule, rule)     => rule == ws
      case Referencer(Referencer.Route, route)   => claims.contains(route)
      case Referencer(Referencer.Entity, entity) => owns(ws, entity)
      case _                                     => false
    }
    Json.obj("here" -> here.size, "elsewhere" -> elsewhere.size)
  }
}

object EntityGraph {

  val Mark     = "threat_studio_workspace"
  val KindMark = "threat_studio_kind"

  def idOf(entity: JsValue): String = entity.select("id").asOptString.getOrElse("")

  def markOf(entity: JsValue): Option[String] = entity.select("metadata").select(Mark).asOptString.filter(_.nonEmpty)

  def strings(json: JsValue): Iterator[String] = json match {
    case JsString(s)     => Iterator.single(s)
    case JsArray(values) => values.iterator.flatMap(strings)
    case JsObject(fields) => fields.valuesIterator.flatMap(strings)
    case _               => Iterator.empty
  }
}

/**
 * A secret the studio was shown masked comes back as this sentinel, and stands for the value already
 * stored. In a url, it stands for the stored value of the query parameter it replaces: a feed's or a
 * geolocation database's licence key often travels that way.
 */
object SecretSentinel {

  val Value = "__threat_studio_secret__"

  def unmasked(form: JsValue, current: JsValue): JsValue = (form, current) match {
    case (JsString(Value), cur)                          => cur
    case (JsString(s), JsString(cur)) if s.contains(Value) => JsString(unmaskedUrl(s, cur))
    case (JsObject(fields), cur)                         =>
      JsObject(fields.flatMap { case (k, v) =>
        (v, cur.select(k).asOpt[JsValue]) match {
          case (JsString(Value), None) => None
          case (_, Some(c))            => Some(k -> unmasked(v, c))
          case _                       => Some(k -> v)
        }
      })
    case (JsArray(values), JsArray(cur))                 =>
      JsArray(values.zipWithIndex.map { case (v, i) => cur.lift(i).map(c => unmasked(v, c)).getOrElse(v) })
    case _                                               => form
  }

  private def params(url: String): Seq[(String, String)] =
    url.split('?').lift(1).toSeq.flatMap(_.split('&')).filter(_.nonEmpty).map { p =>
      val i = p.indexOf('=')
      if (i < 0) p -> "" else p.take(i) -> p.drop(i + 1)
    }

  /** Each parameter that carries the sentinel takes the value it has in the stored url. */
  def unmaskedUrl(masked: String, current: String): String = {
    val stored = params(current).toMap
    masked.split('?').toList match {
      case base :: query :: Nil =>
        val restored = params(s"?$query").map { case (k, v) => if (v == Value) s"$k=${stored.getOrElse(k, "")}" else s"$k=$v" }
        s"$base?${restored.mkString("&")}"
      case _                    => if (masked == Value) current else masked
    }
  }
}
