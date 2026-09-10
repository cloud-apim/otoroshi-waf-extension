package com.cloud.apim.otoroshi.extensions.waf.security

import com.cloud.apim.otoroshi.extensions.waf.challenge.ChallengeService
import com.cloud.apim.otoroshi.extensions.waf.bots.{BotVerificationSettings, BotVerifier}
import com.cloud.apim.otoroshi.extensions.waf.entities.*
import com.cloud.apim.otoroshi.extensions.waf.reputation.WafDetectionRelay
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.models.EntityLocationSupport
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.mvc.{Result, Results}
import play.api.{Configuration, Logger}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class SecurityDatastores(env: Env, extensionId: AdminExtensionId) {
  val threatPolicyDatastore: ThreatPolicyDatastore =
    new KvThreatPolicyDatastore(extensionId, env.datastores.redis, env)
  val challengeProviderDatastore: ChallengeProviderDatastore =
    new KvChallengeProviderDatastore(extensionId, env.datastores.redis, env)
  val botPolicyDatastore: BotPolicyDatastore =
    new KvBotPolicyDatastore(extensionId, env.datastores.redis, env)
  val honeypotPolicyDatastore: HoneypotPolicyDatastore =
    new KvHoneypotPolicyDatastore(extensionId, env.datastores.redis, env)
}

class SecurityState {
  private val _policies   = new UnboundedTrieMap[String, ThreatPolicy]()
  private val _challenges = new UnboundedTrieMap[String, ChallengeProvider]()
  private val _bots       = new UnboundedTrieMap[String, BotPolicy]()
  private val _honeypots  = new UnboundedTrieMap[String, HoneypotPolicy]()

  def botPolicy(id: String): Option[BotPolicy] = _bots.get(id)
  def allBotPolicies(): Seq[BotPolicy]         = _bots.values.toSeq
  def updateBotPolicies(values: Seq[BotPolicy]): Unit = {
    _bots.addAll(values.map(v => (v.id, v))).remAll(_bots.keySet.toSeq.diff(values.map(_.id)))
  }

  def honeypotPolicy(id: String): Option[HoneypotPolicy] = _honeypots.get(id)
  def allHoneypotPolicies(): Seq[HoneypotPolicy]         = _honeypots.values.toSeq
  def updateHoneypotPolicies(values: Seq[HoneypotPolicy]): Unit = {
    _honeypots.addAll(values.map(v => (v.id, v))).remAll(_honeypots.keySet.toSeq.diff(values.map(_.id)))
  }


  def challengeProvider(id: String): Option[ChallengeProvider] = _challenges.get(id)
  def allChallengeProviders(): Seq[ChallengeProvider]          = _challenges.values.toSeq
  def updateChallengeProviders(values: Seq[ChallengeProvider]): Unit = {
    _challenges.addAll(values.map(v => (v.id, v))).remAll(_challenges.keySet.toSeq.diff(values.map(_.id)))
  }

  def threatPolicy(id: String): Option[ThreatPolicy] = _policies.get(id)
  def allThreatPolicies(): Seq[ThreatPolicy]         = _policies.values.toSeq
  def updateThreatPolicies(values: Seq[ThreatPolicy]): Unit = {
    _policies.addAll(values.map(v => (v.id, v))).remAll(_policies.keySet.toSeq.diff(values.map(_.id)))
  }
}

/**
 * The decision fabric: CORE-1 to CORE-4.
 *
 * Detectors contribute signals to a shared score ([[ThreatBus]]), one engine turns that score into
 * a graded action ([[ThreatPolicy]]), bans are held cluster-wide ([[BanStore]]) and fed by a
 * cross-request memory ([[ThreatLedger]]), and everything that happens is emitted in one normalised
 * shape and correlated into incidents ([[IncidentCorrelator]]).
 */
class SecurityModule(env: Env, extensionId: AdminExtensionId, configuration: Configuration) {

  private given ExecutionContext = env.otoroshiExecutionContext
  private given Materializer     = env.otoroshiMaterializer

