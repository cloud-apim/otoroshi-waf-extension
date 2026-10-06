package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.security.*
import com.cloud.apim.otoroshi.extensions.waf.traffic.{TrafficReading, TrafficSettings}
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import play.api.libs.json.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * What the traffic guard watches and what a surge is (BEH-4).
 *
 * Four dimensions, each learned on its own for the route: the route as a whole, each source
 * address, each api key, each network (ASN, when an ASN database is loaded). A surge is a bucket
 * carrying more than `surge_factor` times what the dimension usually does, and more than its floor:
 * floors are requests per second, on this node.
 */
final case class CloudApimTrafficGuardConfig(
    route: Boolean = true,
    source: Boolean = true,
    consumer: Boolean = true,
    asn: Boolean = true,
    bucketSeconds: Int = 10,
    learningBuckets: Int = 60,
    warmupBuckets: Int = 6,
    surgeFactor: Double = 3.0,
    routeFloorRps: Double = 20.0,
    sourceFloorRps: Double = 5.0,
    consumerFloorRps: Double = 10.0,
    asnFloorRps: Double = 20.0,
    routeWeight: Int = 40,
    sourceWeight: Int = 40,
    consumerWeight: Int = 40,
    asnWeight: Int = 30
) extends NgPluginConfig {
  override def json: JsValue = CloudApimTrafficGuardConfig.format.writes(this)

  def settings: TrafficSettings = TrafficSettings(bucketSeconds, learningBuckets, warmupBuckets, surgeFactor)
}

object CloudApimTrafficGuardConfig {

  val default: CloudApimTrafficGuardConfig = CloudApimTrafficGuardConfig()

  /** What the preset's sensitivity means: how far from usual traffic a surge starts. */
  def surgeFactorOf(sensitivity: String): Double = sensitivity.trim.toLowerCase match {
    case "low"  => 5.0
    case "high" => 2.0
    case _      => 3.0
  }

