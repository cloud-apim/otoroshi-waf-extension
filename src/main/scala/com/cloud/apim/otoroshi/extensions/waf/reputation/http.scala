package com.cloud.apim.otoroshi.extensions.waf.reputation

import otoroshi.env.Env
import play.api.libs.json.JsValue
import play.api.libs.ws.writeableOf_JsValue

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

final case class HttpCall(
    method: String,
    url: String,
    headers: Seq[(String, String)] = Seq.empty,
    body: Option[JsValue] = None,
    timeout: FiniteDuration,
    followRedirects: Boolean = true
)

final case class HttpResult(status: Int, body: String, headers: Map[String, Seq[String]]) {
  def header(name: String): Option[String] =
    headers.collectFirst { case (key, values) if key.equalsIgnoreCase(name) => values }.flatMap(_.headOption)
  def isSuccess: Boolean = status >= 200 && status < 300
}

/**
 * The whole outbound surface of this module, in one place.
 *
 * Narrow on purpose: the feed refresher and the CrowdSec client need four verbs and a timeout, not
 * an Otoroshi `Env`. Depending on this rather than on `Env` is what lets the CrowdSec integration
 * suite drive the real client against a real Local API without booting a gateway.
 */
trait ReputationHttpClient {
  def call(request: HttpCall): Future[HttpResult]
}

/** Production implementation, over Otoroshi's pooled and configured WS client. */
class EnvHttpClient(env: Env) extends ReputationHttpClient {

  private given ExecutionContext = env.otoroshiExecutionContext

  override def call(request: HttpCall): Future[HttpResult] = {
    val base = env.Ws
      .url(request.url)
      .withRequestTimeout(request.timeout)
      .withFollowRedirects(request.followRedirects)
      .withHttpHeaders(request.headers*)
    val prepared = request.body match {
      case Some(json) => base.withBody(json)
      case None       => base
    }
    prepared.execute(request.method.toUpperCase).map { response =>
      HttpResult(response.status, response.body, response.headers.view.mapValues(_.toSeq).toMap)
    }
  }
}
