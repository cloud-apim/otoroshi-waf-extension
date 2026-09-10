package com.cloud.apim.otoroshi.extensions.waf.it

/**
 * The admin UI is spliced together from string resources at runtime, so a renamed file or a bad
 * path does not fail the build — it ships an extension.js containing the words "resource not found"
 * and a backoffice that silently loses a page.
 */
class AdminAssetIT extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private lazy val script: String =
    Gateway.await(
      Gateway.ws
        .url(s"http://127.0.0.1:${Gateway.port}/extensions/assets/cloud-apim/extensions/waf/extension.js")
        .withHttpHeaders("Host" -> "otoroshi.oto.tools")
        .get()
    ).body

  test("every page resource was found and spliced in") {
    assert(!script.contains("not found !"), "a page resource path is wrong")
    Seq(
      "class WafConfigsPage",
      "class WafRulesetsPage",
      "class SecurityPosturePage",
      "class WafTuningPage",
      "class WafLearningPage",
      "class SecurityDashboardPage"
    ).foreach(cls => assert(script.contains(cls), s"$cls is missing from the assembled extension.js"))
  }

  test("every page the extension routes is also declared as a feature") {
    // otoroshi's sidebar is built from `features` (`graph()` reads `ext.features` and
    // `ext.categories[].features`); `sidebarItems` is consumed by nothing at all. a page declared
    // only there is reachable by url and by search, and invisible in the menu — which is exactly
    // how the posture page shipped the first time
    Seq(
      "/extensions/cloud-apim/waf/wafconfigs",
      "/extensions/cloud-apim/waf/wafrulesets",
      "/extensions/cloud-apim/waf/posture",
      "/extensions/cloud-apim/waf/tuning",
      "/extensions/cloud-apim/waf/learning",
      "/extensions/cloud-apim/waf/security"
    ).foreach { path =>
      assert(
        script.contains(s"link: '$path'"),
        s"$path is routed but has no `link:` feature entry, so it will not appear in the sidebar"
      )
    }
    assert(script.contains("React.createElement(SecurityPosturePage"))
    assert(script.contains("React.createElement(WafTuningPage"))
    assert(script.contains("React.createElement(WafLearningPage"))
  }

  test("no column is declared without an id react-table can use") {
    // Table builds the react-table column id from `filterId || title`, and react-table 6 throws
    // "A column id is required if using a non-string accessor" when that is empty — every Otoroshi
    // column uses a function accessor, so an untitled column takes the whole page down with a bare
    // "Something went wrong !!!" and no logged cause
    assert(!script.contains("title: ''"), "a column with an empty title and no filterId will crash the table")
    assert(!script.contains("title: \"\""), "a column with an empty title and no filterId will crash the table")
  }

  test("the incident console surfaces a refusal instead of quietly doing nothing") {
    // the api answers `done: false` for the refusals that are decisions rather than failures —
    // banning an allowlisted caller, extending a lapsed ban. an operator who is not told walks away
    // believing a caller is banned when they are not
    val page = script.substring(script.indexOf("class SecurityDashboardPage"))
    assert(page.contains("r.done === false"), "the console must read the api's refusals")
    assert(page.contains("refusedMessage"), "and say which allowlist entry refused the ban")
  }

  test("the console asks for a reason inline rather than behind a modal") {
    // window.prompt on an *optional* note is a trap: cancelling it abandons the action the operator
    // asked for, not the note they were offered
    val page = script.substring(script.indexOf("class SecurityDashboardPage"))
    assert(!page.contains("window.prompt("), "an inline form, not a blocking dialog")
    assert(page.contains("renderPending"), "the reason and the note are collected in the row")
  }

  test("the row that hides the evidence is reachable by keyboard") {
    // a span with an onClick is not focusable and announces nothing, so the evidence behind every
    // ban would be mouse-only — and it is invisible to any tool that reads the accessibility tree
    val page = script.substring(script.indexOf("function suiteDisclosure"))
    assert(page.take(600).contains("aria-expanded"), "the disclosure must declare its state")
    assert(page.take(600).contains("'button'"), "and be a real button")
  }

  test("the posture page uses the shared table rather than hand-rolled rows") {
    // it reads like every other list in the backoffice, with the sorting and filtering that implies
    val page = script.substring(script.indexOf("class SecurityPosturePage"))
    assert(page.contains("React.createElement(\n        Table,") || page.contains("Table,"), "not built on Table")
    assert(page.contains("hideAllActions"), "a derived view must not offer create or delete")
  }
}
