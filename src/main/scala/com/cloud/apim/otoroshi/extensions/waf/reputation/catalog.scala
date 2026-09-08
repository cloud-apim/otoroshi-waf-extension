package com.cloud.apim.otoroshi.extensions.waf.reputation

import play.api.libs.json.*

/**
 * A curated source, ready to be turned into a [[entities.ThreatFeed]].
 *
 * The catalog ships pointers and parser settings, never mirrored data: several of these sources
 * forbid redistribution, and all of them reserve the right to move. An entry seeds an editable
 * feed entity — if a provider changes its endpoint, the operator fixes one field instead of
 * waiting for a release.
 */
final case class CatalogEntry(
    id: String,
    name: String,
    description: String,
    category: String,
    url: String,
    format: String,
    options: JsObject = Json.obj(),
    tag: String,
    weight: Int,
    action: String,
    refreshIntervalSeconds: Long,
    licence: String,
    homepage: String,
    requiresAuth: Boolean = false,
    authHint: Option[String] = None,
    manualUrl: Boolean = false,
    notes: Option[String] = None
) {
  def json: JsValue = Json.obj(
    "id"                       -> id,
    "name"                     -> name,
    "description"              -> description,
    "category"                 -> category,
    "url"                      -> url,
    "format"                   -> format,
    "options"                  -> options,
    "tag"                      -> tag,
    "weight"                   -> weight,
    "action"                   -> action,
    "refresh_interval_seconds" -> refreshIntervalSeconds,
    "licence"                  -> licence,
    "homepage"                 -> homepage,
    "requires_auth"            -> requiresAuth,
    "auth_hint"                -> authHint,
    "manual_url"               -> manualUrl,
    "notes"                    -> notes
  )
}

object ThreatFeedCatalog {

  private val hour = 3600L
  private val day  = 86400L

