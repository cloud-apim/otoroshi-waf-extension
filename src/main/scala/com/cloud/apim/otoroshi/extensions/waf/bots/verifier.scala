package com.cloud.apim.otoroshi.extensions.waf.bots

import play.api.Logger

import java.net.InetAddress
import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

sealed trait BotVerdict { def name: String }
object BotVerdict {
  case object Verified     extends BotVerdict { val name = "verified"     }
  case object Impersonator extends BotVerdict { val name = "impersonator" }
  case object Unknown      extends BotVerdict { val name = "unknown"      }
}

final case class BotVerificationSettings(
    enabled: Boolean = true,
    positiveTtl: FiniteDuration = 6.hours,
    negativeTtl: FiniteDuration = 15.minutes,
    timeout: FiniteDuration = 2.seconds
)

/**
 * Forward-confirmed reverse DNS.
 *
 * The check the crawler operators themselves document: resolve the address to a name, check the
 * name belongs to them, then resolve that name back and check it returns to the same address. One
 * direction alone proves nothing — a reverse record is set by whoever owns the address block.
 *
 * DNS is blocking and the request path is not allowed to block, so the request path **only reads
 * the cache**. An address seen for the first time answers `Unknown` and schedules the lookup; the
 * next request from that caller has the answer. That is the right trade: a crawler makes thousands
 * of requests, so being one request late costs nothing, and a per-request DNS round trip would cost
 * everything.
 */
class BotVerifier(settings: () => BotVerificationSettings, logger: Logger) {

  private given ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4, r => {
      val t = new Thread(r, "cloud-apim-bot-rdns")
      t.setDaemon(true)
      t
    }))

  private val cache    = new TrieMap[String, (BotVerdict, Long)]()
  private val inFlight = new TrieMap[String, Boolean]()

  private def keyOf(ip: String, sig: BotSignature): String = s"${sig.name}|$ip"

  def check(ip: String, sig: BotSignature): BotVerdict = {
    val current = settings()
    if (!current.enabled || !sig.verifiable) BotVerdict.Unknown
    else {
      val key = keyOf(ip, sig)
      cache.get(key) match {
        case Some((verdict, until)) if until > System.currentTimeMillis() => verdict
        case _                                                           =>
          schedule(ip, sig, key, current)
          BotVerdict.Unknown
      }
    }
  }

  private def schedule(ip: String, sig: BotSignature, key: String, current: BotVerificationSettings): Unit = {
    if (inFlight.putIfAbsent(key, true).isEmpty) {
      Future {
        val verdict = resolve(ip, sig)
        val ttl     = if (verdict == BotVerdict.Verified) current.positiveTtl else current.negativeTtl
        cache.put(key, (verdict, System.currentTimeMillis() + ttl.toMillis))
        if (verdict == BotVerdict.Impersonator) {
          logger.info(s"$ip claims to be ${sig.name} but its reverse dns does not confirm it")
        }
        verdict
      }.andThen { case _ => inFlight.remove(key) }
      ()
    }
  }

  private def resolve(ip: String, sig: BotSignature): BotVerdict = {
    Try {
      val address  = InetAddress.getByName(ip)
      val hostname = address.getCanonicalHostName
      // getCanonicalHostName hands back the address itself when there is no ptr record
      if (hostname == ip || hostname.isEmpty) BotVerdict.Impersonator
      else {
        val host    = hostname.toLowerCase.stripSuffix(".")
        val claimed = sig.rdnsSuffixes.exists(suffix => host.endsWith(suffix.toLowerCase.stripSuffix(".")))
        if (!claimed) BotVerdict.Impersonator
        else {
          // forward confirm: the name must come back to the address that presented it
          val forward = InetAddress.getAllByName(hostname).map(_.getHostAddress).toSet
          if (forward.contains(address.getHostAddress)) BotVerdict.Verified else BotVerdict.Impersonator
        }
      }
    }.getOrElse {
      // a dns failure is not evidence of impersonation — say nothing rather than accuse
      BotVerdict.Unknown
    }
  }

  def cached: Int = cache.size

  def forget(): Unit = cache.clear()
}
