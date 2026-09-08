package com.cloud.apim.otoroshi.extensions.waf.reputation

import org.apache.pekko.actor.{Actor, Props}
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*

/**
 * Relays WAF detections into CrowdSec.
 *
 * The WAF plugin already publishes every rule match as a `CloudApimWafTrailEvent`; subscribing to
 * the analytics event stream picks those up without the WAF plugin knowing this exists, which is
 * what makes Otoroshi a genuine CrowdSec detector rather than only an enforcement point — a CRS
 * match is information CrowdSec has no other way to learn.
 *
 * Matching is done on the event's `@type` string rather than its class, so this module never has
 * to depend on the plugins package.
 */
class WafDetectionRelay(module: ReputationModule, logger: Logger)(using env: Env) extends Actor {
  override def receive: Receive = {
    case evt: AnalyticEvent if evt.`@type` == WafDetectionRelay.eventType =>
      try {
        module.onWafDetection(evt.toJson)
      } catch {
        case err: Throwable => logger.error("could not relay a waf detection to crowdsec", err)
      }
    case _                                                                => ()
  }
}

object WafDetectionRelay {

  val eventType: String = "CloudApimWafTrailEvent"

  def props(module: ReputationModule, logger: Logger)(using env: Env): Props =
    Props(new WafDetectionRelay(module, logger))

  /**
   * Pulls the caller's address out of a trail event.
   *
   * The two WAF plugins serialise the request differently, and a response-phase detection carries
   * no client address at all — so this tries each known shape and gives up rather than guessing.
   */
  def clientIp(event: JsValue): Option[String] = {
    val request = (event \ "request").asOpt[JsObject].getOrElse(Json.obj())
    val headers = (request \ "headers").asOpt[JsObject].getOrElse(Json.obj())
    request
      .select("remote")
      .asOptString
      .orElse(header(headers, "Remote-Address"))
      .map(stripPort)
      .orElse(header(headers, "X-Forwarded-For").map(_.split(',').head))
      .map(_.trim)
      .filter(ip => IpParser.parseV4(ip).isDefined || IpParser.parseV6(ip).isDefined)
  }

  private def header(headers: JsObject, name: String): Option[String] = {
    headers.value.collectFirst {
      case (key, JsString(value)) if key.equalsIgnoreCase(name) && value.trim.nonEmpty => value.trim
    }
  }

  /** `1.2.3.4:56789` keeps its address, `2001:db8::1` keeps its colons. */
  private def stripPort(raw: String): String = {
    val value = raw.trim
    val idx   = value.lastIndexOf(':')
    if (idx > 0 && value.indexOf(':') == idx && IpParser.parseV4(value.substring(0, idx)).isDefined) {
      value.substring(0, idx)
    } else {
      value
    }
  }

  /** A short, human-readable reason, since it lands in a CrowdSec alert an operator will read. */
  def message(event: JsValue, enforced: Boolean): String = {
    val matches = (event \ "events").asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty)
    val rules   = matches.flatMap(m => (m \ "rule_id").asOpt[Int]).distinct.take(5)
    val reason  = (event \ "block" \ "msg")
      .asOpt[String]
      .orElse(matches.headOption.flatMap(m => (m \ "msg").asOpt[String]))
      .filter(m => m.nonEmpty && m != "--")
    val verb    = if (enforced) "blocked" else "detected"
    val rulePart = if (rules.isEmpty) "" else s" (rules ${rules.mkString(", ")})"
    s"otoroshi waf $verb a request${rulePart}${reason.map(r => s": $r").getOrElse("")}"
  }
}
