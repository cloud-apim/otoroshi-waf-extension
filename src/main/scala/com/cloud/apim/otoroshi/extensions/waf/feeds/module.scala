package com.cloud.apim.otoroshi.extensions.waf.feeds

import com.cloud.apim.otoroshi.extensions.waf.entities.{RuleFeed, WafRuleset}
import com.cloud.apim.seclang.impl.engine.SecLangEngine
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.next.extensions.AdminExtensionBackofficeAuthRoute
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.{WafExtensionDatastores, WafExtensionState}
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.{Result, Results}

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** One version of a feed, as it was fetched and checked. */
final case class FeedVersion(version: String, publishedAt: Long, fetchedAt: Long, signedBy: Option[String], raw: String, since: Long = 0L) {

  lazy val bundle: Option[RuleBundle] = RuleBundles.parse(raw).toOption

  def json: JsValue = Json.obj(
    "version"      -> version,
    "published_at" -> publishedAt,
    "fetched_at"   -> fetchedAt,
    "signed_by"    -> signedBy,
    "since"        -> since,
    "raw"          -> raw
  )

  /** What the console shows: everything but the bundle itself. */
  def summary: JsValue = Json.obj(
    "version"      -> version,
    "published_at" -> publishedAt,
    "fetched_at"   -> fetchedAt,
    "signed_by"    -> signedBy,
    "since"        -> since,
    "packs"        -> JsArray(bundle.toSeq.flatMap(_.packs.map(_.summary)))
  )
}

object FeedVersion {
  def read(json: JsValue): Option[FeedVersion] = Try {
    FeedVersion(
      version = (json \ "version").as[String],
      publishedAt = (json \ "published_at").asOpt[Long].getOrElse(0L),
      fetchedAt = (json \ "fetched_at").asOpt[Long].getOrElse(0L),
      signedBy = (json \ "signed_by").asOpt[String],
      raw = (json \ "raw").as[String],
      since = (json \ "since").asOpt[Long].getOrElse(0L)
    )
  }.toOption
}

/**
 * Where a feed stands: the version installed, the one before it, one waiting to be promoted, and
 * how the last check went.
 */
final case class FeedState(
    active: Option[FeedVersion] = None,
    previous: Option[FeedVersion] = None,
    pending: Option[FeedVersion] = None,
    // a version an operator rolled back from is not promoted again on its own
    rejected: Option[String] = None,
    lastCheckAt: Long = 0L,
    lastError: Option[String] = None,
    lastChecks: Seq[PackCheck] = Seq.empty
) {
  def json: JsValue = Json.obj(
    "active"        -> active.map(_.json),
    "previous"      -> previous.map(_.json),
    "pending"       -> pending.map(_.json),
    "rejected"      -> rejected,
    "last_check_at" -> lastCheckAt,
    "last_error"    -> lastError,
    "last_checks"   -> JsArray(lastChecks.map(_.json))
  )

  def summary: JsValue = Json.obj(
    "active"        -> active.map(_.summary),
    "previous"      -> previous.map(_.summary),
    "pending"       -> pending.map(_.summary),
    "rejected"      -> rejected,
    "last_check_at" -> lastCheckAt,
    "last_error"    -> lastError,
    "last_checks"   -> JsArray(lastChecks.map(_.json))
  )
}

object FeedState {
  def read(json: JsValue): FeedState = FeedState(
    active = (json \ "active").asOpt[JsObject].flatMap(FeedVersion.read),
    previous = (json \ "previous").asOpt[JsObject].flatMap(FeedVersion.read),
    pending = (json \ "pending").asOpt[JsObject].flatMap(FeedVersion.read),
    rejected = (json \ "rejected").asOpt[String],
    lastCheckAt = (json \ "last_check_at").asOpt[Long].getOrElse(0L),
    lastError = (json \ "last_error").asOpt[String],
    lastChecks = (json \ "last_checks").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { c =>
      PackCheck(
        (c \ "id").asOpt[String].getOrElse(""),
        (c \ "name").asOpt[String].getOrElse(""),
        (c \ "errors").asOpt[Seq[String]].getOrElse(Seq.empty),
        (c \ "tests").asOpt[Int].getOrElse(0),
        (c \ "passed").asOpt[Int].getOrElse(0)
      )
    }
  )
}

