# Cloud APIM - Otoroshi WAF Extension

A Web Application Firewall (WAF) extension for [Otoroshi](https://www.otoroshi.io/) that provides a JVM-native implementation of ModSecurity SecLang with the OWASP Core Rule Set (CRS) included.

This extension is built on top of of the following open-source Cloud APIM libraries:

- [seclang-engine](https://github.com/cloud-apim/seclang-engine) - A JVM-native implementation of the ModSecurity SecLang DSL
- [seclang-engine-coreruleset](https://github.com/cloud-apim/seclang-engine-coreruleset) - The OWASP Core Rule Set (CRS) packaged for seclang-engine
- [libinjection-jvm](https://github.com/cloud-apim/libinjection-jvm) - A JVM port of libinjection for SQL injection and XSS detection

## Features

- **ModSecurity SecLang support**: Native JVM implementation of the ModSecurity SecLang DSL
- **OWASP Core Rule Set (CRS)**: Embedded CRS preset for comprehensive protection against common web attacks
- **Request/Response inspection**: Inspect both incoming requests and outgoing responses
- **Configurable body inspection**: Control body inspection limits and MIME types
- **Blocking or monitoring mode**: Choose to block malicious requests or just log them
- **Analytics events**: WAF events are sent to Otoroshi analytics for monitoring and alerting

## Requirements

- Otoroshi 17.11.0 or later
- Java 11 or later

## Installation

1. Build the extension JAR:

```bash
sbt assembly
```

2. Copy the generated JAR (`target/scala-2.12/otoroshi-waf-extension-assembly_2.12-dev.jar`) to the Otoroshi classpath.

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
