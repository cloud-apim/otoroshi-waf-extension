package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.otoroshi.extensions.waf.entities.CrowdSecBouncer
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*

import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

/** A detection made here, waiting to be reported to CrowdSec. */
final case class CrowdSecSignal(ip: String, message: String, at: DateTime = DateTime.now())

/**
 * Client for the CrowdSec Local API, in both directions.
 *
 * Pull uses the bouncer stream endpoint (`/v1/decisions/stream`), which hands out deltas after an
 * initial full sync — the same contract every official bouncer uses, so several Otoroshi nodes can
 * each keep their own mirror without coordinating.
 *
 * Push uses the watcher endpoints, because CrowdSec models reading decisions and writing alerts as
 * two different roles with two different credentials: a bouncer API key cannot report alerts.
 */
class CrowdSecClient(http: ReputationHttpClient, registry: ReputationRegistry, logger: Logger)(using
    ec: ExecutionContext
) {

  private val tokens = new TrieMap[String, (String, Long)]()
  private val queues = new TrieMap[String, AtomicReference[List[CrowdSecSignal]]]()
  private val lastPush = new TrieMap[String, Long]()

  // ---------------------------------------------------------------------------------------------
  // pull
  // ---------------------------------------------------------------------------------------------

  def pull(bouncer: CrowdSecBouncer): Future[Either[String, Int]] = {
    val store = registry.crowdSecStore(bouncer.id)
    if (!bouncer.pullUsable) {
      store.lastError = Some("bouncer is disabled, or has no lapi url / api key")
      Left(store.lastError.get).vfuture
    } else {
      val startup = !store.initialized
      http
        .call(
          HttpCall(
            method = "GET",
            url = s"${bouncer.baseUrl}/v1/decisions/stream?startup=$startup",
            headers = Seq("X-Api-Key" -> bouncer.apiKey, "Accept" -> "application/json"),
            timeout = bouncer.timeout
          )
        )
        .map { response =>
          if (response.status == 200) {
            val json    = Try(Json.parse(response.body)).getOrElse(Json.obj())
            val added   = decisions(json, "new", bouncer)
            val deleted = decisions(json, "deleted", bouncer)
            if (startup) store.reset()
            store.applyDelta(added, deleted)
            store.initialized = true
            store.lastError = None
            if (added.nonEmpty || deleted.nonEmpty) {
              logger.debug(
                s"crowdsec '${bouncer.name}' sync: +${added.size} -${deleted.size}, ${store.size} decisions held"
              )
            }
            Right(store.size)
          } else {
            val error = s"lapi responded with ${response.status}: ${response.body.take(200)}"
            store.lastError = Some(error)
            logger.warn(s"crowdsec '${bouncer.name}' (${bouncer.id}) sync failed: $error")
            Left(error)
          }
        }
        .andThen { case Failure(err) =>
          store.lastError = Some(s"lapi call failed: ${err.getMessage}")
          logger.warn(s"crowdsec '${bouncer.name}' (${bouncer.id}) sync failed", err)
        }
        .recover { case err: Throwable => Left(s"lapi call failed: ${err.getMessage}") }
    }
  }

  private def decisions(json: JsValue, field: String, bouncer: CrowdSecBouncer): Seq[CrowdSecDecision] = {
    (json \ field).asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty).flatMap { item =>
      val scope = (item \ "scope").asOptString.getOrElse("ip")
      if (!bouncer.acceptsScope(scope)) None
      else
        Some(
          CrowdSecDecision(
            id = (item \ "id").asOpt[Long].getOrElse(0L),
            origin = (item \ "origin").asOptString.getOrElse("unknown"),
            typ = (item \ "type").asOptString.getOrElse("ban"),
            scope = scope,
            value = (item \ "value").asOptString.getOrElse(""),
            duration = (item \ "duration").asOptString.getOrElse(""),
            scenario = (item \ "scenario").asOptString.getOrElse("")
          )
        ).filter(_.value.nonEmpty)
    }
  }

  def isPullDue(bouncer: CrowdSecBouncer, now: Long): Boolean = {
    registry.crowdSecStoreOpt(bouncer.id) match {
      case None        => true
      case Some(store) => !store.initialized || (now - store.lastSync >= bouncer.pollInterval.toMillis)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // push
  // ---------------------------------------------------------------------------------------------

  /** Non-blocking, bounded: signals are buffered and flushed on a timer, never per request. */
  def enqueue(bouncer: CrowdSecBouncer, signal: CrowdSecSignal): Unit = {
    if (bouncer.pushUsable) {
      val queue = queues.getOrElseUpdate(bouncer.id, new AtomicReference[List[CrowdSecSignal]](List.empty))
      queue.updateAndGet { current =>
        if (current.size >= bouncer.pushMaxBatch * 4) current else signal :: current
      }
      ()
    }
  }

  def isPushDue(bouncer: CrowdSecBouncer, now: Long): Boolean = {
    val pending = queues.get(bouncer.id).map(_.get().size).getOrElse(0)
    pending > 0 && (now - lastPush.getOrElse(bouncer.id, 0L) >= bouncer.pushInterval.toMillis)
  }

  def flush(bouncer: CrowdSecBouncer): Future[Either[String, Int]] = {
    val queue = queues.getOrElseUpdate(bouncer.id, new AtomicReference[List[CrowdSecSignal]](List.empty))
    val batch = queue.getAndUpdate(current => current.drop(bouncer.pushMaxBatch)).take(bouncer.pushMaxBatch)
    lastPush.put(bouncer.id, System.currentTimeMillis())
    if (batch.isEmpty) Right(0).vfuture
    else {
      // one alert per distinct source, carrying the number of events observed for it
      val alerts = batch.groupBy(_.ip).toSeq.map { case (ip, signals) => alertJson(bouncer, ip, signals) }
      withToken(bouncer).flatMap {
        case Left(error)  => Left(error).vfuture
        case Right(token) =>
          http
            .call(
              HttpCall(
                method = "POST",
                url = s"${bouncer.baseUrl}/v1/alerts",
                headers = Seq("Authorization" -> s"Bearer $token", "Content-Type" -> "application/json"),
                body = Some(JsArray(alerts)),
                timeout = bouncer.timeout
              )
            )
            .map { response =>
              if (response.status >= 200 && response.status < 300) {
                logger.debug(s"crowdsec '${bouncer.name}' pushed ${alerts.size} alerts")
                Right(alerts.size)
              } else {
                // the token may have been revoked — drop it so the next flush logs in again
                if (response.status == 401 || response.status == 403) tokens.remove(bouncer.id)
                val error = s"alert push failed with ${response.status}: ${response.body.take(200)}"
                logger.warn(s"crowdsec '${bouncer.name}' (${bouncer.id}) $error")
                Left(error)
              }
            }
            .recover { case err: Throwable => Left(s"alert push failed: ${err.getMessage}") }
      }
    }
  }

  private def withToken(bouncer: CrowdSecBouncer): Future[Either[String, String]] = {
    val now = System.currentTimeMillis()
    tokens.get(bouncer.id).filter(_._2 > now) match {
      case Some((token, _)) => Right(token).vfuture
      case None             =>
        http
          .call(
            HttpCall(
              method = "POST",
              url = s"${bouncer.baseUrl}/v1/watchers/login",
              headers = Seq("Content-Type" -> "application/json"),
              body = Some(Json.obj("machine_id" -> bouncer.pushMachineId, "password" -> bouncer.pushPassword)),
              timeout = bouncer.timeout
            )
          )
          .map { response =>
            if (response.status >= 200 && response.status < 300) {
              val json  = Try(Json.parse(response.body)).getOrElse(Json.obj())
              val token = (json \ "token").asOptString.getOrElse("")
              if (token.isEmpty) Left("lapi login returned no token")
              else {
                val expiry = (json \ "expire").asOptString
                  .flatMap(v => Try(DateTime.parse(v).getMillis).toOption)
                  .getOrElse(now + (55 * 60 * 1000L))
                tokens.put(bouncer.id, (token, expiry - 30000L))
                Right(token)
              }
            } else {
              Left(s"lapi login failed with ${response.status}: ${response.body.take(200)}")
            }
          }
          .recover { case err: Throwable => Left(s"lapi login failed: ${err.getMessage}") }
    }
  }

  private def alertJson(bouncer: CrowdSecBouncer, ip: String, signals: List[CrowdSecSignal]): JsValue = {
    val fmt     = ISODateTimeFormat.dateTime()
    val sorted  = signals.sortBy(_.at.getMillis)
    val startAt = sorted.headOption.map(_.at).getOrElse(DateTime.now())
    val stopAt  = sorted.lastOption.map(_.at).getOrElse(DateTime.now())
    val source  = Json.obj("scope" -> "Ip", "value" -> ip, "ip" -> ip)
    val base    = Json.obj(
      "scenario"         -> bouncer.pushScenario,
      "scenario_hash"    -> "",
      "scenario_version" -> "",
      "message"          -> sorted.last.message,
      "events_count"     -> signals.size,
      "start_at"         -> fmt.print(startAt),
      "stop_at"          -> fmt.print(stopAt),
      "capacity"         -> 0,
      "leakspeed"        -> "0s",
      "simulated"        -> false,
      "remediation"      -> bouncer.pushWithDecision,
      "source"           -> source,
      "events"           -> JsArray(sorted.map { signal =>
        Json.obj(
          "timestamp" -> fmt.print(signal.at),
          "meta"      -> Json.arr(
            Json.obj("key" -> "source_ip", "value" -> ip),
            Json.obj("key" -> "message", "value"   -> signal.message)
          )
        )
      })
    )
    // without a decision, CrowdSec's own scenarios decide what to do with the signal — the safer default
    if (bouncer.pushWithDecision) {
      base ++ Json.obj(
        "decisions" -> Json.arr(
          Json.obj(
            "duration" -> bouncer.pushDecisionDuration,
            "origin"   -> "cscli",
            "scenario" -> bouncer.pushScenario,
            "scope"    -> "Ip",
            "type"     -> "ban",
            "value"    -> ip
          )
        )
      )
    } else {
      base ++ Json.obj("decisions" -> JsArray(Seq.empty))
    }
  }

  def pendingPushes(bouncerId: String): Int = queues.get(bouncerId).map(_.get().size).getOrElse(0)

  def forget(bouncerId: String): Unit = {
    tokens.remove(bouncerId)
    queues.remove(bouncerId)
    lastPush.remove(bouncerId)
  }
}
