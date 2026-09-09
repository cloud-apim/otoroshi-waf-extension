package com.cloud.apim.otoroshi.extensions.waf.analytics

import io.vertx.sqlclient.{Tuple => VertxTuple}
import otoroshi.next.analytics.exporter.{AnalyticsProjection, UserAnalyticsExporterSettings}
import play.api.libs.json.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}

/**
 * Where a `CloudApimSecurityEvent` goes when the user-analytics exporter is running.
 *
 * Until Otoroshi grew extensible projections, the suite emitted these events and the exporter threw
 * them away: it accepted gateway events and its own alerts, and nothing else. They reached whatever
 * log pipeline an operator had built themselves, and never a table anyone could query — which is
 * why `analyticsQueries()` had nothing of ours to read.
 *
 * The columns before `category` are the shared contract, so the console's own filters — period,
 * route, apikey, user, tenant — work on this table exactly as they do on the platform's.
 */
object CloudApimSecurityEventProjection extends AnalyticsProjection {

  override val id: String = "cloud-apim.security-events"

  override def accepts(event: JsValue): Boolean =
    (event \ "@type").asOpt[String].contains("CloudApimSecurityEvent")

  override def table(s: UserAnalyticsExporterSettings): String = s"${s.schema}.${s.table}_cloudapim_security"

  private def indexPrefix(s: UserAnalyticsExporterSettings): String = s"${s.table}_cas"

  override def createTableSql(s: UserAnalyticsExporterSettings): String =
    s"""CREATE TABLE IF NOT EXISTS ${table(s)} (
       |${AnalyticsProjection.commonColumns}
       |  category        TEXT,
       |  action          TEXT,
       |  outcome         TEXT,
       |  severity        SMALLINT,
       |  score           SMALLINT,
       |  tags            TEXT[]      NOT NULL DEFAULT '{}',
       |  enforced        BOOLEAN     NOT NULL DEFAULT false,
       |  incident_id     TEXT,
       |  incident_count  INTEGER,
       |  node            TEXT,
       |  raw             JSONB       NOT NULL DEFAULT '{}'::jsonb
       |);""".stripMargin

