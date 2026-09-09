# Cloud APIM - Security Suite for Otoroshi

A security extension for [Otoroshi](https://www.otoroshi.io/): a JVM-native web application firewall
speaking ModSecurity SecLang with the OWASP Core Rule Set embedded, ip reputation fed by threat
intelligence feeds, CrowdSec and the public routing table, bot and AI-crawler control with an
embedded proof-of-work challenge — and a decision fabric that makes those detectors feed one shared
judgement instead of each blocking on its own.

📖 **[Full documentation](https://cloud-apim.github.io/otoroshi-waf-extension/)** — start with
[Protecting a route, end to end](https://cloud-apim.github.io/otoroshi-waf-extension/docs/tutorial)

This extension is built on top of the following open-source Cloud APIM libraries:

- [seclang-engine](https://github.com/cloud-apim/seclang-engine) - A JVM-native implementation of the ModSecurity SecLang DSL
- [seclang-engine-coreruleset](https://github.com/cloud-apim/seclang-engine-coreruleset) - The OWASP Core Rule Set (CRS) packaged for seclang-engine
- [libinjection-jvm](https://github.com/cloud-apim/libinjection-jvm) - A JVM port of libinjection for SQL injection and XSS detection

## Features

### Web application firewall

- **ModSecurity SecLang support**: Native JVM implementation of the ModSecurity SecLang DSL
- **OWASP Core Rule Set (CRS)**: Embedded CRS preset for comprehensive protection against common web attacks
- **Request/Response inspection**: Inspect both incoming requests and outgoing responses
- **Configurable body inspection**: Control body inspection limits and MIME types
- **Blocking or monitoring mode**: Choose to block malicious requests or just log them

### IP reputation

- **Threat intelligence feeds**: Match callers against blocklists and cloud provider ranges, with a catalog of 14 curated sources
- **CrowdSec integration**: Consume decisions from a Local API, and report WAF detections back as alerts
- **ASN classification**: Resolve the caller to its network from the public routing table, shipped as a low weight and never as a block — a datacenter is not a verdict
- **Weighted scoring**: Sources carry a weight and an action, so weak signals accumulate instead of forcing a binary judgement
- **Nothing blocking on the request path**: In-memory range index, scheduled refreshes, fail-open on every external dependency

### Decision fabric

- **One shared threat score**: Detectors contribute weighted signals rather than each blocking alone, so a request is judged on the accumulation instead of on whichever check happens to fire first
- **Graded response**: A threat policy maps score tiers to `log`, `tarpit`, `challenge`, `deny` or `ban` — and defaults to dry run, recording what it would have done and enforcing nothing
- **Cluster-wide bans**: A shared ban store and a cross-request ledger, so a caller banned on one node is banned on every node
- **Distributed fail2ban**: Repeated failed responses ban the caller across the whole cluster — Otoroshi's own plugin keeps its counters and bans node-local, so its threshold means N times what you configured on N nodes
- **Correlated incidents**: Normalised ECS-shaped events grouped into one incident rather than nine thousand alerts
- **One preset plugin**: Lays the whole chain down on a route in the one order that makes it work, with each section switchable

### Bots and automated traffic

- **Embedded proof-of-work challenge**: Self-contained, no third party and no external call, with the difficulty scaled by the caller's score
- **Pluggable CAPTCHA backends**: Friendly Captcha and captcha.eu for a European deployment, Turnstile and hCaptcha otherwise, all behind one mechanism
- **Verified crawlers**: Forward-confirmed reverse DNS over 27 known signatures, which turns a forged `Googlebot` from a suspicion into a demonstrated lie
- **AI crawler policy**: Per-category rules, actually enforced, with a matching `robots.txt` and `llms.txt` generated from them
- **Honeypots**: Decoy paths and canary tokens, evaluated before routing

### Everywhere

- **Analytics events**: Every detection is an Otoroshi analytic event, routable through any data exporter
- **Backwards compatible**: Entities and plugins that predate the security suite are unchanged, and keep working without being edited

## Requirements

- Otoroshi 18.0.0 or later
- Java 17 or later

## Installation

1. Download the latest jar from the [releases page](https://github.com/cloud-apim/otoroshi-waf-extension/releases/latest) — the asset is named `otoroshi-waf-extension_3-<version>.jar`.

2. Start Otoroshi with it on the classpath:

```bash
java -cp "./otoroshi-waf-extension.jar:./otoroshi.jar" \
  -Dotoroshi.storage=file \
  play.core.server.ProdServerStart
```

or mount it into the Otoroshi plugins directory with Docker. See the
[install documentation](https://cloud-apim.github.io/otoroshi-waf-extension/docs/install) for the
full instructions, checksums included.

To build from source instead — only needed for an unreleased change — run `sbt assembly`; the
artifact lands in `target/scala-3.8.4/otoroshi-waf-extension-assembly_3-dev.jar`.

3. Enable the extension in Otoroshi configuration:

```hocon
otoroshi.admin-extensions.configurations.cloud-apim_extensions_waf {
  enabled = true
  integration {
     max-cache-items = 10000
     log = true
  } 
}
```

## Getting started

The fastest correct path is the **preset plugin**: one slot on a route that expands into the whole
detection fabric, in the one order that makes it work.

1. Create a **threat policy** — it starts in dry run, recording everything and enforcing nothing.
2. Create a **WAF config** whose rules are `@import_preset crs` and `SecRuleEngine On`.
3. Create a **threat feed** from the catalog (FireHOL level 1 is the usual first one).
4. Add **Cloud APIM Security Suite - Preset** to the route, referencing those three.
5. Read the events for a week, then arm the four switches one at a time.

→ **[Protecting a route, end to end](https://cloud-apim.github.io/otoroshi-waf-extension/docs/tutorial)**
walks through every step, including what to look at before arming anything.

## Entities

Eight, all with full CRUD, admin API, import/export and Kubernetes CRDs, under the API group
`waf.extensions.cloud-apim.com/v1`:

| Entity | Collection | What it holds |
|---|---|---|
| `WafConfig` | `waf-configs` | SecLang rules, blocking mode, body inspection limits |
| `ThreatPolicy` | `threat-policies` | Score tiers and the action at each, dry run, exemptions |
| `ThreatFeed` | `threat-feeds` | A reputation source: url, format, refresh interval, weight, action |
| `AsnDatabase` | `asn-databases` | Address-to-network table and its ordered categories |
| `CrowdSecBouncer` | `crowdsec-bouncers` | A CrowdSec Local API connection, in both directions |
| `BotPolicy` | `bot-policies` | Crawler signatures, per-category rules, `robots.txt` generation |
| `ChallengeProvider` | `challenge-providers` | Proof of work settings, or a vendor widget |
| `HoneypotPolicy` | `honeypot-policies` | Decoy paths and canary tokens |

Standard Otoroshi entity endpoints, authenticated with an admin apikey:

```
GET    /apis/waf.extensions.cloud-apim.com/v1/waf-configs
POST   /apis/waf.extensions.cloud-apim.com/v1/waf-configs
GET    /apis/waf.extensions.cloud-apim.com/v1/waf-configs/{id}
PUT    /apis/waf.extensions.cloud-apim.com/v1/waf-configs/{id}
PATCH  /apis/waf.extensions.cloud-apim.com/v1/waf-configs/{id}
DELETE /apis/waf.extensions.cloud-apim.com/v1/waf-configs/{id}
```

Same shape for every collection above. Field-by-field reference:
[Entity reference](https://cloud-apim.github.io/otoroshi-waf-extension/docs/reference/entities).

## Plugins

All under the **Cloud APIM - Security Suite** category in the route designer.

| Plugin | Kind | Runs |
|---|---|---|
| Security Suite - Preset | `NgPresetPlugin` | Expands into the five below, correctly ordered |
| Threat gate | `NgAccessValidator` | Refuses callers already banned, before any inspection |
| Bot guard | `NgAccessValidator` | Identifies crawlers and verifies the ones that publish a method |
| IP reputation | `NgAccessValidator` | Scores against feeds, CrowdSec and ASN |
| Fail2ban | `NgAccessValidator` + `NgRequestTransformer` | Counts failed responses, bans cluster-wide |
| Cloud APIM WAF | `NgRequestTransformer` | The rule engine, over the request and optionally the response |
| Threat response | `NgRequestTransformer` | Reads the accumulated score and applies one graded action |

Three more are **incoming request validators**, configured on the global configuration rather than
on a route — they run before routing, so they also cover traffic matching no route at all: the
honeypot, and validator variants of the WAF and IP reputation.

The order of the route plugins matters: the threat response must run after everything that
contributes to the score. That is exactly why the preset exists — it emits the chain with explicit
`plugin_index` values, so the ordering is enforced by the engine rather than by a paragraph in a
document.

## SecLang rules

Standard ModSecurity SecLang directives. The embedded CRS is one import away:

```
@import_preset crs
SecRuleEngine On
```

Custom rules work the same way:

```
SecRule REQUEST_URI "@contains /admin" "id:1001,phase:1,deny,status:403,msg:'Admin access denied'"
SecRule ARGS "@rx <script>" "id:1002,phase:2,deny,status:403,msg:'XSS detected'"
```

and the two combine:

```
@import_preset crs

SecRule REQUEST_URI "@contains /admin" "id:1001,phase:1,deny,status:403,msg:'Admin access denied'"

SecRuleEngine On
```

## Analytics events

Four types, each an Otoroshi `AnalyticEvent`, so they flow through any data exporter with no extra
wiring:

| Event | Emitted by |
|---|---|
| `CloudApimSecurityEvent` | Every component of the fabric — normalised, ECS-shaped, correlated into incidents. **The one to wire up first** |
| `CloudApimWafTrailEvent` | The WAF, once per request where a rule matched |
| `CloudApimWafReputationEvent` | IP reputation, with the verdict and its sources |
| `CloudApimWafAuditEvent` | SecLang `auditlog` actions — verbose, for debugging a specific rule |

## Distributed state

Bans, the ledger and fail2ban counters are shared. On a cluster, point them at a redis so they
actually are:

```hocon
otoroshi.admin-extensions.configurations.cloud-apim_extensions_waf {
  security {
    redis-uri = "redis://localhost:6379/3"
  }
}
```

Without it they fall back to Otoroshi's storage, which is genuinely distributed only when that
storage is. The *Bans & incidents* page reports which mode is running.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## About Cloud APIM

This extension is developed and maintained by [Cloud APIM](https://www.cloud-apim.com/).

## Documentation

The full documentation lives at
**[cloud-apim.github.io/otoroshi-waf-extension](https://cloud-apim.github.io/otoroshi-waf-extension/)**
and its source is in [`documentation/`](./documentation).

```bash
cd documentation
npm install
npm start
```

The built site is not committed. Pushing to `main` with changes under `documentation/` triggers
[`.github/workflows/documentation.yaml`](./.github/workflows/documentation.yaml), which builds it and
publishes it to GitHub Pages as an artifact.
