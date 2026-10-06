package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.objects.*
import com.cloud.apim.otoroshi.extensions.waf.security.*
import com.cloud.apim.otoroshi.extensions.waf.traffic.TrafficSettings
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * What the object guard counts as an object, what it watches for, and what it allows (BEH-1, BEH-2).
 *
 * Objects come from `paths`, templates such as `/api/orders/{id}`, and, with `auto_detect`, from any
 * path segment shaped like an identifier. Three patterns are watched per consumer and kind of object:
 * a surge of objects it had not read lately, a dense walk through numbered identifiers, and object
 * requests mostly refused or not found. By default they are only reported; `contribute` puts them on
 * the threat bus too. `budget` caps the distinct objects a consumer reads per window, cluster-wide.
 */
final case class CloudApimObjectGuardConfig(
    paths: Seq[ObjectTemplate] = Seq.empty,
    autoDetect: Boolean = true,
    contribute: Boolean = false,
    bucketSeconds: Int = 60,
    learningBuckets: Int = 60,
    warmupBuckets: Int = 10,
    surgeFactor: Double = 5.0,
    surgeFloor: Double = 30.0,
    warmupCeiling: Double = 150.0,
    sequentialMin: Int = 30,
    sequentialWindowSeconds: Long = 600L,
    sequentialMaxGap: Long = 3L,
    enumerationMin: Int = 20,
    enumerationRatio: Double = 0.5,
    enumerationWindowSeconds: Long = 300L,
    deniedStatuses: Seq[Int] = Seq(401, 403, 404),
    budget: Long = 0L,
    budgetWindowSeconds: Long = 3600L,
    budgetAction: String = "block",
    surgeWeight: Int = 40,
    sequentialWeight: Int = 50,
    enumerationWeight: Int = 50
) extends NgPluginConfig {
  override def json: JsValue = CloudApimObjectGuardConfig.format.writes(this)

  lazy val settings: ObjectSettings = ObjectSettings(
    surge = TrafficSettings(bucketSeconds, learningBuckets, warmupBuckets, surgeFactor),
    surgeFloor = surgeFloor,
    warmupCeiling = warmupCeiling,
    sequentialMin = sequentialMin,
    sequentialWindowMillis = sequentialWindowSeconds * 1000L,
    sequentialMaxGap = sequentialMaxGap,
    enumerationMin = enumerationMin,
    enumerationRatio = enumerationRatio,
    enumerationWindowMillis = enumerationWindowSeconds * 1000L
  )

  def budgetWindow: FiniteDuration = budgetWindowSeconds.max(1L).seconds
  def blocks: Boolean              = budgetAction == "block"
}

object CloudApimObjectGuardConfig {

  val default: CloudApimObjectGuardConfig = CloudApimObjectGuardConfig()

