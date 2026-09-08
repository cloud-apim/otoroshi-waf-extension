package com.cloud.apim.otoroshi.extensions.waf.security

import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.typedmap.TypedKey
import play.api.mvc.RequestHeader

/**
 * One way of naming the caller.
 *
 * A request can be identified several ways at once — by address, by apikey, by session — and a ban
 * or a threat record attaches to one of them specifically. Keeping them as separate refs rather
 * than collapsing to a single string is what lets a policy ban an apikey without banning the shared
 * NAT address behind it.
 */
final case class IdentityRef(kind: String, value: String) {
  def key: String   = s"$kind:$value"
  def json: JsValue = Json.obj("kind" -> kind, "value" -> value)
}

object IdentityRef {
  val Ip: String          = "ip"
  val ApiKey: String      = "apikey"
  val User: String        = "user"
  val Fingerprint: String = "fingerprint"

  def parse(key: String): Option[IdentityRef] = key.split(":", 2) match {
    case Array(kind, value) if kind.nonEmpty && value.nonEmpty => Some(IdentityRef(kind, value))
    case _                                                     => None
  }
}

final case class ClientIdentity(
    ip: String,
    apikey: Option[String] = None,
    user: Option[String] = None,
    fingerprint: Option[String] = None
) {

  /** Ordered most specific first, so a policy can prefer banning an apikey over an address. */
  lazy val refs: Seq[IdentityRef] = Seq(
    apikey.map(IdentityRef(IdentityRef.ApiKey, _)),
    user.map(IdentityRef(IdentityRef.User, _)),
    fingerprint.map(IdentityRef(IdentityRef.Fingerprint, _)),
    Some(ip).filter(_.nonEmpty).map(IdentityRef(IdentityRef.Ip, _))
  ).flatten

  def primary: IdentityRef = refs.headOption.getOrElse(IdentityRef(IdentityRef.Ip, ip))

  def json: JsValue = Json.obj(
    "ip"          -> ip,
    "apikey"      -> apikey,
    "user"        -> user,
    "fingerprint" -> fingerprint,
    "refs"        -> JsArray(refs.map(_.json))
  )
}

object ClientIdentity {

  /**
   * Built from whatever the gateway has established so far.
   *
   * Called at several points in the request, and the later the call, the richer the result — an
   * apikey is only known once the apikey plugin has run. Callers hold on to the identity they were
   * given rather than assuming it is complete.
   */
  def from(request: RequestHeader, attrs: TypedMap)(using env: Env): ClientIdentity = {
    ClientIdentity(
      ip = request.theIpAddress,
      apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(_.clientId),
      user = attrs.get(otoroshi.plugins.Keys.UserKey).map(_.email),
      fingerprint = attrs.get(ThreatKeys.FingerprintKey)
    )
  }
}

/**
 * One reason to distrust the caller, contributed by one detector.
 *
 * `weight` is the contribution to the score; `confidence` scales it down when the detector is
 * unsure, so a probabilistic signal can be published honestly instead of being rounded up to a
 * certainty it does not have.
 */
final case class ThreatSignal(
    source: String,
    kind: String,
    weight: Int,
    tag: String,
    confidence: Double = 1.0,
    detail: Option[String] = None,
    at: Long = System.currentTimeMillis()
) {
  def effectiveWeight: Int = math.round(weight * math.max(0.0, math.min(1.0, confidence))).toInt
  def json: JsValue        = Json.obj(
    "source"           -> source,
    "kind"             -> kind,
    "weight"           -> weight,
    "effective_weight" -> effectiveWeight,
    "tag"              -> tag,
    "confidence"       -> confidence,
    "detail"           -> detail,
    "at"               -> at
  )
}

/**
 * The shared judgement every detector writes to and one component reads.
 *
 * Additive and capped rather than a maximum, so several weak signals can add up to something worth
 * acting on — which is the whole reason for having a bus instead of letting each detector decide
 * alone. Full attribution is kept so any decision can be explained after the fact.
 */
final case class ThreatScore(identity: ClientIdentity, signals: List[ThreatSignal]) {

  lazy val score: Int          = math.min(100, signals.map(_.effectiveWeight).sum)
  lazy val tags: Seq[String]   = signals.map(_.tag).distinct
  lazy val sources: Seq[String] = signals.map(_.source).distinct

  def isEmpty: Boolean  = signals.isEmpty
  def nonEmpty: Boolean = signals.nonEmpty

  def withSignal(signal: ThreatSignal): ThreatScore = copy(signals = signals :+ signal)
  def withIdentity(other: ClientIdentity): ThreatScore = copy(identity = other)

  def json: JsValue = Json.obj(
    "score"    -> score,
    "identity" -> identity.json,
    "tags"     -> tags,
    "sources"  -> sources,
    "signals"  -> JsArray(signals.map(_.json))
  )
}

object ThreatScore {
  def empty(identity: ClientIdentity): ThreatScore = ThreatScore(identity, List.empty)
}

object ThreatKeys {

  /** The accumulator, carried for the life of the request. */
  val ScoreKey: TypedKey[ThreatScore] = TypedKey[ThreatScore]("cloud-apim.security.ThreatScore")

  /**
   * Published by a fingerprinting module when one exists. Declared here so `ClientIdentity` can
   * pick it up without the identity model depending on a module that may never be installed.
   */
  val FingerprintKey: TypedKey[String] = TypedKey[String]("cloud-apim.security.Fingerprint")

  /** Set by the response engine once it has acted, so nothing acts twice on the same request. */
  val DecisionKey: TypedKey[ThreatDecision] = TypedKey[ThreatDecision]("cloud-apim.security.Decision")
}

/**
 * The contribution API.
 *
 * Detectors call `contribute` and never decide anything themselves. Plugin execution within a
 * request is sequential, so a read-modify-write on the attrs map is safe here.
 */
object ThreatBus {

  def identity(attrs: TypedMap): Option[ClientIdentity] = attrs.get(ThreatKeys.ScoreKey).map(_.identity)

  def current(attrs: TypedMap): Option[ThreatScore] = attrs.get(ThreatKeys.ScoreKey)

  def scoreOf(attrs: TypedMap): Int = attrs.get(ThreatKeys.ScoreKey).map(_.score).getOrElse(0)

  /** Ensures an accumulator exists, refreshing the identity if the caller knows more than before. */
  def start(attrs: TypedMap, identity: ClientIdentity): ThreatScore = {
    val next = attrs.get(ThreatKeys.ScoreKey) match {
      case Some(existing) => existing.withIdentity(identity)
      case None           => ThreatScore.empty(identity)
    }
    attrs.put(ThreatKeys.ScoreKey -> next)
    next
  }

  def contribute(attrs: TypedMap, identity: ClientIdentity, signal: ThreatSignal): ThreatScore = {
    val next = attrs.get(ThreatKeys.ScoreKey).getOrElse(ThreatScore.empty(identity)).withSignal(signal)
    attrs.put(ThreatKeys.ScoreKey -> next)
    next
  }

  def contributeAll(attrs: TypedMap, identity: ClientIdentity, signals: Seq[ThreatSignal]): ThreatScore = {
    val next = signals.foldLeft(attrs.get(ThreatKeys.ScoreKey).getOrElse(ThreatScore.empty(identity)))((acc, signal) => acc.withSignal(signal))
    attrs.put(ThreatKeys.ScoreKey -> next)
    next
  }
}
