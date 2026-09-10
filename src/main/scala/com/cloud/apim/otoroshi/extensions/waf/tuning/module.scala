package com.cloud.apim.otoroshi.extensions.waf.tuning

import com.cloud.apim.otoroshi.extensions.waf.entities.{CloudApimWafConfig, WafRuleset}
import com.cloud.apim.seclang.impl.engine.SecLangEngine
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
import play.api.libs.json.*
import play.api.mvc.{Result, Results}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** What the assistant did, for the people who have to trust that it only did that. */
case class CloudApimWafTuningAudit(
    user: Option[BackOfficeUser],
    configId: String,
    ruleId: Int,
    target: Option[String],
    kind: String,
    seclang: String,
    reason: String,
    rulesetId: String,
    rulesetCreated: Boolean,
    preview: PreviewResult,
    forced: Boolean
) extends AuditEvent {

  override def `@type`: String               = "CloudApimWafTuningAudit"
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
    "audit"      -> "WAF_EXCLUSION_APPLIED",
    "user"       -> user.map(u => Json.obj("name" -> u.name, "email" -> u.email)).getOrElse(JsNull).asValue,
    "config"     -> configId,
    "rule_id"    -> ruleId,
    "target"     -> target,
    "kind"       -> kind,
    "seclang"    -> seclang,
    "reason"     -> reason,
    "ruleset"    -> Json.obj("id" -> rulesetId, "created" -> rulesetCreated),
    // the measured consequence, recorded next to the decision rather than left to be re-derived
    "preview"    -> preview.json,
    "forced"     -> forced
  )
}

/**
 * The false-positive tuning assistant.
 *
 * Hand-writing CRS exclusions is specialist work, and the usual outcome of not having the specialist
 * is that a rule — or the WAF — gets switched off. This turns one observed match into a small set of
 * candidate exclusions, ordered from surgical to blunt, each one *run* before it is offered and run
 * again before it is written.
 *
 * The invariant worth stating plainly: nothing is ever written that has not been demonstrated to
 * work. An exclusion the engine ignores is refused rather than saved, because a saved no-op is
 * indistinguishable from a fix right up until the incident.
 */
