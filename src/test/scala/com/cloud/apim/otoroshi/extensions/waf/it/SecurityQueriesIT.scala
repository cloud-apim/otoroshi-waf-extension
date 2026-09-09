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

  private def securityEvent(id: String, action: String, category: String, enforced: Boolean, ip: String, tag: String) =
    Json.obj(
      "@id"        -> id,
      "@timestamp" -> System.currentTimeMillis(),
      "@type"      -> "CloudApimSecurityEvent",
      "@env"       -> "prod",
      "event"      -> Json.obj("category" -> category, "action" -> action,
        "outcome" -> (if (enforced) "blocked" else "observed"), "severity" -> 3),
      "source"     -> Json.obj("ip" -> ip),
      "threat"     -> Json.obj("score" -> 80, "tags" -> Json.arr(tag), "signals" -> Json.arr()),
      "otoroshi"   -> Json.obj("route_id" -> "route_1", "route_name" -> "api", "tenant" -> "default",
        "teams" -> Json.arr(), "groups" -> Json.arr()),
      "incident"   -> Json.obj("id" -> "inc", "count" -> 1),
      "decision"   -> Json.obj("enforced" -> enforced)
    )

  private def trailEvent(id: String, ruleIds: Seq[Int], blocking: Boolean, blocked: Boolean) = Json.obj(
    "@id"        -> id,
    "@timestamp" -> System.currentTimeMillis(),
    "@type"      -> "CloudApimWafTrailEvent",
    "@env"       -> "prod",
    "blocking"   -> blocking,
    "block"      -> (if (blocked) Json.obj("status" -> 403, "msg" -> "nope") else JsNull),
    "events"     -> JsArray(ruleIds.map(r => Json.obj("rule_id" -> r, "msg" -> "m", "phase" -> 2))),
    "route"      -> Json.obj("id" -> "route_1", "name" -> "api", "groups" -> Json.arr())
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
          securityEvent("s3", "deny", "honeypot", enforced = true, "2.2.2.2", "honeypot:path")
        )
      )
      insert(
        CloudApimWafTrailEventProjection,
        Seq(
          trailEvent("w1", Seq(942100, 942110), blocking = false, blocked = true),
          trailEvent("w2", Seq(942100), blocking = true, blocked = true)
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
    assertEquals((exec(SecurityQueries.EventsTotal).data \ "value").as[Long], 3L)
    assertEquals((exec(SecurityQueries.EnforcedTotal).data \ "value").as[Long], 2L)
  }

  test("blocked versus observed is the ratio a rollout is read against") {
    val items = (exec(SecurityQueries.ByOutcome).data \ "items").as[Seq[JsObject]]
    assertEquals(items.find(i => (i \ "key").as[String] == "blocked").map(i => (i \ "value").as[Long]), Some(2L))
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
    assertEquals((items.head \ "value").as[Long], 2L)
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
    assertEquals(points.map(p => (p \ "value").as[Long]).sum, 3L)
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
