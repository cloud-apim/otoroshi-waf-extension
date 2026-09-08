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

const SecurityRoutes = [
  { path: '/extensions/cloud-apim/waf/security', component: (props) => React.createElement(SecurityDashboardPage, props, null) },
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
