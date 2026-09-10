package com.cloud.apim.otoroshi.extensions.waf.tuning

import com.cloud.apim.otoroshi.extensions.waf.security.SharedStateStore
import com.cloud.apim.seclang.model.MatchEvent
import otoroshi.security.IdGenerator
import play.api.Logger
import play.api.libs.json.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

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

object TuningSample {
  val format: Format[TuningSample] = new Format[TuningSample] {
    override def writes(o: TuningSample): JsValue = o.json
    override def reads(json: JsValue): JsResult[TuningSample] = Try {
      TuningSample(
        id = (json \ "id").as[String],
        at = (json \ "at").asOpt[Long].getOrElse(0L),
        configRef = (json \ "config_ref").as[String],
        routeId = (json \ "route_id").asOpt[String],
        routeName = (json \ "route_name").asOpt[String],
        ruleId = (json \ "rule_id").as[Int],
        msg = (json \ "msg").asOpt[String],
        phase = (json \ "phase").asOpt[Int].getOrElse(0),
        target = (json \ "target" \ "full").asOpt[String].flatMap(MatchedTarget.parse),
        matchedValue = (json \ "matched_value").asOpt[String],
        method = (json \ "method").asOpt[String].getOrElse("GET"),
        path = (json \ "path").asOpt[String].getOrElse("/"),
        blocked = (json \ "blocked").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case scala.util.Success(v) => JsSuccess(v)
      case scala.util.Failure(e) => JsError(e.getMessage)
    }
  }
}

final case class TuningGroup(key: String, count: Int, lastSeen: Long, sample: TuningSample) {
  def json: JsValue = Json.obj("key" -> key, "count" -> count, "last_seen" -> lastSeen) ++
    sample.json.as[JsObject]
}

/**
 * The recent false-positive candidates, across the cluster.
 *
 * Matches happen on whichever node served the request, and the admin api is served by whichever node
 * the operator is talking to — on an Otoroshi leader/worker cluster those are never the same
 * machine. So a node-local buffer would fill on the workers and be read on the leader, and the page
 * would be permanently empty on exactly the deployments that need it most.
 *
 * Each node therefore keeps a cheap in-memory buffer on the request path and publishes a snapshot of
 * it to the shared state on a timer, under a field only it writes. Reads merge every node's field.
 * Whether that shared state actually spans the cluster depends on configuration — see
 * `security.redis-uri` — and the api says which it is rather than letting an empty page be read as
 * "no false positives".
 */
class TuningStore(
    prefix: String,
    store: SharedStateStore,
    nodeId: String,
    logger: Logger,
    val max: Int = 500
)(using ec: ExecutionContext) {

  /** Changes on restart, so a node coming back does not overwrite what it published before. */
  private val instanceId: String = s"$nodeId-${System.currentTimeMillis()}"

  /** Per node, so the merged list stays readable however many nodes there are. */
  private val published: Int = 100

  /** A candidate nobody acted on in a day is not a tuning session, it is history. */
  private val retention: Long = 24L * 60L * 60L * 1000L
  private val ttlMillis: Long = retention * 2

  private def key: String = s"$prefix:tuning:samples"

  private val samples = new ConcurrentLinkedQueue[TuningSample]()
  private val size    = new AtomicInteger(0)

  // -----------------------------------------------------------------------------------------------
  // the request path — in memory only
  // -----------------------------------------------------------------------------------------------

  def record(sample: TuningSample): Unit = {
    samples.add(sample)
    if (size.incrementAndGet() > max) {
      // trim rather than block: losing the oldest example is never worse than adding latency
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

  /** What this node has buffered since it started. */
  def local: Seq[TuningSample] = {
    val it  = samples.iterator()
    val buf = Seq.newBuilder[TuningSample]
    while (it.hasNext) buf += it.next()
    buf.result()
  }

  // -----------------------------------------------------------------------------------------------
  // shared state
  // -----------------------------------------------------------------------------------------------

  def flush(): Future[Unit] = {
    val mine = local.sortBy(-_.at).take(published)
    if (mine.isEmpty) Future.successful(())
    else
      store
        .hset(key, instanceId, Json.stringify(JsArray(mine.map(_.json))))
        .flatMap(_ => store.pexpire(key, ttlMillis))
        .recover { case err: Throwable =>
          logger.warn("could not publish the tuning samples", err)
          ()
        }
  }

  /**
   * Every node's candidates, newest first.
   *
   * This node's live buffer replaces its own published snapshot rather than adding to it, so a match
   * that happened a second ago is already visible and is not counted twice.
   */
  def all(): Future[Seq[TuningSample]] =
    store
      .hgetall(key)
      .map { fields =>
        val others = fields.view
          .filterKeys(_ != instanceId)
          .values
          .flatMap(v => Try(Json.parse(v)).toOption)
          .flatMap(_.asOpt[JsArray].map(_.value).getOrElse(Seq.empty))
          .flatMap(TuningSample.format.reads(_).asOpt)
          .toSeq
        val cutoff = System.currentTimeMillis() - retention
        (others ++ local).filter(_.at >= cutoff).sortBy(-_.at)
      }
      .recover { case err: Throwable =>
        logger.warn("could not read the tuning samples, falling back to this node's buffer", err)
        local.sortBy(-_.at)
      }

  def sample(id: String): Future[Option[TuningSample]] = all().map(_.find(_.id == id))

  /** Newest first, and the most frequent within a timestamp. */
  def groups(): Future[Seq[TuningGroup]] =
    all().map { entries =>
      entries
        .groupBy(_.groupKey)
        .map { case (key, es) =>
          val newest = es.maxBy(_.at)
          TuningGroup(key, es.size, newest.at, newest)
        }
        .toSeq
        .sortBy(g => (-g.lastSeen, -g.count))
    }

  def clear(): Future[Unit] = {
    samples.clear()
    size.set(0)
    store.del(key)
  }

  def status: JsValue = Json.obj("retained" -> size.get(), "max" -> max, "node" -> nodeId)
}
