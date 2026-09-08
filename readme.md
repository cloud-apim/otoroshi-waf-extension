# Cloud APIM - Security Suite for Otoroshi

A security extension for [Otoroshi](https://www.otoroshi.io/): a JVM-native web application firewall
speaking ModSecurity SecLang with the OWASP Core Rule Set embedded, ip reputation fed by threat
intelligence feeds, CrowdSec and the public routing table, bot and AI-crawler control with an
embedded proof-of-work challenge — and a decision fabric that makes those detectors feed one shared
judgement instead of each blocking on its own.

📖 **[Full documentation](https://cloud-apim.github.io/otoroshi-waf-extension/)**

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

## Configuration

### WAF Configuration Entity

Create a WAF configuration with the following properties:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id` | String | - | Unique identifier (auto-generated) |
| `name` | String | - | Configuration name |
| `description` | String | `""` | Description |
| `tags` | Array[String] | `[]` | Tags for organization |
| `metadata` | Object | `{}` | Custom metadata |
| `enabled` | Boolean | `true` | Enable/disable the WAF |
| `block` | Boolean | `true` | Block requests when rules match (false = monitoring mode) |
| `inspect_input_body` | Boolean | `true` | Inspect request body |
| `inspect_output_body` | Boolean | `true` | Inspect response body |
| `input_body_limit` | Number | `null` | Max request body size to inspect (bytes) |
| `output_body_limit` | Number | `null` | Max response body size to inspect (bytes) |
| `output_body_mimetypes` | Array[String] | `[]` | MIME types to inspect in responses |
| `rules` | Array[String] | `[]` | SecLang rules to apply |

### Example Configuration

```json
{
  "id": "waf-config_xxxxx",
  "name": "My WAF Config",
  "description": "WAF configuration with CRS",
  "tags": [],
  "metadata": {},
  "enabled": true,
  "block": true,
  "inspect_input_body": true,
  "inspect_output_body": true,
  "input_body_limit": 1048576,
  "output_body_limit": 1048576,
  "output_body_mimetypes": ["text/html", "application/json"],
  "rules": [
    "@import_preset crs",
    "SecRuleEngine On"
  ]
}
```

## Usage

### Using the WAF Plugin

Two plugins are available:

1. **Cloud APIM WAF** (`CloudApimWaf`): Full WAF plugin that transforms requests and responses
   - Supports request body inspection
   - Supports response body inspection
   - Can be used in blocking or monitoring mode

2. **Cloud APIM WAF - Incoming Request Validator** (`IncomingRequestValidatorCloudApimWaf`): Global WAF plugin for Otoroshi
   - Only inspects incoming requests (no body inspection)
   - Always blocks on rule match
   - Better performances for more traffic

### Adding to a Route

1. Go to the Otoroshi admin dashboard
2. Navigate to your route configuration
3. Add the "Cloud APIM WAF" plugin
4. Select your WAF configuration from the dropdown

### SecLang Rules

The extension supports standard ModSecurity SecLang directives. You can use the embedded CRS by importing the preset:

```
@import_preset crs
SecRuleEngine On
```

You can also write custom rules:

```
SecRule REQUEST_HEADERS:User-Agent "@pm firefox" "id:00001,phase:1,block,t:none,t:lowercase,msg:'someone used firefox to access',logdata:'someone used firefox to access',tag:'test',ver:'0.0.0-dev',status:403,severity:'CRITICAL'"
SecRule REQUEST_URI "@contains /admin" "id:1001,phase:1,deny,status:403,msg:'Admin access denied'"
SecRule ARGS "@rx <script>" "id:1002,phase:2,deny,status:403,msg:'XSS detected'"
```

or combine both

```
@import_preset crs

SecRule REQUEST_HEADERS:User-Agent "@pm firefox" "id:00001,phase:1,block,t:none,t:lowercase,msg:'someone used firefox to access',logdata:'someone used firefox to access',tag:'test',ver:'0.0.0-dev',status:403,severity:'CRITICAL'"
SecRule REQUEST_URI "@contains /admin" "id:1001,phase:1,deny,status:403,msg:'Admin access denied'"
SecRule ARGS "@rx <script>" "id:1002,phase:2,deny,status:403,msg:'XSS detected'"

SecRuleEngine On
```

## Analytics Events

The extension generates two types of analytics events:

- **CloudApimWafAuditEvent**: Audit events from SecLang rule execution
- **CloudApimWafTrailEvent**: Trail events with match details and blocking information

These events can be consumed by Otoroshi exporters for monitoring, alerting, and compliance purposes.

## API

The WAF configurations are exposed via the Otoroshi Admin API:

```
GET    /apis/waf.extensions.cloud-apim.com/v1/waf-configs
POST   /apis/waf.extensions.cloud-apim.com/v1/waf-configs
GET    /apis/waf.extensions.cloud-apim.com/v1/waf-configs/{id}
PUT    /apis/waf.extensions.cloud-apim.com/v1/waf-configs/{id}
DELETE /apis/waf.extensions.cloud-apim.com/v1/waf-configs/{id}
```

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