  val format: Format[CloudApimTrafficGuardConfig] = new Format[CloudApimTrafficGuardConfig] {
    override def writes(o: CloudApimTrafficGuardConfig): JsValue = Json.obj(
      "route"              -> o.route,
      "source"             -> o.source,
      "consumer"           -> o.consumer,
      "asn"                -> o.asn,
      "bucket_seconds"     -> o.bucketSeconds,
      "learning_buckets"   -> o.learningBuckets,
      "warmup_buckets"     -> o.warmupBuckets,
      "surge_factor"       -> o.surgeFactor,
      "route_floor_rps"    -> o.routeFloorRps,
      "source_floor_rps"   -> o.sourceFloorRps,
      "consumer_floor_rps" -> o.consumerFloorRps,
      "asn_floor_rps"      -> o.asnFloorRps,
      "route_weight"       -> o.routeWeight,
      "source_weight"      -> o.sourceWeight,
      "consumer_weight"    -> o.consumerWeight,
      "asn_weight"         -> o.asnWeight
    )
    override def reads(json: JsValue): JsResult[CloudApimTrafficGuardConfig] = Try {
      val d                                    = CloudApimTrafficGuardConfig.default
      def bool(n: String, v: Boolean)          = json.select(n).asOpt[Boolean].getOrElse(v)
      def int(n: String, v: Int, min: Int = 0) = json.select(n).asOpt[Int].filter(_ >= min).getOrElse(v)
      def dbl(n: String, v: Double)            = json.select(n).asOpt[Double].filter(_ > 0.0).getOrElse(v)
      CloudApimTrafficGuardConfig(
        route = bool("route", d.route),
        source = bool("source", d.source),
        consumer = bool("consumer", d.consumer),
        asn = bool("asn", d.asn),
        bucketSeconds = int("bucket_seconds", d.bucketSeconds, 1),
        learningBuckets = int("learning_buckets", d.learningBuckets, 1),
        warmupBuckets = int("warmup_buckets", d.warmupBuckets),
        surgeFactor = json.select("surge_factor").asOpt[Double].filter(_ > 1.0).getOrElse(d.surgeFactor),
        routeFloorRps = dbl("route_floor_rps", d.routeFloorRps),
        sourceFloorRps = dbl("source_floor_rps", d.sourceFloorRps),
        consumerFloorRps = dbl("consumer_floor_rps", d.consumerFloorRps),
        asnFloorRps = dbl("asn_floor_rps", d.asnFloorRps),
        routeWeight = int("route_weight", d.routeWeight),
        sourceWeight = int("source_weight", d.sourceWeight),
        consumerWeight = int("consumer_weight", d.consumerWeight),
        asnWeight = int("asn_weight", d.asnWeight)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "route", "source", "consumer", "asn", "surge_factor", "bucket_seconds", "learning_buckets", "warmup_buckets",
    "route_floor_rps", "source_floor_rps", "consumer_floor_rps", "asn_floor_rps",
    "route_weight", "source_weight", "consumer_weight", "asn_weight"
  )

  private def bool(label: String, help: String)   = Json.obj("type" -> "bool", "label" -> label, "props" -> Json.obj("help" -> help))
  private def number(label: String, help: String) = Json.obj("type" -> "number", "label" -> label, "props" -> Json.obj("help" -> help))

  val configSchema: JsObject = Json.obj(
    "route"              -> bool("Watch the route", "The route's own traffic, all callers together"),
    "source"             -> bool("Watch each source", "Each client address on its own"),
    "consumer"           -> bool("Watch each api key", "Each api key on its own"),
    "asn"                -> bool("Watch each network", "Each ASN on its own, when an ASN database is loaded"),
    "surge_factor"       -> number("Surge factor", "How many times its usual traffic a bucket carries to be a surge"),
    "bucket_seconds"     -> number("Bucket", "Seconds of traffic counted together"),
    "learning_buckets"   -> number("Learning", "About how many buckets the usual traffic is averaged over"),
    "warmup_buckets"     -> number("Warm-up", "Buckets seen before anything is judged"),
    "route_floor_rps"    -> number("Route floor", "Requests per second, on this node, below which the route is never surging"),
    "source_floor_rps"   -> number("Source floor", "Requests per second below which a source is never surging"),
    "consumer_floor_rps" -> number("Api key floor", "Requests per second below which an api key is never surging"),
    "asn_floor_rps"      -> number("Network floor", "Requests per second below which a network is never surging"),
    "route_weight"       -> number("Route surge weight", "What a surge of the whole route contributes to every caller's score. It never escalates"),
    "source_weight"      -> number("Source surge weight", "What a surging source contributes, more past twice and four times its threshold"),
    "consumer_weight"    -> number("Api key surge weight", "What a surging api key contributes"),
    "asn_weight"         -> number("Network surge weight", "What a surging network contributes")
  )
}

/**
 * Notices when traffic changes, and scores what changed (BEH-4).
 *
 * Otoroshi's throttling enforces fixed limits; this learns what each route, source, api key and
 * network usually sends, and turns a departure from it into a signal on the threat bus. The threat
 * response turns that into a challenge, a tarpit or a ban as the policy's tiers say, and as soon as
 * traffic is back to usual the signal is gone and so is the response: escalation and de-escalation
 * are the same mechanism.
 *
 * A surge of a whole route contributes the same weight to every caller and never escalates: it can
 * put everyone behind a challenge, never ban everyone. What escalates is a single source, key or
 * network pulling far ahead of its own usual traffic.
 */
class CloudApimTrafficGuard extends NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Threat Protection - Traffic guard"
  override def description: Option[String]                 =
    "Learns each route's, source's, api key's and network's usual traffic, and scores a surge away from it".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimTrafficGuardConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimTrafficGuardConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimTrafficGuardConfig.configSchema.some

  // a surge is reported once a minute per key, the bus carries every request
  private val reported = new TrieMap[String, Long]()

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(CloudApimTrafficGuardConfig.format).getOrElse(CloudApimTrafficGuardConfig.default)
    ThreatSupport.module.foreach { mod =>
      val now      = System.currentTimeMillis()
      val settings = config.settings
      val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
      val route    = ctx.route.id
      val network  =
        if (config.asn) env.adminExtensions.extension[CloudApimWafExtension].flatMap(e => Try(e.reputation.network(identity.ip)).toOption.flatten)
        else None
      val watched  = Seq(
        Option.when(config.route)(("route", s"$route|route", "the route", config.routeFloorRps, config.routeWeight, false)),
        Option.when(config.source)(("source", s"$route|source:${identity.ip}", identity.ip, config.sourceFloorRps, config.sourceWeight, true)),
        identity.apikey.filter(_ => config.consumer).map(k => ("consumer", s"$route|consumer:$k", s"api key $k", config.consumerFloorRps, config.consumerWeight, true)),
        network.map(n => ("asn", s"$route|asn:${n.asn}", s"AS${n.asn} ${n.org}", config.asnFloorRps, config.asnWeight, true))
      ).flatten
      watched.foreach { case (dimension, key, who, floorRps, weight, escalates) =>
        mod.traffic.observe(key, now, settings, floorRps * settings.bucketSeconds).filter(_.surging).foreach { reading =>
          val score  = if (escalates) CloudApimTrafficGuard.escalate(weight, reading) else weight
          val detail = f"${reading.count} requests in this ${settings.bucketSeconds}s bucket, ${reading.ratio}%.1fx the usual for $who"
          ThreatBus.contribute(ctx.attrs, identity, ThreatSignal(source = "traffic", kind = s"${dimension}_surge", weight = score, tag = s"traffic:${dimension}_surge", detail = Some(detail)))
          if (reported.get(key).forall(_ < now)) {
            reported.put(key, now + 60000L)
            if (reported.size > 10000) reported.filterInPlace((_, until) => until > now)
            mod.record(
              category = "traffic",
              identity = identity,
              decision = ThreatDecision(action = ThreatAction.Log, score = score, tier = None, dryRun = true, reason = detail),
              tags = Seq(s"traffic:${dimension}_surge"),
              signals = Json.arr(
                Json.obj(
                  "dimension" -> dimension,
                  "count"     -> reading.count,
                  "baseline"  -> reading.baseline,
                  "threshold" -> reading.threshold,
                  "ratio"     -> reading.ratio,
                  "bucket_s"  -> settings.bucketSeconds
                )
              ),
              routeId = ctx.route.id.some,
              routeName = ctx.route.name.some,
              message = s"${dimension} surge: $detail"
            )
          }
        }
      }
    }
    NgAccess.NgAllowed.vfuture
  }
}

object CloudApimTrafficGuard {

  /** Further past its threshold, a surge weighs more: twice is 20 more, four times 40, up to 100. */
  def escalate(weight: Int, reading: TrafficReading): Int = {
    val over = reading.count / math.max(reading.threshold, 1.0)
    math.min(100, weight + (if (over >= 4.0) 40 else if (over >= 2.0) 20 else 0))
  }
}
