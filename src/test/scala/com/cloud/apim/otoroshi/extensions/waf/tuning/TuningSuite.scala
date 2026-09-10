package com.cloud.apim.otoroshi.extensions.waf.tuning

import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang
import com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset

class TargetSuite extends munit.FunSuite {

  test("a CRS log line names the parameter") {
    val log = """1788983254567 - GET /search [id "942100"][msg "SQL Injection"] Matched Data: x found within ARGS:comment: 1' or 1=1--"""
    assertEquals(MatchedTarget.fromLogdata(log), Some(MatchedTarget("ARGS", Some("comment"))))
  }

  test("a header target keeps its case") {
    val log = """... Matched Data: passwd found within REQUEST_HEADERS:Referer: https://x/?a=b"""
    assertEquals(MatchedTarget.fromLogdata(log), Some(MatchedTarget("REQUEST_HEADERS", Some("Referer"))))
  }

  test("a scalar variable has no member") {
    val log = """... Matched Data: /etc found within REQUEST_URI: /a/../etc"""
    assertEquals(MatchedTarget.fromLogdata(log), Some(MatchedTarget("REQUEST_URI", None)))
  }

  test("a line with no logdata convention yields nothing") {
    assertEquals(MatchedTarget.fromLogdata("""... [id "949110"][msg "Inbound Anomaly Score Exceeded"]"""), None)
  }

  test("a collection nobody recognises is not trusted") {
    // everything after the rule id comes from the request, so a caller can plant this
    val log = """... Matched Data: x found within NOTACOLLECTION:evil: y"""
    assertEquals(MatchedTarget.fromLogdata(log), None)
  }

  test("the scratch space is parsed but refused as a target") {
    val t = MatchedTarget.fromLogdata("""... Matched Data: 5 found within TX:anomaly_score: 5""").get
    assertEquals(t.collection, "TX")
    assert(!t.excludable, "an exclusion on TX would compile and protect nothing")
  }

  test("a collection with no member is not excludable") {
    assert(!MatchedTarget("REQUEST_URI", None).excludable)
    assert(MatchedTarget("ARGS", Some("comment")).excludable)
  }

  test("the first target across several lines wins") {
    val logs = Seq("""[id "949110"] no data here""", """... found within ARGS:token: abc""")
    assertEquals(MatchedTarget.fromLogs(logs), Some(MatchedTarget("ARGS", Some("token"))))
  }
}

class ExclusionSuite extends munit.FunSuite {

  private val target = Some(MatchedTarget("ARGS", Some("comment")))

  test("the narrowest option comes first and is the recommended one") {
    val ps = ExclusionBuilder.proposals(942100, target, Some("/api/posts"), 50000)
    assertEquals(ps.head.reach, Reach.OneInputOnePath)
    assert(ps.head.recommended)
    assertEquals(ps.map(_.reach.rank), ps.map(_.reach.rank).sorted)
  }

  test("all four shapes are offered when a path and a target are known") {
    val kinds = ExclusionBuilder.proposals(942100, target, Some("/api/posts"), 50000).map(_.kind)
    assertEquals(kinds, Seq("scoped_target", "update_target", "scoped_remove", "remove_rule"))
  }

  test("with no path, the path-scoped options are not offered") {
    val kinds = ExclusionBuilder.proposals(942100, target, None, 50000).map(_.kind)
    assertEquals(kinds, Seq("update_target", "remove_rule"))
  }

  test("with no excludable target, only the rule-level options are offered") {
    val kinds = ExclusionBuilder.proposals(942100, Some(MatchedTarget("TX", Some("score"))), Some("/x"), 50000).map(_.kind)
    assertEquals(kinds, Seq("scoped_remove", "remove_rule"))
  }

