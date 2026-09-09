package com.cloud.apim.otoroshi.extensions.waf.analytics

import io.vertx.pgclient.{PgBuilder, PgConnectOptions}
import io.vertx.sqlclient.{Pool, PoolOptions, Tuple => VertxTuple}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings
import play.api.libs.json.*

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/** `GenericContainer` is self-typed, so its fluent setters only infer through a concrete subclass. */
final class PgContainer extends GenericContainer[PgContainer](DockerImageName.parse("postgres:16-alpine"))

/** A sample of what `SecurityModule.record` actually emits. */
object SampleEvent {
  def apply(id: String = "sec_1", enforced: Boolean = true, category: String = "threat"): JsObject = Json.obj(
    "@id"        -> id,
    "@timestamp" -> System.currentTimeMillis(),
    "@type"      -> "CloudApimSecurityEvent",
    "@product"   -> "otoroshi",
    "@env"       -> "prod",
    "event"      -> Json.obj(
      "kind"     -> "alert",
      "module"   -> "cloud-apim.security-suite",
      "dataset"  -> s"cloud-apim.$category",
      "category" -> category,
      "action"   -> "ban",
      "outcome"  -> (if (enforced) "blocked" else "observed"),
      "severity" -> 4
    ),
    "source"     -> Json.obj("ip" -> "203.0.113.9", "apikey" -> "apikey_7", "user" -> JsNull),
    "threat"     -> Json.obj(
      "score"   -> 95,
      "tags"    -> Json.arr("reputation:firehol1", "waf:match"),
      "signals" -> Json.arr(Json.obj("source" -> "reputation.feed", "weight" -> 80))
    ),
    "otoroshi"   -> Json.obj(
      "route_id"   -> "route_abc",
      "route_name" -> "public-api",
      "node"       -> "node-1",
      "tenant"     -> "acme",
      "teams"      -> Json.arr("red", "blue")
    ),
    "incident"   -> Json.obj("id" -> "inc_1", "count" -> 34),
    "message"    -> "score 95 reached tier 90 (ban)",
    "decision"   -> Json.obj("action" -> "ban", "score" -> 95, "dry_run" -> !enforced, "enforced" -> enforced)
  )
}

/** Pure mapping checks — no database needed. */
class SecurityEventProjectionSuite extends munit.FunSuite {

  private val p = CloudApimSecurityEventProjection

  test("it claims the suite's own event and nothing else") {
    assert(p.accepts(SampleEvent()))
    assertEquals(p.accepts(Json.obj("@type" -> "GatewayEvent")), false)
    assertEquals(p.accepts(Json.obj("@type" -> "CloudApimWafTrailEvent")), false)
    assertEquals(p.accepts(Json.obj()), false)
  }

  test("it writes to its own table, never the platform's") {
    val s = UserAnalyticsExporterSettings()
    assertEquals(p.table(s), s"${s.schema}.${s.table}_cloudapim_security")
    assert(p.insertSql(s).contains(p.table(s)))
  }

  test("the signals survive — a console that cannot say why is a console that says nothing") {
    val event = SampleEvent()
    assertEquals(p.strip(event), event)
  }

  test("the insert lists as many columns as the tuple provides values") {
    val s       = UserAnalyticsExporterSettings()
    val columns = p.insertSql(s).split("\\) VALUES").head.split("\\(", 2)(1).split(",").length
    assertEquals(p.toTuple(SampleEvent()).size(), columns)
  }

  test("an event with nothing in it still produces a row rather than throwing") {
    val tuple = p.toTuple(Json.obj("@type" -> "CloudApimSecurityEvent"))
    assert(tuple.size() > 0)
  }
}

/**
 * The projection against a real postgresql.
 *
 * The mapping above proves the shape; only a database proves that the DDL is valid, that the insert
 * and the tuple agree, and that what comes back out is what went in.
 */
class SecurityEventProjectionPgSuite extends munit.FunSuite {

  private val dockerThere = com.cloud.apim.otoroshi.extensions.waf.DockerSupport.available()

  override def munitIgnore: Boolean = !dockerThere
  override val munitTimeout         = Duration(5, "min")

  given ec: ExecutionContext = ExecutionContext.global

  private var container: PgContainer = null
  private var pool: Pool                     = null
  private lazy val settings                  = UserAnalyticsExporterSettings(
    host = container.getHost,
    port = container.getMappedPort(5432),
    database = "otoroshi",
    user = "otoroshi",
    password = "otoroshi"
  )