  private val logger = Logger("cloud-apim-waf-security")

  val nodeId: String = s"${env.name}-${IdGenerator.token(8)}"

  private val enabled: Boolean =
    configuration.getOptional[Boolean]("security.enabled").getOrElse(true)
  private val tickInterval: FiniteDuration =
    configuration.getOptional[Long]("security.tick-interval-seconds").getOrElse(10L).max(1L).seconds
  private val incidentWindow: FiniteDuration =
    configuration.getOptional[Long]("security.incident-window-seconds").getOrElse(1800L).max(60L).seconds

  private val ledgerSettings: LedgerSettings = LedgerSettings(
    enabled = configuration.getOptional[Boolean]("security.ledger.enabled").getOrElse(true),
    window = configuration.getOptional[Long]("security.ledger.window-seconds").getOrElse(3600L).max(60L).seconds,
    banThreshold = configuration.getOptional[Int]("security.ledger.ban-threshold").getOrElse(100).max(1),
    banDuration = configuration.getOptional[Long]("security.ledger.ban-duration-seconds").getOrElse(3600L).max(1L).seconds
  )

  private val datastores = new SecurityDatastores(env, extensionId)

  /**
   * Where bans and the ledger actually live.
   *
   * Otoroshi's own storage can be in-memory or file-backed, in which case nothing written to it is
   * shared between nodes — and a ban registry that is not shared is not a ban registry. Pointing
   * `security.redis-uri` at a redis makes distribution independent of the storage backend, through
   * the same stateful client manager the distributed rate limiter uses. Without it, this falls back
   * to Otoroshi's storage, which is genuinely distributed only when that storage is.
   */
  private val distributedRedisUri: Option[String] =
    configuration.getOptional[String]("security.redis-uri").map(_.trim).filter(_.nonEmpty)
  private val redisClientId: String = s"cloud-apim-security-${extensionId.cleanup}"

  def redis(): otoroshi.storage.RedisLike = distributedRedisUri match {
    case Some(uri) =>
      env.statefulClientsManager.client(
        redisClientId,
        otoroshi.statefulclients.DistributedRateLimiterLettuceStatefulClientConfig(uri)
      )
    case None      => env.datastores.redis
  }

  private val keyPrefix: String = s"${env.storageRoot}:extensions:${extensionId.cleanup}"

  val sharedState: SharedStateStore = new RedisSharedStateStore(() => redis())

  /**
   * Whether what one node writes to the shared state is visible to the others.
   *
   * The leader/worker case is the one that surprises people. A worker never talks to the configured
   * storage backend at all: Otoroshi hands it a `SwappableInMemoryDataStores` whose contents are
   * replaced from the leader on every sync, and nothing under `:extensions:` is on the short list of
   * keys that survive a swap. So a worker's writes are wiped seconds later and never reach the node
   * serving the admin api — whatever the backend is. Only a dedicated `security.redis-uri` makes the
   * shared state genuinely shared once the cluster is split in two.
   */
  lazy val sharedStateDistributed: Boolean =
    distributedRedisUri.isDefined || (!env.clusterConfig.mode.clusterActive && env.datastores.redis.optimized)

  /** Why it is not, in words a page can print. */
  lazy val sharedStateWarning: Option[String] =
    if (sharedStateDistributed) None
    else if (env.clusterConfig.mode.clusterActive)
      Some(
        "This cluster is running in leader/worker mode without `security.redis-uri`. Workers write to " +
          "an in-memory copy of the leader's state that is replaced on every sync, so nothing they record " +
          "reaches the node serving this page. Point `security.redis-uri` at a redis to fix it."
      )
    else
      Some(
        "The shared state falls back to the Otoroshi storage backend, which is only shared between nodes " +
          "if that backend is. With `file` or `inmemory` storage each node keeps its own. Point " +
          "`security.redis-uri` at a redis to make it independent of the backend."
      )
  val states: SecurityState         = new SecurityState()
  val bans: BanStore                = new BanStore(s"$keyPrefix:bans", sharedState, nodeId, logger)
  val ledger: ThreatLedger          = new ThreatLedger(s"$keyPrefix:ledger", sharedState, bans, () => ledgerSettings, logger)
  val fail2ban: Fail2BanCounter     = new Fail2BanCounter(s"$keyPrefix:fail2ban", sharedState, bans, logger)
  val incidents: IncidentCorrelator = new IncidentCorrelator(() => incidentWindow)
  val challenges: ChallengeService  = new ChallengeService(
    sharedState,
    new com.cloud.apim.otoroshi.extensions.waf.reputation.EnvHttpClient(env),
    s"$keyPrefix:challenges",
    logger
  )

