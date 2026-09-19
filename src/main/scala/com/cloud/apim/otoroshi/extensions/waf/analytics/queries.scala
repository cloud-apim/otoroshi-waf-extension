package com.cloud.apim.otoroshi.extensions.waf.analytics

import io.vertx.sqlclient.Pool
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings
import otoroshi.next.analytics.queries.*
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}

/**
 * The queries behind the security console and Threat Studio.
 *
 * They read the two tables the suite's own projections write, and are declared through the same
 * `analyticsQueries()` point the platform's own queries use — so every one of them shows up in the
 * widget wizard, composes into user dashboards, and can be alerted on, with no UI of ours.
 *
 * Every clause goes through `FilterSql.whereClause`, which is why the tables declare the shared
 * column contract: period, route, api, apikey, group and tenant filtering come for free, and behave
 * identically to the same filters on gateway traffic.
 *
 * On top of that contract every query here takes a `route_ids` parameter. The platform's `Filters`
 * carries a single `route_id`, which is the right shape for a dashboard scoped to one route and the
 * wrong one for a question asked of a *set* of routes — which is what a Threat Studio workspace is,
 * and what an api or a team is too. An empty list means every route, so nothing changes for a caller
 * that does not know about it.
 */
object SecurityQueries {

  private val TopNParam     = QueryParam("top_n", "int", JsNumber(10), "Maximum number of items returned")
  private val RouteIdsParam = QueryParam(
    "route_ids",
    "string[]",
    JsArray(),
    "Restrict to this set of routes. Empty — the default — means every route the filters already allow."
  )

  private val topNParams  = Seq(TopNParam, RouteIdsParam)
  private val plainParams = Seq(RouteIdsParam)

  def all: Seq[AnalyticsQuery] = Seq(
    // volume and enforcement
    EventsTotal,
    EnforcedTotal,
    SourcesTotal,
    EventsOverTime,
    EnforcedOverTime,
    OutcomeOverTime,
    ActionsOverTime,
    ActivityHeatmap,
    // breakdowns
    ByAction,
    ByCategory,
    ByOutcome,
    ByNode,
    ScoreDistribution,
    // sources
    TopSources,
    DistinctSourcesOverTime,
    TopSpreadSources,
    TopSourcesByScore,
    // detectors
    TopTags,
    TagEnforcement,
    // routes and consumers
    TopRoutes,
    RouteEnforcement,
    TopApikeys,
    TopUsers,
    // incidents
    IncidentsOverTime,
    TopIncidents,
    // waf
    WafTopRules,
    WafWouldHaveBlocked,
    WafTopRulesWouldBlock,
    WafRequestsOverTime,
    WafBlockStatus,
    WafBodyLimits,
    // the rows themselves, which is where the attribution lives
    DecisionsLog,
    DecisionDetail,
    WafTrailLog,
    WafTrailDetail
  )

  // -----------------------------------------------------------------------------------------------
  // where clause, accumulated
  // -----------------------------------------------------------------------------------------------

  /**
   * A where clause being built, carrying the values bound so far.
   *
   * `FilterSql` numbers its own placeholders and then hands the values back, so anything added
   * afterwards has to know how many are already bound. Keeping the two together is what makes that
   * impossible to get wrong — every added fragment is numbered from the values it is added with.
   */
  private final case class Where(sql: String, vals: Seq[AnyRef]) {

    def and(fragment: String): Where =
      if (fragment.isEmpty) this
      else Where(if (sql.isEmpty) s" WHERE $fragment" else s"$sql AND $fragment", vals)

    /** `fragment` is given the placeholder of the value it binds, e.g. `p => s"route_id = ANY($p)"`. */
    def andBound(fragment: String => String, value: AnyRef): Where =
      andBoundAll(ps => fragment(ps.head), Seq(value))

    /**
     * The same, for a fragment binding several values.
     *
     * One fragment rather than several: fragments are joined with `AND`, so anything containing an
     * `OR` has to arrive whole and parenthesised or it silently changes meaning.
     */
    def andBoundAll(fragment: Seq[String] => String, values: Seq[AnyRef]): Where = {
      val placeholders = values.indices.map(i => s"$$${vals.size + 1 + i}")
      and(fragment(placeholders)).copy(vals = vals ++ values)
    }
  }

