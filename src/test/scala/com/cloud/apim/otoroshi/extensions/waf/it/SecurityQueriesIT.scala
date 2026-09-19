package com.cloud.apim.otoroshi.extensions.waf.it

import com.cloud.apim.otoroshi.extensions.waf.DockerSupport
import com.cloud.apim.otoroshi.extensions.waf.analytics.*
import io.vertx.pgclient.{PgBuilder, PgConnectOptions}
import io.vertx.sqlclient.{Pool, PoolOptions, Tuple => VertxTuple}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import otoroshi.next.analytics.exporter.{AnalyticsProjection, UserAnalyticsExporterSettings}
import otoroshi.next.analytics.queries.{AnalyticsQuery, Bucket, Filters, QueryResult}
import play.api.libs.json.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

final class QueriesPgContainer
    extends GenericContainer[QueriesPgContainer](DockerImageName.parse("postgres:16-alpine"))

/**
 * The console's queries, executed.
 *
 * A query that compiles proves nothing: the SQL is assembled from string fragments at runtime, and
 * every one of these goes through `FilterSql.whereClause`, whose predicates only exist because the
 * tables declare the shared column contract. Running them is the only way to know that holds.
 */
class SecurityQueriesIT extends munit.FunSuite {

  private val dockerThere = DockerSupport.available()

  override def munitIgnore: Boolean = !dockerThere
  override val munitTimeout         = Duration(5, "min")

  private given ec: ExecutionContext = ExecutionContext.global
  private given otoroshi.env.Env     = Gateway.instance.env