  val entries: Seq[CatalogEntry] = Seq(
    CatalogEntry(
      id = "tor-exit-nodes",
      name = "Tor exit nodes",
      description = "Addresses currently acting as Tor exit relays. Strong anonymity signal, but a legitimate one for many users — score it, do not block on it alone.",
      category = "anonymity",
      url = "https://check.torproject.org/torbulkexitlist",
      format = "cidr_lines",
      tag = "feed:tor-exit",
      weight = 40,
      action = "monitor",
      refreshIntervalSeconds = hour,
      licence = "Public, published by the Tor Project",
      homepage = "https://check.torproject.org/"
    ),
    CatalogEntry(
      id = "firehol-level1",
      name = "FireHOL level 1",
      description = "Conservative aggregation of well-established blocklists. The safest of the FireHOL levels and the usual starting point for blocking.",
      category = "blocklist",
      url = "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level1.netset",
      format = "cidr_lines",
      tag = "feed:firehol-l1",
      weight = 80,
      action = "block",
      refreshIntervalSeconds = 6 * hour,
      licence = "See the FireHOL repository — individual sources carry their own terms",
      homepage = "https://iplists.firehol.org/"
    ),
    CatalogEntry(
      id = "firehol-level2",
      name = "FireHOL level 2",
      description = "Broader aggregation including recent attack sources. Higher coverage, higher false-positive rate — start in monitor mode.",
      category = "blocklist",
      url = "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level2.netset",
      format = "cidr_lines",
      tag = "feed:firehol-l2",
      weight = 60,
      action = "monitor",
      refreshIntervalSeconds = 6 * hour,
      licence = "See the FireHOL repository — individual sources carry their own terms",
      homepage = "https://iplists.firehol.org/"
    ),
    CatalogEntry(
      id = "firehol-level3",
      name = "FireHOL level 3",
      description = "Widest FireHOL aggregation. Useful as a scoring signal, unsuitable for blocking on its own.",
      category = "blocklist",
      url = "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level3.netset",
      format = "cidr_lines",
      tag = "feed:firehol-l3",
      weight = 40,
      action = "monitor",
      refreshIntervalSeconds = 6 * hour,
      licence = "See the FireHOL repository — individual sources carry their own terms",
      homepage = "https://iplists.firehol.org/"
    ),
    CatalogEntry(
      id = "emerging-threats-compromised",
      name = "Emerging Threats — compromised hosts",
      description = "Hosts observed as compromised and used in attacks. Small, high-signal list.",
      category = "blocklist",
      url = "https://rules.emergingthreats.net/blockrules/compromised-ips.txt",
      format = "cidr_lines",
      tag = "feed:et-compromised",
      weight = 70,
      action = "block",
      refreshIntervalSeconds = 6 * hour,
      licence = "Emerging Threats open rules — check current terms for commercial use",
      homepage = "https://rules.emergingthreats.net/"
    ),
    CatalogEntry(
      id = "spamhaus-drop",
      name = "Spamhaus DROP",
      description = "Netblocks Spamhaus considers wholly controlled by criminal operations. Very low false-positive rate, which is why it earns a high weight.",
      category = "blocklist",
      url = "https://www.spamhaus.org/drop/drop.txt",
      format = "cidr_lines",
      tag = "feed:spamhaus-drop",
      weight = 90,
      action = "block",
      refreshIntervalSeconds = 12 * hour,
      licence = "Free for non-commercial use — commercial use requires a Spamhaus agreement",
      homepage = "https://www.spamhaus.org/blocklists/do-not-route-or-peer/",
      notes = Some("Spamhaus has been consolidating DROP onto JSON endpoints. Confirm the current URL and format on their site before enabling; if it serves JSON, switch the format to json_path.")
    ),
    CatalogEntry(
      id = "abuseipdb-blacklist",
      name = "AbuseIPDB blacklist",
      description = "Addresses with a high abuse confidence score, as reported by the AbuseIPDB community.",
      category = "blocklist",
      url = "https://api.abuseipdb.com/api/v2/blacklist",
      format = "json_path",
      options = Json.obj("path" -> "data[].ipAddress"),
      tag = "feed:abuseipdb",
      weight = 85,
      action = "block",
      refreshIntervalSeconds = 12 * hour,
      licence = "Requires an AbuseIPDB account — redistribution is restricted by their terms",
      homepage = "https://www.abuseipdb.com/",
      requiresAuth = true,
      authHint = Some("Add a 'Key' header with your AbuseIPDB API key, and an 'Accept: application/json' header. Use a vault reference rather than pasting the key.")
    ),
    CatalogEntry(
      id = "aws-ranges-v4",
      name = "AWS IP ranges (IPv4)",
      description = "Published AWS prefixes. Not a blocklist — a hosting signal: server-to-server callers live here, and so do most scrapers.",
      category = "cloud",
      url = "https://ip-ranges.amazonaws.com/ip-ranges.json",
      format = "json_path",
      options = Json.obj("path" -> "prefixes[].ip_prefix"),
      tag = "asn:aws",
      weight = 15,
      action = "monitor",
      refreshIntervalSeconds = day,
      licence = "Published publicly by AWS",
      homepage = "https://docs.aws.amazon.com/vpc/latest/userguide/aws-ip-ranges.html"
    ),
    CatalogEntry(
      id = "aws-ranges-v6",
      name = "AWS IP ranges (IPv6)",
      description = "IPv6 half of the published AWS prefixes.",
      category = "cloud",
      url = "https://ip-ranges.amazonaws.com/ip-ranges.json",
      format = "json_path",
      options = Json.obj("path" -> "ipv6_prefixes[].ipv6_prefix"),
      tag = "asn:aws",
      weight = 15,
      action = "monitor",
      refreshIntervalSeconds = day,
      licence = "Published publicly by AWS",
      homepage = "https://docs.aws.amazon.com/vpc/latest/userguide/aws-ip-ranges.html"
    ),
    CatalogEntry(
      id = "gcp-ranges-v4",
      name = "Google Cloud IP ranges (IPv4)",
      description = "Published Google Cloud prefixes. Same hosting signal as the AWS list.",
      category = "cloud",
      url = "https://www.gstatic.com/ipranges/cloud.json",
      format = "json_path",
      options = Json.obj("path" -> "prefixes[].ipv4Prefix"),
      tag = "asn:gcp",
      weight = 15,
      action = "monitor",
      refreshIntervalSeconds = day,
      licence = "Published publicly by Google",
      homepage = "https://cloud.google.com/vpc/docs/subnets"
    ),
    CatalogEntry(
      id = "digitalocean-ranges",
      name = "DigitalOcean IP ranges",
      description = "Published DigitalOcean prefixes — a common origin for scraping and credential-stuffing traffic.",
      category = "cloud",
      url = "https://www.digitalocean.com/geo/google.csv",
      format = "csv",
      options = Json.obj("column" -> 0),
      tag = "asn:digitalocean",
      weight = 20,
      action = "monitor",
      refreshIntervalSeconds = day,
      licence = "Published publicly by DigitalOcean",
      homepage = "https://docs.digitalocean.com/products/platform/"
    ),
    CatalogEntry(
      id = "cloudflare-v4",
      name = "Cloudflare edge (IPv4)",
      description = "Cloudflare's own egress ranges. Use it as an allowlist input: when your traffic legitimately arrives through Cloudflare, these must never be scored as hostile.",
      category = "allowlist",
      url = "https://www.cloudflare.com/ips-v4",
      format = "cidr_lines",
      tag = "infra:cloudflare",
      weight = 0,
      action = "monitor",
      refreshIntervalSeconds = day,
      licence = "Published publicly by Cloudflare",
      homepage = "https://www.cloudflare.com/ips/"
    ),
    CatalogEntry(
      id = "cloudflare-v6",
      name = "Cloudflare edge (IPv6)",
      description = "IPv6 half of Cloudflare's egress ranges.",
      category = "allowlist",
      url = "https://www.cloudflare.com/ips-v6",
      format = "cidr_lines",
      tag = "infra:cloudflare",
      weight = 0,
      action = "monitor",
      refreshIntervalSeconds = day,
      licence = "Published publicly by Cloudflare",
      homepage = "https://www.cloudflare.com/ips/"
    ),
    CatalogEntry(
      id = "azure-ranges",
      name = "Azure IP ranges",
      description = "Published Azure prefixes. Microsoft rotates the download URL every week, so this entry cannot ship a working link.",
      category = "cloud",
      url = "",
      format = "json_path",
      options = Json.obj("path" -> "values[].properties.addressPrefixes[]"),
      tag = "asn:azure",
      weight = 15,
      action = "monitor",
      refreshIntervalSeconds = day,
      licence = "Published publicly by Microsoft",
      homepage = "https://www.microsoft.com/en-us/download/details.aspx?id=56519",
      manualUrl = true,
      notes = Some("Fetch the current weekly JSON link from the Microsoft download page and paste it into the feed's url field.")
    )
  )

  val categories: Seq[String] = entries.map(_.category).distinct

  def find(id: String): Option[CatalogEntry] = entries.find(_.id == id)

  def json: JsValue = Json.obj(
    "categories" -> categories,
    "entries"    -> JsArray(entries.map(_.json))
  )
}
