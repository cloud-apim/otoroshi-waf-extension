// Threat policies, bans and incidents.
// Relies on the themed primitives defined in ReputationPages.js, which is injected first.

const SECURITY_BASE = '/extensions/cloud-apim/extensions/waf/security';

function securityCall(path, body) {
  return fetch(SECURITY_BASE + path, {
    method: body ? 'POST' : 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  }).then((r) => r.json());
}

const THREAT_ACTIONS = [
  { value: 'log', label: 'Log — record only' },
  { value: 'tarpit', label: 'Tarpit — answer slowly' },
  { value: 'deny', label: 'Deny — refuse this request' },
  { value: 'ban', label: 'Ban — refuse this caller for a while' },
];

class ThreatTiersEditor extends Component {
  change = (idx, field, value) => {
    const tiers = (this.props.value || []).map((t, i) => (i === idx ? Object.assign({}, t, { [field]: value }) : t));
    this.props.onChange(tiers);
  };

  add = () => {
    const tiers = (this.props.value || []).slice();
    tiers.push({ min_score: 50, action: 'log', tarpit_millis: 3000, ban_for_seconds: 3600, status: 403 });
    this.props.onChange(tiers);
  };

  remove = (idx) => this.props.onChange((this.props.value || []).filter((_, i) => i !== idx));

  renderTier = (tier, idx) => {
    const action = tier.action || 'log';
    const num = (field, label, width) =>
      React.createElement(
        'div',
        { key: field, style: { display: 'flex', flexDirection: 'column', gap: 2 } },
        React.createElement('span', { className: 'suite-meta' }, label),
        React.createElement('input', {
          type: 'number',
          className: 'form-control',
          style: { width: width || 110 },
          value: tier[field],
          onChange: (e) => this.change(idx, field, parseInt(e.target.value || '0', 10)),
        })
      );
    return suitePanel(
      'tier-' + idx,
      [
        React.createElement(
          'div',
          { key: 'row', style: { display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' } },
          num('min_score', 'At score ≥'),
          React.createElement(
            'div',
            { key: 'action', style: { display: 'flex', flexDirection: 'column', gap: 2 } },
            React.createElement('span', { className: 'suite-meta' }, 'do'),
            React.createElement(
              'select',
              {
                className: 'form-control',
                style: { width: 250 },
                value: action,
                onChange: (e) => this.change(idx, 'action', e.target.value),
              },
              THREAT_ACTIONS.map((a) => React.createElement('option', { key: a.value, value: a.value }, a.label))
            )
          ),
          action === 'tarpit' ? num('tarpit_millis', 'delay (ms)') : null,
          action === 'ban' ? num('ban_for_seconds', 'ban for (s)') : null,
          action === 'deny' || action === 'ban' ? num('status', 'status', 90) : null,
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-danger', type: 'button', onClick: () => this.remove(idx) },
            React.createElement('i', { className: 'fas fa-trash' }, null)
          )
        ),
      ],
      { marginBottom: 8 }
    );
  };

  render() {
    const tiers = (this.props.value || []).slice().sort((a, b) => (a.min_score || 0) - (b.min_score || 0));
    return React.createElement(
      'div',
      { className: 'row mb-3' },
      React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Tiers'),
      React.createElement(
        'div',
        { className: 'col-sm-10' },
        tiers.length
          ? tiers.map(this.renderTier)
          : suiteNotice('empty', 'info', 'No tier: this policy will never act on anything.'),
        React.createElement(
          'button',
          { className: 'btn btn-sm btn-success', type: 'button', onClick: this.add },
          React.createElement('i', { className: 'fas fa-plus' }, null),
          ' Add a tier'
        )
      )
    );
  }
}

class ThreatSimulator extends Component {
  state = { score: 75, result: null };

  run = () => {
    securityCall('/_simulate', {
      score: this.state.score,
      policy: this.props.rawValue && this.props.rawValue.id,
    }).then((r) => this.setState({ result: r }));
  };

  render() {
    const r = this.state.result;
    return [
      React.createElement(
        'div',
        { className: 'row mb-3', key: 'input' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'At score'),
        React.createElement(
          'div',
          { className: 'col-sm-10', style: { display: 'flex', gap: 8, alignItems: 'center' } },
          React.createElement('input', {
            type: 'number',
            className: 'form-control',
            style: { width: 120 },
            value: this.state.score,
            onChange: (e) => this.setState({ score: parseInt(e.target.value || '0', 10) }),
          }),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-success', type: 'button', onClick: this.run },
            React.createElement('i', { className: 'fas fa-play' }, null),
            ' What would happen?'
          )
        )
      ),
      r && r.done
        ? React.createElement(
            'div',
            { className: 'row mb-3', key: 'out' },
            React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
            React.createElement(
              'div',
              { className: 'col-sm-10' },
              suiteNotice(
                'sim',
                r.enforced ? 'danger' : r.action === 'allow' ? 'success' : 'warning',
                r.action +
                  (r.tier === null || r.tier === undefined ? ' — below every tier' : ' — tier ' + r.tier) +
                  (r.enforced ? ' — enforced' : ' — recorded only (dry run)')
              )
            )
          )
        : null,
    ];
  }
}