  private def where(filters: Filters, params: JsObject, extra: String = ""): Where = {
    val (sql, vals) = FilterSql.whereClause(filters)
    val routeIds    = (params \ "route_ids").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty)
    val base        = Where(sql, vals).and(extra)
    if (routeIds.isEmpty) base else base.andBound(p => s"route_id = ANY($p)", routeIds.distinct.toArray)
  }

  private def limitOf(params: JsObject): Int = (params \ "top_n").asOpt[Int].getOrElse(10).max(1).min(1000)

  private def table(s: UserAnalyticsExporterSettings): String    = CloudApimSecurityEventProjection.table(s)
  private def wafTable(s: UserAnalyticsExporterSettings): String = CloudApimWafTrailEventProjection.table(s)

  // -----------------------------------------------------------------------------------------------
  // result helpers, shaped after the platform's own so the results render in the same widgets
  // -----------------------------------------------------------------------------------------------

  private def scalar(from: String, label: String, expr: String = "COUNT(*)", extra: String = "")(
      filters: Filters,
      params: JsObject,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val w   = where(filters, params, extra)
    val sql = s"SELECT $expr AS value FROM $from${w.sql}"
    QueryHelpers.runSelect(pool, sql, w.vals).map { rows =>
      val value = rows.headOption.map(r => QueryHelpers.safeLong(r, 0)).getOrElse(0L)
      QueryResult(
        AnalyticsShape.Scalar,
        Json.obj("value" -> value, "label" -> label),
        JsArray(Seq(Json.obj("value" -> value)))
      )
    }
  }

  private def pie(from: String, key: String, label: String, extra: String = "", order: String = "value DESC")(
      filters: Filters,
      params: JsObject,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val w   = where(filters, params, extra)
    val sql =
      s"""SELECT $key AS key, COUNT(*) AS value
         |FROM $from${w.sql}
         |GROUP BY 1 ORDER BY $order""".stripMargin
    QueryHelpers.runSelect(pool, sql, w.vals).map { rows =>
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
    val w      = where(filters, params, extra)
    val select = labelField match {
      case Some(l) => s"$key AS key, MAX($l) AS label, COUNT(*) AS value"
      case None    => s"$key AS key, COUNT(*) AS value"
    }
    val sql    =
      s"""SELECT $select
         |FROM $from${w.sql}
         |GROUP BY $key
         |ORDER BY value DESC
         |LIMIT ${limitOf(params)}""".stripMargin
    QueryHelpers.runSelect(pool, sql, w.vals).map { rows =>
      val items = rows.map { r =>
        val k = QueryHelpers.optString(r, 0).getOrElse("(unknown)")
        val l = labelField.flatMap(_ => QueryHelpers.optString(r, 1)).getOrElse(k)
        Json.obj("key" -> k, "label" -> l, "value" -> QueryHelpers.safeLong(r, if (labelField.isDefined) 2 else 1))
      }
      QueryResult(AnalyticsShape.TopN, Json.obj("items" -> JsArray(items)), JsArray(items))
    }
  }

  /**
   * A ranked table rather than a ranked bar.
   *
   * `TopN` carries one number per row, and several of the questions worth asking here need two or
   * three side by side — how often a signal fired *and* how often it was acted on, how many routes a
   * caller touched *and* how many times. The platform's table widget renders every column but `key`,
   * so these compose into a dashboard exactly like the rest.
   */
  private def rows(from: String, group: String, columns: Seq[(String, String)], order: String, extra: String = "")(
      filters: Filters,
      params: JsObject,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val w   = where(filters, params, extra)
    val sql =
      s"""SELECT $group AS key, ${columns.map { case (name, expr) => s"$expr AS $name" }.mkString(", ")}
         |FROM $from${w.sql}
         |GROUP BY $group
         |ORDER BY $order
         |LIMIT ${limitOf(params)}""".stripMargin
    QueryHelpers.runSelect(pool, sql, w.vals).map { rs =>
      val items = rs.map { r =>
        val cells = columns.zipWithIndex.map { case ((name, _), i) =>
          val value = r.getValue(i + 1)
          name -> (value match {
            case null                => JsNull
            case n: java.lang.Number => JsNumber(BigDecimal(n.doubleValue()))
            case other               => JsString(other.toString)
          })
        }
        Json.obj("key" -> QueryHelpers.optString(r, 0).getOrElse("(unknown)")) ++ JsObject(cells)
      }
      QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
    }
  }

  private def series(from: String, bucket: Bucket, expr: String = "COUNT(*)", extra: String = "")(
      filters: Filters,
      params: JsObject,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val w   = where(filters, params, extra)
    val sql = TimeseriesQueries.buildSeriesQuery(s"$expr AS value", bucket, w.sql, from)
    QueryHelpers.runSelect(pool, sql, w.vals).map { rs =>
      val points = rs.map { r =>
        Json.obj("ts" -> QueryHelpers.jsTs(r.getOffsetDateTime(0)), "value" -> QueryHelpers.safeLong(r, 2))
      }
      QueryResult(
        AnalyticsShape.Timeseries,
        Json.obj("bucket" -> bucket.name, "points" -> JsArray(points)),
        JsArray(points)
      )
    }
  }

  /** Several named series in one pass, each a `FILTER (WHERE …)` over the same scan. */
  private def multiSeries(from: String, bucket: Bucket, named: Seq[(String, String)], extra: String = "")(
      filters: Filters,
      params: JsObject,
      pool: Pool
  )(using ec: ExecutionContext): Future[QueryResult] = {
    val w   = where(filters, params, extra)
    val agg = named.zipWithIndex.map { case ((_, cond), i) => s"COUNT(*) FILTER (WHERE $cond) AS s$i" }.mkString(", ")
    val sql = TimeseriesQueries.buildSeriesQuery(agg, bucket, w.sql, from)
    QueryHelpers.runSelect(pool, sql, w.vals).map { rs =>
      val all = named.zipWithIndex.map { case ((name, _), i) =>
        val points = rs.map { r =>
          Json.obj("ts" -> QueryHelpers.jsTs(r.getOffsetDateTime(0)), "value" -> QueryHelpers.safeLong(r, 2 + i))
        }
        Json.obj("name" -> name, "points" -> JsArray(points))
      }
      QueryResult(AnalyticsShape.Timeseries, Json.obj("bucket" -> bucket.name, "series" -> JsArray(all)), JsArray())
    }
  }

  // -----------------------------------------------------------------------------------------------
  // volume and enforcement
  // -----------------------------------------------------------------------------------------------

  object EventsTotal extends AnalyticsQuery {
    val id                               = "cloudapim_security_events_total"
    val name                             = "Security decisions"
    val description                      = "Every decision the fabric recorded, enforced or not."
    val shape                            = AnalyticsShape.Scalar
    val defaultWidget                    = "metric"
    override val supportsCompare         = true
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = scalar(table(s), "Security decisions")(f, p, pool)
  }

  object EnforcedTotal extends AnalyticsQuery {
    val id                               = "cloudapim_security_enforced_total"
    val name                             = "Enforced decisions"
    val description                      =
      "Decisions that actually denied, tarpitted, challenged or banned. The rest were observed."
    val shape                            = AnalyticsShape.Scalar
    val defaultWidget                    = "metric"
    override val supportsCompare         = true
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = scalar(table(s), "Enforced decisions", extra = "enforced = true")(f, p, pool)
  }

  object SourcesTotal extends AnalyticsQuery {
    val id                               = "cloudapim_security_sources_total"
    val name                             = "Distinct sources"
    val description                      =
      "How many addresses the fabric decided against. Read beside the decision count it tells apart one persistent caller from a crowd."
    val shape                            = AnalyticsShape.Scalar
    val defaultWidget                    = "metric"
    override val supportsCompare         = true
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      scalar(table(s), "Distinct sources", expr = "COUNT(DISTINCT from_ip)", extra = "from_ip IS NOT NULL")(f, p, pool)
  }

  object EventsOverTime extends AnalyticsQuery {
    val id                               = "cloudapim_security_events_over_time"
    val name                             = "Security decisions over time"
    val description                      = "Attack volume as the fabric saw it."
    val shape                            = AnalyticsShape.Timeseries
    val defaultWidget                    = "area"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = series(table(s), b)(f, p, pool)
  }

  object EnforcedOverTime extends AnalyticsQuery {
    val id                               = "cloudapim_security_enforced_over_time"
    val name                             = "Enforced decisions over time"
    val description                      = "What was actually stopped, as opposed to merely recorded."
    val shape                            = AnalyticsShape.Timeseries
    val defaultWidget                    = "area"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = series(table(s), b, extra = "enforced = true")(f, p, pool)
  }

  object OutcomeOverTime extends AnalyticsQuery {
    val id                               = "cloudapim_security_outcome_over_time"
    val name                             = "Enforced versus observed over time"
    val description                      =
      "The dry-run gap, as a trend. Arming a policy is meant to move the observed band into the enforced one and leave the total where it was."
    val shape                            = AnalyticsShape.Timeseries
    val defaultWidget                    = "area"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      multiSeries(table(s), b, Seq("enforced" -> "enforced = true", "observed" -> "enforced = false"))(f, p, pool)
  }

  object ActionsOverTime extends AnalyticsQuery {
    val id                               = "cloudapim_security_actions_over_time"
    val name                             = "Actions over time"
    val description                      = "The graded response as it moves: log, tarpit, challenge, deny, ban."
    val shape                            = AnalyticsShape.Timeseries
    val defaultWidget                    = "area"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      multiSeries(
        table(s),
        b,
        Seq("log", "tarpit", "challenge", "deny", "ban").map(a => a -> s"action = '$a'")
      )(f, p, pool)
  }

  /**
   * Hour of day against day of week, over the whole period.
   *
   * Not a time series: the question is whether the traffic has a shape a human keeps — office hours,
   * a working week — or the flat, round-the-clock profile of something automated. That only appears
   * once the calendar is folded.
   */
  object ActivityHeatmap extends AnalyticsQuery {
    val id                               = "cloudapim_security_activity_heatmap"
    val name                             = "Decisions by hour and weekday"
    val description                      =
      "Folded over the period: a human audience has office hours, a scanner does not."
    val shape                            = AnalyticsShape.Heatmap
    val defaultWidget                    = "heatmap"
    override val params: Seq[QueryParam] = plainParams

    private val weekdays = Seq("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val w   = where(f, p)
      val sql =
        s"""SELECT EXTRACT(ISODOW FROM ts)::int AS dow, EXTRACT(HOUR FROM ts)::int AS hour, COUNT(*) AS value
           |FROM ${table(s)}${w.sql}
           |GROUP BY 1, 2""".stripMargin
      QueryHelpers.runSelect(pool, sql, w.vals).map { rs =>
        val counts = rs.map { r =>
          (QueryHelpers.safeLong(r, 0).toInt, QueryHelpers.safeLong(r, 1).toInt) -> QueryHelpers.safeLong(r, 2)
        }.toMap
        val values = (1 to 7).map { dow =>
          JsArray((0 to 23).map(hour => JsNumber(BigDecimal(counts.getOrElse((dow, hour), 0L)))))
        }
        QueryResult(
          AnalyticsShape.Heatmap,
          Json.obj(
            "xBuckets" -> JsArray((0 to 23).map(h => JsString(f"$h%02d"))),
            "yBuckets" -> JsArray(weekdays.map(JsString.apply)),
            "values"   -> JsArray(values)
          ),
          JsArray()
        )
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // breakdowns
  // -----------------------------------------------------------------------------------------------

  object ByAction extends AnalyticsQuery {
    val id                               = "cloudapim_security_by_action"
    val name                             = "Decisions by action"
    val description                      = "Distribution across the graded response: log, tarpit, challenge, deny, ban."
    val shape                            = AnalyticsShape.Pie
    val defaultWidget                    = "pie"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = pie(table(s), "action", "Action")(f, p, pool)
  }

  object ByCategory extends AnalyticsQuery {
    val id                               = "cloudapim_security_by_category"
    val name                             = "Decisions by detector"
    val description                      = "Which component decided: threat, honeypot, fail2ban, challenge or ban."
    val shape                            = AnalyticsShape.Pie
    val defaultWidget                    = "donut"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = pie(table(s), "category", "Detector")(f, p, pool)
  }

  object ByOutcome extends AnalyticsQuery {
    val id                               = "cloudapim_security_by_outcome"
    val name                             = "Blocked versus observed"
    val description                      = "The dry-run ratio: how much of what the fabric decided was actually enforced."
    val shape                            = AnalyticsShape.Pie
    val defaultWidget                    = "donut"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = pie(table(s), "outcome", "Outcome")(f, p, pool)
  }

  /**
   * Which node decided.
   *
   * A cluster whose nodes all see traffic produces an even spread. One node holding everything means
   * the shared state is not shared — which makes every other number on a console unreliable rather
   * than merely incomplete, since bans and counters are then per node.
   */
  object ByNode extends AnalyticsQuery {
    val id                               = "cloudapim_security_by_node"
    val name                             = "Decisions by node"
    val description                      = "An uneven spread across a cluster is usually a shared state that is not shared."
    val shape                            = AnalyticsShape.Pie
    val defaultWidget                    = "donut"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = pie(table(s), "node", "Node", extra = "node IS NOT NULL")(f, p, pool)
  }

  object ScoreDistribution extends AnalyticsQuery {
    val id                               = "cloudapim_security_score_distribution"
    val name                             = "Threat score distribution"
    val description                      =
      "Where the accumulated score lands. The tiers of a threat policy are thresholds on this number, so this is the picture to set them from."
    val shape                            = AnalyticsShape.Pie
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = plainParams

    private val bucketExpr =
      """CASE
        |  WHEN score < 20  THEN '0-19'
        |  WHEN score < 40  THEN '20-39'
        |  WHEN score < 60  THEN '40-59'
        |  WHEN score < 80  THEN '60-79'
        |  WHEN score < 100 THEN '80-99'
        |  ELSE '100+'
        |END""".stripMargin

    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      // ordered by the band, not by the count: a distribution read out of order is not a distribution
      pie(table(s), bucketExpr, "Score", order = "MIN(score)")(f, p, pool)
  }

  // -----------------------------------------------------------------------------------------------
  // sources
  // -----------------------------------------------------------------------------------------------

  object TopSources extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_sources"
    val name                             = "Top sources"
    val description                      = "The addresses the fabric decided against most often."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(table(s), "from_ip", None, "from_ip IS NOT NULL")(f, p, pool)
  }

  object DistinctSourcesOverTime extends AnalyticsQuery {
    val id                               = "cloudapim_security_distinct_sources_over_time"
    val name                             = "Distinct sources over time"
    val description                      =
      "Breadth rather than volume. Read against the decision count, a flat source count under a rising decision count is one caller trying harder."
    val shape                            = AnalyticsShape.Timeseries
    val defaultWidget                    = "line"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      series(table(s), b, expr = "COUNT(DISTINCT from_ip)", extra = "from_ip IS NOT NULL")(f, p, pool)
  }

  /**
   * Sources ranked by how many different routes they touched.
   *
   * The counterpart of `TopSources`: a caller hammering one endpoint and a caller walking the whole
   * surface produce similar decision counts and mean entirely different things.
   */
  object TopSpreadSources extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_spread_sources"
    val name                             = "Sources by spread"
    val description                      = "Who is walking the surface, as opposed to hammering one endpoint."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      rows(
        table(s),
        "from_ip",
        Seq("routes" -> "COUNT(DISTINCT route_id)", "decisions" -> "COUNT(*)", "max_score" -> "MAX(score)"),
        "routes DESC, decisions DESC",
        "from_ip IS NOT NULL"
      )(f, p, pool)
  }

  object TopSourcesByScore extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_sources_by_score"
    val name                             = "Sources by threat score"
    val description                      =
      "The most dangerous callers rather than the noisiest ones — one request scoring 90 outranks two hundred scoring 5."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      rows(
        table(s),
        "from_ip",
        Seq(
          "max_score" -> "MAX(score)",
          "avg_score" -> "ROUND(AVG(score))",
          "decisions" -> "COUNT(*)",
          "enforced"  -> "COUNT(*) FILTER (WHERE enforced)"
        ),
        "max_score DESC, decisions DESC",
        "from_ip IS NOT NULL"
      )(f, p, pool)
  }

  // -----------------------------------------------------------------------------------------------
  // detectors
  // -----------------------------------------------------------------------------------------------

  object TopTags extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_tags"
    val name                             = "Top signals"
    val description                      =
      "Which detector contributed, by tag — feed name, asn category, bot category, waf match. The answer to 'what is actually catching things'."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(s"${table(s)}, unnest(tags) AS tag", "tag", None)(f, p, pool)
  }

  /**
   * Per signal: how often it fired, and how often the decision it contributed to was acted on.
   *
   * The question actually asked of a feed is not how much it fires but whether keeping it changes
   * any outcome. A tag with ten thousand hits and no enforcement is noise being paid for.
   */
  object TagEnforcement extends AnalyticsQuery {
    val id                               = "cloudapim_security_tag_enforcement"
    val name                             = "Signals, and whether they change anything"
    val description                      =
      "Every signal with its enforced share. A source that fires constantly and never changes an outcome is noise being paid for."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      rows(
        s"${table(s)}, unnest(tags) AS tag",
        "tag",
        Seq(
          "decisions"  -> "COUNT(*)",
          "enforced"   -> "COUNT(*) FILTER (WHERE enforced)",
          "sources"    -> "COUNT(DISTINCT from_ip)",
          "avg_score"  -> "ROUND(AVG(score))"
        ),
        "decisions DESC"
      )(f, p, pool)
  }

  // -----------------------------------------------------------------------------------------------
  // routes and consumers
  // -----------------------------------------------------------------------------------------------

  object TopRoutes extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_routes"
    val name                             = "Top routes"
    val description                      = "Where the decisions are being taken."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(table(s), "route_id", Some("route_name"), "route_id IS NOT NULL")(f, p, pool)
  }

  /**
   * Per route: decided, enforced, and how many distinct callers.
   *
   * Route posture answers what a route is *configured* to do. This answers what it actually did, and
   * the two disagreeing is the whole reason both views exist.
   */
  object RouteEnforcement extends AnalyticsQuery {
    val id                               = "cloudapim_security_route_enforcement"
    val name                             = "Routes, and what they actually enforced"
    val description                      =
      "Configuration says what a route should do; this says what it did. A route deciding constantly and enforcing nothing is still in dry run."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      rows(
        table(s),
        "route_id",
        Seq(
          "route"     -> "MAX(route_name)",
          "decisions" -> "COUNT(*)",
          "enforced"  -> "COUNT(*) FILTER (WHERE enforced)",
          "sources"   -> "COUNT(DISTINCT from_ip)"
        ),
        "decisions DESC",
        "route_id IS NOT NULL"
      )(f, p, pool)
  }

  object TopApikeys extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_apikeys"
    val name                             = "Top api keys decided against"
    val description                      =
      "Authenticated callers the fabric acted on. An attack from a valid key is the one a perimeter view never shows."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(table(s), "apikey_id", None, "apikey_id IS NOT NULL")(f, p, pool)
  }

  object TopUsers extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_users"
    val name                             = "Top users decided against"
    val description                      = "The same question for callers identified by a user session rather than a key."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(table(s), "user_email", None, "user_email IS NOT NULL")(f, p, pool)
  }

  // -----------------------------------------------------------------------------------------------
  // incidents
  // -----------------------------------------------------------------------------------------------

  object IncidentsOverTime extends AnalyticsQuery {
    val id                               = "cloudapim_security_incidents_over_time"
    val name                             = "Incidents over time"
    val description                      =
      "Correlated incidents rather than raw decisions — the count a human is expected to work through."
    val shape                            = AnalyticsShape.Timeseries
    val defaultWidget                    = "line"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      series(table(s), b, expr = "COUNT(DISTINCT incident_id)", extra = "incident_id IS NOT NULL")(f, p, pool)
  }

  object TopIncidents extends AnalyticsQuery {
    val id                               = "cloudapim_security_top_incidents"
    val name                             = "Largest incidents"
    val description                      = "The incidents holding the most evidence, with the caller they were opened on."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      rows(
        table(s),
        "incident_id",
        Seq(
          "source"    -> "MAX(from_ip)",
          "events"    -> "MAX(incident_count)",
          "decisions" -> "COUNT(*)",
          "enforced"  -> "COUNT(*) FILTER (WHERE enforced)",
          "max_score" -> "MAX(score)"
        ),
        "events DESC, decisions DESC",
        "incident_id IS NOT NULL"
      )(f, p, pool)
  }

  // -----------------------------------------------------------------------------------------------
  // waf
  // -----------------------------------------------------------------------------------------------

  object WafTopRules extends AnalyticsQuery {
    val id                               = "cloudapim_waf_top_rules"
    val name                             = "Top triggered WAF rules"
    val description                      = "Which SecLang rules matched most. The starting point of every tuning session."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topN(s"${wafTable(s)}, unnest(rule_ids) AS rule_id", "rule_id::text", None)(f, p, pool)
  }

  object WafWouldHaveBlocked extends AnalyticsQuery {
    val id                               = "cloudapim_waf_would_have_blocked"
    val name                             = "WAF: would have blocked"
    val description                      =
      "Requests the ruleset reached a deny on while in monitoring mode. Counting these over real traffic is the honest measure of what arming will cost."
    val shape                            = AnalyticsShape.Timeseries
    val defaultWidget                    = "area"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = series(wafTable(s), b, extra = "blocked = true AND blocking = false")(f, p, pool)
  }

  /**
   * The rules behind that cost, named.
   *
   * `WafWouldHaveBlocked` says how much arming would cost; this says which rules to look at to make
   * it cheaper. Ranking every matching rule instead would mostly surface the harmless ones.
   */
  object WafTopRulesWouldBlock extends AnalyticsQuery {
    val id                               = "cloudapim_waf_top_rules_would_block"
    val name                             = "WAF rules behind the blocks"
    val description                      =
      "Ranked over the requests a monitoring ruleset reached a deny on — the rules arming would actually cost you."
    val shape                            = AnalyticsShape.TopN
    val defaultWidget                    = "bar"
    override val params: Seq[QueryParam] = topNParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      topN(
        s"${wafTable(s)}, unnest(rule_ids) AS rule_id",
        "rule_id::text",
        None,
        "blocked = true AND blocking = false"
      )(f, p, pool)
  }

  object WafRequestsOverTime extends AnalyticsQuery {
    val id                               = "cloudapim_waf_requests_over_time"
    val name                             = "WAF traffic over time"
    val description                      =
      "Inspected, blocked, and would have blocked, on one axis. The gap between the last two is what arming closes."
    val shape                            = AnalyticsShape.Timeseries
    val defaultWidget                    = "area"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      multiSeries(
        wafTable(s),
        b,
        Seq(
          "inspected"          -> "true",
          "blocked"            -> "blocked = true AND blocking = true",
          "would have blocked" -> "blocked = true AND blocking = false"
        )
      )(f, p, pool)
  }

  object WafBlockStatus extends AnalyticsQuery {
    val id                               = "cloudapim_waf_block_status"
    val name                             = "WAF block statuses"
    val description                      = "What a blocked caller was actually answered."
    val shape                            = AnalyticsShape.Pie
    val defaultWidget                    = "donut"
    override val params: Seq[QueryParam] = plainParams
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      pie(wafTable(s), "status::text", "Status", extra = "status IS NOT NULL")(f, p, pool)
  }

  /**
   * How often the body limit was actually reached.
   *
   * A verdict on a body that was only half read proves less than one on a whole body, and the limit
   * is a global default nobody revisits. This is the number that says whether it needs revisiting.
   */
  object WafBodyLimits extends AnalyticsQuery {
    val id                               = "cloudapim_waf_body_limits"
    val name                             = "WAF body inspection limits"
    val description                      =
      "How much traffic hit the body limit. A verdict on a truncated body is weaker than one on a whole body."
    val shape                            = AnalyticsShape.Pie
    val defaultWidget                    = "donut"
    override val params: Seq[QueryParam] = plainParams

    private val bucketExpr =
      """CASE
        |  WHEN oversize_rejected THEN 'rejected as oversize'
        |  WHEN truncated         THEN 'truncated'
        |  ELSE 'inspected whole'
        |END""".stripMargin

    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = pie(wafTable(s), bucketExpr, "Body")(f, p, pool)
  }

  // -----------------------------------------------------------------------------------------------
  // the rows themselves
  // -----------------------------------------------------------------------------------------------

  private val LimitParam    = QueryParam("limit", "int", JsNumber(100), "Number of rows (max 500)")
  private val BeforeParam   =
    QueryParam("before", "int", JsNull, "Only rows older than this instant (epoch millis), to fetch the next page")
  private val BeforeIdParam = QueryParam(
    "before_id",
    "string",
    JsNull,
    "The id of the last row of the previous page. Passed with `before`, it is what keeps rows sharing that instant."
  )
  private val IdParam       = QueryParam("id", "string", JsNull, "The row to read, by event id")

  /**
   * The `raw` column, read back as json.
   *
   * The driver hands a jsonb column back as text rather than as a node, so without this the event
   * comes back as a string containing json — which renders as a wall of escaped quotes and makes the
   * signals, the whole reason the column is kept, unreadable.
   */
  private def jsonCell(value: Any): JsValue = value match {
    case null                             => JsNull
    case v: io.vertx.core.json.JsonObject => scala.util.Try(Json.parse(v.encode())).getOrElse(JsNull)
    case v: String                        => scala.util.Try(Json.parse(v)).getOrElse(JsString(v))
    case v                                => cell(v)
  }

  private def cell(value: Any): JsValue = value match {
    case null                              => JsNull
    case v: java.lang.Boolean              => JsBoolean(v)
    case v: java.lang.Number               => JsNumber(BigDecimal(v.toString))
    case v: java.time.OffsetDateTime       => JsNumber(v.toInstant.toEpochMilli)
    case v: Array[?]                       => JsArray(v.toIndexedSeq.map(cell))
    case v: io.vertx.core.json.JsonObject  => scala.util.Try(Json.parse(v.encode())).getOrElse(JsNull)
    case v: io.vertx.core.json.JsonArray   => scala.util.Try(Json.parse(v.encode())).getOrElse(JsNull)
    case v                                 => JsString(v.toString)
  }

  /**
   * A page of rows, newest first.
   *
   * Paged on `(ts, id)` rather than on an offset: an offset walks rows that keep arriving, and a log
   * of security decisions is exactly the table that grows while it is being read.
   *
   * The id is half the cursor, not a detail. A timestamp alone loses every row sharing the instant
   * the page ended on, and at millisecond resolution a burst — which is the traffic this log exists
   * to show — puts many rows on the same instant. Ordering and comparing on the pair keeps them.
   *
   * `bound` carries the narrowing that is not a shared filter — a detector, an action, one address.
   * Each one goes through the same binder as everything else, so the values stay parameters and are
   * numbered against what is already bound.
   */
  private def log(
      from: String,
      columns: Seq[String],
      extra: String,
      bound: Seq[(String => String, AnyRef)] = Seq.empty
  )(filters: Filters, params: JsObject, pool: Pool)(using ec: ExecutionContext): Future[QueryResult] = {
    val limit    = (params \ "limit").asOpt[Int].getOrElse(100).max(1).min(500)
    val before   = (params \ "before").asOpt[Long]
    val beforeId = (params \ "before_id").asOpt[String].map(_.trim).filter(_.nonEmpty)
    val paged    = before.foldLeft(where(filters, params, extra)) { (acc, millis) =>
      val ts = java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC)
      beforeId match {
        case None     => acc.andBound(p => s"ts < $p", ts)
        // the pair, spelled out rather than as a row comparison: it keeps the index on ts usable
        case Some(id) =>
          acc.andBoundAll(ps => s"(ts < ${ps(0)} OR (ts = ${ps(1)} AND id < ${ps(2)}))", Seq(ts, ts, id))
      }
    }
    val w        = bound.foldLeft(paged) { case (acc, (fragment, value)) => acc.andBound(fragment, value) }
    val sql      = s"SELECT ${columns.mkString(", ")} FROM $from${w.sql} ORDER BY ts DESC, id DESC LIMIT $limit"
    QueryHelpers.runSelect(pool, sql, w.vals).map { rs =>
      val items = rs.map(r => JsObject(columns.zipWithIndex.map { case (name, i) => name -> cell(r.getValue(i)) }))
      val last  = items.lastOption
      // null rather than a cursor on a short page: there is nothing after it
      val done  = rs.size < limit
      QueryResult(
        AnalyticsShape.Table,
        Json.obj(
          "items"          -> JsArray(items),
          "next_before"    -> (if (done) JsNull else last.flatMap(i => (i \ "ts").asOpt[JsValue]).getOrElse(JsNull)),
          "next_before_id" -> (if (done) JsNull else last.flatMap(i => (i \ "id").asOpt[JsValue]).getOrElse(JsNull))
        ),
        JsArray(items)
      )
    }
  }

  private val decisionColumns = Seq(
    "id", "ts", "route_id", "route_name", "category", "action", "outcome", "enforced",
    "severity", "score", "tags", "from_ip", "apikey_id", "user_email", "incident_id", "incident_count", "node"
  )

  object DecisionsLog extends AnalyticsQuery {
    val id                               = "cloudapim_security_decisions_log"
    val name                             = "Security decisions log"
    val description                      =
      "The decisions of the period one by one, newest first. Page with `before`, the epoch millis of the last row."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = Seq(
      LimitParam,
      BeforeParam,
      BeforeIdParam,
      RouteIdsParam,
      QueryParam("category", "string", JsNull, "Only the decisions of this detector"),
      QueryParam("action", "string", JsNull, "Only the decisions that took this action"),
      QueryParam("enforced", "boolean", JsNull, "true for the decisions that acted, false for the observed ones"),
      QueryParam("source", "string", JsNull, "Only the decisions taken against this address"),
      QueryParam("tag", "string", JsNull, "Only the decisions carrying this signal"),
      QueryParam("incident_id", "string", JsNull, "Only the decisions of this incident")
    )

    private def text(p: JsObject, name: String): Option[String] =
      (p \ name).asOpt[String].map(_.trim).filter(_.nonEmpty)

    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val bound: Seq[(String => String, AnyRef)] = Seq(
        text(p, "category").map(v => ((ph: String) => s"category = $ph", v)),
        text(p, "action").map(v => ((ph: String) => s"action = $ph", v)),
        text(p, "source").map(v => ((ph: String) => s"from_ip = $ph", v)),
        text(p, "tag").map(v => ((ph: String) => s"$ph = ANY(tags)", v)),
        text(p, "incident_id").map(v => ((ph: String) => s"incident_id = $ph", v))
      ).flatten
      // a boolean needs no binding and reads better inline
      val extra                                  =
        (p \ "enforced").asOpt[Boolean].map(v => s"enforced = ${if (v) "true" else "false"}").getOrElse("")
      log(table(s), decisionColumns, extra, bound)(f, p, pool)
    }
  }

  /** One decision, whole — the signals included, which is the only thing that answers *why*. */
  object DecisionDetail extends AnalyticsQuery {
    val id                               = "cloudapim_security_decision_detail"
    val name                             = "One security decision"
    val description                      = "The whole event behind a row, signals included."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = Seq(IdParam, RouteIdsParam)
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val w   = (p \ "id").asOpt[String].filter(_.nonEmpty) match {
        case None     => where(f, p, "false")
        case Some(id) => where(f, p).andBound(ph => s"id = $ph", id)
      }
      val sql = s"SELECT id, ts, raw FROM ${table(s)}${w.sql} LIMIT 1"
      QueryHelpers.runSelect(pool, sql, w.vals).map { rs =>
        val items = rs.map(r =>
          Json.obj("id" -> cell(r.getValue(0)), "ts" -> cell(r.getValue(1)), "raw" -> jsonCell(r.getValue(2)))
        )
        QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
      }
    }
  }

  private val trailColumns =
    Seq("id", "ts", "route_id", "route_name", "blocking", "blocked", "status", "rule_ids", "truncated", "oversize_rejected")

  object WafTrailLog extends AnalyticsQuery {
    val id                               = "cloudapim_waf_trail_log"
    val name                             = "WAF trail log"
    val description                      =
      "The requests the rule engine inspected, newest first, with the rules they matched. Page with `before`."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = Seq(
      LimitParam,
      BeforeParam,
      BeforeIdParam,
      RouteIdsParam,
      QueryParam("only", "string", JsNull, "blocked, would_block or matched")
    )
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val extra = (p \ "only").asOpt[String].map(_.trim) match {
        case Some("blocked")     => "blocked = true AND blocking = true"
        case Some("would_block") => "blocked = true AND blocking = false"
        case Some("matched")     => "cardinality(rule_ids) > 0"
        case _                   => ""
      }
      log(wafTable(s), trailColumns, extra)(f, p, pool)
    }
  }

  object WafTrailDetail extends AnalyticsQuery {
    val id                               = "cloudapim_waf_trail_detail"
    val name                             = "One WAF trail"
    val description                      = "The whole trail event behind a row, every rule match included."
    val shape                            = AnalyticsShape.Table
    val defaultWidget                    = "table"
    override val params: Seq[QueryParam] = Seq(IdParam, RouteIdsParam)
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val w   = (p \ "id").asOpt[String].filter(_.nonEmpty) match {
        case None     => where(f, p, "false")
        case Some(id) => where(f, p).andBound(ph => s"id = $ph", id)
      }
      val sql = s"SELECT id, ts, raw FROM ${wafTable(s)}${w.sql} LIMIT 1"
      QueryHelpers.runSelect(pool, sql, w.vals).map { rs =>
        val items = rs.map(r =>
          Json.obj("id" -> cell(r.getValue(0)), "ts" -> cell(r.getValue(1)), "raw" -> jsonCell(r.getValue(2)))
        )
        QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
      }
    }
  }
}
