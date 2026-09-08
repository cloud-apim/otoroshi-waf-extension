package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.otoroshi.extensions.waf.entities.*
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.models.EntityLocationSupport
import otoroshi.next.extensions.*
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.mvc.{Result, Results}
import play.api.{Configuration, Logger}

import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

class ReputationDatastores(env: Env, extensionId: AdminExtensionId) {
  val threatFeedDatastore: ThreatFeedDatastore           = new KvThreatFeedDatastore(extensionId, env.datastores.redis, env)
  val crowdSecBouncerDatastore: CrowdSecBouncerDatastore = new KvCrowdSecBouncerDatastore(extensionId, env.datastores.redis, env)
}

class ReputationState {

  private val _feeds    = new UnboundedTrieMap[String, ThreatFeed]()
  private val _bouncers = new UnboundedTrieMap[String, CrowdSecBouncer]()

  val registry: ReputationRegistry = new ReputationRegistry()

  def threatFeed(id: String): Option[ThreatFeed] = _feeds.get(id)
  def allThreatFeeds(): Seq[ThreatFeed]          = _feeds.values.toSeq
  def updateThreatFeeds(values: Seq[ThreatFeed]): Unit = {
    _feeds.addAll(values.map(v => (v.id, v))).remAll(_feeds.keySet.toSeq.diff(values.map(_.id)))
  }

  def crowdSecBouncer(id: String): Option[CrowdSecBouncer] = _bouncers.get(id)
  def allCrowdSecBouncers(): Seq[CrowdSecBouncer]          = _bouncers.values.toSeq
  def updateCrowdSecBouncers(values: Seq[CrowdSecBouncer]): Unit = {
    _bouncers.addAll(values.map(v => (v.id, v))).remAll(_bouncers.keySet.toSeq.diff(values.map(_.id)))
  }
}

/**
 * Everything the reputation layer needs, packaged so the WAF extension only has to delegate.
 *
 * It is deliberately a module inside the existing extension rather than a second extension: the
 * WAF entities, plugins, storage keys and API group are untouched, and an operator who upgrades
 * without configuring a single feed sees no behaviour change at all.
 */
class ReputationModule(env: Env, extensionId: AdminExtensionId, configuration: Configuration) {

  private given ExecutionContext = env.otoroshiExecutionContext
  private given Materializer     = env.otoroshiMaterializer

  private val logger = Logger("cloud-apim-waf-reputation")

  private val enabled: Boolean = configuration.getOptional[Boolean]("reputation.enabled").getOrElse(true)
  private val tickInterval: FiniteDuration =
    configuration.getOptional[Long]("reputation.tick-interval-seconds").getOrElse(5L).max(1L).seconds

  private val datastores = new ReputationDatastores(env, extensionId)

  private val http: ReputationHttpClient = new EnvHttpClient(env)

  val states: ReputationState  = new ReputationState()
  val refresher: FeedRefresher = new FeedRefresher(http, states.registry, logger)
  val crowdsec: CrowdSecClient = new CrowdSecClient(http, states.registry, logger)

  private val ticker   = new AtomicReference[Option[Cancellable]](None)
  private val relay    = new AtomicReference[Option[org.apache.pekko.actor.ActorRef]](None)
  private val inFlight = new TrieMap[String, Boolean]()

  private val basePath = "/extensions/cloud-apim/extensions/waf/reputation"

  // -----------------------------------------------------------------------------------------------
  // lifecycle
  // -----------------------------------------------------------------------------------------------

  def start(): Unit = {
    if (enabled) {
      // nodes stagger their first tick so a cluster restart does not hit every feed provider at once
      val initialDelay = (5L + Random.nextInt(20)).seconds
      ticker.set(Some(env.otoroshiScheduler.scheduleWithFixedDelay(initialDelay, tickInterval)(() => tick())))
      startWafRelay()
      logger.info(s"the WAF reputation module is enabled (tick every ${tickInterval.toSeconds}s)")
    }
  }

  def stop(): Unit = {
    ticker.getAndSet(None).foreach(_.cancel())
    relay.getAndSet(None).foreach { actor =>
      env.analyticsActorSystem.eventStream.unsubscribe(actor)
      env.analyticsActorSystem.stop(actor)
    }
  }

  /**
   * Subscribes to the analytics event stream so waf detections can be reported to CrowdSec
   * without the waf plugin being aware of it.
   *
   * Otoroshi only publishes plugin events on that stream when `useEventStreamForScriptEvents`
   * is on — it is by default. When it is off the relay would be silently inert, so say so at
   * startup rather than let an operator believe detections are being reported.
   */
  private def startWafRelay(): Unit = {
    given Env = env
    if (!env.useEventStreamForScriptEvents) {
      logger.warn(
        "'otoroshi.options.useEventStreamForScriptEvents' is off, so waf detections cannot be relayed to CrowdSec"
      )
    } else {
      val actor = env.analyticsActorSystem.actorOf(WafDetectionRelay.props(this, logger))
      env.analyticsActorSystem.eventStream.subscribe(actor, classOf[otoroshi.events.AnalyticEvent])
      relay.set(Some(actor))
    }
  }