class ThreatPoliciesPage extends Component {
  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: { type: 'string', props: { label: 'Name' } },
    description: { type: 'string', props: { label: 'Description' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    dry_run: {
      type: 'bool',
      props: {
        label: 'Dry run',
        help: 'On: every decision is recorded and none is enforced. Leave it on until the events look right.',
      },
    },
    tiers: { type: ThreatTiersEditor, props: {} },
    exemptions: {
      type: 'array',
      props: { label: 'Never scored', help: 'Addresses and CIDR blocks that bypass the fabric entirely' },
    },
    ban_identity: {
      type: 'select',
      props: {
        label: 'Ban targets',
        help: "'auto' bans the most specific identity known — an apikey rather than the shared address behind it",
        possibleValues: [
          { label: 'Auto — most specific', value: 'auto' },
          { label: 'Always the ip', value: 'ip' },
          { label: 'Always the apikey', value: 'apikey' },
        ],
      },
    },
    waf_block_weight: {
      type: 'number',
      props: { label: 'WAF block weight', help: 'What a WAF denial contributes to the score' },
    },
    simulate: { type: ThreatSimulator, props: {} },
  };

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'Enabled', filterId: 'enabled', content: (item) => (item.enabled ? 'Yes' : 'No'), style: { textAlign: 'center', width: 80 } },
    { title: 'Dry run', filterId: 'dry_run', content: (item) => (item.dry_run ? 'Yes' : 'No'), style: { textAlign: 'center', width: 90 } },
    { title: 'Tiers', content: (item) => (item.tiers || []).map((t) => t.min_score + '→' + t.action).join(', ') },
  ];

  formFlow = [
    '_loc',
    'id',
    'name',
    'description',
    '>>>Metadata and tags',
    'tags',
    'metadata',
    '<<<Policy',
    'enabled',
    'dry_run',
    'ban_identity',
    'waf_block_weight',
    '<<<Escalation',
    'tiers',
    '>>>Never scored',
    'exemptions',
    '>>>Simulate',
    'simulate',
  ];

  componentDidMount() {
    this.props.setTitle('Threat policies');
  }

  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'threat-policies');

  render() {
    return React.createElement(
      Table,
      {
        parentProps: this.props,
        selfUrl: 'extensions/cloud-apim/waf/threatpolicies',
        defaultTitle: 'All threat policies',
        defaultValue: () => ({
          id: 'threat-policy_' + uuid(),
          name: 'New threat policy',
          description: 'How an accumulated threat score turns into an action',
          tags: [],
          metadata: {},
          enabled: true,
          dry_run: true,
          tiers: [
            { min_score: 40, action: 'log', tarpit_millis: 3000, ban_for_seconds: 3600, status: 403 },
            { min_score: 70, action: 'tarpit', tarpit_millis: 3000, ban_for_seconds: 3600, status: 403 },
            { min_score: 90, action: 'ban', tarpit_millis: 3000, ban_for_seconds: 3600, status: 403 },
          ],
          exemptions: [],
          ban_identity: 'auto',
          waf_block_weight: 50,
        }),
        itemName: 'Threat policy',
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/waf/threatpolicies/edit/${item.id}`;
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/waf/threatpolicies/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: 'waf.extensions.cloud-apim.com/ThreatPolicy',
      },
      null
    );
  }
}

class SecurityDashboardPage extends Component {
  state = { status: null, bans: [], incidents: [], error: null };

  componentDidMount() {
    this.props.setTitle('Bans & incidents');
    this.load();
    this.timer = setInterval(this.load, 10000);
  }

  componentWillUnmount() {
    if (this.timer) clearInterval(this.timer);
  }

  load = () => {
    Promise.all([securityCall('/_status'), securityCall('/_bans'), securityCall('/_incidents')])
      .then(([status, bans, incidents]) =>
        this.setState({ status, bans: (bans && bans.bans) || [], incidents: (incidents && incidents.incidents) || [] })
      )
      .catch((e) => this.setState({ error: String(e.message || e) }));
  };

  unban = (ref) => securityCall('/_unban', { ref: ref }).then(this.load);

  renderStatus = () => {
    const s = this.state.status;
    if (!s) return null;
    const rows = [
      ['Node', s.node],
      ['Bans held', String((s.bans && s.bans.bans) || 0)],
      ['Ban list refreshed', reputationAgo(s.bans && s.bans.last_refresh)],
      ['Shared state', s.shared_state && s.shared_state.dedicated_redis ? 'dedicated redis' : 'otoroshi storage'],
      ['Incidents held', String((s.incidents && s.incidents.held) || 0)],
      ['Ledger', s.ledger && s.ledger.enabled ? 'on — bans at ' + s.ledger.ban_threshold : 'off'],
    ];
    return suitePanel('status', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'This node'),
      suiteRows(rows, 200),
      s.bans && s.bans.last_error ? suiteNotice('err', 'danger', s.bans.last_error) : null,
      s.shared_state && !s.shared_state.dedicated_redis
        ? suiteNotice(
            'shared',
            'warning',
            'Bans are stored in the Otoroshi storage backend. They are only shared between nodes if that backend is. Set security.redis-uri to guarantee it.'
          )
        : null,
    ]);
  };

  renderBans = () => {
    const bans = this.state.bans;
    return suitePanel('bans', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'Active bans (' + bans.length + ')'),
      bans.length === 0
        ? suiteNotice('none', 'success', 'Nobody is banned right now.')
        : React.createElement(
            'div',
            { key: 'list' },
            bans.map((b) =>
              React.createElement(
                'div',
                {
                  key: b.key,
                  className: 'suite-row',
                  style: { alignItems: 'center', borderBottom: '1px solid var(--border-color)' },
                },
                React.createElement('span', { style: { width: 220 } }, b.key),
                React.createElement('span', { style: { flex: 1 } }, b.reason),
                suiteBadge('score-' + b.key, 'score ' + b.score, b.score >= 90 ? 'danger' : 'warning'),
                React.createElement('span', { className: 'suite-meta', style: { width: 120 } }, 'ends ' + reputationAgo(b.until)),
                React.createElement(
                  'button',
                  { className: 'btn btn-sm btn-danger', type: 'button', onClick: () => this.unban(b.key) },
                  ' Unban'
                )
              )
            )
          ),
    ]);
  };

  renderIncidents = () => {
    const incidents = this.state.incidents;
    return suitePanel('incidents', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'Incidents (' + incidents.length + ')'),
      React.createElement(
        'div',
        { key: 'lede', className: 'suite-meta', style: { marginBottom: 10 } },
        'One run of activity from one caller, collapsed. Alert on these rather than on individual matches.'
      ),
      incidents.length === 0
        ? suiteNotice('none', 'success', 'Nothing correlated in the current window.')
        : React.createElement(
            'div',
            { key: 'list' },
            incidents.map((i) =>
              React.createElement(
                'div',
                { key: i.id, className: 'suite-row', style: { alignItems: 'center', borderBottom: '1px solid var(--border-color)' } },
                React.createElement('span', { style: { width: 220 } }, i.key),
                React.createElement('span', { style: { flex: 1 } }, i.last_message),
                suiteBadge('c-' + i.id, i.count + ' events', 'neutral'),
                suiteBadge('s-' + i.id, 'max ' + i.max_score, i.max_score >= 90 ? 'danger' : 'warning'),
                React.createElement('span', { className: 'suite-meta', style: { width: 110 } }, reputationAgo(i.last_seen))
              )
            )
          ),
    ]);
  };

  render() {
    return React.createElement(
      'div',
      {},
      this.state.error ? suiteNotice('e', 'danger', this.state.error) : null,
      this.renderStatus(),
      this.renderBans(),
      this.renderIncidents()
    );
  }
}


// A generic editor for an array of flat objects, described by a field spec.
// Used by bot rules and canary tokens rather than writing a bespoke component for each.
function suiteListEditor(label, help, fields, makeEmpty) {
  return class extends Component {
    change = (idx, key, value) =>
      this.props.onChange((this.props.value || []).map((r, i) => (i === idx ? Object.assign({}, r, { [key]: value }) : r)));
    add = () => this.props.onChange((this.props.value || []).concat([makeEmpty()]));
    remove = (idx) => this.props.onChange((this.props.value || []).filter((_, i) => i !== idx));

    field = (row, idx, f) => {
      const common = {
        className: 'form-control',
        style: { width: f.width || 160 },
        value: row[f.key] === undefined || row[f.key] === null ? '' : row[f.key],
        placeholder: f.placeholder || '',
        onChange: (e) =>
          this.change(idx, f.key, f.type === 'number' ? parseInt(e.target.value || '0', 10) : e.target.value),
      };
      const input =
        f.type === 'select'
          ? React.createElement(
              'select',
              common,
              (f.options || []).map((o) => React.createElement('option', { key: o, value: o }, o))
            )
          : React.createElement('input', Object.assign({ type: f.type === 'number' ? 'number' : 'text' }, common));
      return React.createElement(
        'div',
        { key: f.key, style: { display: 'flex', flexDirection: 'column', gap: 2 } },
        React.createElement('span', { className: 'suite-meta' }, f.label),
        input
      );
    };

    render() {
      const rows = this.props.value || [];
      return React.createElement(
        'div',
        { className: 'row mb-3' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, label),
        React.createElement(
          'div',
          { className: 'col-sm-10' },
          help ? suiteNotice('help', 'info', help) : null,
          rows.map((row, idx) =>
            suitePanel('row-' + idx, [
              React.createElement(
                'div',
                { key: 'f', style: { display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' } },
                fields.map((f) => this.field(row, idx, f)),
                React.createElement(
                  'button',
                  { className: 'btn btn-sm btn-danger', type: 'button', style: { marginLeft: 'auto' }, onClick: () => this.remove(idx) },
                  React.createElement('i', { className: 'fas fa-trash' }, null)
                )
              ),
            ], { marginBottom: 8 })
          ),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-success', type: 'button', onClick: this.add },
            React.createElement('i', { className: 'fas fa-plus' }, null),
            ' Add'
          )
        )
      );
    }
  };
}

const BotRulesEditor = suiteListEditor(
  'Rules',
  'A name rule wins over a category rule. To challenge a crawler, give it a weight that reaches a challenge tier in your threat policy — there is one challenge implementation and it lives there.',
  [
    { key: 'target', label: 'Target', width: 220, placeholder: 'category:ai or name:gptbot' },
    { key: 'action', label: 'Action', type: 'select', width: 140, options: ['allow', 'monitor', 'deny'] },
    { key: 'weight', label: 'Weight', type: 'number', width: 110 },
  ],
  () => ({ target: 'category:ai', action: 'monitor', weight: 0 })
);

const CanariesEditor = suiteListEditor(
  'Canary tokens',
  'A value nobody can hold without having taken it. Plant a fake apikey or record id, and presenting it becomes proof rather than suspicion.',
  [
    { key: 'value', label: 'Value', width: 240, placeholder: 'CANARY-a1b2c3' },
    { key: 'description', label: 'Description', width: 240, placeholder: 'planted in the public config' },
    { key: 'where', label: 'Look in', type: 'select', width: 130, options: ['any', 'header', 'query', 'path'] },
  ],
  () => ({ value: '', description: '', where: 'any' })
);

class BotSignaturesSummary extends Component {
  render() {
    const sigs = this.props.value || [];
    const byCategory = {};
    sigs.forEach((s) => {
      byCategory[s.category] = byCategory[s.category] || [];
      byCategory[s.category].push(s);
    });
    return React.createElement(
      'div',
      { className: 'row mb-3' },
      React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Known bots'),
      React.createElement(
        'div',
        { className: 'col-sm-10' },
        suiteNotice(
          'sig',
          'info',
          sigs.length + ' signatures. Edit them through the admin api or the export — a form is the wrong tool for a catalog this size.'
        ),
        Object.keys(byCategory).sort().map((cat) =>
          suitePanel('cat-' + cat, [
            React.createElement('div', { key: 't', className: 'suite-title' }, cat + ' (' + byCategory[cat].length + ')'),
            React.createElement(
              'div',
              { key: 'b' },
              byCategory[cat].map((s) =>
                suiteBadge('s-' + s.name, s.name + (s.verifiable ? ' ✓' : ''), s.verifiable ? 'success' : 'neutral')
              )
            ),
            React.createElement(
              'div',
              { key: 'l', className: 'suite-meta', style: { marginTop: 8 } },
              '✓ publishes a way to verify it. The rest can only be identified by a string they choose to send.'
            ),
          ], { marginBottom: 8 })
        )
      )
    );
  }
}

class RobotsPreview extends Component {
  state = { txt: null };
  load = () => {
    securityCall('/_robots_txt', { policy: this.props.rawValue && this.props.rawValue.id }).then((r) =>
      this.setState({ txt: r.done ? r.robots_txt : r.error })
    );
  };
  render() {
    return [
      React.createElement(
        'div',
        { className: 'row mb-3', key: 'b' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'robots.txt'),
        React.createElement(
          'div',
          { className: 'col-sm-10' },
          suiteNotice(
            'gen',
            'info',
            'Generated from the rules above, so the file and the gateway cannot drift apart. Serve it with Otoroshi\'s own Robots plugin.'
          ),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-success', type: 'button', onClick: this.load },
            React.createElement('i', { className: 'fas fa-file-alt' }, null),
            ' Generate'
          ),
          this.state.txt ? suiteCode('txt', this.state.txt) : null
        )
      ),
    ];
  }
}

class ChallengePresetPicker extends Component {
  state = { entries: [], created: null, error: null };
  componentDidMount() {
    securityCall('/_challenge_presets').then((r) => this.setState({ entries: (r && r.entries) || [] }));
  }
  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'challenge-providers');
  create = (entry) => {
    securityCall('/_challenge_from_preset', { preset: entry.id })
      .then((r) => {
        if (!r.done) throw new Error(r.error);
        return this.client.create(r.provider);
      })
      .then((c) => this.setState({ created: c.id }))
      .catch((e) => this.setState({ error: String(e.message || e) }));
  };
  render() {
    return React.createElement(
      'div',
      {},
      suiteNotice(
        'intro',
        'info',
        'The built-in proof of work needs none of this. These are for deployments whose procurement requires a named product — every one is created disabled until you paste your own keys in.'
      ),
      this.state.error ? suiteNotice('err', 'danger', this.state.error) : null,
      this.state.created
        ? suiteNotice('ok', 'success', 'Created. Open it from the Challenge providers list to add your keys.')
        : null,
      this.state.entries.map((e) =>
        suitePanel(e.id, [
          React.createElement('div', { key: 't', className: 'suite-title' }, e.name),
          React.createElement('div', { key: 'o', style: { marginBottom: 8 } }, suiteBadge('o-' + e.id, e.origin, 'neutral')),
          React.createElement('div', { key: 'd', style: { marginBottom: 8 } }, e.description),
          e.notes ? suiteNotice('n-' + e.id, 'warning', e.notes) : null,
          React.createElement(
            'button',
            { key: 'c', className: 'btn btn-sm btn-success', type: 'button', onClick: () => this.create(e) },
            React.createElement('i', { className: 'fas fa-plus' }, null),
            ' Create a provider from this preset'
          ),
        ])
      )
    );
  }
}

class ChallengeProvidersPage extends Component {
  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id' } },
    name: { type: 'string', props: { label: 'Name' } },
    description: { type: 'string', props: { label: 'Description' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    kind: {
      type: 'select',
      props: {
        label: 'Kind',
        help: 'pow is self-contained: no third party, no external script, no personal data',
        possibleValues: [{ label: 'Proof of work (built in)', value: 'pow' }, { label: 'Vendor widget', value: 'vendor' }],
      },
    },
    difficulty_floor: { type: 'number', props: { label: 'Difficulty floor', help: 'Leading zero bits at score 0' } },
    difficulty_ceiling: { type: 'number', props: { label: 'Difficulty ceiling', help: 'At score 100. Each bit doubles the work.' } },
    challenge_ttl_seconds: { type: 'number', props: { label: 'Puzzle lifetime', suffix: 'seconds' } },
    clearance_ttl_seconds: { type: 'number', props: { label: 'Clearance lifetime', suffix: 'seconds' } },
    cookie_name: { type: 'string', props: { label: 'Cookie name' } },
    secret: { type: 'password', props: { label: 'Signing secret', help: 'Defaults to the Otoroshi secret. Use a vault reference.' } },
    bind_ip: { type: 'bool', props: { label: 'Bind clearance to the address' } },
    bind_ua: { type: 'bool', props: { label: 'Bind clearance to the user agent' } },
    widget_script_url: { type: 'string', props: { label: 'Widget script url' } },
    widget_html: { type: 'string', props: { label: 'Widget html', help: '__SITE_KEY__ is replaced' } },
    response_field: { type: 'string', props: { label: 'Response field name' } },
    verify_url: { type: 'string', props: { label: 'Siteverify url' } },
    site_key: { type: 'string', props: { label: 'Site key' } },
    secret_key: { type: 'password', props: { label: 'Secret key' } },
    title: { type: 'string', props: { label: 'Page title' } },
    message: { type: 'string', props: { label: 'Page message' } },
  };
  columns = [
    { title: 'Name', filterId: 'name', content: (i) => i.name },
    { title: 'Kind', filterId: 'kind', content: (i) => i.kind, style: { width: 100 } },
    { title: 'Enabled', content: (i) => (i.enabled ? 'Yes' : 'No'), style: { textAlign: 'center', width: 80 } },
  ];
  formFlow = [
    '_loc', 'id', 'name', 'description', '>>>Metadata and tags', 'tags', 'metadata',
    '<<<Challenge', 'enabled', 'kind', 'title', 'message',
    '>>>Proof of work', 'difficulty_floor', 'difficulty_ceiling', 'challenge_ttl_seconds',
    '>>>Clearance', 'clearance_ttl_seconds', 'cookie_name', 'secret', 'bind_ip', 'bind_ua',
    '>>>Vendor widget', 'widget_script_url', 'widget_html', 'response_field', 'verify_url', 'site_key', 'secret_key',
  ];
  componentDidMount() { this.props.setTitle('Challenge providers'); }
  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'challenge-providers');
  render() {
    return React.createElement(Table, {
      parentProps: this.props,
      selfUrl: 'extensions/cloud-apim/waf/challengeproviders',
      defaultTitle: 'All challenge providers',
      defaultValue: () => ({
        id: 'challenge-provider_' + uuid(), name: 'Proof of work',
        description: 'Self-contained proof-of-work challenge, no third party involved',
        tags: [], metadata: {}, enabled: true, kind: 'pow',
        difficulty_floor: 18, difficulty_ceiling: 24, challenge_ttl_seconds: 300,
        clearance_ttl_seconds: 1800, cookie_name: 'cloud-apim-clearance', secret: '',
        bind_ip: true, bind_ua: true, widget_script_url: '', widget_html: '', response_field: '',
        verify_url: '', site_key: '', secret_key: '',
        title: 'Checking your browser',
        message: 'This will take a moment. No data about you leaves this page.',
      }),
      itemName: 'Challenge provider',
      formSchema: this.formSchema, formFlow: this.formFlow, columns: this.columns,
      stayAfterSave: true,
      fetchItems: () => this.client.findAll(),
      updateItem: this.client.update, deleteItem: this.client.delete, createItem: this.client.create,
      navigateTo: (i) => { window.location = `/bo/dashboard/extensions/cloud-apim/waf/challengeproviders/edit/${i.id}`; },
      itemUrl: (i) => `/bo/dashboard/extensions/cloud-apim/waf/challengeproviders/edit/${i.id}`,
      showActions: true, showLink: true, rowNavigation: true, extractKey: (i) => i.id, export: true,
      kubernetesKind: 'waf.extensions.cloud-apim.com/ChallengeProvider',
    }, null);
  }
}

class ChallengePresetsPage extends Component {
  componentDidMount() { this.props.setTitle('Challenge presets'); }
  render() { return React.createElement(ChallengePresetPicker, this.props, null); }
}

class BotPoliciesPage extends Component {
  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id' } },
    name: { type: 'string', props: { label: 'Name' } },
    description: { type: 'string', props: { label: 'Description' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    verify_known_bots: { type: 'bool', props: { label: 'Verify claimed crawlers', help: 'Forward-confirmed reverse DNS' } },
    verified_bypass: { type: 'bool', props: { label: 'Get out of a verified crawler\'s way' } },
    impersonator_weight: { type: 'number', props: { label: 'Impersonator weight', help: 'A failed verification is a demonstrated lie' } },
    impersonator_action: { type: 'select', props: { label: 'Impersonator action', possibleValues: [{ label: 'Deny', value: 'deny' }, { label: 'Score only', value: 'monitor' }] } },
    unknown_bot_weight: { type: 'number', props: { label: 'Unknown bot weight' } },
    deny_status: { type: 'number', props: { label: 'Denied status' } },
    rules: { type: BotRulesEditor, props: {} },
    signatures: { type: BotSignaturesSummary, props: {} },
    robots_extra: { type: 'text', props: { label: 'Extra robots.txt directives' } },
    llms_txt: { type: 'text', props: { label: 'llms.txt content' } },
    robots: { type: RobotsPreview, props: {} },
  };
  columns = [
    { title: 'Name', filterId: 'name', content: (i) => i.name },
    { title: 'Enabled', content: (i) => (i.enabled ? 'Yes' : 'No'), style: { textAlign: 'center', width: 80 } },
    { title: 'Rules', content: (i) => (i.rules || []).map((r) => r.target + '→' + r.action).join(', ') },
  ];
  formFlow = [
    '_loc', 'id', 'name', 'description', '>>>Metadata and tags', 'tags', 'metadata',
    '<<<Verification', 'enabled', 'verify_known_bots', 'verified_bypass', 'impersonator_weight', 'impersonator_action',
    '<<<Policy', 'rules', 'unknown_bot_weight', 'deny_status',
    '<<<Declarations', 'robots_extra', 'llms_txt', 'robots',
    '>>>Known bots', 'signatures',
  ];
  componentDidMount() { this.props.setTitle('Bot policies'); }
  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'bot-policies');
  render() {
    return React.createElement(Table, {
      parentProps: this.props,
      selfUrl: 'extensions/cloud-apim/waf/botpolicies',
      defaultTitle: 'All bot policies',
      defaultValue: () => ({
        id: 'bot-policy_' + uuid(), name: 'Bot policy',
        description: 'Verify the crawlers that claim to be somebody, and decide what the rest may do',
        tags: [], metadata: {}, enabled: true,
        rules: [
          { target: 'category:search', action: 'allow', weight: 0 },
          { target: 'category:monitoring', action: 'allow', weight: 0 },
          { target: 'category:ai', action: 'monitor', weight: 0 },
          { target: 'category:seo', action: 'monitor', weight: 10 },
        ],
        verify_known_bots: true, verified_bypass: true, impersonator_weight: 60,
        impersonator_action: 'deny', unknown_bot_weight: 0, deny_status: 403,
        robots_extra: '', llms_txt: '',
      }),
      itemName: 'Bot policy',
      formSchema: this.formSchema, formFlow: this.formFlow, columns: this.columns,
      stayAfterSave: true,
      fetchItems: () => this.client.findAll(),
      updateItem: this.client.update, deleteItem: this.client.delete, createItem: this.client.create,
      navigateTo: (i) => { window.location = `/bo/dashboard/extensions/cloud-apim/waf/botpolicies/edit/${i.id}`; },
      itemUrl: (i) => `/bo/dashboard/extensions/cloud-apim/waf/botpolicies/edit/${i.id}`,
      showActions: true, showLink: true, rowNavigation: true, extractKey: (i) => i.id, export: true,
      kubernetesKind: 'waf.extensions.cloud-apim.com/BotPolicy',
    }, null);
  }
}

class HoneypotPoliciesPage extends Component {
  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id' } },
    name: { type: 'string', props: { label: 'Name' } },
    description: { type: 'string', props: { label: 'Description' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    paths: { type: 'array', props: { label: 'Paths', help: 'Exact, or a trailing * for a prefix. Keep them unambiguous.' } },
    weight: { type: 'number', props: { label: 'Weight' } },
    action: { type: 'select', props: { label: 'Action', possibleValues: [{ label: 'Deny', value: 'deny' }, { label: 'Ban', value: 'ban' }, { label: 'Score only', value: 'monitor' }] } },
    ban_for_seconds: { type: 'number', props: { label: 'Ban for', suffix: 'seconds' } },
    status: { type: 'number', props: { label: 'Status', help: '404 by default — a 403 would confirm something is there' } },
    canaries: { type: CanariesEditor, props: {} },
  };
  columns = [
    { title: 'Name', filterId: 'name', content: (i) => i.name },
    { title: 'Enabled', content: (i) => (i.enabled ? 'Yes' : 'No'), style: { textAlign: 'center', width: 80 } },
    { title: 'Paths', content: (i) => String((i.paths || []).length) + ' paths', style: { width: 110 } },
    { title: 'Action', content: (i) => i.action, style: { width: 90 } },
  ];
  formFlow = [
    '_loc', 'id', 'name', 'description', '>>>Metadata and tags', 'tags', 'metadata',
    '<<<Paths', 'enabled', 'paths', '>>>Response', 'action', 'status', 'weight', 'ban_for_seconds',
    '<<<Canary tokens', 'canaries',
  ];
  componentDidMount() { this.props.setTitle('Honeypots'); }
  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'honeypot-policies');
  render() {
    return React.createElement(Table, {
      parentProps: this.props,
      selfUrl: 'extensions/cloud-apim/waf/honeypots',
      defaultTitle: 'All honeypot policies',
      defaultValue: () => ({
        id: 'honeypot-policy_' + uuid(), name: 'Honeypot policy',
        description: 'Paths nobody legitimate asks for, and values nobody legitimate holds',
        tags: [], metadata: {}, enabled: true,
        paths: ['/.env', '/.git/config', '/wp-login.php', '/wp-admin*', '/phpmyadmin*', '/actuator/env'],
        weight: 100, action: 'deny', ban_for_seconds: 86400, status: 404, canaries: [],
      }),
      itemName: 'Honeypot policy',
      formSchema: this.formSchema, formFlow: this.formFlow, columns: this.columns,
      stayAfterSave: true,
      fetchItems: () => this.client.findAll(),
      updateItem: this.client.update, deleteItem: this.client.delete, createItem: this.client.create,
      navigateTo: (i) => { window.location = `/bo/dashboard/extensions/cloud-apim/waf/honeypots/edit/${i.id}`; },
      itemUrl: (i) => `/bo/dashboard/extensions/cloud-apim/waf/honeypots/edit/${i.id}`,
      showActions: true, showLink: true, rowNavigation: true, extractKey: (i) => i.id, export: true,
      kubernetesKind: 'waf.extensions.cloud-apim.com/HoneypotPolicy',
    }, null);
  }
}

const SecurityFeatures = [
  {
    title: 'Threat policies',
    description: 'How an accumulated threat score turns into an action',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/threatpolicies',
    display: () => true,
    icon: () => 'fa-sliders-h',
  },
  {
    title: 'Challenge providers',
    description: 'Proof of work, and vendor widgets for procurement',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/challengeproviders',
    display: () => true,
    icon: () => 'fa-puzzle-piece',
  },
  {
    title: 'Bot policies',
    description: 'Verify crawlers, and decide what AI agents may do',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/botpolicies',
    display: () => true,
    icon: () => 'fa-robot',
  },
  {
    title: 'Honeypots',
    description: 'Paths and canary tokens that prove intent',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/honeypots',
    display: () => true,
    icon: () => 'fa-bug',
  },
  {
    title: 'Bans & incidents',
    description: 'Who is banned, why, and what is happening right now',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/security',
    display: () => true,
    icon: () => 'fa-gavel',
  },
];

const SecuritySidebarItems = [
  { title: 'Threat policies', text: 'Score to action', path: 'extensions/cloud-apim/waf/threatpolicies', icon: 'sliders-h' },
  { title: 'Challenge providers', text: 'Proof of work and widgets', path: 'extensions/cloud-apim/waf/challengeproviders', icon: 'puzzle-piece' },
  { title: 'Challenge presets', text: 'Vendor presets', path: 'extensions/cloud-apim/waf/challengepresets', icon: 'store' },
  { title: 'Bot policies', text: 'Crawlers and AI agents', path: 'extensions/cloud-apim/waf/botpolicies', icon: 'robot' },
  { title: 'Honeypots', text: 'Paths and canary tokens', path: 'extensions/cloud-apim/waf/honeypots', icon: 'bug' },
  { title: 'Bans & incidents', text: 'Live security state', path: 'extensions/cloud-apim/waf/security', icon: 'gavel' },
];

const SecuritySearchItems = [
  {
    action: () => {
      window.location.href = '/bo/dashboard/extensions/cloud-apim/waf/threatpolicies';
    },
    env: React.createElement('span', { className: 'fas fa-sliders-h' }, null),
    label: 'Cloud APIM Security Suite - Threat policies',
    value: 'threatpolicies',
  },
  {
    action: () => {
      window.location.href = '/bo/dashboard/extensions/cloud-apim/waf/security';
    },
    env: React.createElement('span', { className: 'fas fa-gavel' }, null),
    label: 'Cloud APIM Security Suite - Bans & incidents',
    value: 'bans',
  },
];

function suiteEntityRoutes(path, page) {
  return [
    { path: '/extensions/cloud-apim/waf/' + path + '/:taction/:titem', component: (props) => React.createElement(page, props, null) },
    { path: '/extensions/cloud-apim/waf/' + path + '/:taction', component: (props) => React.createElement(page, props, null) },
    { path: '/extensions/cloud-apim/waf/' + path, component: (props) => React.createElement(page, props, null) },
  ];
}

const SecurityRoutes = [
  { path: '/extensions/cloud-apim/waf/security', component: (props) => React.createElement(SecurityDashboardPage, props, null) },
  { path: '/extensions/cloud-apim/waf/challengepresets', component: (props) => React.createElement(ChallengePresetsPage, props, null) },
  ...suiteEntityRoutes('challengeproviders', ChallengeProvidersPage),
  ...suiteEntityRoutes('botpolicies', BotPoliciesPage),
  ...suiteEntityRoutes('honeypots', HoneypotPoliciesPage),
  {
    path: '/extensions/cloud-apim/waf/threatpolicies/:taction/:titem',
    component: (props) => React.createElement(ThreatPoliciesPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/threatpolicies/:taction',
    component: (props) => React.createElement(ThreatPoliciesPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/threatpolicies',
    component: (props) => React.createElement(ThreatPoliciesPage, props, null),
  },
];
