package com.cloud.apim.otoroshi.extensions.waf.security

import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Where a team is with one incident.
 *
 * Held apart from the incident itself, and keyed by identity rather than by incident id, for one
 * reason: the id is minted by whichever node happened to see the caller first, so two nodes
 * observing the same attacker mint two. Acknowledging one and not the other would be worse than
 * having no state at all.
 */
final case class IncidentState(
    key: String,
    state: String,
    by: String,
    at: Long,
    note: Option[String] = None
) {
  def json: JsValue = Json.obj(
    "key"   -> key,
    "state" -> state,
    "by"    -> by,
    "at"    -> at,
    "note"  -> note
  )
}

object IncidentState {

  val Open: String         = "open"
  val Acknowledged: String = "acknowledged"
  val Resolved: String     = "resolved"

  /** What a caller can be moved to. `open` is the absence of a state, and reachable by clearing it. */
  val settable: Seq[String] = Seq(Open, Acknowledged, Resolved)

  def parse(raw: String): Option[String] = settable.find(_.equalsIgnoreCase(raw.trim))

  def read(json: JsValue): Option[IncidentState] = Try {
    IncidentState(
      key = (json \ "key").as[String],
      state = (json \ "state").asOpt[String].getOrElse(Open),
      by = (json \ "by").asOpt[String].getOrElse("unknown"),
      at = (json \ "at").asOpt[Long].getOrElse(0L),
      note = (json \ "note").asOpt[String].filter(_.trim.nonEmpty)
    )
  }.toOption
}

/**
 * One caller's incident as the console shows it: every node's view of them, merged, with the
 * team's state and whatever the fabric currently holds against them.
 */
final case class IncidentView(
    incident: Incident,
    nodes: Set[String],
    state: Option[IncidentState],
    ban: Option[BanEntry],
    allowlist: Option[AllowlistEntry]
) {

  def key: String = incident.ref.key

  /**
   * The state to act on, which is not always the state that was set.
   *
   * A resolved incident whose caller came back is not resolved — and quietly leaving it off the
   * list is how an attacker gets a second run at you. It reads `reopened` rather than `open` so
   * that "we already looked at this once" is not lost either.
   */
  def effectiveState: String = state match {
    case None     => IncidentState.Open
    case Some(st) =>
      val cameBack = st.state == IncidentState.Resolved &&
        incident.lastSeen > st.at + IncidentBoard.reopenGraceMs
      if (cameBack) "reopened" else st.state
  }

  def json: JsValue = incident.json.as[JsObject] ++ Json.obj(
    "nodes"           -> nodes.toSeq.sorted,
    "state"           -> effectiveState,
    "workflow"        -> state.map(_.json).getOrElse(JsNull).asInstanceOf[JsValue],
    "banned"          -> ban.isDefined,
    "ban"             -> ban.map(_.json).getOrElse(JsNull).asInstanceOf[JsValue],
    "allowlisted"     -> allowlist.isDefined,
    "allowlist_entry" -> allowlist.map(_.json).getOrElse(JsNull).asInstanceOf[JsValue]
  )
}

/**
 * Every node's incidents, merged, plus the state a team put on them.
 *
 * The correlator is deliberately node-local and in memory — it is fed from the request path. That
 * makes it exactly the wrong thing to read an operator console from: on an Otoroshi leader/worker
 * cluster the traffic is served by the workers and the admin api by the leader, so the console
 * would show an empty list on precisely the deployment that has the most to show.
 *
 * So each node publishes a bounded snapshot of its correlator to the shared state on the same timer
 * that refreshes the bans, under a field only it writes, and reads merge every field. The pattern is
 * the one the tuning and learning stores already use, for the same reason.
 *
 * Two things it is careful about. A node that died stops publishing but leaves its field behind, so
 * a field older than [[staleAfter]] is ignored rather than shown as current. And this node's own
 * field is replaced by its live correlator on read, so something that happened a second ago is
 * already visible and is never counted twice.
 */
