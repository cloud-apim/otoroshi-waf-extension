package com.cloud.apim.otoroshi.extensions.waf.learning

import com.cloud.apim.otoroshi.extensions.waf.tuning.*
import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang
import com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset
import play.api.libs.json.Json

class LearningModelSuite extends munit.FunSuite {

  private def entry(rule: Int, target: String, path: String, count: Long, pl: Option[Int] = Some(1)) =
    LearningEntry(rule, MatchedTarget.parse(target), path, "GET", None, None, Some("m"), pl, count, 0L, 100L, 200L, Seq("v"))

  test("two nodes' view of the same group is one group with their counts added") {
    val a = entry(942100, "ARGS:comment", "/x", 3).copy(firstSeen = 10L, lastSeen = 50L)
    val b = entry(942100, "ARGS:comment", "/x", 4).copy(firstSeen = 30L, lastSeen = 90L)
    val m = a.merge(b)
    assertEquals(m.count, 7L)
    assertEquals(m.firstSeen, 10L)
    assertEquals(m.lastSeen, 90L)
  }

  test("samples are capped when merging") {
    val a = entry(1, "ARGS:a", "/x", 1).copy(samples = Seq("1", "2"))
    val b = entry(1, "ARGS:a", "/x", 1).copy(samples = Seq("3", "4", "5"))
    assertEquals(a.merge(b).samples.size, LearningEntry.maxSamples)
  }

  test("the group key distinguishes rule, input and path") {
    assertNotEquals(entry(1, "ARGS:a", "/x", 1).key, entry(1, "ARGS:b", "/x", 1).key)
    assertNotEquals(entry(1, "ARGS:a", "/x", 1).key, entry(1, "ARGS:a", "/y", 1).key)
    assertNotEquals(entry(1, "ARGS:a", "/x", 1).key, entry(2, "ARGS:a", "/x", 1).key)
  }

  test("the paranoia level is read off the rule the engine reports") {
    val raw = Json.obj(
      "actions" -> Json.obj(
        "actions" -> Json.arr(
          Json.obj("action_type" -> "id", "value" -> 942100),
          Json.obj("action_type" -> "tag", "value" -> "attack-sqli"),
          Json.obj("action_type" -> "tag", "value" -> "paranoia-level/2")
        )
      )
    )
    assertEquals(LearningEntry.paranoiaOf(MatchEvent(Some(942100), None, Nil, 2, Json.stringify(raw))), Some(2))
    assertEquals(LearningEntry.paranoiaOf(MatchEvent(Some(1), None, Nil, 2, "not json")), None)
  }

  test("the anomaly score is read off the blocking-evaluation message") {
    val events = Seq(
      MatchEvent(Some(942100), Some("SQL Injection"), Nil, 2, "{}"),
      MatchEvent(Some(949110), Some("Inbound Anomaly Score Exceeded (Total Score: 13)"), Nil, 2, "{}")
    )
    assertEquals(WouldBlockSample.scoreOf(events), Some(13))
    assertEquals(WouldBlockSample.scoreOf(Seq(MatchEvent(Some(1), Some("nothing"), Nil, 1, "{}"))), None)
  }

  test("a snapshot adds up what every node contributed, and one node's update replaces its own") {
    val a = InstanceContribution("n1", 100, 10, 2, Seq(entry(942100, "ARGS:a", "/x", 6)), Seq(WouldBlockSample(1, "/x", Seq("k"), Some(5))))
    val b = InstanceContribution("n2", 50, 5, 1, Seq(entry(942100, "ARGS:a", "/x", 4)), Seq.empty)
    val snap = LearningSnapshot(LearningRun.start("cfg"), Seq(a, b))
    assertEquals(snap.totals.requests, 150L)
    assertEquals(snap.entries.map(_.count), Seq(10L), "the same group on two nodes is one group")
    val updated = snap.replacing(a.copy(requests = 300, entries = Seq(entry(942100, "ARGS:a", "/x", 20))))
    assertEquals(updated.totals.requests, 350L, "a node's fresh numbers must replace its own, not stack on them")
    assertEquals(updated.entries.map(_.count), Seq(24L))
  }

  test("a snapshot round-trips through the stored fields") {
    val fields = Map(
      "run"          -> Json.stringify(LearningRun("cfg", 1000L, Some(2000L), 0, 0, 0).json),
      "c|n1"         -> """{"requests":100,"matched":10,"would_block":2}""",
      "e|n1|942100|ARGS:a|/x" -> Json.stringify(entry(942100, "ARGS:a", "/x", 6).json),
      "s|n1"         -> """[{"at":1,"path":"/x","keys":["942100|ARGS:a|/x"],"score":9}]"""
    )
    val snap = LearningSnapshot.parse("cfg", fields)
    assertEquals(snap.totals.requests, 100L)
    assertEquals(snap.totals.stoppedAt, Some(2000L))
    assertEquals(snap.entries.head.count, 6L)
    assertEquals(snap.samples.head.score, Some(9))
  }