  private val botSettings = BotVerificationSettings(
    enabled = configuration.getOptional[Boolean]("security.bots.verify").getOrElse(true),
    positiveTtl = configuration.getOptional[Long]("security.bots.positive-ttl-seconds").getOrElse(21600L).seconds,
    negativeTtl = configuration.getOptional[Long]("security.bots.negative-ttl-seconds").getOrElse(900L).seconds
  )

  val botVerifier: BotVerifier = new BotVerifier(() => botSettings, logger)

  def botPolicy(id: Option[String]): Option[BotPolicy] =
    id.flatMap(states.botPolicy).orElse(states.allBotPolicies().headOption).filter(_.enabled)

  def honeypotPolicy(id: Option[String]): Option[HoneypotPolicy] =
    id.flatMap(states.honeypotPolicy).orElse(states.allHoneypotPolicies().headOption).filter(_.enabled)

  def challengeProvider(id: Option[String]): Option[ChallengeProvider] =
    id.flatMap(states.challengeProvider).filter(_.usable)

  /** The signing secret for clearance cookies, falling back to the gateway's own. */
  def challengeSecret(provider: ChallengeProvider): String = provider.secretOr(env.otoroshiSecret)

  private val relaySettings: RelaySettings = RelaySettings(
    correlate = configuration.getOptional[Boolean]("security.relay.correlate-waf-events").getOrElse(true),
    // off by default: when the threat response plugin is on the route it already charges the
    // ledger for what it enforced, and charging again from the event would double-count the same
    // detection. Turn this on for routes that run the waf without the response plugin.
    feedLedgerOnBlock = configuration.getOptional[Boolean]("security.ledger.feed-waf-blocks").getOrElse(false),
    feedLedgerOnMonitored = configuration.getOptional[Boolean]("security.ledger.feed-waf-monitored").getOrElse(false),
    weight = configuration.getOptional[Int]("security.ledger.waf-weight").getOrElse(25)
  )

  private val ticker   = new AtomicReference[Option[Cancellable]](None)
  private val relay    = new AtomicReference[Option[org.apache.pekko.actor.ActorRef]](None)
  private val basePath = "/extensions/cloud-apim/extensions/waf/security"

  // -----------------------------------------------------------------------------------------------
  // lifecycle
  // -----------------------------------------------------------------------------------------------

  def start(): Unit = {
    if (enabled) {
      bans.refresh()
      ticker.set(Some(env.otoroshiScheduler.scheduleWithFixedDelay(tickInterval, tickInterval)(() => tick())))
      startRelay()
      logger.info(
        s"the security fabric is enabled on node $nodeId " +
        s"(bans refresh every ${tickInterval.toSeconds}s, ledger window ${ledgerSettings.window.toMinutes}m, " +
        s"shared state on ${distributedRedisUri.map(_ => "a dedicated redis").getOrElse("otoroshi storage")})"
      )
    }
  }

  def stop(): Unit = {
    ticker.getAndSet(None).foreach(_.cancel())
    relay.getAndSet(None).foreach { actor =>
      env.analyticsActorSystem.eventStream.unsubscribe(actor)
      env.analyticsActorSystem.stop(actor)
    }
  }