class IncidentBoard(
    prefix: String,
    store: SharedStateStore,
    nodeId: String,
    correlator: IncidentCorrelator,
    bans: BanStore,
    allowlist: AllowlistStore,
    logger: Logger
)(using ec: ExecutionContext) {

  /** Changes on restart, so a node coming back does not overwrite what it published before. */
  private val instanceId: String = s"$nodeId-${System.currentTimeMillis()}"

  private def incidentsKey: String = s"$prefix:incidents"
  private def stateKey: String     = s"$prefix:incidents:state"

  /** Per node, so the merged list stays readable however many nodes are running. */
  private val published: Int = 200

  private val ttlMillis: Long = 6L * 60L * 60L * 1000L

  // -----------------------------------------------------------------------------------------------
  // publishing
  // -----------------------------------------------------------------------------------------------

  def publish(): Future[Unit] = {
    val mine = correlator.all.take(published).map(_.copy(node = Some(nodeId)))
    if (mine.isEmpty) ().vfuture
    else
      store
        .hset(
          incidentsKey,
          instanceId,
          Json.stringify(Json.obj("at" -> System.currentTimeMillis(), "incidents" -> JsArray(mine.map(_.json))))
        )
        .flatMap(_ => store.pexpire(incidentsKey, ttlMillis))
        .recover { case err: Throwable =>
          logger.warn("could not publish this node's incidents", err)
          ()
        }
  }

  // -----------------------------------------------------------------------------------------------
  // reading
  // -----------------------------------------------------------------------------------------------

  private def othersIncidents(): Future[Seq[Incident]] =
    store
      .hgetall(incidentsKey)
      .map { fields =>
        val cutoff = System.currentTimeMillis() - IncidentBoard.staleAfter.toMillis
        fields.view
          .filterKeys(_ != instanceId)
          .values
          .flatMap(v => Try(Json.parse(v)).toOption)
          .filter(js => (js \ "at").asOpt[Long].exists(_ >= cutoff))
          .flatMap(js => (js \ "incidents").asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty))
          .flatMap(Incident.read)
          .toSeq
      }
      .recover { case err: Throwable =>
        logger.warn("could not read the other nodes' incidents, showing this node's own", err)
        Seq.empty
      }

  private def states(): Future[Map[String, IncidentState]] =
    store
      .hgetall(stateKey)
      .map(_.values.toSeq.flatMap(v => Try(Json.parse(v)).toOption.flatMap(IncidentState.read)).map(s => (s.key, s)).toMap)
      .recover { case err: Throwable =>
        logger.warn("could not read the incident states", err)
        Map.empty
      }

  def all(): Future[Seq[IncidentView]] =
    for {
      others <- othersIncidents()
      byKey  <- states()
    } yield {
      (others ++ correlator.all.map(_.copy(node = Some(nodeId))))
        .groupBy(_.ref.key)
        .values
        .map { parts =>
          val incident = IncidentBoard.merge(parts)
          IncidentView(
            incident = incident,
            // collected before the merge collapses the group: "this caller is hitting three nodes"
            // is one of the few things only the merged view can say
            nodes = parts.flatMap(_.node).toSet,
            state = byKey.get(incident.ref.key),
            ban = bans.check(incident.ref),
            allowlist = allowlist.check(incident.ref)
          )
        }
        .toSeq
        .sortBy(v => (IncidentBoard.stateOrder(v.effectiveState), -v.incident.lastSeen))
    }

  def get(key: String): Future[Option[IncidentView]] = all().map(_.find(_.key == key))

  // -----------------------------------------------------------------------------------------------
  // the team's state
  // -----------------------------------------------------------------------------------------------

  def setState(key: String, state: String, by: String, note: Option[String]): Future[IncidentState] = {
    val entry = IncidentState(key, state, by, System.currentTimeMillis(), note.map(_.trim).filter(_.nonEmpty))
    if (state == IncidentState.Open) {
      // "open" is the absence of a state, so setting it back removes the row rather than storing a
      // third value that then has to be interpreted everywhere
      store.hdel(stateKey, Seq(key)).map(_ => entry).recover { case _ => entry }
    } else {
      store
        .hset(stateKey, key, Json.stringify(entry.json))
        .flatMap(_ => store.pexpire(stateKey, IncidentBoard.stateRetention.toMillis))
        .map(_ => entry)
        .recover { case err: Throwable =>
          logger.error(s"could not record the state of incident $key", err)
          entry
        }
    }
  }

  /** Drops state rows nobody will look at again, so the hash does not grow without bound. */
  def pruneStates(): Future[Int] = {
    val cutoff = System.currentTimeMillis() - IncidentBoard.stateRetention.toMillis
    states()
      .flatMap { all =>
        val stale = all.values.filter(_.at < cutoff).map(_.key).toSeq
        if (stale.isEmpty) 0.vfuture else store.hdel(stateKey, stale).map(_ => stale.size)
      }
      .recover { case _ => 0 }
  }

  def status: JsValue = Json.obj(
    "held"            -> correlator.size,
    "published_as"    -> instanceId,
    "stale_after_sec" -> IncidentBoard.staleAfter.toSeconds
  )
}

object IncidentBoard {

  /**
   * How long a node's published field stays believable.
   *
   * Generous relative to the tick that writes it: a node under load that skips a few publishes
   * should not have its incidents disappear from the console, which is exactly when they matter.
   */
  val staleAfter: FiniteDuration = 5.minutes

  val stateRetention: FiniteDuration = 7.days

  /** Clock skew between the node that resolved and the node that observed, absorbed. */
  val reopenGraceMs: Long = 5000L

  /** Worst first — the console's default order, like every other page in the suite. */
  def stateOrder(state: String): Int = state match {
    case "reopened"                 => 0
    case IncidentState.Open         => 1
    case IncidentState.Acknowledged => 2
    case IncidentState.Resolved     => 3
    case _                          => 4
  }

  /**
   * Several nodes' view of the same caller, as one.
   *
   * Counts add up, extremes take the extreme, sets union, and the timeline is the merge of every
   * node's — which is the only place the order of events across the cluster can be seen at all.
   */
  def merge(parts: Iterable[Incident]): Incident = {
    val list = parts.toSeq
    val newest = list.maxBy(_.lastSeen)
    Incident(
      id = newest.id,
      ref = newest.ref,
      firstSeen = list.map(_.firstSeen).min,
      lastSeen = newest.lastSeen,
      count = list.map(_.count).sum,
      maxScore = list.map(_.maxScore).max,
      categories = list.flatMap(_.categories).toSet,
      tags = list.flatMap(_.tags).toSet,
      actions = list.flatMap(_.actions).toSet,
      lastMessage = newest.lastMessage,
      timeline = list.flatMap(_.timeline).sortBy(-_.at).take(IncidentCorrelator.maxTimeline),
      node = newest.node,
      enforcedCount = list.map(_.enforcedCount).sum,
      routes = list.flatMap(_.routes).toSet
    )
  }
}