class TuningModule(
    env: Env,
    _states: => TuningStates,
    engineOf: Seq[String] => SecLangEngine,
    sharedState: com.cloud.apim.otoroshi.extensions.waf.security.SharedStateStore,
    keyPrefix: String,
    nodeId: String,
    /** Whether what one node publishes is visible to the others — see `security.redis-uri`. */
    distributed: () => Boolean
) {

  private val logger = play.api.Logger("cloud-apim-waf-tuning")

  /** Exposed so an integration test can drive the very write path the routes use. */
  lazy val states: TuningStates = _states

  val store = new TuningStore(keyPrefix, sharedState, nodeId, logger)

  private val flushEvery: FiniteDuration                         = 10.seconds
  private var flusher: Option[org.apache.pekko.actor.Cancellable] = None

  def start(): Unit =
    flusher = Some(
      env.otoroshiScheduler.scheduleAtFixedRate(flushEvery, flushEvery)(() => {
        store.flush().recover { case e: Throwable => logger.warn("tuning flush failed", e); () }
        ()
      })
    )

  def stop(): Unit = {
    Try(scala.concurrent.Await.result(store.flush(), 5.seconds))
    flusher.foreach(_.cancel())
    flusher = None
  }

  private val basePath = "/extensions/cloud-apim/extensions/waf/tuning"

  private given ExecutionContext = env.otoroshiExecutionContext
  private given Materializer     = env.otoroshiMaterializer
  private given Env              = env

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

  // -----------------------------------------------------------------------------------------------
  // reading what happened
  // -----------------------------------------------------------------------------------------------

  private def target(json: JsValue): Option[MatchedTarget] =
    (json \ "target").asOpt[String].flatMap(MatchedTarget.parse)

  /** Everything a proposal needs, from a stored sample or spelled out by the caller. */
  private case class Ask(
      config: CloudApimWafConfig,
      ruleId: Int,
      target: Option[MatchedTarget],
      path: Option[String],
      method: String,
      sampleValue: Option[String],
      routeName: Option[String]
  )

  private def ask(json: JsValue): Future[Either[String, Ask]] =
    (json \ "sample_id").asOpt[String] match {
      case None     => Future.successful(askWith(json, None))
      case Some(id) => store.sample(id).map(sample => askWith(json, sample))
    }

  private def askWith(json: JsValue, fromSample: Option[TuningSample]): Either[String, Ask] = {
    val configRef = fromSample.map(_.configRef).orElse((json \ "config_ref").asOpt[String])
    configRef.flatMap(states.config) match {
      case None         => Left("unknown waf config")
      case Some(config) =>
        val ruleId = fromSample.map(_.ruleId).orElse((json \ "rule_id").asOpt[Int])
        ruleId match {
          case None     => Left("no rule id")
          case Some(id) =>
            Right(
              Ask(
                config = config,
                ruleId = id,
                target = fromSample.flatMap(_.target).orElse(target(json)),
                path = fromSample.map(_.path).orElse((json \ "path").asOpt[String]),
                method = fromSample.map(_.method).orElse((json \ "method").asOpt[String]).getOrElse("GET"),
                sampleValue = fromSample.flatMap(_.matchedValue).orElse((json \ "sample_value").asOpt[String]),
                routeName = fromSample.flatMap(_.routeName)
              )
            )
        }
    }
  }

  private def handleMatches(): Future[Result] =
    store.groups().map { groups =>
      Results.Ok(
        Json.obj(
          "store"       -> store.status,
          "distributed" -> distributed(),
          // an empty page has two very different causes, and the difference matters
          "scope"       ->
            (if (distributed()) "every node, within the retained window"
             else "this node only — the shared state is not reaching the other nodes"),
          "matches"     -> JsArray(groups.map(_.json))
        )
      )
    }

  private def handlePropose(json: JsValue): Future[Result] = ask(json).flatMap {
    case Left(err)  => Results.BadRequest(Json.obj("error" -> err)).vfuture
    case Right(a)   =>
      val nextId = ExclusionBuilder.nextId(states.rulesetsFor(a.config).flatMap(_.rules))
      val ps     = ExclusionBuilder.proposals(a.ruleId, a.target, a.path, nextId)
      val rules  = states.rulesFor(a.config)
      // every proposal is run before it is shown, so an option that cannot work is never presented
      // as one of the choices
      val judged = ps.map { p =>
        val preview = ExclusionPreview.run(
          rules = rules,
          exclusion = p.seclang,
          ruleId = a.ruleId,
          target = a.target,
          sampleValue = a.sampleValue,
          method = a.method,
          path = a.path.getOrElse("/"),
          placement = p.placement,
          engineOf = engineOf
        )
        p.json.as[JsObject] ++ Json.obj("preview" -> preview.json)
      }
      Results
        .Ok(
          Json.obj(
            "config"    -> Json.obj("id" -> a.config.id, "name" -> a.config.name),
            "rule_id"   -> a.ruleId,
            "target"    -> a.target.map(_.json).getOrElse(JsNull).asValue,
            "path"      -> a.path,
            "proposals" -> JsArray(judged)
          )
        )
        .vfuture
  }

  private def handlePreview(json: JsValue): Future[Result] = ask(json).flatMap {
    case Left(err) => Results.BadRequest(Json.obj("error" -> err)).vfuture
    case Right(a)  =>
      (json \ "seclang").asOpt[String].map(_.trim).filter(_.nonEmpty) match {
        case None          => Results.BadRequest(Json.obj("error" -> "no seclang")).vfuture
        case Some(seclang) =>
          val placement = (json \ "placement").asOpt[String] match {
            case Some("before") => Placement.Before
            case Some("after")  => Placement.After
            case _              => Placement.of((json \ "kind").asOpt[String].getOrElse("update_target"))
          }
          val preview   = ExclusionPreview.run(
            rules = states.rulesFor(a.config),
            exclusion = seclang,
            ruleId = a.ruleId,
            target = a.target,
            sampleValue = a.sampleValue,
            method = a.method,
            path = a.path.getOrElse("/"),
            placement = placement,
            engineOf = engineOf
          )
          Results.Ok(preview.json).vfuture
      }
  }

  // -----------------------------------------------------------------------------------------------
  // writing it down
  // -----------------------------------------------------------------------------------------------

  private def handleApply(json: JsValue, user: Option[BackOfficeUser]): Future[Result] = ask(json).flatMap {
    case Left(err) => Results.BadRequest(Json.obj("error" -> err)).vfuture
    case Right(a)  =>
      (json \ "seclang").asOpt[String].map(_.trim).filter(_.nonEmpty) match {
        case None          => Results.BadRequest(Json.obj("error" -> "no seclang")).vfuture
        case Some(seclang) =>
          val kind      = (json \ "kind").asOpt[String].getOrElse("update_target")
          val placement = Placement.of(kind)
          val reason    = (json \ "reason").asOpt[String].map(_.trim).getOrElse("")
          val forced    = (json \ "force").asOpt[Boolean].getOrElse(false)
          val preview   = ExclusionPreview.run(
            rules = states.rulesFor(a.config),
            exclusion = seclang,
            ruleId = a.ruleId,
            target = a.target,
            sampleValue = a.sampleValue,
            method = a.method,
            path = a.path.getOrElse("/"),
            placement = placement,
            engineOf = engineOf
          )
          if (!preview.compiles) {
            Results.BadRequest(Json.obj("error" -> "the exclusion does not compile", "preview" -> preview.json)).vfuture
          } else if (!preview.reproduced && !forced) {
            // not the same claim as "it does not work": the match could not be rebuilt, so there is
            // nothing to measure against. Refused by default so the guarantee stays meaningful,
            // overridable so a rule that logs no value is not untunable
            Results
              .Conflict(
                Json.obj(
                  "error"       ->
                    s"rule ${a.ruleId} could not be reproduced from what was recorded, so this exclusion cannot be verified",
                  "needs_force" -> true,
                  "preview"     -> preview.json
                )
              )
              .vfuture
          } else if (preview.reproduced && !preview.effective) {
            // the whole point of the feature is that this case never reaches a ruleset
            Results
              .BadRequest(
                Json.obj(
                  "error"   -> s"this exclusion does not stop rule ${a.ruleId} firing — it would be saved and change nothing",
                  "preview" -> preview.json
                )
              )
              .vfuture
          } else if (preview.regressions.nonEmpty && !forced) {
            Results
              .Conflict(
                Json.obj(
                  "error"       -> "this exclusion stops known attacks being caught in the same input",
                  "needs_force" -> true,
                  "preview"     -> preview.json
                )
              )
              .vfuture
          } else {
            val entry = ExclusionWriter.entry(
              seclang = seclang,
              ruleId = a.ruleId,
              target = a.target,
              reason = reason,
              by = user.map(u => s"${u.name} <${u.email}>").getOrElse("unknown"),
              route = a.routeName
            )
            val plan  = ExclusionWriter.plan(
              config = a.config,
              placement = placement,
              all = states.allRulesets(),
              entry = entry,
              newRulesetId = IdGenerator.namedId("waf-ruleset", env)
            )
            for {
              _ <- states.saveRuleset(plan.ruleset)
              _ <- plan.config.map(states.saveConfig).getOrElse(true.vfuture)
            } yield {
              Audit.send(
                CloudApimWafTuningAudit(
                  user = user,
                  configId = a.config.id,
                  ruleId = a.ruleId,
                  target = a.target.map(_.full),
                  kind = kind,
                  seclang = seclang,
                  reason = reason,
                  rulesetId = plan.ruleset.id,
                  rulesetCreated = plan.rulesetCreated,
                  preview = preview,
                  forced = forced && preview.regressions.nonEmpty
                )
              )
              Results.Ok(Json.obj("done" -> true, "applied" -> plan.json, "preview" -> preview.json))
            }
          }
      }
  }

  // -----------------------------------------------------------------------------------------------

  def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_matches",
      wantsBody = false,
      handle = (_, _, _, _) => handleMatches()
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_propose",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handlePropose)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_preview",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handlePreview)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_apply",
      wantsBody = true,
      handle = (_, _, user, body) => withJsonBody(body)(json => handleApply(json, user))
    )
  )
}

/** What the assistant needs from the extension, named so it can be faked in a test. */
trait TuningStates {
  def config(id: String): Option[CloudApimWafConfig]
  def allConfigs(): Seq[CloudApimWafConfig]
  def allRulesets(): Seq[WafRuleset]
  def rulesetsFor(config: CloudApimWafConfig): Seq[WafRuleset]
  def rulesFor(config: CloudApimWafConfig): Seq[String]
  def saveRuleset(ruleset: WafRuleset): Future[Boolean]
  def saveConfig(config: CloudApimWafConfig): Future[Boolean]
}
