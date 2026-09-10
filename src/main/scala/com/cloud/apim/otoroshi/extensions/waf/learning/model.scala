package com.cloud.apim.otoroshi.extensions.waf.learning

import com.cloud.apim.otoroshi.extensions.waf.tuning.MatchedTarget
import com.cloud.apim.seclang.model.MatchEvent
import play.api.libs.json.*

import scala.util.Try

/**
 * One rule objecting to one input of one endpoint, counted over a window.
 *
 * The tuning assistant keeps individual samples because it works on one false positive at a time.
 * Learning mode keeps counts, because the question is different: not "what is this" but "what is
 * this *worth*". A rule that fired twice in a week and a rule that fired forty thousand times need
 * opposite decisions, and only the second number tells you which is which.
 */
final case class LearningEntry(
    ruleId: Int,
    target: Option[MatchedTarget],
    path: String,
    method: String,
    routeId: Option[String],
    routeName: Option[String],
    msg: Option[String],
    paranoia: Option[Int],
    count: Long,
    wouldBlock: Long,
    firstSeen: Long,
    lastSeen: Long,
    samples: Seq[String]
) {

  def key: String = LearningEntry.keyOf(ruleId, target, path)

  /** Two nodes' views of the same group, added up. */
  def merge(other: LearningEntry): LearningEntry = copy(
    count = count + other.count,
    wouldBlock = wouldBlock + other.wouldBlock,
    firstSeen = math.min(firstSeen, other.firstSeen),
    lastSeen = math.max(lastSeen, other.lastSeen),
    samples = (samples ++ other.samples).distinct.take(LearningEntry.maxSamples),
    // whichever node saw it, the metadata is the same; keep whatever is known
    msg = msg.orElse(other.msg),
    paranoia = paranoia.orElse(other.paranoia),
    routeId = routeId.orElse(other.routeId),
    routeName = routeName.orElse(other.routeName)
  )

  def json: JsValue = Json.obj(
    "key"         -> key,
    "rule_id"     -> ruleId,
    "target"      -> target.map(_.json).getOrElse(JsNull).asInstanceOf[JsValue],
    "path"        -> path,
    "method"      -> method,
    "route_id"    -> routeId,
    "route_name"  -> routeName,
    "msg"         -> msg,
    "paranoia"    -> paranoia,
    "count"       -> count,
    "would_block" -> wouldBlock,
    "first_seen"  -> firstSeen,
    "last_seen"   -> lastSeen,
    "samples"     -> samples
  )
}

object LearningEntry {

  val maxSamples: Int = 3

  def keyOf(ruleId: Int, target: Option[MatchedTarget], path: String): String =
    s"$ruleId|${target.map(_.full).getOrElse("-")}|$path"

  private val paranoiaTag = """paranoia-level/(\d)""".r

  /**
   * The paranoia level a rule belongs to, read off the rule itself.
   *
   * It is the single most useful thing to know about a noisy rule that the rule id does not tell
   * you: a wall of PL2 matches means the level was raised past what this traffic tolerates, and
   * lowering it is one line instead of forty exclusions.
   */
  def paranoiaOf(event: MatchEvent): Option[Int] =
    Try {
      (Json.parse(event.raw) \ "actions" \ "actions")
        .asOpt[Seq[JsObject]]
        .getOrElse(Seq.empty)
        .filter(a => (a \ "action_type").asOpt[String].contains("tag"))
        .flatMap(a => (a \ "value").asOpt[String])
        .flatMap(tag => paranoiaTag.findFirstMatchIn(tag).map(_.group(1).toInt))
        .headOption
    }.toOption.flatten

