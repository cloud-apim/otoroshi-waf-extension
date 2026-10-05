package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.github.blemale.scaffeine.Scaffeine
import play.api.libs.json.*

import java.net.{InetAddress, UnknownHostException}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

/** The name a DNS blocklist is asked about: the address reversed, in front of the zone. */
object RblQuery {

  private val Label = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$".r

  /** Project Honey Pot's http:BL puts the access key in front of the reversed address. */
  val HttpBl: String = "dnsbl.httpbl.org"

  def validZone(zone: String): Boolean =
    zone.nonEmpty && zone.length <= 200 && zone.split('.').forall(label => Label.matches(label))

  /**
   * `1.2.3.4` in `zen.spamhaus.org` is `4.3.2.1.zen.spamhaus.org`; an ipv6 address is reversed
   * nibble by nibble. Only address literals are asked about: a host name in the request must never
   * become a DNS query.
   */
  def name(ip: String, zone: String, httpBlKey: Option[String] = None): Option[String] = {
    val z = zone.trim.toLowerCase.stripPrefix(".").stripSuffix(".")
    if (!validZone(z)) None
    else
      IpParser.parseV4(ip.trim) match {
        case Some(v4) =>
          val reversed = Seq(v4 & 0xff, (v4 >> 8) & 0xff, (v4 >> 16) & 0xff, (v4 >> 24) & 0xff).mkString(".")
          if (z == HttpBl) httpBlKey.map(_.trim).filter(k => Label.matches(k.toLowerCase)).map(key => s"$key.$reversed.$z")
          else Some(s"$reversed.$z")
        case None     =>
          // http:BL has no ipv6 form
          if (z == HttpBl) None
          else
            IpParser.parseV6(ip.trim).map { v6 =>
              val hex = v6.toString(16).reverse.padTo(32, '0').reverse
              s"${hex.reverse.mkString(".")}.$z"
            }
      }
  }
}

/** What a blocklist said about one address. */
sealed trait RblAnswer {
  def listed: Boolean = false
}

object RblAnswer {
  /** In the list: the codes say which sublist, for the lists that combine several. */
  final case class Listed(codes: Seq[String])          extends RblAnswer { override def listed: Boolean = true }
  case object NotListed                                 extends RblAnswer
  /** The list answered, but not with a listing: an error code, or a resolver that rewrites misses. */
  final case class Refused(reason: String)              extends RblAnswer
  /** No answer in time, or a resolver error: unknown, which is never a listing. */
  final case class Unknown(reason: String)              extends RblAnswer

  /**
   * Only `127.0.0.0/8` is a listing, and not all of it.
   *
   * Spamhaus answers its errors inside `127.255.255.0/24` (`.254` is "you are asking through a
   * public resolver", which is how most cloud nodes ask), and some resolvers answer a miss with
   * the address of a search page. Counting either as a listing would block every caller.
   */
  def classify(addresses: Seq[InetAddress]): RblAnswer = {
    val v4 = addresses.collect { case a if a.getAddress.length == 4 => a.getAddress.map(_ & 0xff).toSeq }
    val (errors, rest) = v4.filter(_.head == 127).partition(b => b(1) == 255 && b(2) == 255)
    if (rest.nonEmpty) Listed(rest.map(_.mkString(".")).distinct)
    else if (errors.nonEmpty) Refused(errors.map(b => describeError(b(3))).distinct.mkString(", "))
    else if (addresses.nonEmpty) Refused(s"answered ${addresses.map(_.getHostAddress).mkString(", ")}, outside 127.0.0.0/8: a resolver rewriting misses?")
    else NotListed
  }

  private def describeError(code: Int): String = code match {
    case 252 => "127.255.255.252: the zone name is wrong"
    case 254 => "127.255.255.254: queries through a public resolver are refused, configure reputation.rbl.nameservers"
    case 255 => "127.255.255.255: too many queries"
    case other => s"127.255.255.$other: error"
  }
}

final case class RblSettings(
    enabled: Boolean = true,
    timeout: FiniteDuration = 2.seconds,
    waitFor: FiniteDuration = 100.millis,
    listedTtl: FiniteDuration = 15.minutes,
    unlistedTtl: FiniteDuration = 5.minutes,
    errorTtl: FiniteDuration = 30.seconds,
    maxEntries: Long = 100000L,
    nameservers: Seq[String] = Seq.empty,
    httpBlKey: Option[String] = None
)

