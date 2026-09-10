package com.cloud.apim.otoroshi.extensions.waf.learning

import com.cloud.apim.otoroshi.extensions.waf.security.SharedStateStore
import com.cloud.apim.otoroshi.extensions.waf.tuning.*
import com.cloud.apim.seclang.impl.engine.SecLangEngine
import com.cloud.apim.seclang.model.MatchEvent
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.events.{Audit, AuditEvent}
import otoroshi.models.BackOfficeUser
import otoroshi.next.extensions.AdminExtensionBackofficeAuthRoute
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.{Result, Results}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** What a learning run concluded, and what was done about it. */
case class CloudApimWafLearningAudit(
    user: Option[BackOfficeUser],
    configId: String,
    applied: Seq[String],
    report: LearningReport
) extends AuditEvent {

  override def `@type`: String               = "CloudApimWafLearningAudit"
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  val `@id`: String                          = IdGenerator.uuid
  val `@timestamp`: DateTime                 = DateTime.now()
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(using _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> "otoroshi",
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "audit"      -> "WAF_LEARNING_APPLIED",
    "user"       -> user.map(u => Json.obj("name" -> u.name, "email" -> u.email)).getOrElse(JsNull).asValue,
    "config"     -> configId,
    "applied"    -> applied,
    // the window the decision was taken on, kept with the decision
    "run"        -> report.run.json,
    "impact"     -> report.impact.json,
    "paranoia"   -> report.paranoia.json,
    "threshold"  -> report.threshold.json
  )
}

/**
 * Learning mode.
 *
 * Monitoring mode answers "what would this have done"; it does not answer "so can I turn it on".
 * Teams sit in monitoring for months because nobody ever converts the second question into
 * evidence. A run does that conversion: it counts what matched over a window, proposes the
 * exclusions that account for it — each one run through the tuning assistant's own preview — and
 * says plainly what arming today would still break.
 *
 * It proposes and never acts. The output is a diff, and applying it goes through exactly the checks
 * a hand-written exclusion goes through.
 */