  private def startRelay(): Unit = {
    given Env = env
    if (!env.useEventStreamForScriptEvents) {
      logger.warn(
        "'otoroshi.options.useEventStreamForScriptEvents' is off, so waf detections cannot feed the security fabric"
      )
    } else {
      val actor = env.analyticsActorSystem.actorOf(SecurityEventRelay.props(this, () => relaySettings, logger))
      env.analyticsActorSystem.eventStream.subscribe(actor, classOf[otoroshi.events.AnalyticEvent])
      relay.set(Some(actor))
    }
  }

  /**
   * A waf detection observed after the fact.
   *
   * Correlated always — an incident is just a count and costs nothing. Charged to the ledger only
   * when asked, because the in-request plugin may already have charged for the same detection.
   */
  def onWafDetection(event: JsValue, settings: RelaySettings): Unit = {
    val hasBlock = (event \ "block").asOpt[JsObject].isDefined
    val enforced = hasBlock && (event \ "blocking").asOpt[Boolean].getOrElse(false)
    if (hasBlock || settings.feedLedgerOnMonitored) {
      WafDetectionRelay.clientIp(event).foreach { ip =>
        val identity = ClientIdentity(ip = ip)
        val ref      = identity.primary
        val message  = WafDetectionRelay.message(event, enforced)
        if (settings.correlate) {
          incidents.record(ref, "waf", settings.weight, Seq("waf:match"), if (enforced) "deny" else "log", message)
        }
        val shouldCharge = (enforced && settings.feedLedgerOnBlock) || (!enforced && settings.feedLedgerOnMonitored)
        if (shouldCharge) ledger.record(ref, settings.weight, message, Seq("waf:match"))
      }
    }
  }

  def syncStates(): Future[Unit] = {
    given Env = env
    for {
      policies   <- datastores.threatPolicyDatastore.findAllAndFillSecrets()
      challenges <- datastores.challengeProviderDatastore.findAllAndFillSecrets()
      bots       <- datastores.botPolicyDatastore.findAllAndFillSecrets()
      honeypots  <- datastores.honeypotPolicyDatastore.findAllAndFillSecrets()
    } yield {
      states.updateThreatPolicies(policies)
      states.updateChallengeProviders(challenges)
      states.updateBotPolicies(bots)
      states.updateHoneypotPolicies(honeypots)
      ()
    }
  }

  def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = Seq(
    AdminExtensionEntity(ThreatPolicy.resource(env, datastores, states)),
    AdminExtensionEntity(ChallengeProvider.resource(env, datastores, states)),
    AdminExtensionEntity(BotPolicy.resource(env, datastores, states)),
    AdminExtensionEntity(HoneypotPolicy.resource(env, datastores, states))
  )

  private def tick(): Unit = {
    Try {
      bans.refresh()
      incidents.evict()
    } match {
      case scala.util.Failure(err) => logger.error("security tick failed", err)
      case _                       => ()
    }
  }

  // -----------------------------------------------------------------------------------------------
  // used by the plugins and the event relay
  // -----------------------------------------------------------------------------------------------

  def policy(id: String): Option[ThreatPolicy] = states.threatPolicy(id).filter(_.enabled)

  /** Falls back to a permissive built-in policy so the plugins work before anything is configured. */
  def policyOrDefault(id: Option[String]): ThreatPolicy =
    id.flatMap(policy).getOrElse(SecurityModule.builtinPolicy)

  /**
   * The single place an outcome is recorded: correlate, emit the normalised event, and charge the
   * ledger. Everything that decides something calls this rather than emitting its own event.
   */
  def record(
      category: String,
      identity: ClientIdentity,
      decision: ThreatDecision,
      tags: Seq[String],
      signals: JsValue,
      routeId: Option[String],
      routeName: Option[String],
      message: String,
      ledgerWeight: Int = 0
  ): Incident = {
    val ref      = identity.refs.headOption.getOrElse(IdentityRef(IdentityRef.Ip, identity.ip))
    val incident = incidents.record(ref, category, decision.score, tags, decision.action.name, message)
    CloudApimSecurityEvent(
      category = category,
      action = decision.action.name,
      outcome = if (decision.enforced) "blocked" else "observed",
      severity = severityOf(decision.score),
      identity = identity,
      score = decision.score,
      tags = tags,
      signals = signals,
      routeId = routeId,
      routeName = routeName,
      node = nodeId,
      incidentId = Some(incident.id),
      incidentCount = incident.count,
      message = message,
      extra = Json.obj("decision" -> decision.json)
    ).toAnalytics()(using env)
    if (ledgerWeight > 0) ledger.recordAll(identity, ledgerWeight, message, tags)
    incident
  }

