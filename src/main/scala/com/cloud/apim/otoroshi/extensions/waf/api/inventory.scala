package com.cloud.apim.otoroshi.extensions.waf.api

import com.cloud.apim.otoroshi.extensions.waf.objects.ObjectPaths
import com.cloud.apim.otoroshi.extensions.waf.security.SharedStateStore
import play.api.libs.json.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** How often an endpoint was seen, when first and last, and what was answered, by status class. */
final case class Sighting(
    hits: Long = 0L,
    first: Long = 0L,
    last: Long = 0L,
    // index 0 counts what the gateway refused itself, 1 to 5 the backend's 1xx to 5xx
    statuses: Vector[Long] = Vector.fill(6)(0L),
    observed: String = "",
    sensitive: Boolean = false
) {
  def json: JsValue = Json.obj("hits" -> hits, "first" -> first, "last" -> last, "statuses" -> statuses, "observed" -> observed, "sensitive" -> sensitive)

  def merge(o: Sighting): Sighting = Sighting(
    hits = hits + o.hits,
    first = Seq(first, o.first).filter(_ > 0L).minOption.getOrElse(0L),
    last = math.max(last, o.last),
    statuses = statuses.zipAll(o.statuses, 0L, 0L).map { case (a, b) => a + b },
    observed = if (o.last >= last && o.observed.nonEmpty) o.observed else observed,
    sensitive = sensitive || o.sensitive
  )

  def statusesJson: JsObject = Json.obj(
    "refused" -> statuses.lift(0).getOrElse(0L),
    "1xx"     -> statuses.lift(1).getOrElse(0L),
    "2xx"     -> statuses.lift(2).getOrElse(0L),
    "3xx"     -> statuses.lift(3).getOrElse(0L),
    "4xx"     -> statuses.lift(4).getOrElse(0L),
    "5xx"     -> statuses.lift(5).getOrElse(0L)
  )
}

object Sighting {
  def read(json: JsValue): Sighting = Sighting(
    hits = (json \ "hits").asOpt[Long].getOrElse(0L),
    first = (json \ "first").asOpt[Long].getOrElse(0L),
    last = (json \ "last").asOpt[Long].getOrElse(0L),
    statuses = (json \ "statuses").asOpt[Vector[Long]].getOrElse(Vector.fill(6)(0L)).padTo(6, 0L),
    observed = (json \ "observed").asOpt[String].getOrElse(""),
    sensitive = (json \ "sensitive").asOpt[Boolean].getOrElse(false)
  )
}

/**
 * What traffic the API contracts see, cluster-wide and over months (API-2, API-3).
 *
 * Each node counts on its own, in memory, and publishes what changed to a hash of its own in the
 * shared store, kept for six months; a report merges the hashes of every node there is. Three kinds
 * of record:
 *
 *  - `op`: an operation of a contract, with its hits and the statuses its backend answered
 *  - `shadow`: a path no contract has, its identifiers folded into `{id}`, and what was answered —
 *    a backend answering 2xx on it is an endpoint nobody documented
 *  - `drift`: a field, a type or a status the contract does not declare, seen on real traffic
 *
 * The number of records is bounded per node, and shadows per route, so that a scanner walking random
 * paths fills a bucket rather than the store.
 */
final class ApiInventory(prefix: String, nodeId: String, store: SharedStateStore, maxRecords: Int = 20000, maxShadowsPerRoute: Int = 200) {

  private val records = new TrieMap[String, Sighting]()
  private val dirty   = TrieMap.empty[String, Unit]
  private val shadows = new TrieMap[String, Int]()

  def size: Int = records.size

  private def touch(key: String, now: Long)(f: Sighting => Sighting): Unit =
    if (records.contains(key) || records.size < maxRecords) {
      records.updateWith(key) { current =>
        val s = current.getOrElse(Sighting(first = now))
        Some(f(s.copy(last = math.max(s.last, now))))
      }
      dirty.put(key, ())
    }

  def operationKey(routeId: String, contractId: String, method: String, path: String): String = s"op|$routeId|$contractId|$method $path"

  /** A request matched an operation. */
  def operation(routeId: String, contractId: String, method: String, path: String, now: Long): String = {
    val key = operationKey(routeId, contractId, method, path)
    touch(key, now)(s => s.copy(hits = s.hits + 1))
    key
  }

  /** A request no operation of the contract has: what it is folded into, and counted. */
  def shadow(routeId: String, method: String, rawPath: String, now: Long): String = {
    val parts      = ObjectPaths.split(rawPath).take(8)
    val normalized = parts.map(p => if (ObjectPaths.idLike(p) || p.length > 32) "{id}" else p).mkString("/", "/", "")
    val candidate  = s"shadow|$routeId|${method.toUpperCase} $normalized"
    val key        =
      if (records.contains(candidate)) candidate
      else {
        val count = shadows.getOrElse(routeId, 0)
        if (count >= maxShadowsPerRoute) s"shadow|$routeId|${method.toUpperCase} (other paths)"
        else {
          shadows.put(routeId, count + 1)
          candidate
        }
      }
    touch(key, now)(s => s.copy(hits = s.hits + 1))
    key
  }

  /** What was answered on a counted request: 0 when the gateway refused it itself. */
  def status(key: String, status: Int): Unit = {
    val index = if (status <= 0) 0 else math.min(5, math.max(1, status / 100))
    records.get(key).foreach { _ =>
      records.updateWith(key)(_.map(s => s.copy(statuses = s.statuses.updated(index, s.statuses(index) + 1))))
      dirty.put(key, ())
    }
  }

  /** A departure from the contract, seen on real traffic. */
  def drift(routeId: String, contractId: String, method: String, path: String, finding: DriftFinding, now: Long): Unit =
    touch(s"drift|$routeId|$contractId|$method $path|${finding.kind}|${finding.where}", now)(s =>
      s.copy(hits = s.hits + 1, observed = finding.observed, sensitive = s.sensitive || finding.sensitive)
    )

  private def nodeKey: String = s"$prefix:$nodeId"

  /** Writes what changed since the last time to this node's hash. */
  def publish()(using ec: ExecutionContext): Future[Int] = {
    val keys = dirty.keys.toSeq
    keys.foreach(dirty.remove)
    if (keys.isEmpty) Future.successful(0)
    else
      Future
        .sequence(keys.flatMap(k => records.get(k).map(s => store.hset(nodeKey, k, Json.stringify(s.json)))))
        .flatMap(_ => store.pexpire(nodeKey, 180L * 24L * 3600L * 1000L))
        .map(_ => keys.size)
  }

  /** Every node's records, merged. */
  def merged()(using ec: ExecutionContext): Future[Map[String, Sighting]] =
    store.keys(s"$prefix:*").flatMap { hashes =>
      Future.sequence(hashes.map(store.hgetall)).map { all =>
        all.flatMap(_.toSeq).foldLeft(Map.empty[String, Sighting]) { case (acc, (k, raw)) =>
          Try(Sighting.read(Json.parse(raw))).toOption match {
            case Some(s) => acc.updated(k, acc.get(k).map(_.merge(s)).getOrElse(s))
            case None    => acc
          }
        }
      }
    }
}
