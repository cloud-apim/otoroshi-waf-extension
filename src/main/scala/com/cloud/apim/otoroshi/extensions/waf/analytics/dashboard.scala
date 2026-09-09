package com.cloud.apim.otoroshi.extensions.waf.analytics

import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.analytics.models.{UserDashboard, Widget}
import otoroshi.security.IdGenerator
import play.api.Logger
import play.api.libs.json.Json

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

/**
 * A security console, ready on first boot.
 *
 * The widget wizard would expose the suite's queries anyway, but a catalogue is not a console: it
 * asks the operator to know what to look at before they have ever looked. This lays out the answer
 * once, as an ordinary user dashboard they are free to rearrange or delete.
 *
 * Seeded like the platform's own defaults — only when missing, and marked so that a later edit is
 * never overwritten.
 */
object SecurityDashboard {

  private val logger = Logger("cloud-apim-security-dashboard")

  private val DefaultIdKey   = "otoroshi-default-id"
  private val DefaultIdValue = "cloud-apim-security-suite"
  private val seeded         = new AtomicBoolean(false)

  private def widget(id: String, title: String, query: String, kind: String, width: Int, height: Int) =
    Widget(id = id, title = title, query = query, `type` = kind, width = width, height = height)

  private def dashboard(env: Env): UserDashboard = UserDashboard(
    location = EntityLocation.default,
    id = IdGenerator.namedId("dashboard", env),
    name = "Security suite",
    description = "What the security suite decided, and what it actually enforced",
    tags = Seq("cloud-apim", "security"),
    metadata = Map(DefaultIdKey -> DefaultIdValue),
    enabled = true,
    widgets = Seq(
      // the top row answers "is anything happening, and is any of it real"
      widget("total", "Security decisions", "cloudapim_security_events_total", "metric", 1, 1),
      widget("enforced", "Enforced", "cloudapim_security_enforced_total", "metric", 1, 1),
      widget("outcome", "Blocked vs observed", "cloudapim_security_by_outcome", "donut", 2, 1),
      widget("over-time", "Decisions over time", "cloudapim_security_events_over_time", "area", 4, 2),
      widget("by-detector", "By detector", "cloudapim_security_by_category", "donut", 2, 2),
      widget("by-action", "By action", "cloudapim_security_by_action", "pie", 2, 2),
      widget("top-sources", "Top sources", "cloudapim_security_top_sources", "bar", 2, 2),
      widget("top-tags", "Top signals", "cloudapim_security_top_tags", "bar", 2, 2),
      widget("top-routes", "Top routes", "cloudapim_security_top_routes", "bar", 2, 2),
      // and the bottom row is the tuning backlog
      widget("waf-rules", "Top triggered WAF rules", "cloudapim_waf_top_rules", "bar", 2, 2),
      widget("waf-would", "WAF: would have blocked", "cloudapim_waf_would_have_blocked", "area", 4, 2)
    ),
    defaults = Json.obj()
  )

  /**
   * Creates it once, if nobody has one already.
   *
   * Leader-only, and idempotent on the marker rather than on the name, so renaming the dashboard
   * does not bring a second copy back on the next boot.
   */
  def seedIfMissing()(using env: Env, ec: ExecutionContext): Future[Unit] = {
    if (seeded.get() || !(env.clusterConfig.mode.isOff || env.clusterConfig.mode.isLeader)) {
      Future.successful(())
    } else {
      env.datastores.userDashboardDataStore
        .findAll()
        .flatMap { existing =>
          if (existing.exists(_.metadata.get(DefaultIdKey).contains(DefaultIdValue))) {
            seeded.set(true)
            Future.successful(())
          } else {
            val db = dashboard(env)
            env.datastores.userDashboardDataStore.set(db).map { _ =>
              seeded.set(true)
              logger.info(s"seeded the '${db.name}' dashboard with ${db.widgets.size} widgets")
              ()
            }
          }
        }
        .recover { case e: Throwable =>
          // a console that could not be seeded is a missing convenience, never a reason to fail a sync
          logger.warn(s"could not seed the security dashboard: ${e.getMessage}")
          ()
        }
    }
  }
}