class LearningModule(
    env: Env,
    _states: => TuningStates,
    engineOf: Seq[String] => SecLangEngine,
    store: SharedStateStore,
    keyPrefix: String,
    nodeId: String,
    /** Whether what one node counts is visible to the node serving this api — see `security.redis-uri`. */
    distributed: () => Boolean
) {

  private val logger = Logger("cloud-apim-waf-learning")

  lazy val states: TuningStates = _states

  private given ExecutionContext = env.otoroshiExecutionContext
  private given Materializer     = env.otoroshiMaterializer
  private given Env              = env

  val aggregator = new LearningAggregator(keyPrefix, store, nodeId, logger)

  private val flushEvery: FiniteDuration = 10.seconds
  private var flusher: Option[org.apache.pekko.actor.Cancellable] = None

  private val basePath = "/extensions/cloud-apim/extensions/waf/learning"

  def start(): Unit = {
    flusher = Some(
      env.otoroshiScheduler.scheduleAtFixedRate(flushEvery, flushEvery)(() => {
        // picking the open windows back up is part of the same tick: a node that restarted mid-window
        // has to rejoin it, or the report quietly loses that node's traffic from every denominator
        aggregator
          .refreshRunning()
          .flatMap(_ => aggregator.flushAll())
          .recover { case e: Throwable => logger.warn("learning flush failed", e); () }
        ()
      })
    )
  }

  def stop(): Unit = {
    // a node going down should not take the last few minutes of its window with it
    Try(scala.concurrent.Await.result(aggregator.flushAll(), 5.seconds))
    flusher.foreach(_.cancel())
    flusher = None
  }

  // -----------------------------------------------------------------------------------------------
  // recording, from the request path
  // -----------------------------------------------------------------------------------------------

  def observeRequest(configRef: String): Unit = aggregator.observeRequest(configRef)

  def observe(
      configRef: String,
      events: Seq[MatchEvent],
      routeId: Option[String],
      routeName: Option[String],
      method: String,
      path: String,
      wouldBlock: Boolean,
      countRun: Boolean
  ): Unit = aggregator.observe(configRef, events, routeId, routeName, method, path, wouldBlock, countRun)

  // -----------------------------------------------------------------------------------------------
  // reporting
  // -----------------------------------------------------------------------------------------------

  /** The engine mode the configuration actually composes to, which decides whether data is complete. */
  private def modeOf(rules: Seq[String]) =
    Try(engineOf(rules).program.mode).toOption.flatten

  def report(configRef: String): Future[Either[String, LearningReport]] =
    states.config(configRef) match {
      case None         => Left("unknown waf config").vfuture
      case Some(config) =>
        aggregator.snapshot(configRef).map { snapshot =>
          val rules = states.rulesFor(config)
          Right(
            LearningReporter.build(
              snapshot = snapshot,
              rules = rules,
              mode = modeOf(rules),
              configBlocking = config.block,
              firstRuleId = ExclusionBuilder.nextId(states.rulesetsFor(config).flatMap(_.rules)),
              engineOf = engineOf
            )
          )
        }
    }

  private def withJsonBody(body: Option[Source[ByteString, ?]])(f: JsValue => Future[Result]): Future[Result] =
    body match {
      case None         => Results.BadRequest(Json.obj("error" -> "no body")).vfuture
      case Some(source) =>
        source.runFold(ByteString.empty)(_ ++ _).flatMap { raw =>
          Try(Json.parse(raw.utf8String)) match {
            case Failure(e)    => Results.BadRequest(Json.obj("error" -> s"invalid json: ${e.getMessage}")).vfuture
            case Success(json) => f(json)
          }
        }
    }

  private def configRefOf(json: JsValue): Option[String] = (json \ "config_ref").asOpt[String]

  private def handleStart(json: JsValue): Future[Result] = configRefOf(json) match {
    case None      => Results.BadRequest(Json.obj("error" -> "no config_ref")).vfuture
    case Some(ref) =>
      states.config(ref) match {
        case None         => Results.BadRequest(Json.obj("error" -> "unknown waf config")).vfuture
        case Some(config) =>
          aggregator.start(ref).map { _ =>
            val rules = states.rulesFor(config)
            Results.Ok(
              Json.obj(
                "done"      -> true,
                "config"    -> Json.obj("id" -> config.id, "name" -> config.name),
                // said at the moment someone starts a window, not discovered in the report a week later
                "mode"      -> LearningReporter.modeAdvice(modeOf(rules), config.block).json
              )
            )
          }
      }
  }

  private def handleStop(json: JsValue): Future[Result] = configRefOf(json) match {
    case None      => Results.BadRequest(Json.obj("error" -> "no config_ref")).vfuture
    case Some(ref) => aggregator.stop(ref).map(_ => Results.Ok(Json.obj("done" -> true)))
  }

  private def handleDiscard(json: JsValue): Future[Result] = configRefOf(json) match {
    case None      => Results.BadRequest(Json.obj("error" -> "no config_ref")).vfuture
    case Some(ref) => aggregator.discard(ref).map(_ => Results.Ok(Json.obj("done" -> true)))
  }

  private def handleReport(json: JsValue): Future[Result] = configRefOf(json) match {
    case None      => Results.BadRequest(Json.obj("error" -> "no config_ref")).vfuture
    case Some(ref) =>
      report(ref).map {
        case Left(err)     => Results.BadRequest(Json.obj("error" -> err))
        case Right(report) =>
          Results.Ok(
            report.json.as[JsObject] ++ Json.obj(
              "running"     -> aggregator.isRunning(ref),
              // a window whose counts never leave the workers is not a small inaccuracy, it is a
              // different measurement — and the number it produces reads as reassuring
              "distributed" -> distributed()
            )
          )
      }
  }

  // -----------------------------------------------------------------------------------------------
  // applying — the same checks a hand-written exclusion goes through
  // -----------------------------------------------------------------------------------------------

  private def handleApply(json: JsValue, user: Option[BackOfficeUser]): Future[Result] = configRefOf(json) match {
    case None      => Results.BadRequest(Json.obj("error" -> "no config_ref")).vfuture
    case Some(ref) =>
      val wanted = (json \ "keys").asOpt[Seq[String]].getOrElse(Seq.empty).toSet
      val reason = (json \ "reason").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse("applied from a learning run")
      report(ref).flatMap {
        case Left(err)     => Results.BadRequest(Json.obj("error" -> err)).vfuture
        case Right(report) =>
          // only what the report itself verified, and only what the caller ticked
          val chosen = report.exclusions.filter(p => p.accepted && (wanted.isEmpty || wanted.contains(p.entry.key)))
          if (chosen.isEmpty) {
            Results.BadRequest(Json.obj("error" -> "nothing to apply: no verified exclusion matched the selection")).vfuture
          } else {
            val by = user.map(u => s"${u.name} <${u.email}>").getOrElse("unknown")
            // sequential, because each write changes the ruleset the next one is planned against
            chosen
              .foldLeft(Future.successful(Seq.empty[String])) { case (acc, proposal) =>
                acc.flatMap { done =>
                  states.config(ref) match {
                    case None         => done.vfuture
                    case Some(config) =>
                      val entry = ExclusionWriter.entry(
                        seclang = proposal.proposal.seclang,
                        ruleId = proposal.entry.ruleId,
                        target = proposal.entry.target,
                        reason = s"$reason — ${proposal.entry.count} matches over the learning window",
                        by = by,
                        route = proposal.entry.routeName
                      )
                      val plan  = ExclusionWriter.plan(
                        config = config,
                        placement = proposal.proposal.placement,
                        all = states.allRulesets(),
                        entry = entry,
                        newRulesetId = IdGenerator.namedId("waf-ruleset", env)
                      )
                      for {
                        _ <- states.saveRuleset(plan.ruleset)
                        _ <- plan.config.map(states.saveConfig).getOrElse(true.vfuture)
                      } yield done :+ proposal.entry.key
                  }
                }
              }
              .map { applied =>
                Audit.send(CloudApimWafLearningAudit(user, ref, applied, report))
                Results.Ok(Json.obj("done" -> true, "applied" -> applied, "count" -> applied.size))
              }
          }
      }
  }

  def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_start",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleStart)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_stop",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleStop)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_discard",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleDiscard)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_report",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleReport)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_apply",
      wantsBody = true,
      handle = (_, _, user, body) => withJsonBody(body)(json => handleApply(json, user))
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_running",
      wantsBody = false,
      handle = (_, _, _, _) =>
        Results
          .Ok(
            Json.obj(
              "node"    -> nodeId,
              // per node on purpose: a window is started on the cluster but each node runs its own
              // accumulation, and saying otherwise would hide a node that missed the start
              "running" -> JsArray(states.allConfigs().map(_.id).filter(aggregator.isRunning).map(JsString.apply))
            )
          )
          .vfuture
    )
  )
}
