package com.cloud.apim.otoroshi.extensions.waf.security

import org.apache.pekko.util.ByteString
import otoroshi.storage.RedisLike
import otoroshi.utils.syntax.implicits.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/**
 * The whole shared-state surface of the fabric, in eight verbs.
 *
 * `RedisLike` carries about forty methods, most of them about service descriptors and apikeys.
 * Depending on this instead keeps the ban store and the ledger honest about what they actually
 * need — and makes both of them testable without a Redis, an `Env` or a container.
 */
trait SharedStateStore {
  def set(key: String, value: String, ttlMillis: Option[Long]): Future[Unit]
  def hset(key: String, field: String, value: String): Future[Unit]
  def hgetall(key: String): Future[Map[String, String]]
  def hdel(key: String, fields: Seq[String]): Future[Unit]
  def del(key: String): Future[Unit]
  def get(key: String): Future[Option[String]]
  def incrBy(key: String, by: Long): Future[Long]
  def pexpire(key: String, millis: Long): Future[Unit]
  def keys(pattern: String): Future[Seq[String]]
}

/** Production implementation, over whichever RedisLike the module resolved. */
class RedisSharedStateStore(redis: () => RedisLike)(using ec: ExecutionContext) extends SharedStateStore {

  override def set(key: String, value: String, ttlMillis: Option[Long]): Future[Unit] =
    redis().setBS(key, ByteString(value), None, ttlMillis).map(_ => ())

  override def hset(key: String, field: String, value: String): Future[Unit] =
    redis().hsetBS(key, field, ByteString(value)).map(_ => ())

  override def hgetall(key: String): Future[Map[String, String]] =
    redis().hgetall(key).map(_.view.mapValues(_.utf8String).toMap)

  override def hdel(key: String, fields: Seq[String]): Future[Unit] =
    if (fields.isEmpty) ().vfuture else redis().hdel(key, fields*).map(_ => ())

  override def del(key: String): Future[Unit] = redis().del(key).map(_ => ())

  override def get(key: String): Future[Option[String]] = redis().get(key).map(_.map(_.utf8String))

  override def incrBy(key: String, by: Long): Future[Long] = redis().incrby(key, by)

  override def pexpire(key: String, millis: Long): Future[Unit] = redis().pexpire(key, millis).map(_ => ())

  override def keys(pattern: String): Future[Seq[String]] = redis().keys(pattern)
}

/**
 * Node-local implementation.
 *
 * Used by the tests, and a correct fallback for a single-node deployment — where "shared" and
 * "local" are the same thing. It honours expiry so ledger windows behave as they do in production.
 */
class InMemorySharedStateStore extends SharedStateStore {

  private val values  = new TrieMap[String, String]()
  private val hashes  = new TrieMap[String, TrieMap[String, String]]()
  private val expiries = new TrieMap[String, Long]()

  private def alive(key: String): Boolean = expiries.get(key) match {
    case Some(at) if at <= System.currentTimeMillis() =>
      values.remove(key); hashes.remove(key); expiries.remove(key); false
    case _                                            => true
  }

  override def set(key: String, value: String, ttlMillis: Option[Long]): Future[Unit] = {
    values.put(key, value)
    ttlMillis.foreach(ms => expiries.put(key, System.currentTimeMillis() + ms))
    ().vfuture
  }

  override def hset(key: String, field: String, value: String): Future[Unit] = {
    hashes.getOrElseUpdate(key, new TrieMap[String, String]()).put(field, value)
    ().vfuture
  }

  override def hgetall(key: String): Future[Map[String, String]] =
    (if (alive(key)) hashes.get(key).map(_.toMap).getOrElse(Map.empty) else Map.empty[String, String]).vfuture

  override def hdel(key: String, fields: Seq[String]): Future[Unit] = {
    hashes.get(key).foreach(h => fields.foreach(h.remove))
    ().vfuture
  }

  override def del(key: String): Future[Unit] = {
    values.remove(key); hashes.remove(key); expiries.remove(key)
    ().vfuture
  }

  override def get(key: String): Future[Option[String]] =
    (if (alive(key)) values.get(key) else None).vfuture

  override def incrBy(key: String, by: Long): Future[Long] = {
    val next = (if (alive(key)) values.get(key).flatMap(_.toLongOption).getOrElse(0L) else 0L) + by
    values.put(key, next.toString)
    next.vfuture
  }

  override def pexpire(key: String, millis: Long): Future[Unit] = {
    expiries.put(key, System.currentTimeMillis() + millis)
    ().vfuture
  }

  override def keys(pattern: String): Future[Seq[String]] = {
    val regex = ("^" + java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q") + "$").r
    (values.keys ++ hashes.keys).filter(alive).filter(k => regex.findFirstIn(k).isDefined).toSeq.vfuture
  }
}