  private def severityOf(score: Int): Int =
    if (score >= 90) 4 else if (score >= 70) 3 else if (score >= 40) 2 else 1

  // -----------------------------------------------------------------------------------------------
  // backoffice routes
  // -----------------------------------------------------------------------------------------------

  def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_status",
      wantsBody = false,
      handle = (_, _, _, _) => Results.Ok(status).vfuture
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_posture",
      wantsBody = false,
      handle = (_, _, _, _) =>
        Results.Ok(com.cloud.apim.otoroshi.extensions.waf.analytics.PostureReport.json(using env)).vfuture
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_bot_catalog",
      wantsBody = false,
      handle = (_, _, _, _) => Results.Ok(com.cloud.apim.otoroshi.extensions.waf.bots.BotCatalog.json).vfuture
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_robots_txt",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleRobots)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_challenge_presets",
      wantsBody = false,
      handle = (_, _, _, _) => Results.Ok(ChallengePresets.json).vfuture
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_challenge_from_preset",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleChallengePreset)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_bans",
      wantsBody = false,
      handle = (_, _, _, _) => Results.Ok(Json.obj("bans" -> JsArray(bans.all.map(_.json)))).vfuture
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_incidents",
      wantsBody = false,
      handle = (_, _, _, _) => Results.Ok(Json.obj("incidents" -> JsArray(incidents.all.map(_.json)))).vfuture
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_ban",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleBan)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_unban",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleUnban)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_ledger",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleLedger)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_simulate",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleSimulate)
    )
  )

  def status: JsValue = Json.obj(
    "node"      -> nodeId,
    "enabled"   -> enabled,
    "bans"      -> bans.status,
    "shared_state" -> Json.obj(
      "dedicated_redis" -> distributedRedisUri.isDefined,
      "distributed"     -> (distributedRedisUri.isDefined || env.datastores.redis.optimized)
    ),
    "incidents" -> Json.obj("held" -> incidents.size, "window_seconds" -> incidentWindow.toSeconds),
    "ledger"    -> Json.obj(
      "enabled"              -> ledgerSettings.enabled,
      "window_seconds"       -> ledgerSettings.window.toSeconds,
      "ban_threshold"        -> ledgerSettings.banThreshold,
      "ban_duration_seconds" -> ledgerSettings.banDuration.toSeconds
    ),
    "policies"  -> JsArray(states.allThreatPolicies().map(p => Json.obj("id" -> p.id, "name" -> p.name, "dry_run" -> p.dryRun)))
  )

  private def refFrom(json: JsValue): Option[IdentityRef] =
    (json \ "ref").asOpt[String].flatMap(IdentityRef.parse).orElse {
      for {
        kind  <- (json \ "kind").asOpt[String]
        value <- (json \ "value").asOpt[String]
      } yield IdentityRef(kind, value)
    }.orElse((json \ "ip").asOpt[String].map(IdentityRef(IdentityRef.Ip, _)))

  private def handleRobots(body: JsValue): Future[Result] = {
    botPolicy((body \ "policy").asOpt[String]) match {
      case None         => Results.Ok(Json.obj("done" -> false, "error" -> "no bot policy")).vfuture
      case Some(policy) =>
        Results.Ok(Json.obj("done" -> true, "robots_txt" -> policy.robotsTxt, "llms_txt" -> policy.llmsTxt)).vfuture
    }
  }

  private def handleChallengePreset(body: JsValue): Future[Result] = {
    (body \ "preset").asOpt[String].flatMap(ChallengePresets.find) match {
      case None         => Results.Ok(Json.obj("done" -> false, "error" -> "unknown preset")).vfuture
      case Some(preset) =>
        val provider = ChallengePresets.apply(preset, IdGenerator.namedId("challenge-provider", env))
        Results.Ok(Json.obj("done" -> true, "provider" -> provider.json)).vfuture
    }
  }

  private def handleBan(body: JsValue): Future[Result] = refFrom(body) match {
    case None      => Results.Ok(Json.obj("done" -> false, "error" -> "no identity provided")).vfuture
    case Some(ref) =>
      val duration = (body \ "duration_seconds").asOpt[Long].getOrElse(3600L).max(1L).seconds
      val reason   = (body \ "reason").asOpt[String].getOrElse("banned from the admin api")
      bans.ban(ref, duration, reason, Seq("manual")).map(e => Results.Ok(Json.obj("done" -> true, "ban" -> e.json)))
  }

  private def handleUnban(body: JsValue): Future[Result] = {
    if ((body \ "all").asOpt[Boolean].contains(true)) {
      bans.unbanAll().map(n => Results.Ok(Json.obj("done" -> true, "removed" -> n)))
    } else {
      refFrom(body) match {
        case None      => Results.Ok(Json.obj("done" -> false, "error" -> "no identity provided")).vfuture
        case Some(ref) => bans.unban(ref).map(ok => Results.Ok(Json.obj("done" -> ok, "ref" -> ref.json)))
      }
    }
  }

  private def handleLedger(body: JsValue): Future[Result] = refFrom(body) match {
    case Some(ref) if (body \ "forget").asOpt[Boolean].contains(true) =>
      ledger.forget(ref).map(ok => Results.Ok(Json.obj("done" -> ok)))
    case Some(ref) =>
      ledger.scoreOf(ref).map(score => Results.Ok(Json.obj("done" -> true, "ref" -> ref.json, "score" -> score)))
    case None      =>
      ledger.top((body \ "limit").asOpt[Int].getOrElse(50)).map(t => Results.Ok(Json.obj("done" -> true, "top" -> JsArray(t))))
  }

  /** Answers "what would this policy do at this score", without any traffic. */
  private def handleSimulate(body: JsValue): Future[Result] = {
    val score  = (body \ "score").asOpt[Int].getOrElse(0)
    val policy = policyOrDefault((body \ "policy").asOpt[String])
    val tier   = policy.tierFor(score)
    Results
      .Ok(
        Json.obj(
          "done"    -> true,
          "policy"  -> Json.obj("id" -> policy.id, "name" -> policy.name, "dry_run" -> policy.dryRun),
          "score"   -> score,
          "tier"    -> tier.map(_._1),
          "action"  -> tier.map(_._2.resolvedAction.name).getOrElse(ThreatAction.Allow.name),
          "enforced" -> (!policy.dryRun && tier.exists(_._2.resolvedAction.denies))
        )
      )
      .vfuture
  }

  private def withJsonBody(body: Option[Source[ByteString, ?]])(f: JsValue => Future[Result]): Future[Result] = {
    (body match {
      case None         => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(source) =>
        source.runFold(ByteString.empty)(_ ++ _).flatMap { raw =>
          Try(Json.parse(raw.utf8String)).toOption match {
            case None       => Results.Ok(Json.obj("done" -> false, "error" -> "invalid json body")).vfuture
            case Some(json) => f(json)
          }
        }
    }).recover { case err: Throwable =>
      logger.error("security backoffice call failed", err)
      Results.Ok(Json.obj("done" -> false, "error" -> err.getMessage))
    }
  }
}

object SecurityModule {
  /** Used when a plugin references no policy: observe and record, never enforce. */
  val builtinPolicy: ThreatPolicy = ThreatPolicy(
    id = "builtin",
    name = "Built-in (dry run)",
    description = "Records what it would have done. Create a ThreatPolicy to enforce anything.",
    dryRun = true
  )
}
