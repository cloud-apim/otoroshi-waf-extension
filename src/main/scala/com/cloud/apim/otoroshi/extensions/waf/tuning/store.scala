package com.cloud.apim.otoroshi.extensions.waf.tuning

import com.cloud.apim.seclang.model.MatchEvent
import otoroshi.security.IdGenerator
import play.api.libs.json.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** One rule objecting to one input, kept so it can be tuned rather than only counted. */
final case class TuningSample(
    id: String,
    at: Long,
    configRef: String,
    routeId: Option[String],
    routeName: Option[String],
    ruleId: Int,
    msg: Option[String],
    phase: Int,
    target: Option[MatchedTarget],
    matchedValue: Option[String],
    method: String,
    path: String,
    blocked: Boolean
) {
  /** What the assistant groups by: the same rule on the same input of the same endpoint. */
  def groupKey: String = s"$configRef|$ruleId|${target.map(_.full).getOrElse("-")}|$path"

  def json: JsValue = Json.obj(
    "id"            -> id,
    "at"            -> at,
    "config_ref"    -> configRef,
    "route_id"      -> routeId,
    "route_name"    -> routeName,
    "rule_id"       -> ruleId,
    "msg"           -> msg,
    "phase"         -> phase,
    "target"        -> target.map(_.json).getOrElse(JsNull).asInstanceOf[JsValue],
    "matched_value" -> matchedValue,
    "method"        -> method,
    "path"          -> path,
    "blocked"       -> blocked
  )
}

final case class TuningGroup(key: String, count: Int, lastSeen: Long, sample: TuningSample) {
  def json: JsValue = Json.obj("key" -> key, "count" -> count, "last_seen" -> lastSeen) ++
    sample.json.as[JsObject]
}

/**
 * The recent false-positive candidates, per node.
 *
 * Deliberately in memory and bounded. The tuning assistant needs *examples* — which rule, on which
 * input, with what value — not a census, and the census already exists in the analytics tables that
 * OPS-1 fills. Keeping a ring here means the assistant works on an install that has never
 * configured an exporter, which is exactly the install still deciding whether to arm the WAF.
 *
 * Two consequences said out loud rather than papered over: it holds one node's traffic, and it
 * forgets. Counts are "within what is retained", and the api says so.
 */
class TuningStore(val max: Int = 500) {

  private val samples = new ConcurrentLinkedQueue[TuningSample]()
  private val size    = new AtomicInteger(0)

  def record(sample: TuningSample): Unit = {
    samples.add(sample)
    if (size.incrementAndGet() > max) {
      // trim rather than block: this runs on the request path, and losing the oldest example is
      // never worse than adding latency to a live request
      if (samples.poll() != null) size.decrementAndGet()
    }
  }

  /**
   * Everything one engine result had to say about one request.
   *
   * Only rules that named an input are kept. A rule with no `logdata` cannot be tuned surgically,
   * and the anomaly-score and correlation rules that fire on every match would otherwise drown the
   * list in entries nobody can act on.
   */
  def recordAll(
      events: Seq[MatchEvent],
      configRef: String,
      routeId: Option[String],
      routeName: Option[String],
      method: String,
      path: String,
      blocked: Boolean
  ): Unit = {
    events.foreach { evt =>
      val target = MatchedTarget.fromLogs(evt.logs)
      evt.ruleId.foreach { rid =>
        if (target.exists(_.excludable)) {
          record(
            TuningSample(
              id = IdGenerator.uuid,
              at = System.currentTimeMillis(),
              configRef = configRef,
              routeId = routeId,
              routeName = routeName,
              ruleId = rid,
              msg = evt.msg.filter(_ != "--"),
              phase = evt.phase,
              target = target,
              matchedValue = MatchedTarget.valueFromLogs(evt.logs),
              method = method,
              path = path,
              blocked = blocked
            )
          )
        }
      }
    }
  }

  def all: Seq[TuningSample] = {
    val it  = samples.iterator()
    val buf = Seq.newBuilder[TuningSample]
    while (it.hasNext) buf += it.next()
    buf.result()
  }

  def sample(id: String): Option[TuningSample] = all.find(_.id == id)

  /** Newest first, and the most frequent within a timestamp. */
  def groups: Seq[TuningGroup] =
    all
      .groupBy(_.groupKey)
      .map { case (key, entries) =>
        val newest = entries.maxBy(_.at)
        TuningGroup(key, entries.size, newest.at, newest)
      }
      .toSeq
      .sortBy(g => (-g.lastSeen, -g.count))

  def clear(): Unit = { samples.clear(); size.set(0) }

  def status: JsValue = Json.obj("retained" -> size.get(), "max" -> max)
}
