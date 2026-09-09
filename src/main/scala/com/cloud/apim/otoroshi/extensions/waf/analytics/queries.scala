package com.cloud.apim.otoroshi.extensions.waf.analytics

import io.vertx.sqlclient.Pool
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings
import otoroshi.next.analytics.queries.*
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}

/**
 * The queries behind the security console.
 *
 * They read the two tables the suite's own projections write, and are declared through the same
 * `analyticsQueries()` point the platform's own queries use — so every one of them shows up in the
 * widget wizard, composes into user dashboards, and can be alerted on, with no UI of ours.
 *
 * Every clause goes through `FilterSql.whereClause`, which is why the tables declare the shared
 * column contract: period, route, api, apikey, group and tenant filtering come for free, and behave
 * identically to the same filters on gateway traffic.
 */
object SecurityQueries {

  private val TopNParam = QueryParam("top_n", "int", JsNumber(10), "Maximum number of items returned")

  def all: Seq[AnalyticsQuery] = Seq(
    EventsTotal,
    EnforcedTotal,
    EventsOverTime,
    EnforcedOverTime,
    ByAction,
    ByCategory,
    ByOutcome,
    TopSources,
    TopTags,
    TopRoutes,
    WafTopRules,
    WafWouldHaveBlocked
  )

  // -----------------------------------------------------------------------------------------------
  // helpers, shaped after the platform's own so the results render in the same widgets
  // -----------------------------------------------------------------------------------------------

  private def table(s: UserAnalyticsExporterSettings): String  = CloudApimSecurityEventProjection.table(s)
  private def wafTable(s: UserAnalyticsExporterSettings): String = CloudApimWafTrailEventProjection.table(s)

  private def and(where: String, extra: String): String =
    if (extra.isEmpty) where else if (where.isEmpty) s" WHERE $extra" else s"$where AND $extra"

  private def scalar(from: String, label: String, extra: String = "")(
      filters: Filters,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val (where, vals) = FilterSql.whereClause(filters)
    val sql           = s"SELECT COUNT(*) AS value FROM $from${and(where, extra)}"
    QueryHelpers.runSelect(pool, sql, vals).map { rows =>
      val value = rows.headOption.map(r => QueryHelpers.safeLong(r, 0)).getOrElse(0L)
      QueryResult(AnalyticsShape.Scalar, Json.obj("value" -> value, "label" -> label), JsArray(Seq(Json.obj("value" -> value))))
    }
  }

  private def pie(from: String, key: String, label: String, extra: String = "")(
      filters: Filters,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val (where, vals) = FilterSql.whereClause(filters)
    val sql           =
      s"""SELECT $key AS key, COUNT(*) AS value
         |FROM $from${and(where, extra)}
         |GROUP BY 1 ORDER BY value DESC""".stripMargin
    QueryHelpers.runSelect(pool, sql, vals).map { rows =>
      val items = rows.map { r =>
        Json.obj("key" -> QueryHelpers.optString(r, 0).getOrElse("(unknown)"), "value" -> QueryHelpers.safeLong(r, 1))
      }
      QueryResult(AnalyticsShape.Pie, Json.obj("label" -> label, "items" -> JsArray(items)), JsArray(items))
    }
  }

  private def topN(from: String, key: String, labelField: Option[String], extra: String = "")(
      filters: Filters,
      params: JsObject,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val limit         = (params \ "top_n").asOpt[Int].getOrElse(10).max(1).min(1000)
    val (where, vals) = FilterSql.whereClause(filters)
    val select        = labelField match {
      case Some(l) => s"$key AS key, MAX($l) AS label, COUNT(*) AS value"
      case None    => s"$key AS key, COUNT(*) AS value"
    }
    val sql           =
      s"""SELECT $select
         |FROM $from${and(where, extra)}
         |GROUP BY $key
         |ORDER BY value DESC
         |LIMIT $limit""".stripMargin
    QueryHelpers.runSelect(pool, sql, vals).map { rows =>
      val items = rows.map { r =>
        val k = QueryHelpers.optString(r, 0).getOrElse("(unknown)")
        val l = labelField.flatMap(_ => QueryHelpers.optString(r, 1)).getOrElse(k)
        Json.obj("key" -> k, "label" -> l, "value" -> QueryHelpers.safeLong(r, if (labelField.isDefined) 2 else 1))
      }
      QueryResult(AnalyticsShape.TopN, Json.obj("items" -> JsArray(items)), JsArray(items))
    }
  }

  private def series(from: String, bucket: Bucket, extra: String = "")(
      filters: Filters,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val (where, vals) = FilterSql.whereClause(filters)
    val sql           = TimeseriesQueries.buildSeriesQuery("COUNT(*) AS value", bucket, and(where, extra), from)
    QueryHelpers.runSelect(pool, sql, vals).map { rows =>
      val points = rows.map { r =>
        Json.obj("ts" -> QueryHelpers.jsTs(r.getOffsetDateTime(0)), "value" -> QueryHelpers.safeLong(r, 2))
      }
      QueryResult(AnalyticsShape.Timeseries, Json.obj("bucket" -> bucket.name, "points" -> JsArray(points)), JsArray(points))
    }
  }