  override def beforeAll(): Unit = {
    if (dockerThere) {
      container = new PgContainer()
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
            .setHost(settings.host)
            .setPort(settings.port)
            .setDatabase(settings.database)
            .setUser(settings.user)
            .setPassword(settings.password)
        )
        .`with`(new PoolOptions().setMaxSize(4))
        .build()
      run(s"CREATE SCHEMA IF NOT EXISTS ${settings.schema};")
      run(CloudApimSecurityEventProjection.createTableSql(settings))
      CloudApimSecurityEventProjection.indexStatements(settings).foreach(run)
    }
  }

  override def afterAll(): Unit = {
    if (pool != null) pool.close()
    if (container != null) container.stop()
  }

  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)

  private def run(sql: String): Unit = {
    val promise = Promise[Unit]()
    pool.query(sql).execute().onComplete { ar =>
      if (ar.succeeded()) promise.trySuccess(()) else promise.tryFailure(ar.cause())
    }
    await(promise.future)
  }

  private def query(sql: String): Seq[io.vertx.sqlclient.Row] = {
    val promise = Promise[Seq[io.vertx.sqlclient.Row]]()
    pool.query(sql).execute().onComplete { ar =>
      if (ar.succeeded()) promise.trySuccess(ar.result().asScala.toSeq) else promise.tryFailure(ar.cause())
    }
    await(promise.future)
  }

  private def insert(events: JsObject*): Unit = {
    val rows: java.util.List[VertxTuple] = events
      .map(e => CloudApimSecurityEventProjection.toTuple(CloudApimSecurityEventProjection.strip(e)))
      .asJava
    val promise                          = Promise[Unit]()
    pool.preparedQuery(CloudApimSecurityEventProjection.insertSql(settings)).executeBatch(rows).onComplete { ar =>
      if (ar.succeeded()) promise.trySuccess(()) else promise.tryFailure(ar.cause())
    }
    await(promise.future)
  }

  test("the table it declares is valid SQL and accepts the rows it builds") {
    insert(SampleEvent("sec_a"), SampleEvent("sec_b", enforced = false, category = "fail2ban"))
    val rows = query(s"SELECT * FROM ${CloudApimSecurityEventProjection.table(settings)} ORDER BY id")
    assertEquals(rows.size, 2)
  }

  test("every column the console groups by comes back as it went in") {
    val row = query(
      s"SELECT * FROM ${CloudApimSecurityEventProjection.table(settings)} WHERE id = 'sec_a'"
    ).head
    assertEquals(row.getString("category"), "threat")
    assertEquals(row.getString("action"), "ban")
    assertEquals(row.getString("outcome"), "blocked")
    assertEquals(row.getInteger("score").intValue(), 95)
    assertEquals(row.getBoolean("enforced").booleanValue(), true)
    assertEquals(row.getString("route_id"), "route_abc")
    assertEquals(row.getString("apikey_id"), "apikey_7")
    assertEquals(row.getString("from_ip"), "203.0.113.9")
    assertEquals(row.getString("incident_id"), "inc_1")
    assertEquals(row.getInteger("incident_count").intValue(), 34)
  }

  test("the tenant and teams the filters need are real, not defaulted") {
    val row = query(s"SELECT tenant, teams FROM ${CloudApimSecurityEventProjection.table(settings)} WHERE id = 'sec_a'").head
    assertEquals(row.getString("tenant"), "acme")
    assertEquals(row.getArrayOfStrings("teams").toSeq, Seq("red", "blue"))
  }

  test("tags land as an array, so a dashboard can group on them") {
    val rows = query(
      s"SELECT id FROM ${CloudApimSecurityEventProjection.table(settings)} WHERE 'waf:match' = ANY(tags)"
    )
    assertEquals(rows.size, 2)
  }

  test("dry run is queryable — the question a rollout actually asks") {
    val observed = query(
      s"SELECT id FROM ${CloudApimSecurityEventProjection.table(settings)} WHERE enforced = false"
    )
    assertEquals(observed.size, 1)
    assertEquals(observed.head.getString("id"), "sec_b")
  }

  test("the signals survived the trip, so a row can be drilled into") {
    val raw = query(s"SELECT raw FROM ${CloudApimSecurityEventProjection.table(settings)} WHERE id = 'sec_a'").head
    assert(raw.getValue("raw").toString.contains("reputation.feed"))
  }

  test("a replayed batch does not duplicate rows") {
    insert(SampleEvent("sec_a"))
    val rows = query(s"SELECT id FROM ${CloudApimSecurityEventProjection.table(settings)} WHERE id = 'sec_a'")
    assertEquals(rows.size, 1, "the exporter retries on failure; a retry must not double-count an attack")
  }
}
