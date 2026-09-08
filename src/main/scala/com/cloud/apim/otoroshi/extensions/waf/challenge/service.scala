package com.cloud.apim.otoroshi.extensions.waf.challenge

import com.cloud.apim.otoroshi.extensions.waf.entities.ChallengeProvider
import com.cloud.apim.otoroshi.extensions.waf.reputation.{HttpCall, ReputationHttpClient}
import com.cloud.apim.otoroshi.extensions.waf.security.SharedStateStore
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*

import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

final case class IssuedChallenge(id: String, payload: JsObject)

/**
 * Issues and verifies challenges.
 *
 * The puzzle is stored in shared state rather than being self-signed, for one reason: a solution
 * has to be usable **once**. A stateless challenge can be solved on one node and replayed forever
 * across the cluster, which turns the whole scheme into a formality.
 */
class ChallengeService(
    store: SharedStateStore,
    http: ReputationHttpClient,
    keyPrefix: String,
    logger: Logger
)(using ec: ExecutionContext) {

  private def keyOf(id: String): String = s"$keyPrefix:$id"

  // -----------------------------------------------------------------------------------------------
  // issuing
  // -----------------------------------------------------------------------------------------------

  def issue(provider: ChallengeProvider, score: Int): Future[IssuedChallenge] = {
    val id = IdGenerator.token(24)
    if (provider.isPow) {
      val difficulty = Pow.difficultyFor(score, provider.difficultyFloor, provider.difficultyCeiling)
      val payload    = Json.obj("kind" -> "pow", "challenge" -> id, "difficulty" -> difficulty)
      store
        .set(keyOf(id), Json.stringify(Json.obj("difficulty" -> difficulty, "score" -> score)), Some(provider.challengeTtl.toMillis))
        .map(_ => IssuedChallenge(id, payload))
        .recover { case err: Throwable =>
          logger.warn(s"could not store challenge $id, it will not be verifiable", err)
          IssuedChallenge(id, payload)
        }
    } else {
      IssuedChallenge(
        id,
        Json.obj(
          "kind"           -> "vendor",
          "site_key"       -> provider.siteKey,
          "script"         -> provider.widgetScriptUrl,
          "widget"         -> provider.widgetHtml.replace("__SITE_KEY__", provider.siteKey),
          "response_field" -> provider.responseField
        )
      ).vfuture
    }
  }

  // -----------------------------------------------------------------------------------------------
  // verifying
  // -----------------------------------------------------------------------------------------------

  def verify(
      provider: ChallengeProvider,
      submission: JsValue,
      ip: Option[String],
      ua: Option[String]
  ): Future[Either[String, Pow.Clearance]] = {
    if (provider.isPow) verifyPow(provider, submission, ip, ua) else verifyVendor(provider, submission, ip, ua)
  }

  private def clearanceFor(provider: ChallengeProvider, ip: Option[String], ua: Option[String], score: Int) =
    Pow.Clearance(
      exp = (System.currentTimeMillis() / 1000L) + provider.clearanceTtl.toSeconds,
      ip = if (provider.bindIp) ip else None,
      ua = if (provider.bindUa) ua else None,
      score = score
    )

  private def verifyPow(
      provider: ChallengeProvider,
      submission: JsValue,
      ip: Option[String],
      ua: Option[String]
  ): Future[Either[String, Pow.Clearance]] = {
    val challenge = (submission \ "challenge").asOpt[String].getOrElse("")
    val nonce     = (submission \ "nonce").asOpt[String].getOrElse("")
    if (challenge.isEmpty || nonce.isEmpty) {
      Left("incomplete solution").vfuture
    } else {
      store.get(keyOf(challenge)).flatMap {
        case None       => Left("unknown or expired challenge").vfuture
        case Some(raw)  =>
          val stored     = Try(Json.parse(raw)).getOrElse(Json.obj())
          val difficulty = (stored \ "difficulty").asOpt[Int].getOrElse(provider.difficultyFloor)
          val score      = (stored \ "score").asOpt[Int].getOrElse(0)
          if (!Pow.solves(challenge, nonce, difficulty)) {
            Left("the solution does not meet the required difficulty").vfuture
          } else {
            // one shot: consume it before granting, so the same nonce cannot be replayed
            store.del(keyOf(challenge)).map(_ => Right(clearanceFor(provider, ip, ua, score)))
          }
      }
    }
  }

  private def verifyVendor(
      provider: ChallengeProvider,
      submission: JsValue,
      ip: Option[String],
      ua: Option[String]
  ): Future[Either[String, Pow.Clearance]] = {
    val token = (submission \ "token").asOpt[String].getOrElse("")
    if (token.isEmpty) {
      Left("no widget response").vfuture
    } else {
      val form = Seq(
        Some(s"secret=${enc(provider.secretKey)}"),
        Some(s"response=${enc(token)}"),
        ip.map(v => s"remoteip=${enc(v)}")
      ).flatten.mkString("&")
      http
        .call(
          HttpCall(
            method = "POST",
            url = provider.verifyUrl,
            headers = Seq("Content-Type" -> "application/x-www-form-urlencoded"),
            body = None,
            timeout = 10.seconds
          ).copy(body = Some(JsString(form)))
        )
        .map { response =>
          // every siteverify api in circulation answers with a top level "success" boolean
          val json = Try(Json.parse(response.body)).getOrElse(Json.obj())
          if ((json \ "success").asOpt[Boolean].contains(true)) {
            Right(clearanceFor(provider, ip, ua, 0))
          } else {
            val why = (json \ "error-codes").asOpt[Seq[String]].map(_.mkString(", ")).getOrElse(s"status ${response.status}")
            Left(s"the provider rejected the response: $why")
          }
        }
        .recover { case err: Throwable =>
          // fail closed here on purpose: an unverifiable answer is not a passed challenge
          Left(s"could not reach the challenge provider: ${err.getMessage}")
        }
    }
  }

  private def enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")
}
