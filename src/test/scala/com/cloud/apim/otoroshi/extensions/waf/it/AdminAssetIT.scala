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
      "/extensions/cloud-apim/waf/posture"
    ).foreach { path =>
      assert(
        script.contains(s"link: '$path'"),
        s"$path is routed but has no `link:` feature entry, so it will not appear in the sidebar"
      )
    }
    assert(script.contains("React.createElement(SecurityPosturePage"))
  }

  test("the posture page uses the shared table rather than hand-rolled rows") {
    // it reads like every other list in the backoffice, with the sorting and filtering that implies
    val page = script.substring(script.indexOf("class SecurityPosturePage"))
    assert(page.contains("React.createElement(\n        Table,") || page.contains("Table,"), "not built on Table")
    assert(page.contains("hideAllActions"), "a derived view must not offer create or delete")
  }
}
