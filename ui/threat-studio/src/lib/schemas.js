import { Resources } from './entities';

/**
 * What the studio lets you edit, per entity.
 *
 * Not every field: the ones that decide behaviour, in the order they are reasoned about, with the
 * help text that says what a number actually does. The full generated form stays one click away for
 * the rest — this is an edited view, not a second generator.
 *
 * Keys are the json keys of the entity. A dotted key reaches into a nested object.
 */

const ACTION_OPTIONS = [
  { value: 'monitor', label: 'Monitor — contribute a signal, never deny alone' },
  { value: 'block', label: 'Block — this source may deny on its own' },
];

const IDENTITY = [
  { value: 'auto', label: 'Auto — whatever identified the caller' },
  { value: 'ip', label: 'IP address' },
  { value: 'apikey', label: 'Api key' },
  { value: 'user', label: 'User' },
  { value: 'fingerprint', label: 'Fingerprint' },
];

const common = [
  { key: 'name', label: 'Name', type: 'text' },
  { key: 'description', label: 'Description', type: 'text' },
  { key: 'enabled', label: 'Enabled', type: 'bool', help: 'A disabled entity is not consulted at all' },
  { key: 'tags', label: 'Tags', type: 'lines', rows: 2, help: 'One per line' },
];

export const SCHEMAS = {
  /* ------------------------------------------------------------------ WAF config */
  'waf-configs': {
    resource: () => Resources.wafConfigs,
    label: 'WAF config',
    create: [
      { key: 'name', label: 'Name', type: 'text', placeholder: 'Public APIs baseline' },
      { key: 'description', label: 'Description', type: 'text' },
    ],
    sections: [
      {
        title: 'Identity',
        fields: common,
      },
      {
        title: 'Verdict',
        description: 'What the engine is allowed to do with what it finds.',
        fields: [
          {
            key: 'block',
            label: 'Blocking',
            type: 'bool',
            help: 'Off, the ruleset is evaluated and nothing is ever refused — which is what the "would have blocked" count measures.',
          },
          {
            key: 'oversize_body_action',
            label: 'Body over the limit',
            type: 'select',
            options: [
              { value: 'inspect_prefix', label: 'Inspect what fits, forward the rest' },
              { value: 'reject', label: 'Reject the request' },
            ],
            help: 'A verdict on a truncated body is weaker than one on a whole body; rejecting is the strict reading.',
          },
        ],
      },
      {
        title: 'Rules',
        description: 'Referenced rulesets run first, in order, then the inline rules below. SecLang is position-sensitive, so that order is the configuration.',
        fields: [
          {
            key: 'rulesets',
            label: 'Rulesets',
            type: 'refs',
            loader: () => Resources.wafRulesets.list(),
            placeholder: 'Add a ruleset…',
            help: 'Applied in this order, before the inline rules',
          },
          {
            key: 'rules',
            label: 'Inline rules',
            type: 'strings',
            placeholder: '@import_preset crs',
            addLabel: 'Add a rule',
            help: 'One entry per directive. A rule may span several lines.',
          },
        ],
      },
      {
        title: 'Core Rule Set',
        description: 'Read by CRS and by nothing else: a config that sets these without importing CRS emits no rule at all, and says so on compile.',
        fields: [
          {
            key: 'crs.paranoia_level',
            label: 'Paranoia level',
            type: 'number',
            min: 1,
            max: 4,
            help: 'How much of CRS runs. Each level catches more and objects more often — 1 is the sane start.',
          },
          {
            key: 'crs.detection_paranoia_level',
            label: 'Detection level',
            type: 'number',
            min: 1,
            max: 4,
            help: 'Runs rules up to this level without letting them affect the verdict. Never lower than the paranoia level, or they would have nothing to act on.',
          },
          { key: 'crs.inbound_threshold', label: 'Inbound threshold', type: 'number', help: 'Anomaly score at which a request is refused' },
          { key: 'crs.outbound_threshold', label: 'Outbound threshold', type: 'number', help: 'The same, for the response' },
          { key: 'crs.early_blocking', label: 'Early blocking', type: 'bool', help: 'Decide as soon as the threshold is reached rather than at the end of the phase' },
        ],
      },
      {
        title: 'Bodies',
        description: 'The limit is a bound on what is held in memory, not a trim afterwards: a 500 MB upload against a 2 MB limit costs 2 MB.',
        fields: [
          { key: 'inspect_input_body', label: 'Inspect the request body', type: 'bool' },
          { key: 'input_body_limit', label: 'Request body limit', type: 'number', placeholder: '2097152', help: 'In bytes. Empty means the built-in 2 MB cap, never "unlimited".' },
          { key: 'inspect_output_body', label: 'Inspect the response body', type: 'bool', help: 'Phase 3 and 4 rules that have never fired will start to.' },
          { key: 'output_body_limit', label: 'Response body limit', type: 'number', placeholder: '2097152' },
          {
            key: 'output_body_mimetypes',
            label: 'Response types inspected',
            type: 'lines',
            rows: 3,
            help: 'One per line, matched against the content type without its parameters. Empty means every type.',
          },
        ],
      },
    ],
  },

  /* ------------------------------------------------------------------ WAF ruleset */
  'waf-rulesets': {
    resource: () => Resources.wafRulesets,
    label: 'WAF ruleset',
    create: [
      { key: 'name', label: 'Name', type: 'text', placeholder: 'Shared exclusions' },
      { key: 'description', label: 'Description', type: 'text' },
    ],
    sections: [
      { title: 'Identity', fields: common },
      {
        title: 'Rules',
        description: 'A disabled ruleset contributes nothing, which is not the same as deleting it — the configs listing it keep their reference.',
        fields: [
          { key: 'rules', label: 'Rules', type: 'strings', placeholder: 'SecRuleUpdateTargetById 942100 "!ARGS:q"', addLabel: 'Add a rule' },
        ],
      },
    ],
  },

  /* ------------------------------------------------------------------ threat policy */
  'threat-policies': {
    resource: () => Resources.threatPolicies,
    label: 'Threat policy',
    create: [
      { key: 'name', label: 'Name', type: 'text', placeholder: 'Standard response' },
      { key: 'description', label: 'Description', type: 'text' },
    ],
    sections: [
      { title: 'Identity', fields: common },
      {
        title: 'Enforcement',
        fields: [
          {
            key: 'dry_run',
            label: 'Dry run',
            type: 'bool',
            help: 'On, every tier is computed and reported and nothing is applied. It is how the thresholds below become readable against real traffic before they start refusing it.',
          },
          {
            key: 'ban_identity',
            label: 'Ban against',
            type: 'select',
            options: IDENTITY,
            help: 'What a ban is issued against. Auto takes whatever identified the caller.',
          },
          {
            key: 'waf_match_weight',
            label: 'WAF match weight',
            type: 'number',
            help: 'What a WAF rule match that did NOT reach a block contributes. The weaker, below-threshold verdict — kept moderate so a lone sub-threshold match is recorded, not punished, and escalates only with corroboration.',
          },
          {
            key: 'waf_block_weight',
            label: 'WAF block weight',
            type: 'number',
            help: 'What a WAF block contributes to the score. The WAF stays the sole authority on its own blocking; this is what the rest of the fabric makes of it. Set it at or above your deny/ban tier for a block to refuse on its own.',
          },
          {
            key: 'waf_block_decisive',
            label: 'WAF block is decisive',
            type: 'bool',
            help: 'On, a WAF block reaches the top tier whatever the arithmetic — the fabric honours the engine’s own block instead of diluting it into a sum. Off by default, because putting the WAF in monitoring is a deliberate "do not act on it alone" that this overrides.',
          },
          {
            key: 'challenge_provider',
            label: 'Challenge provider',
            type: 'ref',
            loader: () => Resources.challengeProviders.list(),
            placeholder: 'First enabled one',
            help: 'What a challenge tier serves',
          },
        ],
      },
      {
        title: 'Tiers',
        description: 'The highest matching rung wins, so they can be listed in any order. A score below every rung does nothing at all.',
        fields: [
          {
            key: 'tiers',
            label: 'Tiers',
            type: 'objects',
            addLabel: 'Add a tier',
            empty: 'No tier: this policy decides nothing whatever the score.',
            fields: [
              { key: 'min_score', label: 'From score', type: 'number', default: 50, width: 100 },
              {
                key: 'action',
                label: 'Action',
                type: 'select',
                default: 'log',
                width: 140,
                options: ['log', 'tarpit', 'challenge', 'deny', 'ban'].map((v) => ({ value: v, label: v })),
              },
              { key: 'tarpit_millis', label: 'Tarpit (ms)', type: 'number', default: 3000, width: 110 },
              { key: 'ban_for_seconds', label: 'Ban (s)', type: 'number', default: 3600, width: 110 },
              { key: 'status', label: 'Status', type: 'number', default: 403, width: 90 },
            ],
          },
          {
            key: 'exemptions',
            label: 'Never acted on',
            type: 'lines',
            rows: 3,
            help: 'Addresses or ranges this policy leaves alone, one per line. Your own monitoring belongs here.',
          },
        ],
      },
    ],
  },

  /* ------------------------------------------------------------------ bot policy */
  'bot-policies': {
    resource: () => Resources.botPolicies,
    label: 'Bot policy',
    create: [
      { key: 'name', label: 'Name', type: 'text', placeholder: 'AI crawlers' },
      { key: 'description', label: 'Description', type: 'text' },
    ],
    sections: [
      { title: 'Identity', fields: common },
      {
        title: 'Verification',
        description: 'A crawler that claims to be somebody is checked by forward-confirmed reverse DNS before being believed.',
        fields: [
          { key: 'verify_known_bots', label: 'Verify known crawlers', type: 'bool' },
          {
            key: 'verified_bypass',
            label: 'Verified crawlers bypass',
            type: 'bool',
            help: 'A crawler that proved who it is skips the rest of the detection',
          },
          {
            key: 'impersonator_weight',
            label: 'Impersonator weight',
            type: 'number',
            help: 'What claiming to be a crawler and failing the check contributes. This is the signal the whole verification exists to produce.',
          },
          {
            key: 'impersonator_action',
            label: 'Impersonator action',
            type: 'select',
            options: ['monitor', 'deny', 'allow'].map((v) => ({ value: v, label: v })),
          },
          { key: 'unknown_bot_weight', label: 'Unknown crawler weight', type: 'number', help: 'An unrecognised crawler is not an attacker: 0 is the honest default' },
          { key: 'deny_status', label: 'Deny status', type: 'number' },
        ],
      },
      {
        title: 'Rules',
        description: 'Matched against the identified crawler. `name:x`, `category:x`, or `*` for everything.',
        fields: [
          {
            key: 'rules',
            label: 'Rules',
            type: 'objects',
            addLabel: 'Add a rule',
            empty: 'No rule: crawlers are identified and nothing is applied.',
            fields: [
              { key: 'target', label: 'Target', type: 'text', default: 'category:ai', grow: true },
              {
                key: 'action',
                label: 'Action',
                type: 'select',
                default: 'monitor',
                width: 120,
                options: ['allow', 'monitor', 'deny'].map((v) => ({ value: v, label: v })),
              },
              { key: 'weight', label: 'Weight', type: 'number', default: 0, width: 90 },
            ],
          },
        ],
      },
      {
        title: 'Published files',
        description: 'What the policy says about itself to anything that asks politely.',
        fields: [
          { key: 'robots_extra', label: 'Extra robots.txt', type: 'textarea', rows: 4, mono: true, help: 'Appended to the generated file' },
          { key: 'llms_txt', label: 'llms.txt', type: 'textarea', rows: 6, mono: true },
        ],
      },
    ],
  },

  /* ------------------------------------------------------------------ challenge provider */
  'challenge-providers': {
    resource: () => Resources.challengeProviders,
    label: 'Challenge provider',
    create: [{ key: 'name', label: 'Name', type: 'text', placeholder: 'Proof of work' }],
    sections: [
      { title: 'Identity', fields: common },
      {
        title: 'Kind',
        fields: [
          {
            key: 'kind',
            label: 'Kind',
            type: 'select',
            options: [
              { value: 'pow', label: 'Proof of work — self contained, no third party' },
              { value: 'vendor', label: 'Vendor widget' },
            ],
          },
        ],
      },
      {
        title: 'Proof of work',
        description: 'The difficulty is scaled by the threat score between these two, so a suspicious caller pays more than a merely unknown one.',
        fields: [
          { key: 'difficulty_floor', label: 'Difficulty floor', type: 'number', when: (v) => v.kind !== 'vendor' },
          { key: 'difficulty_ceiling', label: 'Difficulty ceiling', type: 'number', when: (v) => v.kind !== 'vendor' },
          { key: 'challenge_ttl_seconds', label: 'Puzzle lifetime (s)', type: 'number', when: (v) => v.kind !== 'vendor' },
        ],
      },
      {
        title: 'Vendor',
        description: 'Filled in from a preset. Confirm the urls against your account before enabling it.',
        fields: [
          { key: 'site_key', label: 'Site key', type: 'text', when: (v) => v.kind === 'vendor' },
          { key: 'secret_key', label: 'Secret key', type: 'secret', when: (v) => v.kind === 'vendor' },
          { key: 'verify_url', label: 'Verification url', type: 'text', when: (v) => v.kind === 'vendor' },
          { key: 'widget_script_url', label: 'Widget script', type: 'text', when: (v) => v.kind === 'vendor' },
          { key: 'widget_html', label: 'Widget html', type: 'textarea', rows: 3, mono: true, when: (v) => v.kind === 'vendor' },
          { key: 'response_field', label: 'Response field', type: 'text', when: (v) => v.kind === 'vendor' },
        ],
      },
      {
        title: 'Clearance',
        description: 'What a caller gets for solving one, and how hard it is to hand that on to somebody else.',
        fields: [
          { key: 'clearance_ttl_seconds', label: 'Clearance lifetime (s)', type: 'number' },
          { key: 'cookie_name', label: 'Cookie name', type: 'text' },
          { key: 'bind_ip', label: 'Bind to the address', type: 'bool', help: 'A clearance handed to another address stops working' },
          { key: 'bind_ua', label: 'Bind to the user agent', type: 'bool' },
          { key: 'secret', label: 'Signing secret', type: 'secret', help: 'Empty means one derived from the otoroshi secret' },
        ],
      },
      {
        title: 'Page',
        fields: [
          { key: 'title', label: 'Title', type: 'text' },
          { key: 'message', label: 'Message', type: 'textarea', rows: 3 },
        ],
      },
    ],
  },

  /* ------------------------------------------------------------------ honeypot */
  'honeypot-policies': {
    resource: () => Resources.honeypotPolicies,
    label: 'Honeypot policy',
    create: [
      { key: 'name', label: 'Name', type: 'text', placeholder: 'Decoy paths' },
      { key: 'description', label: 'Description', type: 'text' },
    ],
    sections: [
      { title: 'Identity', fields: common },
      {
        title: 'Decoys',
        description: 'Nothing legitimate requests a path that does not exist, so a hit here is intent rather than a heuristic. No traffic model, no false positive.',
        fields: [
          { key: 'paths', label: 'Paths', type: 'lines', rows: 6, help: 'One per line, for instance /.env or /wp-admin' },
          { key: 'weight', label: 'Weight', type: 'number', help: 'What touching one contributes to the score' },
          { key: 'action', label: 'Action', type: 'select', options: ['monitor', 'deny', 'ban'].map((v) => ({ value: v, label: v })) },
          { key: 'ban_for_seconds', label: 'Ban for (s)', type: 'number', when: (v) => v.action === 'ban' },
          { key: 'status', label: 'Answered with', type: 'number', help: '404 gives nothing away; 403 tells the caller they were seen' },
        ],
      },
      {
        title: 'Canary tokens',
        description: 'Values nobody legitimate holds. Seeing one come back means it was taken from somewhere it should not have been.',
        fields: [
          {
            key: 'canaries',
            label: 'Tokens',
            type: 'objects',
            addLabel: 'Add a token',
            empty: 'No canary token.',
            fields: [
              { key: 'value', label: 'Value', type: 'text', grow: true },
              { key: 'description', label: 'Description', type: 'text', grow: true },
              {
                key: 'where',
                label: 'Looked for in',
                type: 'select',
                default: 'any',
                width: 120,
                options: ['any', 'header', 'query', 'path'].map((v) => ({ value: v, label: v })),
              },
            ],
          },
        ],
      },
    ],
  },

  /* ------------------------------------------------------------------ threat feed */
  'threat-feeds': {
    resource: () => Resources.threatFeeds,
    label: 'Threat feed',
    create: [
      { key: 'name', label: 'Name', type: 'text' },
      { key: 'url', label: 'Url', type: 'text' },
    ],
    sections: [
      { title: 'Identity', fields: common },
      {
        title: 'Source',
        fields: [
          { key: 'url', label: 'Url', type: 'text' },
          { key: 'method', label: 'Method', type: 'select', options: ['GET', 'POST'].map((v) => ({ value: v, label: v })) },
          {
            key: 'format',
            label: 'Format',
            type: 'select',
            options: ['cidr_lines', 'csv', 'json_array', 'json_path', 'misp'].map((v) => ({ value: v, label: v })),
            help: 'How the body is parsed into ranges',
          },
          { key: 'options', label: 'Parser options', type: 'json', rows: 4, help: 'What the format needs — a column index, a json path' },
          { key: 'headers', label: 'Headers', type: 'json', rows: 3, help: 'For a source that needs a key' },
          { key: 'follow_redirects', label: 'Follow redirects', type: 'bool' },
        ],
      },
      {
        title: 'Refresh',
        description: 'A refresh that fails leaves the previous snapshot in place rather than emptying the set.',
        fields: [
          { key: 'refresh_interval_seconds', label: 'Every (s)', type: 'number' },
          { key: 'timeout_millis', label: 'Timeout (ms)', type: 'number' },
          { key: 'max_entries', label: 'Max entries', type: 'number', help: 'A bound on what one source may hold in memory' },
        ],
      },
      {
        title: 'What it contributes',
        fields: [
          { key: 'weight', label: 'Weight', type: 'number', help: 'What a match contributes to the score' },
          { key: 'action', label: 'Action', type: 'select', options: ACTION_OPTIONS },
          { key: 'tag', label: 'Signal tag', type: 'text', help: 'How this source appears in the analytics — this is what makes feeds comparable' },
        ],
      },
    ],
  },

  /* ------------------------------------------------------------------ crowdsec */
  'crowdsec-bouncers': {
    resource: () => Resources.crowdsecBouncers,
    label: 'CrowdSec bouncer',
    create: [
      { key: 'name', label: 'Name', type: 'text', placeholder: 'Local CrowdSec' },
      { key: 'lapi_url', label: 'API url', type: 'text', placeholder: 'http://127.0.0.1:8080' },
      { key: 'api_key', label: 'Bouncer key', type: 'secret', help: 'Created with `cscli bouncers add otoroshi`' },
    ],
    sections: [
      { title: 'Identity', fields: common },
      {
        title: 'Local API',
        fields: [
          { key: 'lapi_url', label: 'Url', type: 'text' },
          { key: 'api_key', label: 'Bouncer key', type: 'secret' },
          { key: 'poll_interval_seconds', label: 'Poll every (s)', type: 'number' },
          { key: 'timeout_millis', label: 'Timeout (ms)', type: 'number' },
          { key: 'scopes', label: 'Scopes', type: 'lines', rows: 2, help: 'Which decision scopes are consulted — ip, range' },
          { key: 'origins_filter', label: 'Origins', type: 'lines', rows: 2, help: 'Empty means every origin CrowdSec reports' },
        ],
      },
      {
        title: 'What it contributes',
        fields: [
          { key: 'weight', label: 'Weight', type: 'number' },
          { key: 'action', label: 'Action', type: 'select', options: ACTION_OPTIONS },
          { key: 'tag', label: 'Signal tag', type: 'text' },
        ],
      },
      {
        title: 'Reporting back',
        description: 'Sends what this gateway decided to the community list. Off by default: it publishes your decisions.',
        fields: [
          { key: 'push_enabled', label: 'Report decisions', type: 'bool' },
          { key: 'push_machine_id', label: 'Machine id', type: 'text', when: (v) => v.push_enabled },
          { key: 'push_password', label: 'Machine password', type: 'secret', when: (v) => v.push_enabled },
          { key: 'push_scenario', label: 'Scenario', type: 'text', when: (v) => v.push_enabled },
          { key: 'push_interval_seconds', label: 'Every (s)', type: 'number', when: (v) => v.push_enabled },
          { key: 'push_max_batch', label: 'Max batch', type: 'number', when: (v) => v.push_enabled },
          { key: 'push_with_decision', label: 'Include a decision', type: 'bool', when: (v) => v.push_enabled, help: 'Not only the alert: this asks other bouncers to act' },
          { key: 'push_decision_duration', label: 'Decision duration', type: 'text', when: (v) => v.push_enabled && v.push_with_decision },
          { key: 'push_waf_detections', label: 'Report WAF blocks', type: 'bool', when: (v) => v.push_enabled },
          { key: 'push_waf_monitored', label: 'Report WAF monitoring matches', type: 'bool', when: (v) => v.push_enabled && v.push_waf_detections, help: 'A monitored match is a weaker claim: it was never acted on here' },
        ],
      },
    ],
  },

  /* ------------------------------------------------------------------ asn database */
  'asn-databases': {
    resource: () => Resources.asnDatabases,
    label: 'ASN database',
    create: [
      { key: 'name', label: 'Name', type: 'text', placeholder: 'ASN database' },
      { key: 'url', label: 'Source url', type: 'text' },
    ],
    sections: [
      { title: 'Identity', fields: common },
      {
        title: 'Source',
        fields: [
          { key: 'url', label: 'Url', type: 'text' },
          { key: 'gzip', label: 'Gzipped', type: 'bool' },
          { key: 'format', label: 'Format', type: 'select', options: ['iptoasn_tsv'].map((v) => ({ value: v, label: v })) },
          { key: 'refresh_interval_seconds', label: 'Refresh every (s)', type: 'number' },
          { key: 'timeout_millis', label: 'Timeout (ms)', type: 'number' },
          { key: 'max_entries', label: 'Max entries', type: 'number' },
        ],
      },
      {
        title: 'Classification',
        description: 'Ordered: the first category whose organisation or ASN matches wins. A hosting network is not a reason to refuse a caller, it is a reason to weigh them differently.',
        fields: [
          {
            key: 'categories',
            label: 'Categories',
            type: 'objects',
            addLabel: 'Add a category',
            empty: 'No category: the database resolves networks and classifies nothing.',
            fields: [
              { key: 'name', label: 'Name', type: 'text', default: 'hosting', width: 120 },
              { key: 'weight', label: 'Weight', type: 'number', default: 15, width: 90 },
              {
                key: 'action',
                label: 'Action',
                type: 'select',
                default: 'monitor',
                width: 120,
                options: ['monitor', 'block'].map((v) => ({ value: v, label: v })),
              },
              { key: 'tag', label: 'Tag', type: 'text', default: '', width: 110 },
              { key: 'org_contains', label: 'Organisation contains', type: 'lines', rows: 3, grow: true },
              { key: 'asns', label: 'AS numbers', type: 'numbers', rows: 3, width: 140 },
            ],
          },
        ],
      },
    ],
  },
};

export function schemaOf(plural) {
  return SCHEMAS[plural];
}