object RblSettings {
  def from(configuration: play.api.Configuration): RblSettings = {
    def seconds(key: String, default: FiniteDuration): FiniteDuration =
      configuration.getOptional[Long](s"reputation.rbl.$key").map(_.max(1L).seconds).getOrElse(default)
    def millis(key: String, default: FiniteDuration): FiniteDuration =
      configuration.getOptional[Long](s"reputation.rbl.$key").map(_.max(1L).millis).getOrElse(default)
    RblSettings(
      enabled = configuration.getOptional[Boolean]("reputation.rbl.enabled").getOrElse(true),
      timeout = millis("timeout-millis", 2.seconds),
      waitFor = configuration.getOptional[Long]("reputation.rbl.wait-millis").map(_.max(0L).millis).getOrElse(100.millis),
      listedTtl = seconds("listed-ttl-seconds", 15.minutes),
      unlistedTtl = seconds("unlisted-ttl-seconds", 5.minutes),
      errorTtl = seconds("error-ttl-seconds", 30.seconds),
      maxEntries = configuration.getOptional[Long]("reputation.rbl.max-entries").getOrElse(100000L).max(100L),
      nameservers = configuration.getOptional[Seq[String]]("reputation.rbl.nameservers").getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty),
      httpBlKey = configuration.getOptional[String]("reputation.rbl.httpbl-key").map(_.trim).filter(_.nonEmpty)
    )
  }
}

/**
 * DNS blocklist answers, for `@rbl`.
 *
 * The engine asks synchronously and must never wait on the network, so this is a cache in front of
 * an asynchronous resolver: `listed` answers from the cache and, on a miss, starts the query and
 * says no; `warm` is what the WAF plugin calls before evaluating, so that a first request can have
 * its answer if it comes within `waitFor`. A query that times out, a resolver error, an error code from
 * the list: all of them are "not listed", cached briefly, and counted where an operator can see them.
 */