/**
 * Keeps every rule feed's packs installed (WAF-2, WAF-3).
 *
 * A version is installed only once it has been checked here: its signature, every pack compiled by
 * this gateway's engine and run against its own tests, and a version never older than the one in
 * place, so a replayed old bundle cannot take a patch away. It then waits the feed's promotion
 * delay, and each of its packs becomes a managed WAF ruleset. A rollback reinstalls the previous
 * version and refuses the one rolled back from until a newer one comes.
 *
 * Only the leader, or every node of a cluster without one, fetches and installs: workers get the
 * rulesets the way they get every entity.
 */
final class RuleFeedModule(
    env: Env,
    datastores: WafExtensionDatastores,
    states: WafExtensionState,
    engine: Seq[String] => SecLangEngine,
    statePrefix: String
) {

  private given ExecutionContext = env.otoroshiExecutionContext
  private given Env              = env

  private val logger   = Logger("cloud-apim-waf-rule-feeds")
  private val ticker   = new AtomicReference[Option[Cancellable]](None)
  private val inFlight = new TrieMap[String, Boolean]()
  private val basePath = "/extensions/cloud-apim/extensions/waf/feeds"

  def start(): Unit =
    if (!env.clusterConfig.mode.isWorker)
      ticker.set(Some(env.otoroshiScheduler.scheduleWithFixedDelay((10 + scala.util.Random.nextInt(20)).seconds, 30.seconds)(() => tick())))

  def stop(): Unit = ticker.getAndSet(None).foreach(_.cancel())

  private def stateKey(id: String): String = s"$statePrefix:rulefeeds:state:$id"

  def state(id: String): Future[FeedState] =
    env.datastores.redis.get(stateKey(id)).map(_.flatMap(bs => Try(Json.parse(bs.utf8String)).toOption).map(FeedState.read).getOrElse(FeedState()))

  private def save(id: String, state: FeedState): Future[FeedState] =
    env.datastores.redis.setBS(stateKey(id), ByteString(Json.stringify(state.json)), None, None).map(_ => state)

  private def tick(): Unit = {
    val now = System.currentTimeMillis()
    states.allRuleFeeds().filter(_.usable).foreach { feed =>
      if (inFlight.putIfAbsent(feed.id, true).isEmpty) {
        state(feed.id)
          .flatMap { s =>
            if (s.pending.exists(p => p.since + feed.promotionDelaySeconds * 1000L <= now)) promote(feed).map(_ => ())
            else if (s.lastCheckAt + feed.refreshIntervalSeconds * 1000L <= now) refresh(feed).map(_ => ())
            else Future.successful(())
          }
          .recover { case e => logger.error(s"rule feed '${feed.name}' could not be refreshed", e) }
          .andThen { case _ => inFlight.remove(feed.id) }
      }
    }
  }

  private def fetch(feed: RuleFeed): Future[Either[String, String]] =
    if (feed.url.startsWith("file:"))
      Future(Try(Files.readString(Paths.get(new java.net.URI(feed.url)))).toEither.left.map(e => s"could not read ${feed.url}: ${e.getMessage}"))
    else
      env.Ws
        .url(feed.url)
        .withHttpHeaders(feed.headers.toSeq*)
        .withRequestTimeout(feed.timeoutMillis.millis)
        .get()
        .map(res => if (res.status == 200) Right(res.body) else Left(s"the feed answered ${res.status}"))
        .recover { case e => Left(s"could not reach the feed: ${e.getMessage}") }

  /** The packs of a version this feed installs, checked by the local engine. */
  def check(feed: RuleFeed, bundle: RuleBundle): Seq[PackCheck] =
    RuleBundles.check(bundle.packs.filter(p => feed.installs(p.id)), engine)

  /** Fetches the feed, checks what it serves, and stages it when it is new and sound. */
  def refresh(feed: RuleFeed): Future[FeedState] = state(feed.id).flatMap { current =>
    val now = System.currentTimeMillis()
    def failed(error: String, checks: Seq[PackCheck] = Seq.empty) = {
      logger.warn(s"rule feed '${feed.name}': $error")
      save(feed.id, current.copy(lastCheckAt = now, lastError = Some(error), lastChecks = checks))
    }
    fetch(feed).flatMap {
      case Left(error)   => failed(error)
      case Right(served) =>
        RuleBundles.open(served, feed.trustedKeys, feed.allowUnsigned) match {
          case Left(error)     => failed(error)
          case Right(verified) =>
            val bundle  = verified.bundle
            val known   = current.active.exists(_.version == bundle.version) || current.pending.exists(_.version == bundle.version)
            val older   = current.active.exists(_.publishedAt > bundle.publishedAt)
            if (known) save(feed.id, current.copy(lastCheckAt = now, lastError = None))
            else if (current.rejected.contains(bundle.version)) failed(s"version ${bundle.version} was rolled back from, waiting for a newer one")
            else if (older) failed(s"version ${bundle.version} is older than the installed ${current.active.get.version}, a feed never goes back on its own")
            else {
              val checks = check(feed, bundle)
              if (checks.exists(!_.ok)) {
                val first = checks.find(!_.ok).get
                failed(s"version ${bundle.version} refused: pack '${first.id}' ${first.errors.head}", checks)
              } else {
                val staged = FeedVersion(bundle.version, bundle.publishedAt, now, verified.signedBy, verified.raw, since = now)
                logger.info(s"rule feed '${feed.name}': version ${bundle.version} checked, ${checks.map(_.tests).sum} pack tests passed")
                save(feed.id, current.copy(pending = Some(staged), lastCheckAt = now, lastError = None, lastChecks = checks)).flatMap { s =>
                  if (feed.promotionDelaySeconds <= 0L) promote(feed).map(_.getOrElse(s)) else Future.successful(s)
                }
              }
            }
        }
    }
  }

  /** Installs the pending version now, whatever is left of its delay. */
  def promote(feed: RuleFeed): Future[Either[String, FeedState]] = state(feed.id).flatMap { current =>
    current.pending match {
      case None          => Future.successful(Left("nothing is waiting to be installed"))
      case Some(pending) =>
        install(feed, pending).flatMap { _ =>
          save(feed.id, current.copy(active = Some(pending.copy(since = System.currentTimeMillis())), previous = current.active, pending = None))
            .map(Right(_))
        }
    }
  }

  /** Puts the previous version back, and refuses the one rolled back from until a newer one comes. */
  def rollback(feed: RuleFeed): Future[Either[String, FeedState]] = state(feed.id).flatMap { current =>
    (current.active, current.previous) match {
      case (Some(active), Some(previous)) =>
        install(feed, previous).flatMap { _ =>
          save(feed.id, current.copy(active = Some(previous), previous = Some(active), pending = None, rejected = Some(active.version))).map(Right(_))
        }
      case _                              => Future.successful(Left("there is no previous version to go back to"))
    }
  }

  /** The managed ruleset a pack becomes. Its id is stable across versions, so configs keep it. */
  def managedId(feedId: String, packId: String): String =
    s"rule-feed_${feedId.stripPrefix("rule-feed_").replaceAll("[^A-Za-z0-9_-]", "_")}_$packId"

  private def install(feed: RuleFeed, version: FeedVersion): Future[Unit] = version.bundle match {
    case None         => Future.failed(new IllegalStateException(s"version ${version.version} no longer parses"))
    case Some(bundle) =>
      val owner     = s"rule-feed:${feed.id}"
      val installed = bundle.packs.filter(p => feed.installs(p.id)).map { pack =>
        WafRuleset(
          id = managedId(feed.id, pack.id),
          name = pack.name,
          description = pack.description,
          tags = Seq("managed", pack.kind),
          metadata = Map(
            "managed_by"   -> owner,
            "feed"         -> feed.name,
            "feed_version" -> version.version,
            "pack_id"      -> pack.id,
            "pack_kind"    -> pack.kind
          ) ++ Option.when(pack.references.nonEmpty)("references" -> pack.references.mkString(" ")),
          enabled = true,
          rules = pack.rules
        )
      }
      // a pack the new version no longer carries is disabled, not deleted: a config still naming it
      // says so, rather than silently protecting less
      val retired   = states
        .allRulesets()
        .filter(r => r.metadata.get("managed_by").contains(owner) && !installed.exists(_.id == r.id) && r.enabled)
        .map(r => r.copy(enabled = false, metadata = r.metadata + ("retired_in" -> version.version)))
      val all       = installed ++ retired
      Future.sequence(all.map(r => datastores.wafRulesetDatastore.set(r))).map { _ =>
        states.updateRulesets(states.allRulesets().filterNot(r => all.exists(_.id == r.id)) ++ all)
        logger.info(s"rule feed '${feed.name}': version ${version.version} installed, ${installed.size} packs")
      }
  }

  def statuses(): Future[JsValue] =
    Future
      .sequence(states.allRuleFeeds().map(feed => state(feed.id).map(s => Json.obj("id" -> feed.id, "name" -> feed.name, "state" -> s.summary))))
      .map(JsArray(_))

  // -----------------------------------------------------------------------------------------------
  // backoffice
  // -----------------------------------------------------------------------------------------------

  private def withJsonBody(body: Option[Source[ByteString, ?]])(f: JsValue => Future[Result]): Future[Result] = body match {
    case None         => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
    case Some(source) =>
      source.runFold(ByteString.empty)(_ ++ _)(using env.otoroshiMaterializer).flatMap { raw =>
        Try(Json.parse(raw.utf8String)).toOption match {
          case None       => Results.Ok(Json.obj("done" -> false, "error" -> "invalid json body")).vfuture
          case Some(json) => f(json)
        }
      }
  }

  private def withFeed(json: JsValue)(f: RuleFeed => Future[Result]): Future[Result] =
    (json \ "id").asOpt[String].flatMap(states.ruleFeed) match {
      case None       => Results.Ok(Json.obj("done" -> false, "error" -> "no such rule feed")).vfuture
      case Some(feed) => f(feed)
    }

  private def answer(result: Future[Either[String, FeedState]]): Future[Result] = result.map {
    case Left(error)  => Results.Ok(Json.obj("done" -> false, "error" -> error))
    case Right(state) => Results.Ok(Json.obj("done" -> true, "state" -> state.summary))
  }

  def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "GET",
      path = s"$basePath/_status",
      wantsBody = false,
      handle = (_, _, _, _) => statuses().map(s => Results.Ok(Json.obj("feeds" -> s)))
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_refresh",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(json => withFeed(json)(feed => answer(refresh(feed).map(Right(_)))))
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_promote",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(json => withFeed(json)(feed => answer(promote(feed))))
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_rollback",
      wantsBody = true,
      handle = (_, _, _, body) => withJsonBody(body)(json => withFeed(json)(feed => answer(rollback(feed))))
    ),
    // what a bundle's author or its CI runs before publishing: the same checks, nothing installed
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = s"$basePath/_check",
      wantsBody = true,
      handle = (_, _, _, body) =>
        withJsonBody(body) { json =>
          val served  = (json \ "served").asOpt[String].orElse((json \ "bundle").toOption.map(Json.stringify)).getOrElse("")
          val keys    = (json \ "trusted_keys").asOpt[Seq[String]].getOrElse(Seq.empty)
          val unsigned = (json \ "allow_unsigned").asOpt[Boolean].getOrElse(keys.isEmpty)
          RuleBundles.open(served, keys, unsigned) match {
            case Left(error)     => Results.Ok(Json.obj("done" -> false, "error" -> error)).vfuture
            case Right(verified) =>
              val checks = RuleBundles.check(verified.bundle.packs, engine)
              Results
                .Ok(
                  Json.obj(
                    "done"      -> checks.forall(_.ok),
                    "version"   -> verified.bundle.version,
                    "signed_by" -> verified.signedBy,
                    "packs"     -> JsArray(checks.map(_.json))
                  )
                )
                .vfuture
          }
        }
    )
  )
}
