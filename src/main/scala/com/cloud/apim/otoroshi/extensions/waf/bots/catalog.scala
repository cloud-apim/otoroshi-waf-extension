package com.cloud.apim.otoroshi.extensions.waf.bots

import play.api.libs.json.*

/**
 * One recognisable automated client.
 *
 * `rdnsSuffixes` is what separates a claim from a fact. A user-agent string is self-declared and
 * free to forge; a reverse DNS name that forward-resolves back to the same address is not.
 */
final case class BotSignature(
    name: String,
    category: String,
    uaContains: Seq[String],
    rdnsSuffixes: Seq[String] = Seq.empty,
    homepage: String = ""
) {
  def verifiable: Boolean = rdnsSuffixes.nonEmpty
  def matches(userAgent: String): Boolean = {
    val ua = userAgent.toLowerCase
    uaContains.exists(p => p.trim.nonEmpty && ua.contains(p.trim.toLowerCase))
  }
  def json: JsValue = Json.obj(
    "name"          -> name,
    "category"      -> category,
    "ua_contains"   -> uaContains,
    "rdns_suffixes" -> rdnsSuffixes,
    "homepage"      -> homepage,
    "verifiable"    -> verifiable
  )
}

object BotSignature {
  def read(json: JsValue): BotSignature = BotSignature(
    name = (json \ "name").asOpt[String].getOrElse("unknown"),
    category = (json \ "category").asOpt[String].getOrElse("other"),
    uaContains = (json \ "ua_contains").asOpt[Seq[String]].getOrElse(Seq.empty),
    rdnsSuffixes = (json \ "rdns_suffixes").asOpt[Seq[String]].getOrElse(Seq.empty),
    homepage = (json \ "homepage").asOpt[String].getOrElse("")
  )
}

object BotCatalog {

  val categorySearch: String     = "search"
  val categoryAi: String         = "ai"
  val categorySeo: String        = "seo"
  val categoryMonitoring: String = "monitoring"

  /**
   * Search crawlers that publish a verification method.
   *
   * Every one of these is worth verifying rather than trusting: "Googlebot" is the single most
   * forged user-agent string on the web, and a forward-confirmed reverse DNS lookup is the check
   * the operators themselves document.
   */
  val searchBots: Seq[BotSignature] = Seq(
    BotSignature("googlebot", categorySearch, Seq("googlebot"), Seq(".googlebot.com", ".google.com"), "https://developers.google.com/search/docs/crawling-indexing/verifying-googlebot"),
    BotSignature("bingbot", categorySearch, Seq("bingbot", "adidxbot", "msnbot"), Seq(".search.msn.com"), "https://www.bing.com/webmasters/help/how-to-verify-bingbot-3905dc26"),
    BotSignature("duckduckbot", categorySearch, Seq("duckduckbot", "duckassistbot"), Seq(".duckduckgo.com")),
    BotSignature("yandexbot", categorySearch, Seq("yandexbot"), Seq(".yandex.ru", ".yandex.net", ".yandex.com")),
    BotSignature("baiduspider", categorySearch, Seq("baiduspider"), Seq(".baidu.com", ".baidu.jp")),
    BotSignature("applebot", categorySearch, Seq("applebot"), Seq(".applebot.apple.com"))
  )

  /**
   * AI and dataset crawlers.
   *
   * Most publish no verification method at all, which is itself informative: a policy on these can
   * only ever be as strong as the user-agent they choose to send. Enforcement at the gateway is
   * what makes a robots.txt line more than a request.
   */
  val aiBots: Seq[BotSignature] = Seq(
    BotSignature("gptbot", categoryAi, Seq("gptbot"), Seq.empty, "https://platform.openai.com/docs/bots"),
    BotSignature("oai-searchbot", categoryAi, Seq("oai-searchbot")),
    BotSignature("chatgpt-user", categoryAi, Seq("chatgpt-user")),
    BotSignature("claudebot", categoryAi, Seq("claudebot", "claude-web", "claude-searchbot", "claude-user")),
    BotSignature("perplexitybot", categoryAi, Seq("perplexitybot", "perplexity-user")),
    BotSignature("ccbot", categoryAi, Seq("ccbot"), Seq.empty, "https://commoncrawl.org/ccbot"),
    BotSignature("bytespider", categoryAi, Seq("bytespider")),
    BotSignature("amazonbot", categoryAi, Seq("amazonbot")),
    BotSignature("meta-externalagent", categoryAi, Seq("meta-externalagent", "facebookbot")),
    BotSignature("google-extended", categoryAi, Seq("google-extended")),
    BotSignature("applebot-extended", categoryAi, Seq("applebot-extended")),
    BotSignature("cohere-ai", categoryAi, Seq("cohere-ai", "cohere-training-data-crawler")),
    BotSignature("diffbot", categoryAi, Seq("diffbot")),
    BotSignature("timpibot", categoryAi, Seq("timpibot")),
    BotSignature("omgili", categoryAi, Seq("omgili", "omgilibot"))
  )

  val otherBots: Seq[BotSignature] = Seq(
    BotSignature("ahrefsbot", categorySeo, Seq("ahrefsbot"), Seq(".ahrefs.com")),
    BotSignature("semrushbot", categorySeo, Seq("semrushbot")),
    BotSignature("mj12bot", categorySeo, Seq("mj12bot")),
    BotSignature("dotbot", categorySeo, Seq("dotbot")),
    BotSignature("uptimerobot", categoryMonitoring, Seq("uptimerobot")),
    BotSignature("pingdom", categoryMonitoring, Seq("pingdom"))
  )

  val all: Seq[BotSignature] = searchBots ++ aiBots ++ otherBots

  def json: JsValue = Json.obj(
    "categories" -> all.map(_.category).distinct,
    "signatures" -> JsArray(all.map(_.json))
  )
}