  test("junk in a field is ignored rather than failing the whole report") {
    val snap = LearningSnapshot.parse("cfg", Map("run" -> "{", "c|n1" -> "nope", "e|n1|x" -> "[]", "other" -> "x"))
    assertEquals(snap.totals.requests, 0L)
    assertEquals(snap.entries, Seq.empty[LearningEntry])
  }
}

class LearningAdviceSuite extends munit.FunSuite {

  private def entry(rule: Int, pl: Int, count: Long) =
    LearningEntry(rule, MatchedTarget.parse("ARGS:a"), "/x", "GET", None, None, None, Some(pl), count, 0L, 0L, 0L, Nil)

  test("the current level and threshold are read out of the configuration") {
    val rules = Seq(
      "@import_preset crs",
      "SecAction \"id:900110,phase:1,nolog,pass,setvar:tx.blocking_paranoia_level=3,setvar:tx.inbound_anomaly_score_threshold=10\""
    )
    assertEquals(LearningAdvice.currentParanoia(rules), 3)
    assertEquals(LearningAdvice.currentThreshold(rules), 10)
  }

  test("with nothing configured, the CRS defaults are assumed") {
    assertEquals(LearningAdvice.currentParanoia(Seq("@import_preset crs")), 1)
    assertEquals(LearningAdvice.currentThreshold(Seq("@import_preset crs")), 5)
  }

  test("a level drop is recommended when most of the noise lives above it") {
    val entries = Seq(entry(1, 1, 10), entry(2, 2, 60), entry(3, 3, 30))
    val advice  = LearningAdvice.paranoia(entries, current = 3)
    assertEquals(advice.recommended, Some(1))
    assert(advice.rationale.contains("gives up"), "a level drop must say what it costs")
  }

  test("no drop is recommended when the noise is at the level already running") {
    val advice = LearningAdvice.paranoia(Seq(entry(1, 1, 100)), current = 2)
    assertEquals(advice.recommended, None)
    assert(advice.rationale.contains("exclusions"))
  }

  test("nothing observed says nothing about the level") {
    assertEquals(LearningAdvice.paranoia(Seq.empty, current = 2).recommended, None)
  }

  private def sample(score: Int) = WouldBlockSample(0L, "/x", Seq("k"), Some(score))

  test("the threshold curve reports what each candidate would still deny") {
    val advice = LearningAdvice.threshold(Seq(sample(5), sample(6), sample(7)), current = 5)
    assertEquals(advice.curve.head, (5, 3))
    assert(advice.curve.exists { case (t, r) => t == 10 && r == 0 })
    assertEquals(advice.recommended, Some(10))
    assert(advice.rationale.contains("blunter"), "raising the threshold must be presented as the blunt tool it is")
  }

  test("no threshold is recommended when the scores are genuinely spread out") {
    val advice = LearningAdvice.threshold(Seq(sample(5), sample(40), sample(90)), current = 5)
    assertEquals(advice.recommended, None)
  }

  test("with no scores at all, the advice says so rather than inventing one") {
    assertEquals(LearningAdvice.threshold(Seq.empty, current = 5).recommended, None)
  }

  test("a denial counts as resolved only when every rule that fired on it is excluded") {
    val e1 = LearningEntry(1, MatchedTarget.parse("ARGS:a"), "/x", "GET", None, None, None, None, 5, 5, 0, 0, Nil)
    val e2 = LearningEntry(2, MatchedTarget.parse("ARGS:b"), "/x", "GET", None, None, None, None, 5, 5, 0, 0, Nil)
    val proposal = ExclusionBuilder.proposals(1, MatchedTarget.parse("ARGS:a"), Some("/x"), 50000).head
    val preview  = PreviewResult(true, None, true, true, RuleVerdict(Set.empty, false), RuleVerdict(Set.empty, false), Seq.empty, Set.empty)
    val accepted = Seq(ProposedExclusion(e1, proposal, preview, true, ""))
    val samples  = Seq(
      WouldBlockSample(0, "/x", Seq(e1.key), Some(5)),          // fully covered
      WouldBlockSample(0, "/x", Seq(e1.key, e2.key), Some(10))  // partly covered
    )
    val impact = LearningAdvice.impact(LearningRun("cfg", 0, None, 1000, 20, 2), samples, accepted)
    assertEquals(impact.resolved, 1)
    assertEquals(impact.residual, 1)
    assert(impact.rationale.contains("at least this good"), "a conservative estimate must say which way it errs")
  }
}

/** The report, against the real CRS — the exclusions in it are the ones OPS-2 would generate. */
class LearningReporterSuite extends munit.FunSuite {

  private val factory = SecLang.factory(Map("crs" -> EmbeddedCRSPreset.embedded), SecLangEngineConfig.default, DefaultNoCacheSecLangIntegration.default)
  private val rules   = Seq("@import_preset crs", "SecRuleEngine On")