  val format: Format[LearningEntry] = new Format[LearningEntry] {
    override def writes(o: LearningEntry): JsValue = o.json
    override def reads(json: JsValue): JsResult[LearningEntry] = Try {
      LearningEntry(
        ruleId = (json \ "rule_id").as[Int],
        target = (json \ "target" \ "full").asOpt[String].flatMap(MatchedTarget.parse),
        path = (json \ "path").as[String],
        method = (json \ "method").asOpt[String].getOrElse("GET"),
        routeId = (json \ "route_id").asOpt[String],
        routeName = (json \ "route_name").asOpt[String],
        msg = (json \ "msg").asOpt[String],
        paranoia = (json \ "paranoia").asOpt[Int],
        count = (json \ "count").asOpt[Long].getOrElse(0L),
        wouldBlock = (json \ "would_block").asOpt[Long].getOrElse(0L),
        firstSeen = (json \ "first_seen").asOpt[Long].getOrElse(0L),
        lastSeen = (json \ "last_seen").asOpt[Long].getOrElse(0L),
        samples = (json \ "samples").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case scala.util.Success(v) => JsSuccess(v)
      case scala.util.Failure(e) => JsError(e.getMessage)
    }
  }
}

/**
 * The rules that fired together on one request the ruleset would have denied.
 *
 * Counting how often each rule fires says nothing about how many *requests* stop breaking once you
 * exclude it, because a denial under anomaly scoring is usually several rules agreeing. Keeping a
 * bounded reservoir of the actual combinations is what turns "these forty exclusions" into "and
 * then 187 of the 200 sampled denials go away" — a number with something behind it.
 */
final case class WouldBlockSample(at: Long, path: String, keys: Seq[String], score: Option[Int]) {
  def json: JsValue = Json.obj("at" -> at, "path" -> path, "keys" -> keys, "score" -> score)
}

object WouldBlockSample {
  val format: Format[WouldBlockSample] = new Format[WouldBlockSample] {
    override def writes(o: WouldBlockSample): JsValue = o.json
    override def reads(json: JsValue): JsResult[WouldBlockSample] = Try {
      WouldBlockSample(
        at = (json \ "at").asOpt[Long].getOrElse(0L),
        path = (json \ "path").asOpt[String].getOrElse("/"),
        keys = (json \ "keys").asOpt[Seq[String]].getOrElse(Seq.empty),
        score = (json \ "score").asOpt[Int]
      )
    } match {
      case scala.util.Success(v) => JsSuccess(v)
      case scala.util.Failure(e) => JsError(e.getMessage)
    }
  }

  private val scorePattern = """Total Score:\s*(\d+)""".r

  /**
   * The anomaly total, as the CRS blocking-evaluation rule reports it in its own message.
   *
   * `findFirstMatchIn` rather than a pattern match: a regex used as a pattern has to match the whole
   * string, and the score is one clause inside a sentence.
   */
  def scoreOf(events: Seq[MatchEvent]): Option[Int] =
    events.flatMap(_.msg).flatMap(m => scorePattern.findFirstMatchIn(m).map(_.group(1).toInt)).headOption
}

/** One learning window over one WAF configuration. */
final case class LearningRun(
    configRef: String,
    startedAt: Long,
    stoppedAt: Option[Long],
    requests: Long,
    matched: Long,
    wouldBlock: Long
) {
  def running: Boolean       = stoppedAt.isEmpty
  def durationMillis: Long   = stoppedAt.getOrElse(System.currentTimeMillis()) - startedAt
  def matchRate: Double      = if (requests <= 0) 0d else matched.toDouble / requests.toDouble
  def wouldBlockRate: Double = if (requests <= 0) 0d else wouldBlock.toDouble / requests.toDouble

  def merge(other: LearningRun): LearningRun = copy(
    startedAt = math.min(startedAt, other.startedAt),
    stoppedAt = (stoppedAt, other.stoppedAt) match {
      case (Some(a), Some(b)) => Some(math.max(a, b))
      case _                  => None
    },
    requests = requests + other.requests,
    matched = matched + other.matched,
    wouldBlock = wouldBlock + other.wouldBlock
  )

  def json: JsValue = Json.obj(
    "config_ref"       -> configRef,
    "started_at"       -> startedAt,
    "stopped_at"       -> stoppedAt,
    "running"          -> running,
    "duration_millis"  -> durationMillis,
    "requests"         -> requests,
    "matched"          -> matched,
    "would_block"      -> wouldBlock,
    "would_block_rate" -> wouldBlockRate
  )
}

object LearningRun {
  def start(configRef: String, at: Long = System.currentTimeMillis()): LearningRun =
    LearningRun(configRef, at, None, 0L, 0L, 0L)

  val format: Format[LearningRun] = new Format[LearningRun] {
    override def writes(o: LearningRun): JsValue = o.json
    override def reads(json: JsValue): JsResult[LearningRun] = Try {
      LearningRun(
        configRef = (json \ "config_ref").as[String],
        startedAt = (json \ "started_at").asOpt[Long].getOrElse(0L),
        stoppedAt = (json \ "stopped_at").asOpt[Long],
        requests = (json \ "requests").asOpt[Long].getOrElse(0L),
        matched = (json \ "matched").asOpt[Long].getOrElse(0L),
        wouldBlock = (json \ "would_block").asOpt[Long].getOrElse(0L)
      )
    } match {
      case scala.util.Success(v) => JsSuccess(v)
      case scala.util.Failure(e) => JsError(e.getMessage)
    }
  }
}
