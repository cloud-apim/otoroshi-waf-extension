package com.cloud.apim.otoroshi.extensions.waf.security

import com.cloud.apim.otoroshi.extensions.waf.reputation.WafDetectionRelay
import org.apache.pekko.actor.{Actor, Props}
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import play.api.Logger
import play.api.libs.json.*

/**
 * Feeds the fabric from WAF detections that no threat-response plugin saw.
 *
 * The WAF plugin cannot be asked to contribute to the in-request bus — this extension does not
 * modify it — so its verdicts are picked up from the analytics event stream instead. That is late
 * for the current request, and exactly on time for the next one: this is what gives the gateway a
 * memory of a caller it has already caught.
 */
class SecurityEventRelay(module: SecurityModule, settings: () => RelaySettings, logger: Logger)(using env: Env)
    extends Actor {

  override def receive: Receive = {
    case evt: AnalyticEvent if evt.`@type` == WafDetectionRelay.eventType =>
      try {
        module.onWafDetection(evt.toJson, settings())
      } catch {
        case err: Throwable => logger.error("could not relay a waf detection into the security fabric", err)
      }
    case _                                                                => ()
  }
}

final case class RelaySettings(
    correlate: Boolean = true,
    feedLedgerOnBlock: Boolean = false,
    feedLedgerOnMonitored: Boolean = false,
    weight: Int = 25
)

object SecurityEventRelay {
  def props(module: SecurityModule, settings: () => RelaySettings, logger: Logger)(using env: Env): Props =
    Props(new SecurityEventRelay(module, settings, logger))
}