  test("the generated seclang is what the engine expects") {
    val ps = ExclusionBuilder.proposals(942100, target, Some("/api/posts"), 50123).map(p => p.kind -> p.seclang).toMap
    assertEquals(ps("update_target"), """SecRuleUpdateTargetById 942100 "!ARGS:comment"""")
    assertEquals(ps("remove_rule"), "SecRuleRemoveById 942100")
    assert(ps("scoped_target").contains("ctl:ruleRemoveTargetById=942100;ARGS:comment"))
    assert(ps("scoped_target").contains("id:50123"))
  }

  test("ids are allocated above whatever the destination already uses") {
    assertEquals(ExclusionBuilder.nextId(Seq.empty), 50000)
    assertEquals(ExclusionBuilder.nextId(Seq("""SecRule X "@rx y" "id:50007,phase:1"""")), 50008)
    // ids outside the generated band are none of its business
    assertEquals(ExclusionBuilder.nextId(Seq("""SecRule X "@rx y" "id:942100"""")), 50000)
    assertEquals(ExclusionBuilder.nextId(Seq("id:50000", "id:50042", "id:1000")), 50043)
  }

  test("a query string is dropped from the scope path") {
    assertEquals(ExclusionBuilder.normalizePath("/api/posts?id=1"), Some("/api/posts"))
    assertEquals(ExclusionBuilder.normalizePath("not-a-path"), None)
  }

  test("a quote in the path cannot end the seclang argument") {
    val p = ExclusionBuilder.proposals(1, target, Some("""/a"b"""), 50000).find(_.kind == "scoped_target").get
    assert(p.seclang.contains("""/a\"b"""), p.seclang)
  }
}

/** The preview, against the real CRS — the part that has to be true for any of this to be safe. */
class PreviewSuite extends munit.FunSuite {

  private val factory = SecLang.factory(
    Map("crs" -> EmbeddedCRSPreset.embedded),
    SecLangEngineConfig.default,
    DefaultNoCacheSecLangIntegration.default
  )

  // the order the entity template produces, and the one that leaves the engine in blocking mode.
  // with `SecRuleEngine On` first the composed mode comes from the preset instead and the engine
  // never short-circuits — which is how a probe that trips a phase 1 rule went unnoticed
  private val rules = Seq("@import_preset crs", "SecRuleEngine On")

  private def preview(
      exclusion: String,
      target: Option[MatchedTarget],
      value: String = "1' or 1=1--",
      placement: Placement = Placement.After
  ) =
    ExclusionPreview.run(
      rules = rules,
      exclusion = exclusion,
      ruleId = 942100,
      target = target,
      sampleValue = Some(value),
      method = "GET",
      path = "/search",
      placement = placement,
      engineOf = rs => factory.engine(rs.toList)
    )

  private val comment = Some(MatchedTarget("ARGS", Some("comment")))

  test("a working exclusion is reported as effective") {
    val r = preview("""SecRuleUpdateTargetById 942100 "!ARGS:comment"""", comment)
    assert(r.compiles)
    assert(r.effective, "the rule still fired after the exclusion")
  }

  test("an exclusion aimed at the wrong parameter is reported as ineffective") {
    val r = preview("""SecRuleUpdateTargetById 942100 "!ARGS:somethingelse"""", comment)
    assert(r.compiles)
    assert(!r.effective, "a no-op exclusion must never be presented as a fix")
  }

  test("an exclusion aimed at the wrong rule is reported as ineffective") {
    val r = preview("""SecRuleUpdateTargetById 999999 "!ARGS:comment"""", comment)
    assert(!r.effective)
  }

  test("the corpus reports what stops being caught") {
    val r = preview("""SecRuleUpdateTargetById 942100 "!ARGS:comment"""", comment)
    assert(r.corpus.nonEmpty, "the corpus did not run")
    // 942100 is the libinjection rule: excluding the parameter necessarily gives up sqli on it
    assert(r.regressions.exists(_.category == "sqli"), s"expected sqli regressions, got ${r.regressions}")
  }

  test("turning the whole rule off gives up strictly more than excluding one parameter") {
    val narrow = preview("""SecRuleUpdateTargetById 942100 "!ARGS:comment"""", comment)
    val blunt  = preview("SecRuleRemoveById 942100", comment)
    assert(blunt.regressions.size >= narrow.regressions.size)
  }

  /**
   * The reason `Placement` exists at all.
   *
   * A `ctl:` exclusion is an ordinary phase 1 rule setting state the target rule reads later, so it
   * has to be evaluated first. Against a phase 2 rule any position works, which is what makes this
   * easy to get wrong and never notice — the CRS rules people tune most are phase 2.
   */
  test("a ctl: exclusion is a no-op when it runs after a phase 1 rule") {
    val phase1 = """SecRule ARGS "@rx select" "id:7001,phase:1,deny,status:403,log,msg:'p1'""""
    val ctl    =
      """SecRule REQUEST_URI "@beginsWith /search" "id:50001,phase:1,pass,nolog,ctl:ruleRemoveTargetById=7001;ARGS:comment""""
    def run(placement: Placement) = ExclusionPreview.run(
      rules = Seq("SecRuleEngine On", phase1),
      exclusion = ctl,
      ruleId = 7001,
      target = Some(MatchedTarget("ARGS", Some("comment"))),
      sampleValue = Some("select 1"),
      method = "GET",
      path = "/search",
      placement = placement,
      engineOf = rs => factory.engine(rs.toList)
    )
    assert(run(Placement.Before).effective, "placed before, it should take effect")
    assert(!run(Placement.After).effective, "placed after a phase 1 rule it cannot work, and must not be reported as working")
  }

  test("the placement of each proposal matches its mechanism") {
    val ps = ExclusionBuilder.proposals(942100, Some(MatchedTarget("ARGS", Some("c"))), Some("/x"), 50000)
    assertEquals(ps.find(_.kind == "scoped_target").get.placement, Placement.Before)
    assertEquals(ps.find(_.kind == "scoped_remove").get.placement, Placement.Before)
    assertEquals(ps.find(_.kind == "update_target").get.placement, Placement.After)
    assertEquals(ps.find(_.kind == "remove_rule").get.placement, Placement.After)
  }

  /**
   * The probe has to look like a request a gateway would hand over.
   *
   * Dropping the payload raw into the URI produces an invalid request line, which the CRS objects to
   * in phase 1 — so on a blocking configuration evaluation stops there and the rule being tuned never
   * runs. Every parameter match then reports "could not reproduce", which reads like the assistant
   * being cautious and is really the assistant being broken.
   */
  test("the probe does not trip a request-line rule on its way in") {
    val ctx = AttackCorpus.requestFor(MatchedTarget("ARGS", Some("comment")), "GET", "/api/posts", "1' or 1=1--")
    val res = factory.engine(rules.toList).evaluate(ctx, List(1, 2, 5))
    val fired = res.events.flatMap(_.ruleId).toSet
    assert(!fired.contains(920100), "the generated request line is malformed: CRS 920100 fired on the probe itself")
    assert(fired.contains(942100), s"the probe did not reproduce the match it exists to reproduce, fired: ${fired.toSeq.sorted}")
  }

  test("a blocking configuration still reproduces the match") {
    val r = preview("""SecRuleUpdateTargetById 942100 "!ARGS:comment"""", comment)
    assert(r.reproduced, "nothing was measured, so nothing can be concluded")
    assert(r.effective)
  }

  test("broken seclang is reported, not thrown") {
    val r = preview("""SecRuleUpdateTargetById notanumber "!ARGS:comment"""", comment)
    assert(!r.compiles || !r.effective)
    assert(!r.safe)
  }
}

class WriterSuite extends munit.FunSuite {

  import com.cloud.apim.otoroshi.extensions.waf.entities.{CloudApimWafConfig, WafRuleset}

  private def config(rulesets: Seq[String] = Seq.empty) =
    CloudApimWafConfig(id = "waf-config_1", name = "public api", rulesets = rulesets)

  private val entry = "# why\nSecRuleRemoveById 942100"

  test("the first exclusion creates a ruleset owned by the config") {
    val plan = ExclusionWriter.plan(config(), Placement.After, Seq.empty, entry, "waf-ruleset_new")
    assert(plan.rulesetCreated)
    assertEquals(plan.ruleset.rules, Seq(entry))
    assertEquals(plan.ruleset.metadata(ExclusionWriter.ConfigKey), "waf-config_1")
    assertEquals(plan.ruleset.metadata(ExclusionWriter.ManagedKey), "after")
    assertEquals(plan.config.map(_.rulesets), Some(Seq("waf-ruleset_new")))
  }

  test("the second exclusion appends to the same ruleset and leaves the config alone") {
    val first = ExclusionWriter.plan(config(), Placement.After, Seq.empty, entry, "waf-ruleset_new")
    val plan  = ExclusionWriter.plan(
      config(Seq("waf-ruleset_new")),
      Placement.After,
      Seq(first.ruleset),
      "SecRuleRemoveById 941100",
      "waf-ruleset_other"
    )
    assert(!plan.rulesetCreated)
    assertEquals(plan.ruleset.id, "waf-ruleset_new")
    assertEquals(plan.ruleset.rules.size, 2)
    assertEquals(plan.config, None, "the config already references it, so it must not be rewritten")
  }

  test("before and after get separate rulesets") {
    val a = ExclusionWriter.plan(config(), Placement.After, Seq.empty, entry, "rs_after")
    val b = ExclusionWriter.plan(config(), Placement.Before, Seq(a.ruleset), entry, "rs_before")
    assert(b.rulesetCreated, "a before-ruleset must not reuse the after-one: position is the point")
    assertEquals(b.ruleset.id, "rs_before")
  }

  test("a before-ruleset is wired first and an after-ruleset last") {
    assertEquals(ExclusionWriter.wire(Seq("a", "b"), "x", Placement.Before), Seq("x", "a", "b"))
    assertEquals(ExclusionWriter.wire(Seq("a", "b"), "x", Placement.After), Seq("a", "b", "x"))
  }

  test("a reference in the wrong position is moved, not duplicated") {
    assertEquals(ExclusionWriter.wire(Seq("a", "x", "b"), "x", Placement.Before), Seq("x", "a", "b"))
    assertEquals(ExclusionWriter.wire(Seq("x", "a"), "x", Placement.After), Seq("a", "x"))
  }

  test("a rule set of another config is not adopted") {
    val other = WafRuleset(
      id = "rs_other",
      name = "other",
      metadata = Map(ExclusionWriter.ConfigKey -> "waf-config_2", ExclusionWriter.ManagedKey -> "after")
    )
    assertEquals(ExclusionWriter.managed(config(), Placement.After, Seq(other)), None)
  }

  test("the reason cannot break out of its comment line") {
    val e = ExclusionWriter.entry(
      seclang = "SecRuleRemoveById 1",
      ruleId = 1,
      target = Some(MatchedTarget("ARGS", Some("a"))),
      reason = "line one\nSecRuleRemoveById 942100\nline two",
      by = "someone",
      route = None
    )
    val injected = e.linesIterator.filterNot(_.startsWith("#")).toList
    assertEquals(injected, List("SecRuleRemoveById 1"), "a newline in the reason must not become a rule")
  }

  test("the entry records who, what and why next to the directive") {
    val e = ExclusionWriter.entry(
      seclang = """SecRuleUpdateTargetById 942100 "!ARGS:comment"""",
      ruleId = 942100,
      target = Some(MatchedTarget("ARGS", Some("comment"))),
      reason = "the comment field legitimately contains SQL",
      by = "Ada <ada@example.com>",
      route = Some("public api")
    )
    assert(e.contains("942100"))
    assert(e.contains("ARGS:comment"))
    assert(e.contains("Ada <ada@example.com>"))
    assert(e.contains("legitimately contains SQL"))
    assert(e.contains("public api"))
  }
}

class StoreSuite extends munit.FunSuite {

  import com.cloud.apim.seclang.model.MatchEvent

  private def event(id: Int, target: String, value: String) =
    MatchEvent(Some(id), Some("msg"), List(s"""[id "$id"] Matched Data: x found within $target: $value"""), 2, "{}")

  test("only matches naming an excludable input are kept") {
    val store = new TuningStore()
    store.recordAll(
      Seq(
        event(942100, "ARGS:comment", "1' or 1=1"),
        event(949110, "TX:anomaly_score", "5"),
        MatchEvent(Some(980170), Some("scores"), List("no logdata convention"), 5, "{}")
      ),
      "cfg", Some("r1"), Some("route"), "POST", "/api/posts", blocked = false
    )
    assertEquals(store.all.map(_.ruleId), Seq(942100))
    assertEquals(store.all.head.target, Some(MatchedTarget("ARGS", Some("comment"))))
    assertEquals(store.all.head.matchedValue, Some("1' or 1=1"))
  }

  test("the ring forgets the oldest rather than growing") {
    val store = new TuningStore(max = 3)
    (1 to 10).foreach { i =>
      store.recordAll(Seq(event(942100, "ARGS:a", s"v$i")), "cfg", None, None, "GET", "/x", blocked = false)
    }
    assertEquals(store.all.size, 3)
    assertEquals(store.all.map(_.matchedValue.get), Seq("v8", "v9", "v10"))
  }

  test("the same rule on the same input of the same path is one group") {
    val store = new TuningStore()
    (1 to 4).foreach { i =>
      store.recordAll(Seq(event(942100, "ARGS:a", s"v$i")), "cfg", None, None, "GET", "/x", blocked = false)
    }
    store.recordAll(Seq(event(942100, "ARGS:b", "v")), "cfg", None, None, "GET", "/x", blocked = false)
    val groups = store.groups
    assertEquals(groups.size, 2)
    assertEquals(groups.map(_.count).sorted, Seq(1, 4))
  }
}
