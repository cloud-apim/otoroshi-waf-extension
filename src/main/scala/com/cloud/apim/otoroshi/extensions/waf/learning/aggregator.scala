package com.cloud.apim.otoroshi.extensions.waf.learning

import com.cloud.apim.otoroshi.extensions.waf.security.SharedStateStore
import com.cloud.apim.otoroshi.extensions.waf.tuning.MatchedTarget
import com.cloud.apim.seclang.model.MatchEvent
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.libs.json.*
import play.api.Logger

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * A learning window, accumulated per node and added up on read.
 *
 * Two constraints shape this. It has to survive a restart and cover the whole cluster, because a
 * window worth trusting is a week long and a week-long in-memory count on one node is neither. And
 * it has to cost nothing on the request path, because it runs on every request the configuration
 * evaluates, not only on the ones that match.
 *
 * So: counting is in memory, and a scheduler flushes a snapshot to the shared store. Each node
 * writes only its own fields, keyed by an id that changes when the process restarts, so no two
 * writers ever touch the same field and no read-modify-write is needed. The report sums them.
 */
class LearningAggregator(
    prefix: String,
    store: SharedStateStore,
    nodeId: String,
    logger: Logger
)(using ec: ExecutionContext) {

  /** Changes on restart, so a node that comes back does not overwrite what it contributed before. */
  private val instanceId: String = s"$nodeId-${System.currentTimeMillis()}"

  private val maxEntries: Int = 2000
  private val maxSamples: Int = 200
  private val ttlMillis: Long = 30L * 24L * 60L * 60L * 1000L

  private def keyOf(configRef: String): String = s"$prefix:learning:$configRef"

  /**
   * Which configurations have an open window, shared rather than remembered.
   *
   * A window is a week long and a node restarts inside it. Keeping "is this running" in memory meant
   * a restarted node silently stopped contributing to a run that was still open — the report kept
   * working, with a denominator quietly missing one node's traffic, which is worse than an error.
   */
  private def runningKey: String = s"$prefix:learning:__running__"

  // -----------------------------------------------------------------------------------------------
  // node-local accumulation
  // -----------------------------------------------------------------------------------------------

  private class Local {
    val entries   = new UnboundedTrieMap[String, LearningEntry]()
    val samples   = scala.collection.mutable.ArrayBuffer.empty[WouldBlockSample]
    val requests  = new AtomicLong(0L)
    val matched   = new AtomicLong(0L)
    val wouldBlock = new AtomicLong(0L)
  }

  private val locals  = new UnboundedTrieMap[String, Local]()
  private val running = new UnboundedTrieMap[String, Boolean]()

  def isRunning(configRef: String): Boolean = running.getOrElse(configRef, false)

  /** Re-reads the shared list, so a node that restarted mid-window picks the window back up. */
  def refreshRunning(): Future[Unit] =
    store
      .hgetall(runningKey)
      .map { fields =>
        val open = fields.keySet
        open.foreach(ref => running.put(ref, true))
        running.keySet.toSeq.filterNot(open.contains).foreach(ref => running.put(ref, false))
        ()
      }
      .recover { case _: Throwable => () }

  private def local(configRef: String): Local =
    locals.getOrElseUpdate(configRef, new Local())

  /** Every request the configuration looked at, matched or not — the denominator of every rate. */
  def observeRequest(configRef: String): Unit =
    if (isRunning(configRef)) local(configRef).requests.incrementAndGet()

  /**
   * What one evaluation found.
   *
   * `wouldBlock` is the interesting flag: during a monitoring rollout it is the request that will
   * break the day someone arms the configuration, and counting those is the entire point of running
   * a window before arming.
   */
  def observe(
      configRef: String,
      events: Seq[MatchEvent],
      routeId: Option[String],
      routeName: Option[String],
      method: String,
      path: String,
      wouldBlock: Boolean,
      countRun: Boolean
  ): Unit = {
    if (!isRunning(configRef) || events.isEmpty) return
    val l   = local(configRef)
    val now = System.currentTimeMillis()
    val keys = Seq.newBuilder[String]
    events.foreach { evt =>
      val target = MatchedTarget.fromLogs(evt.logs)
      evt.ruleId.foreach { rid =>
        if (target.exists(_.excludable)) {
          val key = LearningEntry.keyOf(rid, target, path)
          keys += key
          val fresh = LearningEntry(
            ruleId = rid,
            target = target,
            path = path,
            method = method,
            routeId = routeId,
            routeName = routeName,
            msg = evt.msg.filter(_ != "--"),
            paranoia = LearningEntry.paranoiaOf(evt),
            count = 1L,
            wouldBlock = if (wouldBlock) 1L else 0L,
            firstSeen = now,
            lastSeen = now,
            samples = MatchedTarget.valueFromLogs(evt.logs).toSeq
          )
          l.entries.get(key) match {
            case Some(existing)                     => l.entries.put(key, existing.merge(fresh))
            // a window on badly-behaved traffic could otherwise grow a key per distinct path
            case None if l.entries.size < maxEntries => l.entries.put(key, fresh)
            case None                                => ()
          }
        }
      }
    }
    val ks = keys.result().distinct
    // "matched" means a rule objected to something a caller sent, not "the engine emitted an event":
    // the CRS fires its initialisation rules on every request, so counting those made the figure
    // equal to the request count and say nothing at all.
    //
    // One request is also evaluated twice when response inspection is on, and in monitoring mode both
    // halves can reach a deny. Counting each would inflate the one number the whole feature is read
    // against — how much traffic breaks when this is armed — so the run counters are charged once per
    // request while the per-rule counts still record everything that fired.
    if (countRun && ks.nonEmpty) l.matched.incrementAndGet()
    if (wouldBlock) {
      if (countRun) l.wouldBlock.incrementAndGet()
      l.synchronized {
        if (countRun && l.samples.size < maxSamples) {
          l.samples += WouldBlockSample(now, path, ks, WouldBlockSample.scoreOf(events))
        }
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // shared state
  // -----------------------------------------------------------------------------------------------

  def start(configRef: String): Future[Unit] = {
    locals.remove(configRef)
    running.put(configRef, true)
    val run = LearningRun.start(configRef)
    store.del(keyOf(configRef)).flatMap { _ =>
      store.hset(keyOf(configRef), "run", Json.stringify(run.json)).flatMap { _ =>
        store.pexpire(keyOf(configRef), ttlMillis).flatMap { _ =>
          store.hset(runningKey, configRef, run.startedAt.toString).flatMap { _ =>
            store.pexpire(runningKey, ttlMillis)
          }
        }
      }
    }
  }

  def stop(configRef: String): Future[Unit] = {
    val done = flush(configRef)
    running.put(configRef, false)
    done.flatMap { _ => store.hdel(runningKey, Seq(configRef)) }.flatMap { _ =>
      store.hgetall(keyOf(configRef)).flatMap { fields =>
        val run = fields
          .get("run")
          .flatMap(s => Try(Json.parse(s)).toOption)
          .flatMap(LearningRun.format.reads(_).asOpt)
          .getOrElse(LearningRun.start(configRef))
        store.hset(keyOf(configRef), "run", Json.stringify(run.copy(stoppedAt = Some(System.currentTimeMillis())).json))
      }
    }
  }

  /** Push this node's snapshot. Idempotent: the whole cumulative value is written every time. */
  def flush(configRef: String): Future[Unit] = {
    val l = locals.get(configRef)
    l match {
      case None    => Future.successful(())
      case Some(l) =>
        val key     = keyOf(configRef)
        val counters = Json.obj(
          "requests"    -> l.requests.get(),
          "matched"     -> l.matched.get(),
          "would_block" -> l.wouldBlock.get()
        )
        val samples = l.synchronized(l.samples.toList)
        val writes  = Seq(
          store.hset(key, s"c|$instanceId", Json.stringify(counters)),
          store.hset(key, s"s|$instanceId", Json.stringify(JsArray(samples.map(_.json))))
        ) ++ l.entries.toSeq.map { case (k, entry) =>
          store.hset(key, s"e|$instanceId|$k", Json.stringify(entry.json))
        }
        Future.sequence(writes).map(_ => ()).recover { case err: Throwable =>
          logger.warn(s"could not flush the learning window for $configRef", err)
          ()
        }
    }
  }

  def flushAll(): Future[Unit] =
    Future.sequence(locals.keySet.toSeq.filter(isRunning).map(flush)).map(_ => ())

  def discard(configRef: String): Future[Unit] = {
    locals.remove(configRef)
    running.put(configRef, false)
    store.hdel(runningKey, Seq(configRef)).flatMap(_ => store.del(keyOf(configRef)))
  }

  /**
   * Everything every node contributed, added up.
   *
   * This node's own in-flight numbers replace its last stored snapshot rather than adding to it, so
   * a report asked for a second after a match reflects it without waiting for the next flush and
   * without counting the same request twice.
   */
  def snapshot(configRef: String): Future[LearningSnapshot] =
    store.hgetall(keyOf(configRef)).map { fields =>
      val stored = LearningSnapshot.parse(configRef, fields)
      locals.get(configRef) match {
        case None    => stored
        case Some(l) =>
          stored.replacing(
            InstanceContribution(
              instanceId = instanceId,
              requests = l.requests.get(),
              matched = l.matched.get(),
              wouldBlock = l.wouldBlock.get(),
              entries = l.entries.values.toSeq,
              samples = l.synchronized(l.samples.toList)
            )
          )
      }
    }
}

/** What one node observed during the window. Nodes never write each other's fields. */
final case class InstanceContribution(
    instanceId: String,
    requests: Long,
    matched: Long,
    wouldBlock: Long,
    entries: Seq[LearningEntry],
    samples: Seq[WouldBlockSample]
)

/** The window as a whole, assembled from the per-node contributions. */
final case class LearningSnapshot(run: LearningRun, contributions: Seq[InstanceContribution]) {

  /** The same group seen by several nodes is one group with their counts added. */
  lazy val entries: Seq[LearningEntry] =
    contributions
      .flatMap(_.entries)
      .groupBy(_.key)
      .values
      .map(_.reduce((a, b) => a.merge(b)))
      .toSeq
      .sortBy(e => (-e.count, e.ruleId))

  lazy val samples: Seq[WouldBlockSample] = contributions.flatMap(_.samples)

  /** The run with the totals filled in — start and stop come from the stored record. */
  lazy val totals: LearningRun = run.copy(
    requests = contributions.map(_.requests).sum,
    matched = contributions.map(_.matched).sum,
    wouldBlock = contributions.map(_.wouldBlock).sum
  )

  def replacing(c: InstanceContribution): LearningSnapshot =
    copy(contributions = contributions.filterNot(_.instanceId == c.instanceId) :+ c)

  def json: JsValue = Json.obj(
    "run"     -> totals.json,
    "nodes"   -> contributions.size,
    "entries" -> JsArray(entries.map(_.json)),
    "samples" -> samples.size
  )
}

object LearningSnapshot {

  def empty(configRef: String): LearningSnapshot = LearningSnapshot(LearningRun.start(configRef), Seq.empty)

  /** Rebuild the per-node fields written by [[LearningAggregator.flush]]. */
  def parse(configRef: String, fields: Map[String, String]): LearningSnapshot = {
    def json(s: String): Option[JsValue] = Try(Json.parse(s)).toOption

    val run = fields
      .get("run")
      .flatMap(json)
      .flatMap(LearningRun.format.reads(_).asOpt)
      .getOrElse(LearningRun.start(configRef))

    val builder = scala.collection.mutable.Map.empty[String, InstanceContribution]
    def at(id: String): InstanceContribution =
      builder.getOrElse(id, InstanceContribution(id, 0L, 0L, 0L, Seq.empty, Seq.empty))

    fields.foreach {
      case (field, value) if field.startsWith("c|") =>
        val id = field.drop(2)
        json(value).foreach { js =>
          builder.put(
            id,
            at(id).copy(
              requests = (js \ "requests").asOpt[Long].getOrElse(0L),
              matched = (js \ "matched").asOpt[Long].getOrElse(0L),
              wouldBlock = (js \ "would_block").asOpt[Long].getOrElse(0L)
            )
          )
        }
      case (field, value) if field.startsWith("s|") =>
        val id     = field.drop(2)
        val parsed = json(value)
          .flatMap(_.asOpt[JsArray])
          .map(_.value.flatMap(WouldBlockSample.format.reads(_).asOpt).toSeq)
          .getOrElse(Seq.empty)
        builder.put(id, at(id).copy(samples = at(id).samples ++ parsed))
      case (field, value) if field.startsWith("e|") =>
        val id = field.drop(2).takeWhile(_ != '|')
        json(value)
          .flatMap(LearningEntry.format.reads(_).asOpt)
          .foreach(entry => builder.put(id, at(id).copy(entries = at(id).entries :+ entry)))
      case _                                        => ()
    }
    LearningSnapshot(run, builder.values.toSeq)
  }
}