  private var container: QueriesPgContainer = null
  private var pool: Pool                    = null
  private lazy val settings                 = UserAnalyticsExporterSettings(
    host = container.getHost,
    port = container.getMappedPort(5432),
    database = "otoroshi",
    user = "otoroshi",
    password = "otoroshi"
  )

  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)

  private def run(sql: String): Unit = {
    val p = Promise[Unit]()
    pool.query(sql).execute().onComplete(ar => if (ar.succeeded()) p.trySuccess(()) else p.tryFailure(ar.cause()))
    await(p.future)
  }

  private def insert(projection: AnalyticsProjection, events: Seq[JsObject]): Unit = {
    val rows: java.util.List[VertxTuple] =
      events.map(e => projection.toTuple(projection.strip(e))).asJava
    val p                                = Promise[Unit]()
    pool
      .preparedQuery(projection.insertSql(settings))
      .executeBatch(rows)
      .onComplete(ar => if (ar.succeeded()) p.trySuccess(()) else p.tryFailure(ar.cause()))
    await(p.future)
  }

  private def securityEvent(
      id: String,
      action: String,
      category: String,
      enforced: Boolean,
      ip: String,
      tag: String,
      routeId: String = "route_1",
      routeName: String = "api"
  ) =
    Json.obj(
      "@id"        -> id,
      "@timestamp" -> System.currentTimeMillis(),
      "@type"      -> "CloudApimSecurityEvent",
      "@env"       -> "prod",
      "event"      -> Json.obj("category" -> category, "action" -> action,
        "outcome" -> (if (enforced) "blocked" else "observed"), "severity" -> 3),
      "source"     -> Json.obj("ip" -> ip),
      "threat"     -> Json.obj("score" -> 80, "tags" -> Json.arr(tag), "signals" -> Json.arr()),
      "otoroshi"   -> Json.obj("route_id" -> routeId, "route_name" -> routeName, "tenant" -> "default",
        "teams" -> Json.arr(), "groups" -> Json.arr(), "node" -> "node-1"),
      "incident"   -> Json.obj("id" -> "inc", "count" -> 1),
      "decision"   -> Json.obj("enforced" -> enforced)
    )

  private def trailEvent(
      id: String,
      ruleIds: Seq[Int],
      blocking: Boolean,
      blocked: Boolean,
      routeId: String = "route_1",
      routeName: String = "api"
  ) = Json.obj(
    "@id"        -> id,
    "@timestamp" -> System.currentTimeMillis(),
    "@type"      -> "CloudApimWafTrailEvent",
    "@env"       -> "prod",
    "blocking"   -> blocking,
    "block"      -> (if (blocked) Json.obj("status" -> 403, "msg" -> "nope") else JsNull),
    "events"     -> JsArray(ruleIds.map(r => Json.obj("rule_id" -> r, "msg" -> "m", "phase" -> 2))),
    "route"      -> Json.obj("id" -> routeId, "name" -> routeName, "groups" -> Json.arr())
  )

  override def beforeAll(): Unit = {
    if (dockerThere) {
      container = new QueriesPgContainer()
        .withExposedPorts(Integer.valueOf(5432))
        .withEnv("POSTGRES_USER", "otoroshi")
        .withEnv("POSTGRES_PASSWORD", "otoroshi")
        .withEnv("POSTGRES_DB", "otoroshi")
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
      container.start()
      pool = PgBuilder
        .pool()
        .connectingTo(
          new PgConnectOptions()
            .setHost(settings.host).setPort(settings.port).setDatabase(settings.database)
            .setUser(settings.user).setPassword(settings.password)
        )
        .`with`(new PoolOptions().setMaxSize(4))
        .build()
      run(s"CREATE SCHEMA IF NOT EXISTS ${settings.schema};")
      Seq(CloudApimSecurityEventProjection, CloudApimWafTrailEventProjection).foreach { p =>
        run(p.createTableSql(settings))
        p.indexStatements(settings).foreach(run)
      }
      insert(
        CloudApimSecurityEventProjection,
        Seq(
          securityEvent("s1", "ban", "threat", enforced = true, "1.1.1.1", "reputation:firehol1"),
          securityEvent("s2", "log", "threat", enforced = false, "1.1.1.1", "waf:match"),
          securityEvent("s3", "deny", "honeypot", enforced = true, "2.2.2.2", "honeypot:path"),
          // a second route, and a caller seen on both: without it nothing here tells a set of routes
          // apart from a single one, and no spread is measurable
          securityEvent("s4", "deny", "threat", enforced = true, "1.1.1.1", "waf:match", "route_2", "checkout")
        )
      )
      insert(
        CloudApimWafTrailEventProjection,
        Seq(
          trailEvent("w1", Seq(942100, 942110), blocking = false, blocked = true),
          trailEvent("w2", Seq(942100), blocking = true, blocked = true),
          trailEvent("w3", Seq(949110), blocking = true, blocked = false, "route_2", "checkout")
        )
      )
    }
  }

  override def afterAll(): Unit = {
    if (pool != null) pool.close()
    if (container != null) container.stop()
  }

  private def exec(q: AnalyticsQuery, params: JsObject = Json.obj()): QueryResult =
    await(
      q.execute(
        Filters(from = Instant.now().minusSeconds(3600), to = Instant.now().plusSeconds(60)),
        params,
        Bucket.OneMinute,
        settings,
        pool
      )
    )

  test("every query in the catalogue runs against a real database") {
    // the whole catalogue at once: any one of them referencing a column that is not there fails here
    SecurityQueries.all.foreach { q =>
      val result = exec(q)
      assertEquals(result.shape.name, q.shape.name, s"${q.id} returned the wrong shape")
    }
  }

  test("the totals count what was written") {
    assertEquals((exec(SecurityQueries.EventsTotal).data \ "value").as[Long], 4L)
    assertEquals((exec(SecurityQueries.EnforcedTotal).data \ "value").as[Long], 3L)
    assertEquals((exec(SecurityQueries.SourcesTotal).data \ "value").as[Long], 2L, "two addresses, four decisions")
  }

  test("blocked versus observed is the ratio a rollout is read against") {
    val items = (exec(SecurityQueries.ByOutcome).data \ "items").as[Seq[JsObject]]
    assertEquals(items.find(i => (i \ "key").as[String] == "blocked").map(i => (i \ "value").as[Long]), Some(3L))
    assertEquals(items.find(i => (i \ "key").as[String] == "observed").map(i => (i \ "value").as[Long]), Some(1L))
  }

  test("detectors and actions break down separately") {
    val cats = (exec(SecurityQueries.ByCategory).data \ "items").as[Seq[JsObject]].map(i => (i \ "key").as[String]).toSet
    assertEquals(cats, Set("threat", "honeypot"))
    val acts = (exec(SecurityQueries.ByAction).data \ "items").as[Seq[JsObject]].map(i => (i \ "key").as[String]).toSet
    assertEquals(acts, Set("ban", "log", "deny"))
  }

  test("top sources ranks by how often the fabric decided against them") {
    val items = (exec(SecurityQueries.TopSources).data \ "items").as[Seq[JsObject]]
    assertEquals((items.head \ "key").as[String], "1.1.1.1")
    assertEquals((items.head \ "value").as[Long], 3L)
  }

  test("top signals unnests the tag array") {
    val keys = (exec(SecurityQueries.TopTags).data \ "items").as[Seq[JsObject]].map(i => (i \ "key").as[String]).toSet
    assertEquals(keys, Set("reputation:firehol1", "waf:match", "honeypot:path"))
  }

  test("top routes carries the name, not only the id") {
    val head = (exec(SecurityQueries.TopRoutes).data \ "items").as[Seq[JsObject]].head
    assertEquals((head \ "key").as[String], "route_1")
    assertEquals((head \ "label").as[String], "api")
  }

  test("top WAF rules ranks rule ids across events") {
    val items = (exec(SecurityQueries.WafTopRules).data \ "items").as[Seq[JsObject]]
    assertEquals((items.head \ "key").as[String], "942100")
    assertEquals((items.head \ "value").as[Long], 2L)
  }

  test("would-have-blocked counts only the monitoring-mode denials") {
    val points = (exec(SecurityQueries.WafWouldHaveBlocked).data \ "points").as[Seq[JsObject]]
    assertEquals(points.map(p => (p \ "value").as[Long]).sum, 1L, "the blocking-mode one was actually blocked")
  }

  test("the timeseries fills empty buckets rather than skipping them") {
    val points = (exec(SecurityQueries.EventsOverTime).data \ "points").as[Seq[JsObject]]
    assert(points.size > 1, "a series with holes cannot be drawn")
    assertEquals(points.map(p => (p \ "value").as[Long]).sum, 4L)
  }

  test("top_n is honoured") {
    val items = (exec(SecurityQueries.TopTags, Json.obj("top_n" -> 1)).data \ "items").as[Seq[JsObject]]
    assertEquals(items.size, 1)
  }

  test("the shared filters apply — the reason the tables declare the common columns") {
    val scoped = await(
      SecurityQueries.EventsTotal.execute(
        Filters(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60), routeId = Some("route_other")),
        Json.obj(),
        Bucket.OneMinute,
        settings,
        pool
      )
    )
    assertEquals((scoped.data \ "value").as[Long], 0L)
  }

  // -----------------------------------------------------------------------------------------------
  // route_ids — a question asked of a set of routes rather than of one
  // -----------------------------------------------------------------------------------------------

  private def scoped(q: AnalyticsQuery, routeIds: Seq[String], extra: JsObject = Json.obj()): QueryResult =
    exec(q, Json.obj("route_ids" -> JsArray(routeIds.map(JsString.apply))) ++ extra)

  test("route_ids narrows to a set, and an empty list means every route") {
    def total(ids: Seq[String]) = (scoped(SecurityQueries.EventsTotal, ids).data \ "value").as[Long]
    assertEquals(total(Seq.empty), 4L, "no list at all is not a filter")
    assertEquals(total(Seq("route_1")), 3L)
    assertEquals(total(Seq("route_2")), 1L)
    assertEquals(total(Seq("route_1", "route_2")), 4L, "the whole point: several routes at once")
    assertEquals(total(Seq("route_1", "route_unknown")), 3L, "an id matching nothing contributes nothing")
    assertEquals(total(Seq("route_unknown")), 0L)
  }

  test("route_ids composes with the platform filters rather than replacing them") {
    val both = await(
      SecurityQueries.EventsTotal.execute(
        Filters(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60), routeId = Some("route_1")),
        Json.obj("route_ids" -> Json.arr("route_2")),
        Bucket.OneMinute,
        settings,
        pool
      )
    )
    assertEquals((both.data \ "value").as[Long], 0L, "the two narrow, they do not widen each other")
  }

  test("route_ids reaches the waf table too, and the joined queries with it") {
    assertEquals((scoped(SecurityQueries.WafBlockStatus, Seq("route_2")).data \ "items").as[Seq[JsObject]], Seq.empty)
    val rules = (scoped(SecurityQueries.WafTopRules, Seq("route_2")).data \ "items").as[Seq[JsObject]]
    assertEquals(rules.map(i => (i \ "key").as[String]), Seq("949110"), "unnest and the scope apply together")
  }

  test("every query in the catalogue accepts the parameter it declares") {
    // a query declaring route_ids and then ignoring it would silently widen a workspace to the fleet
    SecurityQueries.all.foreach { q =>
      assert(q.params.exists(_.name == "route_ids"), s"${q.id} does not declare route_ids")
      val result = scoped(q, Seq("route_1"))
      assertEquals(result.shape.name, q.shape.name, s"${q.id} returned the wrong shape when scoped")
    }
  }

  // -----------------------------------------------------------------------------------------------
  // the tables, which carry several numbers per row
  // -----------------------------------------------------------------------------------------------

  test("spread tells a caller walking the surface from one hammering an endpoint") {
    val items = (exec(SecurityQueries.TopSpreadSources).data \ "items").as[Seq[JsObject]]
    val head  = items.head
    assertEquals((head \ "key").as[String], "1.1.1.1")
    assertEquals((head \ "routes").as[Double], 2.0, "seen on both routes")
    assertEquals((head \ "decisions").as[Double], 3.0)
    assertEquals((items.last \ "routes").as[Double], 1.0, "2.2.2.2 only ever hit one")
  }

  test("a signal is reported with the share of decisions it was acted on") {
    val items = (exec(SecurityQueries.TagEnforcement).data \ "items").as[Seq[JsObject]].map(i => (i \ "key").as[String] -> i).toMap
    assertEquals((items("waf:match") \ "decisions").as[Double], 2.0)
    assertEquals((items("waf:match") \ "enforced").as[Double], 1.0, "one of the two changed an outcome")
    assertEquals((items("reputation:firehol1") \ "enforced").as[Double], 1.0)
  }

  test("route enforcement says what a route did, not what it is configured to do") {
    val items = (exec(SecurityQueries.RouteEnforcement).data \ "items").as[Seq[JsObject]].map(i => (i \ "key").as[String] -> i).toMap
    assertEquals((items("route_1") \ "route").as[String], "api", "the name travels with the id")
    assertEquals((items("route_1") \ "decisions").as[Double], 3.0)
    assertEquals((items("route_1") \ "enforced").as[Double], 2.0)
    assertEquals((items("route_2") \ "enforced").as[Double], 1.0)
  }

  test("the largest incidents carry the caller they were opened on") {
    val head = (exec(SecurityQueries.TopIncidents).data \ "items").as[Seq[JsObject]].head
    assertEquals((head \ "key").as[String], "inc")
    assertEquals((head \ "decisions").as[Double], 4.0)
  }

  test("a table row keeps its key apart from its columns, which is what the widget renders") {
    val head = (exec(SecurityQueries.TopSourcesByScore).data \ "items").as[Seq[JsObject]].head
    assertEquals((head \ "key").as[String], "1.1.1.1")
    assertEquals((head \ "max_score").as[Double], 80.0)
    assert((head.keys -- Set("key", "max_score", "avg_score", "decisions", "enforced")).isEmpty)
  }

  // -----------------------------------------------------------------------------------------------
  // the shapes the studio draws
  // -----------------------------------------------------------------------------------------------

  test("enforced and observed come back as two named series, not one total") {
    val series = (exec(SecurityQueries.OutcomeOverTime).data \ "series").as[Seq[JsObject]]
    assertEquals(series.map(x => (x \ "name").as[String]), Seq("enforced", "observed"))
    val sums = series.map(x => (x \ "points").as[Seq[JsObject]].map(p => (p \ "value").as[Long]).sum)
    assertEquals(sums, Seq(3L, 1L))
  }

  test("the action series carries every tier, including the ones nothing reached") {
    val series = (exec(SecurityQueries.ActionsOverTime).data \ "series").as[Seq[JsObject]]
    assertEquals(series.map(x => (x \ "name").as[String]), Seq("log", "tarpit", "challenge", "deny", "ban"))
    val byName = series.map(x => (x \ "name").as[String] -> (x \ "points").as[Seq[JsObject]].map(p => (p \ "value").as[Long]).sum).toMap
    assertEquals(byName("deny"), 2L)
    assertEquals(byName("tarpit"), 0L, "a tier nothing reached is a flat line, not a missing series")
  }

  test("the score distribution is ordered by band rather than by count") {
    val items = (exec(SecurityQueries.ScoreDistribution).data \ "items").as[Seq[JsObject]]
    assertEquals(items.map(i => (i \ "key").as[String]), Seq("80-99"), "every event scored 80")
  }

  test("the activity heatmap is a full week of full days, holes included") {
    val data = exec(SecurityQueries.ActivityHeatmap).data
    assertEquals((data \ "yBuckets").as[Seq[String]].size, 7)
    assertEquals((data \ "xBuckets").as[Seq[String]].size, 24)
    val values = (data \ "values").as[Seq[Seq[Long]]]
    assertEquals(values.size, 7)
    assert(values.forall(_.size == 24), "a ragged grid cannot be drawn")
    assertEquals(values.map(_.sum).sum, 4L)
  }

  test("waf traffic separates what was blocked from what would have been") {
    val byName = (exec(SecurityQueries.WafRequestsOverTime).data \ "series")
      .as[Seq[JsObject]]
      .map(x => (x \ "name").as[String] -> (x \ "points").as[Seq[JsObject]].map(p => (p \ "value").as[Long]).sum)
      .toMap
    assertEquals(byName("inspected"), 3L)
    assertEquals(byName("blocked"), 1L)
    assertEquals(byName("would have blocked"), 1L)
  }

  test("the rules behind the blocks are ranked over the monitoring-mode denials alone") {
    val items = (exec(SecurityQueries.WafTopRulesWouldBlock).data \ "items").as[Seq[JsObject]]
    assertEquals(items.map(i => (i \ "key").as[String]).toSet, Set("942100", "942110"), "only w1 reached a deny while monitoring")
  }

  test("body limit pressure is reported, since a truncated verdict is a weaker verdict") {
    val items = (exec(SecurityQueries.WafBodyLimits).data \ "items").as[Seq[JsObject]]
    assertEquals(items.map(i => (i \ "key").as[String]), Seq("inspected whole"))
    assertEquals((items.head \ "value").as[Long], 3L)
  }

  // -----------------------------------------------------------------------------------------------
  // the rows themselves, which is where the attribution lives
  // -----------------------------------------------------------------------------------------------

  test("the decisions log returns the rows, newest first, with their arrays intact") {
    val items = (exec(SecurityQueries.DecisionsLog).data \ "items").as[Seq[JsObject]]
    assertEquals(items.size, 4)
    val tss   = items.map(i => (i \ "ts").as[Long])
    assertEquals(tss, tss.sorted.reverse, "newest first, or paging on the timestamp makes no sense")
    val first = items.head
    assert((first \ "tags").as[Seq[String]].nonEmpty, "a text[] must come back as an array, not as a java toString")
    assert((first \ "id").asOpt[String].isDefined)
    assertEquals((first \ "enforced").asOpt[Boolean].isDefined, true)
  }

  test("paging one row at a time returns every row exactly once, ties included") {
    // the four events were written within the same millisecond, which is the case a cursor on the
    // timestamp alone silently drops: the next page would start strictly after an instant several
    // rows share. Paging on (ts, id) is what keeps them.
    var cursor: Option[(Long, String)] = None
    val seen                           = scala.collection.mutable.ListBuffer[String]()
    var pages                          = 0
    var done                           = false
    while (!done && pages < 20) {
      val params = Json.obj("limit" -> 1) ++ cursor
        .map { case (ts, id) => Json.obj("before" -> ts, "before_id" -> id) }
        .getOrElse(Json.obj())
      val page   = exec(SecurityQueries.DecisionsLog, params)
      (page.data \ "items").as[Seq[JsObject]].foreach(i => seen += (i \ "id").as[String])
      cursor = for {
        ts <- (page.data \ "next_before").asOpt[Long]
        id <- (page.data \ "next_before_id").asOpt[String]
      } yield (ts, id)
      done = cursor.isEmpty
      pages += 1
    }
    assertEquals(seen.toList.size, 4, "every row, and none of them twice")
    assertEquals(seen.toList.toSet, Set("s1", "s2", "s3", "s4"))
  }

  test("a full page announces a cursor, a short one announces the end") {
    val page = exec(SecurityQueries.DecisionsLog, Json.obj("limit" -> 2))
    assertEquals((page.data \ "items").as[Seq[JsObject]].size, 2)
    assert((page.data \ "next_before").asOpt[Long].isDefined, "a full page has to say where the next one starts")
    assert((page.data \ "next_before_id").asOpt[String].isDefined, "the id is half the cursor")
    val full = exec(SecurityQueries.DecisionsLog, Json.obj("limit" -> 500))
    assertEquals((full.data \ "next_before").asOpt[Long], None)
    assertEquals((full.data \ "next_before_id").asOpt[String], None)
  }

  test("the rows come back in a total order, so the cursor is never ambiguous") {
    val keys = (exec(SecurityQueries.DecisionsLog).data \ "items")
      .as[Seq[JsObject]]
      .map(i => ((i \ "ts").as[Long], (i \ "id").as[String]))
    assert(
      keys.sliding(2).forall {
        case Seq(a, b) => a._1 > b._1 || (a._1 == b._1 && a._2 > b._2)
        case _         => true
      },
      s"(ts, id) must strictly decrease, or two pages can overlap or skip: $keys"
    )
  }

  test("the log narrows on the things an incident is worked from") {
    def count(params: JsObject) = (exec(SecurityQueries.DecisionsLog, params).data \ "items").as[Seq[JsObject]].size
    assertEquals(count(Json.obj("source" -> "2.2.2.2")), 1)
    assertEquals(count(Json.obj("category" -> "honeypot")), 1)
    assertEquals(count(Json.obj("action" -> "deny")), 2)
    assertEquals(count(Json.obj("enforced" -> true)), 3)
    assertEquals(count(Json.obj("enforced" -> false)), 1)
    assertEquals(count(Json.obj("tag" -> "waf:match")), 2)
    assertEquals(count(Json.obj("incident_id" -> "nope")), 0)
  }

  test("the narrowing composes with route_ids rather than replacing it") {
    val params = Json.obj("route_ids" -> Json.arr("route_1"), "action" -> "deny")
    assertEquals((exec(SecurityQueries.DecisionsLog, params).data \ "items").as[Seq[JsObject]].size, 1)
  }

  test("a decision can be opened for the signals that explain it") {
    val first  = (exec(SecurityQueries.DecisionsLog).data \ "items").as[Seq[JsObject]].head
    val detail = (exec(SecurityQueries.DecisionDetail, Json.obj("id" -> (first \ "id").as[String])).data \ "items")
      .as[Seq[JsObject]]
    assertEquals(detail.size, 1)
    val raw    = (detail.head \ "raw").as[JsObject]
    assertEquals((raw \ "@type").as[String], "CloudApimSecurityEvent")
    assert((raw \ "threat" \ "tags").asOpt[Seq[String]].isDefined, "the attribution has to survive the round trip")
  }

  test("asking for a decision that is not there returns nothing rather than the first one") {
    val items = (exec(SecurityQueries.DecisionDetail, Json.obj("id" -> "nope")).data \ "items").as[Seq[JsObject]]
    assertEquals(items, Seq.empty[JsObject])
    assertEquals((exec(SecurityQueries.DecisionDetail).data \ "items").as[Seq[JsObject]], Seq.empty[JsObject])
  }

  test("the waf trail log carries the rule ids as numbers and can be narrowed to what it stopped") {
    val items = (exec(SecurityQueries.WafTrailLog).data \ "items").as[Seq[JsObject]]
    assertEquals(items.size, 3)
    assert(items.exists(i => (i \ "rule_ids").as[Seq[Int]].contains(942100)))
    def only(v: String) = (exec(SecurityQueries.WafTrailLog, Json.obj("only" -> v)).data \ "items").as[Seq[JsObject]].size
    assertEquals(only("blocked"), 1)
    assertEquals(only("would_block"), 1)
    assertEquals(only("matched"), 3)
  }

  test("a waf trail can be opened for every rule that matched") {
    val first  = (exec(SecurityQueries.WafTrailLog).data \ "items").as[Seq[JsObject]].head
    val detail = (exec(SecurityQueries.WafTrailDetail, Json.obj("id" -> (first \ "id").as[String])).data \ "items")
      .as[Seq[JsObject]]
    assertEquals((detail.head \ "raw" \ "@type").as[String], "CloudApimWafTrailEvent")
  }

  test("the dashboard only references queries that exist") {
    val catalogue = SecurityQueries.all.map(_.id).toSet
    val widgets   = Seq(
      "cloudapim_security_events_total", "cloudapim_security_enforced_total", "cloudapim_security_by_outcome",
      "cloudapim_security_events_over_time", "cloudapim_security_by_category", "cloudapim_security_by_action",
      "cloudapim_security_top_sources", "cloudapim_security_top_tags", "cloudapim_security_top_routes",
      "cloudapim_waf_top_rules", "cloudapim_waf_would_have_blocked"
    )
    assertEquals(widgets.filterNot(catalogue.contains), Seq.empty[String])
  }
}