class RblResolver(
    resolve: String => Future[Seq[InetAddress]],
    settings: RblSettings,
    after: (FiniteDuration, () => Future[Unit]) => Future[Unit],
    logger: play.api.Logger
)(using ec: ExecutionContext) {

  private final case class Entry(answer: RblAnswer, ttl: FiniteDuration)

  private val cache = Scaffeine()
    .expireAfter[String, Entry](
      create = (_, entry) => entry.ttl,
      update = (_, entry, _) => entry.ttl,
      read = (_, _, remaining) => remaining
    )
    .maximumSize(settings.maxEntries)
    .build[String, Entry]()

  private val inFlight = new TrieMap[String, Future[RblAnswer]]()
  // one warning per zone and reason: an operator needs to hear it once, not once per caller
  private val warned   = new TrieMap[String, Long]()
  private val refusals = new TrieMap[String, String]()

  // RFC 5782: every list must answer for 127.0.0.2. a list that does not is not answering at all,
  // whatever it says about real callers: through some public resolvers spamhaus answers nxdomain
  // to everything, which is indistinguishable from "not listed" on any single lookup
  private final case class ZoneHealth(checkedAt: Long, healthy: Boolean, detail: String)
  private val health          = new TrieMap[String, ZoneHealth]()
  private val healthInFlight  = new TrieMap[String, Boolean]()
  private val healthInterval  = 10.minutes.toMillis

  private val lookups   = new AtomicLong(0L)
  private val listedC   = new AtomicLong(0L)
  private val unlistedC = new AtomicLong(0L)
  private val refusedC  = new AtomicLong(0L)
  private val unknownC  = new AtomicLong(0L)

  /** For the engine: what is already known, and a query started for what is not. */
  def listed(ip: String, zone: String): Boolean = {
    if (!settings.enabled) false
    else
      RblQuery.name(ip, zone, settings.httpBlKey) match {
        case None       => false
        case Some(name) =>
          cache.getIfPresent(name) match {
            case Some(entry) => entry.answer.listed
            case None        =>
              query(name, zone)
              false
          }
      }
  }

  /** Before evaluation: every zone the rules name, for this caller, for at most `waitFor`. */
  def warm(ip: String, zones: Seq[String]): Future[Unit] = {
    if (!settings.enabled || zones.isEmpty) Future.unit
    else {
      val pending = zones.flatMap(zone => RblQuery.name(ip, zone, settings.httpBlKey).map(_ -> zone)).collect {
        case (name, zone) if cache.getIfPresent(name).isEmpty => query(name, zone).map(_ => ())
      }
      if (pending.isEmpty) Future.unit
      else if (settings.waitFor.length == 0) Future.unit
      else Future.firstCompletedOf(Seq(Future.sequence(pending).map(_ => ()), after(settings.waitFor, () => Future.unit)))
    }
  }

  private def checkHealth(zone: String): Unit = {
    val now = System.currentTimeMillis()
    val due = health.get(zone).forall(h => now - h.checkedAt > healthInterval)
    // http:BL publishes its own test addresses, not 127.0.0.2
    if (due && zone != RblQuery.HttpBl && healthInFlight.putIfAbsent(zone, true).isEmpty) {
      RblQuery.name("127.0.0.2", zone, settings.httpBlKey) match {
        case None       => healthInFlight.remove(zone)
        case Some(name) =>
          Future
            .delegate(resolve(name))
            .map(RblAnswer.classify)
            .recover {
              case e if RblResolver.isTimeout(e) => RblAnswer.Unknown(s"no answer within ${settings.timeout.toMillis} ms")
              case _: UnknownHostException       => RblAnswer.NotListed
              case e                             => RblAnswer.Unknown(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
            }
            .onComplete { result =>
              val (healthy, detail) = result.getOrElse(RblAnswer.Unknown("resolver failure")) match {
                case _: RblAnswer.Listed       => (true, "answers its test entry")
                case RblAnswer.NotListed       =>
                  (false, "does not list its test entry 127.0.0.2: queries are probably refused silently, configure reputation.rbl.nameservers")
                case RblAnswer.Refused(reason) => (false, reason)
                case RblAnswer.Unknown(reason) => (false, reason)
              }
              health.put(zone, ZoneHealth(System.currentTimeMillis(), healthy, detail))
              if (!healthy) warnOnce(zone, detail)
              healthInFlight.remove(zone)
            }
      }
    }
  }

  private def query(name: String, zone: String): Future[RblAnswer] = {
    checkHealth(zone)
    inFlight.get(name) match {
      case Some(running) => running
      case None          =>
        val promise = Promise[RblAnswer]()
        inFlight.putIfAbsent(name, promise.future) match {
          case Some(running) => running
          case None          =>
            lookups.incrementAndGet()
            Future
              .delegate(resolve(name))
              .map(RblAnswer.classify)
              .recover {
                case e if RblResolver.isTimeout(e) => RblAnswer.Unknown(s"no answer within ${settings.timeout.toMillis} ms")
                case _: UnknownHostException       => RblAnswer.NotListed
                case e                             => RblAnswer.Unknown(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
              }
              .onComplete { result =>
                val answer = result.getOrElse(RblAnswer.Unknown("resolver failure"))
                record(zone, answer)
                cache.put(name, Entry(answer, ttlOf(answer)))
                inFlight.remove(name)
                promise.success(answer)
              }
            promise.future
        }
    }
  }

  private def ttlOf(answer: RblAnswer): FiniteDuration = answer match {
    case _: RblAnswer.Listed   => settings.listedTtl
    case RblAnswer.NotListed   => settings.unlistedTtl
    case _: RblAnswer.Refused  => settings.unlistedTtl
    case _: RblAnswer.Unknown  => settings.errorTtl
  }

  private def record(zone: String, answer: RblAnswer): Unit = answer match {
    case _: RblAnswer.Listed       => listedC.incrementAndGet(); ()
    case RblAnswer.NotListed       => unlistedC.incrementAndGet(); ()
    case RblAnswer.Refused(reason) =>
      refusedC.incrementAndGet()
      refusals.put(zone, reason)
      warnOnce(zone, reason)
    case RblAnswer.Unknown(reason) =>
      unknownC.incrementAndGet()
      warnOnce(zone, reason)
  }

  private def warnOnce(zone: String, reason: String): Unit = {
    val key = s"$zone|$reason"
    val now = System.currentTimeMillis()
    // again after an hour, so a problem that lasts is not forgotten by the logs either
    if (warned.get(key).forall(at => now - at > 3600000L)) {
      warned.put(key, now)
      logger.warn(s"dns blocklist '$zone' is not answering usefully, its lookups count as not listed: $reason")
    }
  }

  def status: JsValue = Json.obj(
    "enabled"     -> settings.enabled,
    "nameservers" -> (if (settings.nameservers.isEmpty) JsString("system") else JsArray(settings.nameservers.map(JsString.apply))),
    "wait_millis" -> settings.waitFor.toMillis,
    "lookups"     -> lookups.get(),
    "listed"      -> listedC.get(),
    "not_listed"  -> unlistedC.get(),
    "refused"     -> refusedC.get(),
    "unknown"     -> unknownC.get(),
    "cached"      -> cache.estimatedSize(),
    "refusals"    -> JsObject(refusals.readOnlySnapshot().toMap.view.mapValues(JsString.apply).toMap),
    "zones"       -> JsObject(health.readOnlySnapshot().toMap.view.mapValues { h =>
      Json.obj("healthy" -> h.healthy, "detail" -> h.detail, "checked_at" -> h.checkedAt)
    }.toMap)
  )
}

object RblResolver {

  def isTimeout(e: Throwable): Boolean = {
    var current: Throwable = e
    var found              = false
    var depth              = 0
    while (current != null && !found && depth < 10) {
      found = current.isInstanceOf[java.util.concurrent.TimeoutException] ||
        current.getClass.getSimpleName.contains("Timeout")
      current = current.getCause
      depth += 1
    }
    found
  }

  /** `@rbl <zone>` in a ruleset, for the zones that can be known before a request: no macros. */
  private val Operator = """@rbl\s+([^\s"%]+)(?=[\s"])""".r

  def zonesIn(rules: Seq[String]): Seq[String] =
    rules.iterator.flatMap(rule => Operator.findAllMatchIn(rule).map(_.group(1).trim.toLowerCase)).filter(RblQuery.validZone).toSeq.distinct
}

/**
 * The production resolver: Netty's, asynchronous, on one event loop thread of its own.
 *
 * It caches nothing (the `RblResolver` does, with the ttls an operator configured), asks for ipv4
 * answers only (a blocklist answers `A` records), and uses the system's name servers unless
 * `reputation.rbl.nameservers` names others.
 */
class NettyRblLookup(settings: RblSettings) {

  import io.netty.channel.MultiThreadIoEventLoopGroup
  import io.netty.channel.nio.NioIoHandler
  import io.netty.channel.socket.nio.NioDatagramChannel
  import io.netty.resolver.ResolvedAddressTypes
  import io.netty.resolver.dns.*

  private val group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())

  private val resolver: DnsNameResolver = {
    val provider: DnsServerAddressStreamProvider =
      if (settings.nameservers.isEmpty) {
        // the platform default tries a native library on macos and logs an error when it is absent;
        // elsewhere it reads resolv.conf, which is what a server wants
        if (System.getProperty("os.name", "").toLowerCase.contains("mac")) DefaultDnsServerAddressStreamProvider.INSTANCE
        else DnsServerAddressStreamProviders.platformDefault()
      } else {
        val addresses = settings.nameservers.map { ns =>
          val idx = ns.lastIndexOf(':')
          if (idx > 0 && !ns.endsWith("]") && ns.indexOf(':') == idx) new java.net.InetSocketAddress(ns.substring(0, idx), ns.substring(idx + 1).toInt)
          else new java.net.InetSocketAddress(ns.stripPrefix("[").stripSuffix("]"), 53)
        }
        new SequentialDnsServerAddressStreamProvider(addresses*)
      }
    new DnsNameResolverBuilder(group.next())
      .datagramChannelType(classOf[NioDatagramChannel])
      .queryTimeoutMillis(settings.timeout.toMillis)
      .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
      .resolveCache(NoopDnsCache.INSTANCE)
      .nameServerProvider(provider)
      // a blocklist query is an absolute name: a search domain appended to it is a wrong question
      .searchDomains(java.util.Collections.emptyList[String]())
      .ndots(1)
      .build()
  }

  def resolve(name: String): Future[Seq[InetAddress]] = {
    val promise = Promise[Seq[InetAddress]]()
    resolver.resolveAll(name).addListener { (f: io.netty.util.concurrent.Future[java.util.List[InetAddress]]) =>
      if (f.isSuccess) {
        import scala.jdk.CollectionConverters.*
        promise.success(f.getNow.asScala.toSeq)
      } else promise.failure(f.cause())
      ()
    }
    promise.future
  }

  def close(): Unit = {
    Try(resolver.close())
    Try(group.shutdownGracefully())
    ()
  }
}