  private def entry(rule: Int, target: String, count: Long, sample: String) =
    LearningEntry(rule, MatchedTarget.parse(target), "/api/posts", "GET", None, None, Some("m"), Some(1), count, count, 0L, 0L, Seq(sample))

  private def report(entries: Seq[LearningEntry], samples: Seq[WouldBlockSample] = Seq.empty, requests: Long = 10000) =
    LearningReporter.build(
      snapshot = LearningSnapshot(
        LearningRun("cfg", System.currentTimeMillis() - 172800000L, None, 0, 0, 0),
        Seq(InstanceContribution("n1", requests, entries.map(_.count).sum, samples.size.toLong, entries, samples))
      ),
      rules = rules,
      mode = Some(EngineMode.On),
      configBlocking = false,
      firstRuleId = 50000,
      engineOf = rs => factory.engine(rs.toList)
    )

  test("a real noisy rule produces a verified exclusion") {
    val r = report(Seq(entry(942100, "ARGS:comment", 40, "1' or 1=1--")))
    val e = r.exclusions.head
    assert(e.preview.reproduced, "the report must rebuild the match before proposing anything")
    assert(e.preview.effective)
    assertEquals(e.proposal.kind, "scoped_target", "the narrowest option is the one proposed")
  }

  test("an exclusion that gives up known attacks is proposed but not accepted") {
    val r = report(Seq(entry(942100, "ARGS:comment", 40, "1' or 1=1--")))
    val e = r.exclusions.head
    // excluding the libinjection rule from a parameter necessarily gives up sqli on it
    assert(!e.accepted, "an exclusion with regressions must not be auto-accepted")
    assert(e.note.contains("gives up"), e.note)
  }

  test("groups below the floor are not proposed at all") {
    val r = report(Seq(entry(942100, "ARGS:comment", 1, "1' or 1=1--")))
    assertEquals(r.exclusions, Seq.empty[ProposedExclusion])
    assertEquals(r.entries.size, 1, "they are still reported, just not acted on")
  }

  test("each proposal gets its own generated rule id") {
    val r = report(Seq(
      entry(942100, "ARGS:comment", 40, "1' or 1=1--"),
      entry(941100, "ARGS:query", 30, "<script>alert(1)</script>")
    ))
    val ids = r.exclusions.map(_.proposal.seclang).filter(_.contains("id:"))
    assertEquals(ids.size, 2)
    assertNotEquals(ids.head, ids(1))
  }

  test("each engine mode is reported with what it can and cannot measure") {
    val on = LearningReporter.modeAdvice(Some(EngineMode.On), configBlocking = false)
    assert(on.armingEstimateReliable, "blocking mode is exactly what production does")
    assert(!on.ruleCountsComplete, "it stops at the first deny, so the inventory is partial")
    assert(on.note.contains("objection to each request"), on.note)

    val det = LearningReporter.modeAdvice(Some(EngineMode.DetectionOnly), configBlocking = false)
    assert(det.ruleCountsComplete, det.note)
    assert(!det.armingEstimateReliable, "the engine keeps only the last phase's verdict in this mode")
    assert(det.note.contains("withheld rather than reported as zero"), det.note)

    val off = LearningReporter.modeAdvice(Some(EngineMode.Off), configBlocking = false)
    assert(!off.armingEstimateReliable && !off.ruleCountsComplete, off.note)
  }

  test("a window shorter than a day refuses to conclude") {
    val r = LearningReporter.build(
      snapshot = LearningSnapshot(LearningRun("cfg", System.currentTimeMillis() - 3600000L, None, 0, 0, 0),
        Seq(InstanceContribution("n1", 500, 0, 0, Seq.empty, Seq.empty))),
      rules = rules, mode = Some(EngineMode.DetectionOnly), configBlocking = false,
      firstRuleId = 50000, engineOf = rs => factory.engine(rs.toList)
    )
    assert(r.verdict.contains("arming on a guess"), r.verdict)
  }

  test("nothing denied over a real window reads as safe to arm") {
    val r = report(Seq.empty, Seq.empty, requests = 50000)
    assert(r.verdict.contains("safe to arm"), r.verdict)
  }

  test("in detection-only the arming cost is withheld rather than reported as zero") {
    val r = LearningReporter.build(
      snapshot = LearningSnapshot(
        LearningRun("cfg", System.currentTimeMillis() - 172800000L, None, 0, 0, 0),
        Seq(InstanceContribution("n1", 5000, 40, 0, Seq.empty, Seq.empty))
      ),
      rules = rules, mode = Some(EngineMode.DetectionOnly), configBlocking = false,
      firstRuleId = 50000, engineOf = rs => factory.engine(rs.toList)
    )
    assert(!r.mode.armingEstimateReliable, r.mode.note)
    assert(r.verdict.contains("cannot be measured"), r.verdict)
    assert(!r.verdict.contains("safe to arm"), "a mode that cannot measure must never say this")
  }
}
