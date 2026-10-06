package com.cloud.apim.otoroshi.extensions.waf.login

import com.cloud.apim.otoroshi.extensions.waf.security.SharedStateStore
import org.apache.pekko.util.ByteString
import play.api.libs.json.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** What a login request said about who it is for (BEH-3). */
final case class Credentials(username: String, password: Option[String]) {

  /** How an account is shown in an event: enough to recognise it, not enough to collect it. */
  def masked: String = Credentials.mask(username)
}

object Credentials {

  val defaultUsernameFields: Seq[String] = Seq("username", "user", "login", "email", "user_name", "userName", "identifier", "account")
  val defaultPasswordFields: Seq[String] = Seq("password", "pass", "passwd", "pwd")

  def mask(username: String): String = {
    val at = username.indexOf('@')
    if (at > 0) s"${username.take(1)}***${username.substring(at)}"
    else if (username.length <= 2) "***"
    else s"${username.take(1)}***${username.takeRight(1)}"
  }

  /**
   * The credentials of a login request, from a JSON body, a form body or a Basic authorization
   * header, the first that has a username. A dotted field reaches into a JSON object
   * (`credentials.username`).
   */
  def extract(
      body: ByteString,
      contentType: Option[String],
      authorization: Option[String],
      usernameFields: Seq[String],
      passwordFields: Seq[String]
  ): Option[Credentials] = {
    val mt = contentType.map(_.takeWhile(_ != ';').trim.toLowerCase).getOrElse("")
    def pick(lookup: String => Option[String], fields: Seq[String]) = fields.iterator.flatMap(lookup).find(_.trim.nonEmpty)
    val fromBody =
      if (mt.endsWith("json") && body.nonEmpty)
        Try(Json.parse(body.toArray)).toOption.flatMap { json =>
          def at(path: String): Option[String] =
            path.split('.').foldLeft(Option(json)) { (acc, key) => acc.flatMap(j => (j \ key).toOption) }.flatMap {
              case JsString(s) => Some(s)
              case JsNumber(n) => Some(n.toString)
              case _           => None
            }
          pick(at, usernameFields).map(u => Credentials(u.trim, pick(at, passwordFields)))
        }
      else if (mt == "application/x-www-form-urlencoded" && body.nonEmpty) {
        val form = body.utf8String
          .split('&')
          .toSeq
          .flatMap { pair =>
            val i = pair.indexOf('=')
            Option.when(i > 0)(
              Try(URLDecoder.decode(pair.substring(0, i), "UTF-8")).getOrElse("") -> Try(URLDecoder.decode(pair.substring(i + 1), "UTF-8")).getOrElse("")
            )
          }
          .toMap
        pick(form.get, usernameFields).map(u => Credentials(u.trim, pick(form.get, passwordFields)))
      } else None
    fromBody.orElse(authorization.filter(_.trim.toLowerCase.startsWith("basic ")).flatMap { header =>
      Try(new String(Base64.getDecoder.decode(header.trim.drop(6).trim), StandardCharsets.UTF_8)).toOption.flatMap { decoded =>
        val i = decoded.indexOf(':')
        Option.when(i > 0)(Credentials(decoded.substring(0, i), Some(decoded.substring(i + 1))))
      }
    })
  }
}

/** Where counters stand for one login attempt: its source, and the account it targets. */
final case class LoginState(sourceFailures: Long, sourceAccounts: Int, accountFailures: Long, accountSources: Int)

/**
 * What the counters say about an attempt, as signals for the threat score (BEH-3).
 *
 * Three patterns, each with its own threshold: one source failing over and over (credential
 * stuffing), one source failing against many accounts (password spraying), and one account failing
 * from many sources (a distributed attack on it). A pattern past twice its threshold weighs more,
 * which is how an attack that goes on climbs the policy's tiers.
 *
 * The account under attack is never refused for it: only the sources trying it are scored, so its
 * owner is at worst challenged, never locked out.
 */
final case class LoginThresholds(
    sourceFailures: Int = 20,
    sourceAccounts: Int = 10,
    accountFailures: Int = 10,
    accountSources: Int = 5,
    stuffingWeight: Int = 50,
    sprayingWeight: Int = 70,
    accountAttackWeight: Int = 40
) {

  private def escalate(weight: Int, value: Long, threshold: Int): Int =
    if (value >= threshold.toLong * 2) math.min(100, weight + 20) else weight

  /** The patterns this state shows: name, weight, and what it saw. */
  def signals(state: LoginState): Seq[(String, Int, String)] = Seq(
    Option.when(sourceFailures > 0 && state.sourceFailures >= sourceFailures)(
      ("credential_stuffing", escalate(stuffingWeight, state.sourceFailures, sourceFailures), s"${state.sourceFailures} failed logins from this source")
    ),
    Option.when(sourceAccounts > 0 && state.sourceAccounts >= sourceAccounts)(
      ("password_spraying", escalate(sprayingWeight, state.sourceAccounts.toLong, sourceAccounts), s"${state.sourceAccounts} accounts failed from this source")
    ),
    Option.when(accountFailures > 0 && state.accountFailures >= accountFailures && state.accountSources >= accountSources)(
      (
        "account_under_attack",
        escalate(accountAttackWeight, state.accountFailures, accountFailures),
        s"${state.accountFailures} failed logins on this account from ${state.accountSources} sources"
      )
    )
  ).flatten
}