  def syncStates(): Future[Unit] = {
    given Env = env
    for {
      feeds    <- datastores.threatFeedDatastore.findAllAndFillSecrets()
      bouncers <- datastores.crowdSecBouncerDatastore.findAllAndFillSecrets()
    } yield {
      states.updateThreatFeeds(feeds)
      states.updateCrowdSecBouncers(bouncers)
      // drop the runtime index of anything that no longer exists
      states.registry.retainSnapshots(feeds.map(_.id).toSet)
      val bouncerIds = bouncers.map(_.id).toSet
      states.registry.allCrowdSecStores.keySet.diff(bouncerIds).foreach(crowdsec.forget)
      states.registry.retainCrowdSecStores(bouncerIds)
      ()
    }
  }

  def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = Seq(
    AdminExtensionEntity(ThreatFeed.resource(env, datastores, states)),
    AdminExtensionEntity(CrowdSecBouncer.resource(env, datastores, states))
  )

  // -----------------------------------------------------------------------------------------------
  // scheduled work
  // -----------------------------------------------------------------------------------------------

  private def tick(): Unit = {
    Try {
      val now = System.currentTimeMillis()
      states.allThreatFeeds().filter(_.usable).foreach { feed =>
        guard(s"feed:${feed.id}", refresher.isDue(feed, now))(refresher.refresh(feed).map(_ => ()))
      }
      states.allCrowdSecBouncers().foreach { bouncer =>
        guard(s"pull:${bouncer.id}", bouncer.pullUsable && crowdsec.isPullDue(bouncer, now))(
          crowdsec.pull(bouncer).map(_ => ())
        )
        guard(s"push:${bouncer.id}", bouncer.pushUsable && crowdsec.isPushDue(bouncer, now))(
          crowdsec.flush(bouncer).map(_ => ())
        )
      }
    } match {
      case scala.util.Failure(err) => logger.error("reputation tick failed", err)
      case _                       => ()
    }
  }

  /** Never lets two refreshes of the same source overlap, however slow the source is. */
  private def guard(key: String, due: Boolean)(work: => Future[Unit]): Unit = {
    if (due && inFlight.putIfAbsent(key, true).isEmpty) {
      work.andThen { case _ => inFlight.remove(key) }
      ()
    }
  }

  // -----------------------------------------------------------------------------------------------
  // lookup, used by the plugins
  // -----------------------------------------------------------------------------------------------

  def lookup(ip: String, feedIds: Seq[String], bouncerIds: Seq[String]): ReputationVerdict = {
    val feeds = if (feedIds.isEmpty) states.allThreatFeeds().filter(_.enabled)
                else feedIds.flatMap(states.threatFeed).filter(_.enabled)
    val bouncers = if (bouncerIds.isEmpty) states.allCrowdSecBouncers().filter(_.enabled)
                   else bouncerIds.flatMap(states.crowdSecBouncer).filter(_.enabled)
    states.registry.lookup(ip, feeds, bouncers)
  }

  /**
   * Called for every waf trail event.
   *
   * Only bouncers that opted in receive anything, and by default only enforced blocks are
   * reported — a WAF running in monitoring mode should not be quietly getting addresses banned
   * elsewhere.
   */
  def onWafDetection(event: JsValue): Unit = {
    val targets = states.allCrowdSecBouncers().filter(_.relaysWafDetections)
    if (targets.nonEmpty) {
      val hasBlock = (event \ "block").asOpt[JsObject].isDefined
      val enforced = hasBlock && event.select("blocking").asOpt[Boolean].getOrElse(false)
      val eligible = targets.filter(b => enforced || b.pushWafMonitored)
      if (eligible.nonEmpty) {
        WafDetectionRelay.clientIp(event).foreach { ip =>
          val message = WafDetectionRelay.message(event, enforced)
          eligible.foreach(bouncer => crowdsec.enqueue(bouncer, CrowdSecSignal(ip, message)))
        }
      }
    }
  }

  def reportToCrowdSec(ip: String, message: String, bouncerIds: Seq[String]): Unit = {
    val bouncers = if (bouncerIds.isEmpty) states.allCrowdSecBouncers()
                   else bouncerIds.flatMap(states.crowdSecBouncer)
    bouncers.filter(_.pushUsable).foreach(b => crowdsec.enqueue(b, CrowdSecSignal(ip, message)))
  }

  // -----------------------------------------------------------------------------------------------
  // backoffice routes
  // -----------------------------------------------------------------------------------------------