  val format: Format[CloudApimObjectGuardConfig] = new Format[CloudApimObjectGuardConfig] {
    override def writes(o: CloudApimObjectGuardConfig): JsValue = Json.obj(
      // a bare path stays a string, which the form can edit
      "paths"                      -> JsArray(o.paths.map(t => if (t.name.isEmpty && t.methods.isEmpty && t.budget.isEmpty) JsString(t.path) else t.json)),
      "auto_detect"                -> o.autoDetect,
      "contribute"                 -> o.contribute,
      "bucket_seconds"             -> o.bucketSeconds,
      "learning_buckets"           -> o.learningBuckets,
      "warmup_buckets"             -> o.warmupBuckets,
      "surge_factor"               -> o.surgeFactor,
      "surge_floor"                -> o.surgeFloor,
      "warmup_ceiling"             -> o.warmupCeiling,
      "sequential_min"             -> o.sequentialMin,
      "sequential_window_seconds"  -> o.sequentialWindowSeconds,
      "sequential_max_gap"         -> o.sequentialMaxGap,
      "enumeration_min"            -> o.enumerationMin,
      "enumeration_ratio"          -> o.enumerationRatio,
      "enumeration_window_seconds" -> o.enumerationWindowSeconds,
      "denied_statuses"            -> o.deniedStatuses,
      "budget"                     -> o.budget,
      "budget_window_seconds"      -> o.budgetWindowSeconds,
      "budget_action"              -> o.budgetAction,
      "surge_weight"               -> o.surgeWeight,
      "sequential_weight"          -> o.sequentialWeight,
      "enumeration_weight"         -> o.enumerationWeight
    )
    override def reads(json: JsValue): JsResult[CloudApimObjectGuardConfig] = Try {
      val d                                      = CloudApimObjectGuardConfig.default
      def bool(n: String, v: Boolean)            = json.select(n).asOpt[Boolean].getOrElse(v)
      def int(n: String, v: Int, min: Int = 0)   = json.select(n).asOpt[Int].filter(_ >= min).getOrElse(v)
      def long(n: String, v: Long, min: Long = 0L) = json.select(n).asOpt[Long].filter(_ >= min).getOrElse(v)
      def dbl(n: String, v: Double)              = json.select(n).asOpt[Double].filter(_ > 0.0).getOrElse(v)
      CloudApimObjectGuardConfig(
        paths = json.select("paths").asOpt[JsArray].map(_.value.toSeq.flatMap(ObjectTemplate.read)).getOrElse(d.paths),
        autoDetect = bool("auto_detect", d.autoDetect),
        contribute = bool("contribute", d.contribute),
        bucketSeconds = int("bucket_seconds", d.bucketSeconds, 1),
        learningBuckets = int("learning_buckets", d.learningBuckets, 1),
        warmupBuckets = int("warmup_buckets", d.warmupBuckets),
        surgeFactor = json.select("surge_factor").asOpt[Double].filter(_ > 1.0).getOrElse(d.surgeFactor),
        surgeFloor = dbl("surge_floor", d.surgeFloor),
        warmupCeiling = dbl("warmup_ceiling", d.warmupCeiling),
        sequentialMin = int("sequential_min", d.sequentialMin, 2),
        sequentialWindowSeconds = long("sequential_window_seconds", d.sequentialWindowSeconds, 1L),
        sequentialMaxGap = long("sequential_max_gap", d.sequentialMaxGap, 1L),
        enumerationMin = int("enumeration_min", d.enumerationMin, 1),
        enumerationRatio = json.select("enumeration_ratio").asOpt[Double].filter(r => r > 0.0 && r <= 1.0).getOrElse(d.enumerationRatio),
        enumerationWindowSeconds = long("enumeration_window_seconds", d.enumerationWindowSeconds, 1L),
        deniedStatuses = json.select("denied_statuses").asOpt[Seq[Int]].getOrElse(d.deniedStatuses),
        budget = long("budget", d.budget),
        budgetWindowSeconds = long("budget_window_seconds", d.budgetWindowSeconds, 1L),
        budgetAction = json.select("budget_action").asOpt[String].map(_.trim.toLowerCase).filter(Set("block", "log")).getOrElse(d.budgetAction),
        surgeWeight = int("surge_weight", d.surgeWeight),
        sequentialWeight = int("sequential_weight", d.sequentialWeight),
        enumerationWeight = int("enumeration_weight", d.enumerationWeight)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "paths", "auto_detect", "contribute", "budget", "budget_window_seconds", "budget_action",
    "surge_factor", "surge_floor", "warmup_ceiling", "bucket_seconds", "learning_buckets", "warmup_buckets",
    "sequential_min", "sequential_window_seconds", "sequential_max_gap",
    "enumeration_min", "enumeration_ratio", "enumeration_window_seconds", "denied_statuses",
    "surge_weight", "sequential_weight", "enumeration_weight"
  )

  private def bool(label: String, help: String)   = Json.obj("type" -> "bool", "label" -> label, "props" -> Json.obj("help" -> help))
  private def number(label: String, help: String) = Json.obj("type" -> "number", "label" -> label, "props" -> Json.obj("help" -> help))

  val configSchema: JsObject = Json.obj(
    "paths"                      -> Json.obj(
      "type"  -> "array",
      "label" -> "Object paths",
      "props" -> Json.obj("help" -> "Templates such as /api/orders/{id}: each {name} is one segment of the identifier, a trailing * anything after")
    ),
    "auto_detect"                -> bool("Detect identifiers", "Also take any path segment shaped like an identifier (a number, a UUID, an ObjectId, a ULID) as an object"),
    "contribute"                 -> bool("Score", "Put what is seen on the threat bus. Off, it is only reported"),
    "budget"                     -> number("Budget", "Distinct objects of one kind a consumer may read per window, across the cluster. 0 is none"),
    "budget_window_seconds"      -> number("Budget window", "Seconds over which the budget is counted"),
    "budget_action"              -> Json.obj(
      "type"  -> "select",
      "label" -> "Past the budget",
      "props" -> Json.obj(
        "help"    -> "Refuse a new object with a 429, or only report it",
        "options" -> Json.arr(Json.obj("label" -> "Block", "value" -> "block"), Json.obj("label" -> "Log", "value" -> "log"))
      )
    ),
    "surge_factor"               -> number("Surge factor", "How many times its usual pace of new objects a consumer reaches to be surging"),
    "surge_floor"                -> number("Surge floor", "New objects per bucket, on this node, below which a consumer is never surging"),
    "warmup_ceiling"             -> number("Warm-up ceiling", "New objects per bucket a consumer may read before its pace is learned"),
    "bucket_seconds"             -> number("Bucket", "Seconds of new objects counted together"),
    "learning_buckets"           -> number("Learning", "About how many buckets a consumer's pace is averaged over"),
    "warmup_buckets"             -> number("Warm-up", "Buckets before a consumer's own pace is what it is judged against"),
    "sequential_min"             -> number("Walk: objects", "Numbered objects in a dense run to call it a walk"),
    "sequential_window_seconds"  -> number("Walk: window", "Seconds the run is read within"),
    "sequential_max_gap"         -> number("Walk: gap", "Median distance between identifiers in a dense run"),
    "enumeration_min"            -> number("Enumeration: refused", "Object requests refused or not found, in a window, to call it an enumeration"),
    "enumeration_ratio"          -> number("Enumeration: ratio", "Share of the object requests refused or not found"),
    "enumeration_window_seconds" -> number("Enumeration: window", "Seconds the responses are counted over"),
    "denied_statuses"            -> Json.obj("type" -> "array", "label" -> "Refused statuses", "props" -> Json.obj("help" -> "What the backend answers when it refuses an object or has none")),
    "surge_weight"               -> number("Surge weight", "What a surge contributes, more past twice and four times its threshold"),
    "sequential_weight"          -> number("Walk weight", "What a dense walk through identifiers contributes"),
    "enumeration_weight"         -> number("Enumeration weight", "What mostly refused object requests contribute")
  )
}

/** One object request, carried from the request to its response. */
final case class ObjectTouch(key: String, ref: ObjectRef)

object CloudApimObjectGuard {

  val TouchKey: TypedKey[ObjectTouch] = TypedKey[ObjectTouch]("cloud-apim.waf.objects.touch")

  /** Who reads: the api key, then the user, then the address. */
  def consumerOf(identity: ClientIdentity): String =
    identity.apikey.map(k => s"apikey:$k").orElse(identity.user.map(u => s"user:$u")).getOrElse(s"ip:${identity.ip}")
}

/**
 * Object-level abuse: BOLA and enumeration (BEH-1), and scraping within the rate limit (BEH-2).
 *
 * A consumer that usually reads eleven records an hour and walks forty thousand sequential ids is
 * the canonical broken object level authorisation incident, and no single request shows it. On the
 * way in, each object request is noted against its consumer and kind of object; on the way back,
 * the status says whether it was refused or not found. A surge of new objects, a dense walk and
 * mostly refused requests are reported, once a minute per consumer, kind and pattern, and with
 * `contribute` scored on the threat bus.
 *
 * The budget is the part that refuses: a new object past it gets a 429 until the window ends, while
 * objects already read stay readable.
 */
class CloudApimObjectGuard extends NgRequestTransformer {

  private val logger = Logger("cloud-apim-waf-objects")

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Threat Protection - Object guard"
  override def description: Option[String]                 =
    "Sees enumeration and walks through object identifiers per consumer, and budgets the distinct objects each one reads".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimObjectGuardConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimObjectGuardConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimObjectGuardConfig.configSchema.some

  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  // a pattern is reported once a minute per key, the bus carries every request
  private val reported = new TrieMap[String, Long]()

  private def config(ctx: NgCachedConfigContext): CloudApimObjectGuardConfig =
    ctx.cachedConfig(internalName)(CloudApimObjectGuardConfig.format).getOrElse(CloudApimObjectGuardConfig.default)

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val cfg     = config(ctx)
    val request = ctx.otoroshiRequest
    // API-1: what the contract says the object is, when a contract matched the request first
    val contract = ctx.attrs.get(CloudApimApiContract.TouchKey).flatMap(t => t.matched.objectId.map(ObjectRef(t.matched.operation.path, _)))
    (ThreatSupport.module, ObjectPaths.resolve(cfg.paths, cfg.autoDetect, ctx.request.method, ctx.request.path, contract)) match {
      case (Some(mod), Some((ref, template))) =>
        val now      = System.currentTimeMillis()
        val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
        val consumer = CloudApimObjectGuard.consumerOf(identity)
        val key      = s"${ctx.route.id}|$consumer|${ref.kind}"
        ctx.attrs.put(CloudApimObjectGuard.TouchKey -> ObjectTouch(key, ref))
        mod.objects.touch(key, ref, now, cfg.settings).foreach(sighting => observe(mod, ctx, cfg, identity, consumer, key, ref, sighting, now))
        val budget = template.flatMap(_.budget).getOrElse(cfg.budget)
        if (budget <= 0L) request.rightf
        else
          mod.objectBudgets
            .take(key, ref.id, budget, cfg.budgetWindow, now)
            .flatMap {
              case BudgetVerdict.Over(limit, retryAfter) =>
                val detail = s"$consumer read $limit distinct ${ref.kind} in ${cfg.budgetWindowSeconds}s, and asked for ${ref.id}"
                report(mod, ctx, identity, key, "over_budget", 0, detail, cfg.blocks, Json.obj("budget" -> limit, "window_s" -> cfg.budgetWindowSeconds, "kind" -> ref.kind), now)
                if (!cfg.blocks) request.rightf
                else {
                  val seconds = math.max(1L, (retryAfter + 999L) / 1000L)
                  ThreatSupport.denyT(429, ctx).map(r => Left(r.withHeaders("Retry-After" -> seconds.toString)))
                }
              case _                                     => request.rightf
            }
            .recover { case e =>
              // a budget is never worth an outage: the store unreachable, the object goes through
              logger.error("could not count an object against its budget, it goes through", e)
              Right(request)
            }
      case _                                  => request.rightf
    }
  }

  private def observe(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      cfg: CloudApimObjectGuardConfig,
      identity: ClientIdentity,
      consumer: String,
      key: String,
      ref: ObjectRef,
      sighting: ObjectSighting,
      now: Long
  ): Unit = {
    val seen = Seq(
      sighting.surge.map { r =>
        (
          "surge",
          CloudApimTrafficGuard.escalate(cfg.surgeWeight, r),
          f"${r.count} ${ref.kind} not read lately in this ${cfg.bucketSeconds}s, ${r.ratio}%.1fx the usual for $consumer",
          Json.obj("count" -> r.count, "baseline" -> r.baseline, "threshold" -> r.threshold, "bucket_s" -> cfg.bucketSeconds)
        )
      },
      sighting.sequential.map { w =>
        (
          "sequential",
          cfg.sequentialWeight,
          s"$consumer read ${w.ids} ${ref.kind} in a dense run, from ${w.from} to ${w.to}, a median of ${w.medianGap} apart",
          Json.obj("ids" -> w.ids, "from" -> w.from, "to" -> w.to, "median_gap" -> w.medianGap)
        )
      },
      sighting.enumeration.map { e =>
        (
          "enumeration",
          cfg.enumerationWeight,
          s"${e.denied} of $consumer's last ${e.total} ${ref.kind} requests were refused or not found",
          Json.obj("denied" -> e.denied, "total" -> e.total, "ratio" -> e.ratio)
        )
      }
    ).flatten
    seen.foreach { case (kind, weight, detail, facts) =>
      if (cfg.contribute)
        ThreatBus.contribute(ctx.attrs, identity, ThreatSignal(source = "objects", kind = kind, weight = weight, tag = s"objects:$kind", detail = Some(detail)))
      report(mod, ctx, identity, key, kind, weight, detail, false, facts ++ Json.obj("kind" -> ref.kind, "object" -> ref.id), now)
    }
  }

  private def report(
      mod: SecurityModule,
      ctx: NgTransformerRequestContext,
      identity: ClientIdentity,
      key: String,
      pattern: String,
      weight: Int,
      detail: String,
      refused: Boolean,
      facts: JsObject,
      now: Long
  ): Unit = {
    val at = s"$key|$pattern"
    if (reported.get(at).forall(_ < now)) {
      reported.put(at, now + 60000L)
      if (reported.size > 10000) reported.filterInPlace((_, until) => until > now)
      mod.record(
        category = "objects",
        identity = identity,
        decision = ThreatDecision(
          action = if (refused) ThreatAction.Deny else ThreatAction.Log,
          score = weight,
          tier = None,
          dryRun = !refused,
          reason = detail
        ),
        tags = Seq(s"objects:$pattern"),
        signals = Json.arr(facts ++ Json.obj("pattern" -> pattern)),
        routeId = ctx.route.id.some,
        routeName = ctx.route.name.some,
        message = s"${pattern.replace('_', ' ')}: $detail"
      )
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    (ThreatSupport.module, ctx.attrs.get(CloudApimObjectGuard.TouchKey)) match {
      case (Some(mod), Some(touch)) =>
        val cfg = config(ctx)
        mod.objects.settle(touch.key, cfg.deniedStatuses.contains(ctx.otoroshiResponse.status), System.currentTimeMillis(), cfg.settings)
      case _                        => ()
    }
    ctx.otoroshiResponse.rightf
  }
}