  // -----------------------------------------------------------------------------------------------
  // the catalogue
  // -----------------------------------------------------------------------------------------------

  object EventsTotal extends AnalyticsQuery {
    val id                       = "cloudapim_security_events_total"
    val name                     = "Security decisions"
    val description              = "Every decision the fabric recorded, enforced or not."
    val shape                    = AnalyticsShape.Scalar
    val defaultWidget            = "metric"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = scalar(table(s), "Security decisions")(f, pool)
  }

  object EnforcedTotal extends AnalyticsQuery {
    val id                       = "cloudapim_security_enforced_total"
    val name                     = "Enforced decisions"
    val description              = "Decisions that actually denied, tarpitted, challenged or banned. The rest were observed."
    val shape                    = AnalyticsShape.Scalar
    val defaultWidget            = "metric"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = scalar(table(s), "Enforced decisions", "enforced = true")(f, pool)
  }

  object EventsOverTime extends AnalyticsQuery {
    val id                 = "cloudapim_security_events_over_time"
    val name               = "Security decisions over time"
    val description        = "Attack volume as the fabric saw it."
    val shape              = AnalyticsShape.Timeseries
    val defaultWidget      = "area"
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = series(table(s), b)(f, pool)
  }

  object EnforcedOverTime extends AnalyticsQuery {
    val id                 = "cloudapim_security_enforced_over_time"
    val name               = "Enforced decisions over time"
    val description        = "What was actually stopped, as opposed to merely recorded."
    val shape              = AnalyticsShape.Timeseries
    val defaultWidget      = "area"
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = series(table(s), b, "enforced = true")(f, pool)
  }

  object ByAction extends AnalyticsQuery {
    val id                 = "cloudapim_security_by_action"
    val name               = "Decisions by action"
    val description        = "Distribution across the graded response: log, tarpit, challenge, deny, ban."
    val shape              = AnalyticsShape.Pie
    val defaultWidget      = "pie"
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = pie(table(s), "action", "Action")(f, pool)
  }

  object ByCategory extends AnalyticsQuery {
    val id                 = "cloudapim_security_by_category"
    val name               = "Decisions by detector"
    val description        = "Which component decided: threat, honeypot, fail2ban, challenge or ban."
    val shape              = AnalyticsShape.Pie
    val defaultWidget      = "donut"
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = pie(table(s), "category", "Detector")(f, pool)
  }

  object ByOutcome extends AnalyticsQuery {
    val id                 = "cloudapim_security_by_outcome"
    val name               = "Blocked versus observed"
    val description        = "The dry-run ratio: how much of what the fabric decided was actually enforced."
    val shape              = AnalyticsShape.Pie
    val defaultWidget      = "donut"
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = pie(table(s), "outcome", "Outcome")(f, pool)
  }

  object TopSources extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_sources"
    val name                             = "Top sources"
    val description                      = "The addresses the fabric decided against most often."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = Seq(TopNParam)
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(table(s), "from_ip", None, "from_ip IS NOT NULL")(f, p, pool)
  }

  object TopTags extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_tags"
    val name                             = "Top signals"
    val description                      =
      "Which detector contributed, by tag — feed name, asn category, bot category, waf match. The answer to 'what is actually catching things'."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = Seq(TopNParam)
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(s"${table(s)}, unnest(tags) AS tag", "tag", None)(f, p, pool)
  }

  object TopRoutes extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_routes"
    val name                             = "Top routes"
    val description                      = "Where the decisions are being taken."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = Seq(TopNParam)
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      topN(table(s), "route_id", Some("route_name"), "route_id IS NOT NULL")(f, p, pool)
  }

  object WafTopRules extends AnalyticsQuery {
    val id                               = "cloudapim_waf_top_rules"
    val name                             = "Top triggered WAF rules"
    val description                      = "Which SecLang rules matched most. The starting point of every tuning session."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = Seq(TopNParam)
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(s"${wafTable(s)}, unnest(rule_ids) AS rule_id", "rule_id::text", None)(f, p, pool)
  }

  object WafWouldHaveBlocked extends AnalyticsQuery {
    val id                 = "cloudapim_waf_would_have_blocked"
    val name               = "WAF: would have blocked"
    val description        =
      "Requests the ruleset reached a deny on while in monitoring mode. Counting these over real traffic is the honest measure of what arming will cost."
    val shape              = AnalyticsShape.Timeseries
    val defaultWidget      = "area"
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = series(wafTable(s), b, "blocked = true AND blocking = false")(f, pool)
  }
}