  override def indexStatements(s: UserAnalyticsExporterSettings): Seq[String] =
    AnalyticsProjection.commonIndexes(table(s), indexPrefix(s)) ++ Seq(
      // the three the console groups by: what fired, what it did, and whether it did it for real
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_cat_ts    ON ${table(s)} (category, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_action_ts ON ${table(s)} (action, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_enforced  ON ${table(s)} (ts DESC) WHERE enforced = true;",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_tags_gin  ON ${table(s)} USING GIN (tags);"
    )

  override def insertSql(s: UserAnalyticsExporterSettings): String =
    s"""INSERT INTO ${table(s)} (
       |  id, ts, env, tenant, teams, route_id, route_name, api_id, group_ids, apikey_id, user_email, from_ip,
       |  category, action, outcome, severity, score, tags, enforced, incident_id, incident_count, node, raw
       |) VALUES (
       |  $$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9, $$10, $$11, $$12,
       |  $$13, $$14, $$15, $$16, $$17, $$18, $$19, $$20, $$21, $$22, $$23::jsonb
       |) ON CONFLICT (id) DO NOTHING""".stripMargin

  /**
   * Signals are kept.
   *
   * They are the reason the event exists — the attribution that answers "why was this caller
   * judged" — and a console that cannot drill into them can only ever show that something happened.
   * They are also bounded: a handful of entries per request, not a body or a header dump. The
   * decision object goes for the same reason: it is what `enforced` is derived from.
   */
  override def strip(event: JsValue): JsValue = event

  override def toTuple(event: JsValue): VertxTuple = {
    val ts = (event \ "@timestamp").asOpt[Long] match {
      case Some(millis) => OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
      case None         => OffsetDateTime.now(ZoneOffset.UTC)
    }
    VertxTuple.of(
      (event \ "@id").asOpt[String].getOrElse(java.util.UUID.randomUUID().toString),
      ts,
      (event \ "@env").asOpt[String].getOrElse("prod"),
      (event \ "otoroshi" \ "tenant").asOpt[String].getOrElse("default"),
      (event \ "otoroshi" \ "teams").asOpt[Seq[String]].getOrElse(Seq.empty).toArray,
      (event \ "otoroshi" \ "route_id").asOpt[String].orNull,
      (event \ "otoroshi" \ "route_name").asOpt[String].orNull,
      (event \ "otoroshi" \ "api_id").asOpt[String].orNull,
      (event \ "otoroshi" \ "groups").asOpt[Seq[String]].getOrElse(Seq.empty).toArray,
      (event \ "source" \ "apikey").asOpt[String].orNull,
      (event \ "source" \ "user").asOpt[String].orNull,
      (event \ "source" \ "ip").asOpt[String].orNull,
      (event \ "event" \ "category").asOpt[String].orNull,
      (event \ "event" \ "action").asOpt[String].orNull,
      (event \ "event" \ "outcome").asOpt[String].orNull,
      Integer.valueOf((event \ "event" \ "severity").asOpt[Int].getOrElse(0)),
      Integer.valueOf((event \ "threat" \ "score").asOpt[Int].getOrElse(0)),
      (event \ "threat" \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty).toArray,
      // the unambiguous "did this actually do anything", denormalised so a dashboard does not have
      // to reach into the raw json to ask the only question that matters on a dry-run rollout
      java.lang.Boolean.valueOf((event \ "decision" \ "enforced").asOpt[Boolean].getOrElse(false)),
      (event \ "incident" \ "id").asOpt[String].orNull,
      Integer.valueOf((event \ "incident" \ "count").asOpt[Int].getOrElse(0)),
      (event \ "otoroshi" \ "node").asOpt[String].orNull,
      Json.stringify(event)
    )
  }
}

/**
 * Where a `CloudApimWafTrailEvent` goes.
 *
 * A second table rather than a column on the first: a trail event is about a *ruleset*, not about a
 * caller, and the question it answers — which rules fire, and which of those would have blocked —
 * is the one that drives tuning. Rule ids land as an array so `unnest` can rank them.
 */
object CloudApimWafTrailEventProjection extends AnalyticsProjection {

  override val id: String = "cloud-apim.waf-trail"

  override def accepts(event: JsValue): Boolean =
    (event \ "@type").asOpt[String].contains("CloudApimWafTrailEvent")

  override def table(s: UserAnalyticsExporterSettings): String = s"${s.schema}.${s.table}_cloudapim_waf"

  private def indexPrefix(s: UserAnalyticsExporterSettings): String = s"${s.table}_caw"

  override def createTableSql(s: UserAnalyticsExporterSettings): String =
    s"""CREATE TABLE IF NOT EXISTS ${table(s)} (
       |${AnalyticsProjection.commonColumns}
       |  blocking          BOOLEAN     NOT NULL DEFAULT false,
       |  blocked           BOOLEAN     NOT NULL DEFAULT false,
       |  status            SMALLINT,
       |  rule_ids          INTEGER[]   NOT NULL DEFAULT '{}',
       |  truncated         BOOLEAN     NOT NULL DEFAULT false,
       |  oversize_rejected BOOLEAN     NOT NULL DEFAULT false,
       |  raw               JSONB       NOT NULL DEFAULT '{}'::jsonb
       |);""".stripMargin

  override def indexStatements(s: UserAnalyticsExporterSettings): Seq[String] =
    AnalyticsProjection.commonIndexes(table(s), indexPrefix(s)) ++ Seq(
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_rules_gin ON ${table(s)} USING GIN (rule_ids);",
      // "what would have been blocked" — the number a rollout is read against
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_would     ON ${table(s)} (ts DESC) WHERE blocked = true AND blocking = false;"
    )

  override def insertSql(s: UserAnalyticsExporterSettings): String =
    s"""INSERT INTO ${table(s)} (
       |  id, ts, env, tenant, teams, route_id, route_name, api_id, group_ids, apikey_id, user_email, from_ip,
       |  blocking, blocked, status, rule_ids, truncated, oversize_rejected, raw
       |) VALUES (
       |  $$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9, $$10, $$11, $$12,
       |  $$13, $$14, $$15, $$16, $$17, $$18, $$19::jsonb
       |) ON CONFLICT (id) DO NOTHING""".stripMargin

  /**
   * The event embeds the whole route and the request headers.
   *
   * Both are worth having in an event stream and neither belongs in an analytics row: the route is
   * re-serialised on every match, and headers are unbounded. What is kept is the identity the
   * filters need.
   */
  override def strip(event: JsValue): JsValue = event match {
    case obj: JsObject =>
      val keptRoute = (obj \ "route").asOpt[JsObject].map { route =>
        JsObject(route.fields.filter { case (k, _) => Set("id", "name", "_loc", "groups", "api_ref").contains(k) })
      }
      val leaner    = (obj \ "request").asOpt[JsObject].orElse((obj \ "response").asOpt[JsObject]) match {
        case None      => obj
        case Some(req) =>
          val pruned = JsObject(req.fields.filterNot { case (k, _) => Set("headers", "cookies", "body").contains(k) })
          obj ++ Json.obj((if ((obj \ "request").asOpt[JsObject].isDefined) "request" else "response") -> pruned)
      }
      keptRoute.map(r => leaner ++ Json.obj("route" -> r)).getOrElse(leaner)
    case other         => other
  }

  override def toTuple(event: JsValue): VertxTuple = {
    val ts       = (event \ "@timestamp").asOpt[Long] match {
      case Some(millis) => OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
      case None         => OffsetDateTime.now(ZoneOffset.UTC)
    }
    val route    = (event \ "route").asOpt[JsObject]
    val block    = (event \ "block").asOpt[JsObject]
    val ruleIds  = (event \ "events")
      .asOpt[Seq[JsObject]]
      .getOrElse(Seq.empty)
      .flatMap(e => (e \ "rule_id").asOpt[Int])
      .map(Integer.valueOf)
      .toArray
    VertxTuple.of(
      (event \ "@id").asOpt[String].getOrElse(java.util.UUID.randomUUID().toString),
      ts,
      (event \ "@env").asOpt[String].getOrElse("prod"),
      route.flatMap(r => (r \ "_loc" \ "tenant").asOpt[String]).getOrElse("default"),
      route.flatMap(r => (r \ "_loc" \ "teams").asOpt[Seq[String]]).getOrElse(Seq.empty).toArray,
      route.flatMap(r => (r \ "id").asOpt[String]).orNull,
      route.flatMap(r => (r \ "name").asOpt[String]).orNull,
      route.flatMap(r => (r \ "api_ref" \ "id").asOpt[String]).orNull,
      route.flatMap(r => (r \ "groups").asOpt[Seq[String]]).getOrElse(Seq.empty).toArray,
      null,
      null,
      null,
      java.lang.Boolean.valueOf((event \ "blocking").asOpt[Boolean].getOrElse(false)),
      java.lang.Boolean.valueOf(block.isDefined),
      block.flatMap(b => (b \ "status").asOpt[Int]).map(Integer.valueOf).orNull,
      ruleIds,
      java.lang.Boolean.valueOf((event \ "truncated").asOpt[Boolean].getOrElse(false)),
      java.lang.Boolean.valueOf((event \ "oversize_rejected").asOpt[Boolean].getOrElse(false)),
      Json.stringify(event)
    )
  }
}
