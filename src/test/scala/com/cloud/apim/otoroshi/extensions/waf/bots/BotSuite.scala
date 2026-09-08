package com.cloud.apim.otoroshi.extensions.waf.bots

import com.cloud.apim.otoroshi.extensions.waf.entities.{BotPolicy, BotRule, CanaryToken, HoneypotPolicy}

class BotCatalogSuite extends munit.FunSuite {

  test("user agents match case-insensitively, on a substring") {
    val googlebot = BotCatalog.all.find(_.name == "googlebot").get
    assert(googlebot.matches("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"))
    assert(googlebot.matches("GOOGLEBOT"))
    assert(!googlebot.matches("Mozilla/5.0 (Macintosh) Safari/605"))
  }

  test("catalog names are unique") {
    val names = BotCatalog.all.map(_.name)
    assertEquals(names.distinct.size, names.size)
  }

  test("every search crawler is verifiable — that is the point of listing it") {
    BotCatalog.searchBots.foreach(sig => assert(sig.verifiable, s"${sig.name} has no rdns suffix to check"))
  }

  test("ai crawlers are mostly unverifiable, which is itself the finding") {
    val verifiable = BotCatalog.aiBots.count(_.verifiable)
    assertEquals(verifiable, 0, "none of them publish a verification method, so a policy on them rests on a self-declared string")
  }

  test("every signature carries at least one user agent fragment") {
    BotCatalog.all.foreach(sig => assert(sig.uaContains.exists(_.trim.nonEmpty), s"${sig.name} matches nothing"))
  }

  test("the ai category covers the crawlers people actually ask about") {
    val names = BotCatalog.aiBots.map(_.name).toSet
    Seq("gptbot", "claudebot", "ccbot", "perplexitybot", "bytespider").foreach { n =>
      assert(names.contains(n), s"$n is missing from the catalog")
    }
  }
}

class BotPolicySuite extends munit.FunSuite {

  private val policy = BotPolicy(id = "p", name = "p")

  test("a name rule beats a category rule") {
    val custom = policy.copy(rules = Seq(BotRule("category:ai", "monitor", 10), BotRule("name:gptbot", "deny", 0)))
    val gptbot = BotCatalog.all.find(_.name == "gptbot").get
    val ccbot  = BotCatalog.all.find(_.name == "ccbot").get
    assertEquals(custom.ruleFor(gptbot).action, "deny")
    assertEquals(custom.ruleFor(ccbot).action, "monitor")
  }

  test("an unmatched signature falls back to monitoring, never to denying") {
    val bare = policy.copy(rules = Seq.empty)
    assertEquals(bare.ruleFor(BotCatalog.all.head).action, "monitor")
  }

  test("the shipped defaults deny nothing") {
    BotCatalog.all.foreach { sig =>
      assertNotEquals(policy.ruleFor(sig).action, "deny", s"${sig.name} would be denied on install")
    }
  }

  test("challenge is not a bot rule action — there is one challenge implementation") {
    assertEquals(BotRule.actions, Seq("allow", "monitor", "deny"))
  }

  test("robots.txt is generated from the rules, so the file cannot drift from what is enforced") {
    val custom = policy.copy(rules = Seq(BotRule("category:ai", "deny", 0), BotRule("category:search", "allow", 0)))
    val txt    = custom.robotsTxt
    assert(txt.contains("User-agent: gptbot"), txt)
    assert(txt.contains("Disallow: /"))
    assert(!txt.contains("User-agent: googlebot"), "an allowed crawler must not be disallowed in the file")
    assert(txt.contains("enforced at the gateway"))
  }

  test("nothing is disallowed when nothing is denied") {
    assert(!policy.robotsTxt.contains("Disallow: /"))
  }

  test("extra robots directives are appended") {
    assert(policy.copy(robotsExtra = "Sitemap: https://x/sitemap.xml").robotsTxt.contains("Sitemap:"))
  }

  test("the entity round-trips with its catalog") {
    val back = BotPolicy.format.reads(BotPolicy.format.writes(policy)).get
    assertEquals(back.signatures.size, BotCatalog.all.size)
    assertEquals(back.rules, BotRule.defaults)
  }
}

class HoneypotPolicySuite extends munit.FunSuite {

  private val policy = HoneypotPolicy(id = "h", name = "h")

  test("exact paths match, and nothing else does") {
    assertEquals(policy.matchingPath("/.env"), Some("/.env"))
    assertEquals(policy.matchingPath("/.ENV"), Some("/.env"), "matching is case-insensitive")
    assertEquals(policy.matchingPath("/api/users"), None)
    assertEquals(policy.matchingPath("/.environment"), None, "an exact pattern must not match a longer path")
  }

  test("a trailing star is a prefix match") {
    assertEquals(policy.matchingPath("/wp-admin/install.php"), Some("/wp-admin*"))
    assertEquals(policy.matchingPath("/phpmyadmin/index.php"), Some("/phpmyadmin*"))
  }

  test("the shipped paths are things no client of yours ever requests") {
    assert(HoneypotPolicy.defaultPaths.contains("/.env"))
    assert(HoneypotPolicy.defaultPaths.contains("/wp-login.php"))
    assert(HoneypotPolicy.defaultPaths.contains("/.git/config"))
    assert(HoneypotPolicy.defaultPaths.forall(_.startsWith("/")))
  }

  test("it answers 404 by default — a 403 would confirm something is there") {
    assertEquals(policy.status, 404)
    assert(policy.denies)
    assert(!policy.bans, "banning on the first hit is opt-in")
  }

  test("canaries are found wherever they were told to look") {
    val anywhere = CanaryToken("CANARY-123", "planted apikey")
    assert(anywhere.presentIn("/x?k=CANARY-123", "/x", "k=CANARY-123", Seq.empty))
    assert(anywhere.presentIn("/x", "/x", "", Seq("Bearer CANARY-123")))
    assert(!anywhere.presentIn("/x", "/x", "", Seq("Bearer something-else")))

    val headerOnly = CanaryToken("CANARY-123", where = "header")
    assert(headerOnly.presentIn("/x?k=CANARY-123", "/x", "k=CANARY-123", Seq.empty) == false, "query is not header")
    assert(headerOnly.presentIn("/x", "/x", "", Seq("CANARY-123")))

    val queryOnly = CanaryToken("CANARY-123", where = "query")
    assert(queryOnly.presentIn("/x?k=CANARY-123", "/x", "k=CANARY-123", Seq.empty))
    assert(!queryOnly.presentIn("/x", "/x", "", Seq("CANARY-123")))
  }

  test("an empty canary never matches anything") {
    assert(!CanaryToken("").presentIn("/anything", "/anything", "everything", Seq("everything")))
    assert(!CanaryToken("   ").presentIn("/x", "/x", "", Seq.empty))
  }

  test("the entity round-trips") {
    val p    = policy.copy(canaries = Seq(CanaryToken("v", "d", "query")), action = "ban")
    val back = HoneypotPolicy.format.reads(HoneypotPolicy.format.writes(p)).get
    assertEquals(back, p)
    assert(back.bans)
  }
}