/**
 * Failed logins, counted cluster-wide in windows of `window` (BEH-3).
 *
 * Accounts are never stored as they were typed: they are keyed by an HMAC of the gateway's secret,
 * so the shared state holds who failed how often, not a list of usernames.
 */
final class LoginCounters(prefix: String, store: SharedStateStore, secret: String)(using ec: ExecutionContext) {

  def account(realm: String, username: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(s"$realm\u0000${username.trim.toLowerCase}".getBytes(StandardCharsets.UTF_8)).take(12).map("%02x".format(_)).mkString
  }

  private def keys(realm: String, windowMillis: Long) = {
    val bucket = System.currentTimeMillis() / windowMillis
    (k: String) => s"$prefix:$realm:$bucket:$k"
  }

  /** One failed login: the source's and the account's counters, and who failed against whom. */
  def failed(realm: String, source: String, account: String, windowMillis: Long): Future[Unit] = {
    val key = keys(realm, windowMillis)
    val ttl = windowMillis * 2
    def expiring(k: String)(write: Future[?]): Future[Unit] = write.flatMap(_ => store.pexpire(k, ttl))
    Future
      .sequence(
        Seq(
          expiring(key(s"sf:$source"))(store.incrBy(key(s"sf:$source"), 1L)),
          expiring(key(s"af:$account"))(store.incrBy(key(s"af:$account"), 1L)),
          expiring(key(s"sa:$source"))(store.hset(key(s"sa:$source"), account, "1")),
          expiring(key(s"as:$account"))(store.hset(key(s"as:$account"), source, "1"))
        )
      )
      .map(_ => ())
  }

  def state(realm: String, source: String, account: String, windowMillis: Long): Future[LoginState] = {
    val key = keys(realm, windowMillis)
    for {
      sf <- store.get(key(s"sf:$source"))
      af <- store.get(key(s"af:$account"))
      sa <- store.hgetall(key(s"sa:$source"))
      as <- store.hgetall(key(s"as:$account"))
    } yield LoginState(sf.flatMap(_.toLongOption).getOrElse(0L), sa.size, af.flatMap(_.toLongOption).getOrElse(0L), as.size)
  }

  /** Whether `key` is seen for the first time in this window: an observation is reported once. */
  def first(realm: String, key: String, windowMillis: Long): Future[Boolean] = {
    val k = keys(realm, windowMillis)(s"seen:$key")
    store.incrBy(k, 1L).flatMap(n => if (n == 1L) store.pexpire(k, windowMillis * 2).map(_ => true) else Future.successful(false))
  }
}

/**
 * Whether a password is in a known breach, by Have I Been Pwned's k-anonymity range API (BEH-3).
 *
 * Only the first five hexadecimal characters of the password's SHA-1 leave the gateway; the range
 * that comes back is matched here, and kept for an hour so a stuffing run costs one call per prefix.
 */
final class BreachedPasswords(fetch: String => Future[Option[String]])(using ec: ExecutionContext) {

  private val cache    = new TrieMap[String, (Long, Set[String])]()
  private val ttlMillis = 3600L * 1000L

  def breached(password: String): Future[Option[Boolean]] = {
    val sha1   = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)).map("%02X".format(_)).mkString
    val (p, s) = sha1.splitAt(5)
    val now    = System.currentTimeMillis()
    cache.get(p).filter(_._1 > now) match {
      case Some((_, suffixes)) => Future.successful(Some(suffixes.contains(s)))
      case None                =>
        fetch(p).map(_.map { body =>
          val suffixes = body.split("\r?\n").toSeq.flatMap { line =>
            val i = line.indexOf(':')
            // with padding on, the range also lists suffixes that were never breached: count 0
            Option.when(i > 0 && !line.substring(i + 1).trim.startsWith("0"))(line.substring(0, i).trim.toUpperCase)
          }.toSet
          if (cache.size > 10000) cache.clear()
          cache.put(p, (now + ttlMillis, suffixes))
          suffixes.contains(s)
        })
    }
  }
}