  def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_catalog",
      wantsBody = false,
      handle = (_, _, _, _) => Results.Ok(ThreatFeedCatalog.json).vfuture
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_status",
      wantsBody = false,
      handle = (_, _, _, _) => Results.Ok(status).vfuture
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_template",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleTemplate)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_refresh",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleRefresh)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_rollback",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleRollback)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_lookup",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleLookup)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_crowdsec_sync",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(handleCrowdSecSync)
    )
  )

  private def status: JsValue = {
    val feeds = states.allThreatFeeds().map { feed =>
      val snapshot = states.registry.snapshot(feed.id)
      Json.obj(
        "id"       -> feed.id,
        "name"     -> feed.name,
        "enabled"  -> feed.enabled,
        "url"      -> feed.url,
        "format"   -> feed.format,
        "action"   -> feed.action,
        "weight"   -> feed.weight,
        "tag"      -> feed.effectiveTag,
        "snapshot" -> snapshot.map(_.json).getOrElse(JsNull).asValue,
        "previous" -> states.registry.previousSnapshot(feed.id).map(_.json).getOrElse(JsNull).asValue
      )
    }
    val bouncers = states.allCrowdSecBouncers().map { bouncer =>
      Json.obj(
        "id"            -> bouncer.id,
        "name"          -> bouncer.name,
        "enabled"       -> bouncer.enabled,
        "lapi_url"      -> bouncer.lapiUrl,
        "action"        -> bouncer.action,
        "weight"        -> bouncer.weight,
        "push_enabled"  -> bouncer.pushEnabled,
        "pending_push"  -> crowdsec.pendingPushes(bouncer.id),
        "store"         -> states.registry.crowdSecStoreOpt(bouncer.id).map(_.status).getOrElse(JsNull).asValue
      )
    }
    Json.obj("feeds" -> JsArray(feeds), "crowdsec" -> JsArray(bouncers))
  }

  private def handleTemplate(body: JsValue): Future[Result] = {
    body.select("entry").asOptString.flatMap(id => ThreatFeed.fromCatalog(id, env)) match {
      case None       => Results.Ok(Json.obj("done" -> false, "error" -> "unknown catalog entry")).vfuture
      case Some(feed) => Results.Ok(Json.obj("done" -> true, "feed" -> feed.json)).vfuture
    }
  }

  private def handleRefresh(body: JsValue): Future[Result] = {
    val requested = body.select("feed").asOptString
    val feeds     = requested match {
      case Some(id) => states.threatFeed(id).toSeq
      case None     => states.allThreatFeeds().filter(_.usable)
    }
    if (feeds.isEmpty) {
      Results.Ok(Json.obj("done" -> false, "error" -> "no matching feed")).vfuture
    } else {
      Future.sequence(feeds.map(refresher.refresh)).map { snapshots =>
        Results.Ok(Json.obj("done" -> true, "snapshots" -> JsArray(snapshots.map(_.json))))
      }
    }
  }

  private def handleRollback(body: JsValue): Future[Result] = {
    body.select("feed").asOptString match {
      case None     => Results.Ok(Json.obj("done" -> false, "error" -> "no feed provided")).vfuture
      case Some(id) =>
        states.registry.rollbackSnapshot(id) match {
          case None           =>
            Results.Ok(Json.obj("done" -> false, "error" -> "no previous snapshot to roll back to")).vfuture
          case Some(restored) =>
            logger.warn(s"threat feed '$id' rolled back to its previous snapshot (${restored.entries} entries)")
            Results.Ok(Json.obj("done" -> true, "snapshot" -> restored.json)).vfuture
        }
    }
  }

  private def handleLookup(body: JsValue): Future[Result] = {
    body.select("ip").asOptString.map(_.trim).filter(_.nonEmpty) match {
      case None     => Results.Ok(Json.obj("done" -> false, "error" -> "no ip provided")).vfuture
      case Some(ip) =>
        val feedIds    = body.select("feeds").asOpt[Seq[String]].getOrElse(Seq.empty)
        val bouncerIds = body.select("crowdsec").asOpt[Seq[String]].getOrElse(Seq.empty)
        Results.Ok(Json.obj("done" -> true, "verdict" -> lookup(ip, feedIds, bouncerIds).json)).vfuture
    }
  }

  private def handleCrowdSecSync(body: JsValue): Future[Result] = {
    val bouncers = body.select("bouncer").asOptString match {
      case Some(id) => states.crowdSecBouncer(id).toSeq
      case None     => states.allCrowdSecBouncers().filter(_.pullUsable)
    }
    if (bouncers.isEmpty) {
      Results.Ok(Json.obj("done" -> false, "error" -> "no matching crowdsec bouncer")).vfuture
    } else {
      Future.sequence(bouncers.map(b => crowdsec.pull(b).map(res => (b, res)))).map { results =>
        Results.Ok(
          Json.obj(
            "done"    -> true,
            "results" -> JsArray(results.map { case (bouncer, res) =>
              Json.obj(
                "id"        -> bouncer.id,
                "name"      -> bouncer.name,
                "ok"        -> res.isRight,
                "decisions" -> res.toOption.getOrElse(0),
                "error"     -> res.left.toOption
              )
            })
          )
        )
      }
    }
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
      logger.error("reputation backoffice call failed", err)
      Results.Ok(Json.obj("done" -> false, "error" -> err.getMessage))
    }
  }
}
