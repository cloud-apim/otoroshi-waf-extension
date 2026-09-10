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

// ---------------------------------------------------------------------------------------------
// OPS-7 — the operator's console during an incident.
//
// Three registers, in the order an incident is worked: what the fabric is holding right now
// (bans), what it is still watching (incidents), and what it has been told to leave alone
// (allowlist). Every row can be opened for its evidence, and every action is one click from the
// evidence rather than from a separate form.
// ---------------------------------------------------------------------------------------------

const BAN_EXTENSIONS = [
  { label: '+1h', seconds: 3600 },
  { label: '+24h', seconds: 86400 },
  { label: '+7d', seconds: 604800 },
];

const INCIDENT_FILTERS = [
  { value: 'active', label: 'Needs attention' },
  { value: 'all', label: 'All' },
  { value: 'open', label: 'Open' },
  { value: 'reopened', label: 'Reopened' },
  { value: 'acknowledged', label: 'Acknowledged' },
  { value: 'resolved', label: 'Resolved' },
];

function incidentTone(state) {
  if (state === 'reopened') return 'danger';
  if (state === 'open') return 'warning';
  if (state === 'acknowledged') return 'info';
  if (state === 'resolved') return 'success';
  return 'neutral';
}

function suiteWhen(millis) {
  if (!millis) return '—';
  return new Date(millis).toLocaleString();
}

/** `ends in 42m`, or `lapsed` — the question in front of a ban is always how long is left. */
function suiteRemaining(until) {
  if (!until) return '—';
  const seconds = Math.round((until - Date.now()) / 1000);
  if (seconds <= 0) return 'lapsed';
  if (seconds < 60) return 'in ' + seconds + 's';
  if (seconds < 3600) return 'in ' + Math.round(seconds / 60) + 'm';
  if (seconds < 86400) return 'in ' + Math.round(seconds / 3600) + 'h';
  return 'in ' + Math.round(seconds / 86400) + 'd';
}

/**
 * Driven by the suite's accent variables rather than by bootstrap's `btn-*` utilities.
 *
 * Those utilities are not flipped for Otoroshi's dark theme — `btn-warning` renders as dim grey on
 * grey, so the most consequential action on this page read as disabled sitting next to three that
 * were not. Same reason `.suite-panel` exists instead of `.card`.
 */
function suiteButton(key, label, tone, onClick, disabled) {
  return React.createElement(
    'button',
    {
      key: key,
      type: 'button',
      className: 'suite-btn ' + suiteTone(tone),
      style: { marginRight: 6 },
      disabled: !!disabled,
      onClick: onClick,
    },
    label
  );
}

/**
 * A caret that also carries the row's identity, so the whole left column is the hit target.
 *
 * A real `button` rather than a span with a click handler: a span is not focusable, announces
 * nothing, and cannot be reached by keyboard at all — which on this page would mean the evidence
 * behind every ban is mouse-only. The browser chrome is reset rather than the element downgraded.
 */
function suiteDisclosure(open, label, onClick) {
  return React.createElement(
    'button',
    {
      type: 'button',
      onClick: onClick,
      'aria-expanded': open ? 'true' : 'false',
      title: 'Show the evidence',
      style: {
        cursor: 'pointer',
        userSelect: 'none',
        width: 250,
        flex: 'none',
        color: 'var(--color_level3)',
        background: 'none',
        border: 'none',
        padding: 0,
        font: 'inherit',
        textAlign: 'left',
      },
    },
    (open ? '▾ ' : '▸ ') + label
  );
}

class SecurityDashboardPage extends Component {
  state = {
    status: null,
    bans: [],
    incidents: [],
    allowlist: [],
    error: null,
    boardError: null,
    notice: null,
    busy: false,
    filter: '',
    incidentFilter: 'active',
    open: {},
    // one inline form at a time, anchored to the row it belongs to: a reason for an allowlist
    // entry, a note for an incident. Never a window.prompt — cancelling one of those on an
    // *optional* note would abandon the action the operator actually asked for
    pending: null,
    banRef: '',
    banDuration: 3600,
    banReason: '',
  };

  componentDidMount() {
    ensureSuiteStyles();
    this.props.setTitle('Bans & incidents');
    this.load();
    this.timer = setInterval(this.load, 10000);
  }

  componentWillUnmount() {
    if (this.timer) clearInterval(this.timer);
  }

  load = () =>
    Promise.all([
      securityCall('/_status'),
      securityCall('/_bans'),
      securityCall('/_incidents'),
      securityCall('/_allowlist'),
    ])
      .then(([status, bans, incidents, allowlist]) =>
        this.setState({
          status: status,
          bans: (bans && bans.bans) || [],
          incidents: (incidents && incidents.incidents) || [],
          allowlist: (allowlist && allowlist.entries) || [],
          // kept apart from `error`: this one is about the board, and the periodic reload must not
          // wipe the refusal an operator has just been told about
          boardError: (incidents && incidents.error) || null,
        })
      )
      .catch((e) => this.setState({ error: String(e.message || e) }));

  /**
   * Runs one operator action.
   *
   * The api answers `done: false` with a reason for the refusals that are decisions rather than
   * failures — banning an allowlisted caller, extending a ban that just lapsed. Those have to
   * reach the screen: silently doing nothing is how someone walks away believing a caller is
   * banned when they are not.
   */
  act = (path, body, success) => {
    this.setState({ busy: true, notice: null, error: null });
    return securityCall(path, body)
      .then((r) => {
        const refused =
          r && r.done === false
            ? r.error || (r.refused === 'allowlisted' ? this.refusedMessage(r) : 'refused')
            : null;
        // the reload first, the message after — so what the operator reads is the state *after*
        // the action, and a refusal is never overwritten by the response to its own reload
        return this.load().then(() =>
          this.setState(refused ? { error: refused, notice: null } : success ? { notice: success } : {})
        );
      })
      .catch((e) => this.setState({ error: String(e.message || e) }))
      .then(() => this.setState({ busy: false }));
  };

  refusedMessage = (r) => {
    const entry = r.allowlist || {};
    return (
      'Refused: ' + (entry.key || 'this caller') + ' is on the allowlist' +
      (entry.reason ? ' — ' + entry.reason : '') + '. Remove the allowlist entry first.'
    );
  };

  toggle = (key) =>
    this.setState((prev) => {
      const open = Object.assign({}, prev.open);
      if (open[key]) delete open[key];
      else open[key] = true;
      return { open: open };
    });

  matches = (text) => {
    const needle = this.state.filter.trim().toLowerCase();
    if (!needle) return true;
    return String(text || '').toLowerCase().indexOf(needle) >= 0;
  };

  // -------------------------------------------------------------------------------------------
  // status
  // -------------------------------------------------------------------------------------------

  renderStatus = () => {
    const s = this.state.status;
    if (!s) return null;
    const shared = s.shared_state || {};
    const rows = [
      ['Node', s.node],
      ['Bans held', String((s.bans && s.bans.bans) || 0)],
      ['Ban list refreshed', reputationAgo(s.bans && s.bans.last_refresh)],
      ['Allowlisted', String((s.allowlist && s.allowlist.entries) || 0)],
      ['Incidents on this node', String((s.incidents && s.incidents.held) || 0)],
      ['Shared state', shared.dedicated_redis ? 'dedicated redis' : 'otoroshi storage'],
      ['Ledger', s.ledger && s.ledger.enabled ? 'on — bans at ' + s.ledger.ban_threshold : 'off'],
    ];
    return suitePanel('status', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'This node'),
      suiteRows(rows, 200),
      s.bans && s.bans.last_error ? suiteNotice('err', 'danger', s.bans.last_error) : null,
      // said in words rather than left to be inferred from an empty list: on a leader/worker
      // cluster the workers see the traffic and the leader serves this page
      shared.warning ? suiteNotice('shared', 'warning', shared.warning) : null,
    ]);
  };

  renderControls = () =>
    React.createElement(
      'div',
      { key: 'controls', className: 'suite-panel', style: { display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' } },
      React.createElement('input', {
        key: 'filter',
        type: 'text',
        className: 'form-control',
        style: { width: 260 },
        placeholder: 'Filter by identity, reason or tag',
        value: this.state.filter,
        onChange: (e) => this.setState({ filter: e.target.value }),
      }),
      React.createElement(
        'select',
        {
          key: 'state',
          className: 'form-select',
          style: { width: 200 },
          value: this.state.incidentFilter,
          onChange: (e) => this.setState({ incidentFilter: e.target.value }),
        },
        INCIDENT_FILTERS.map((f) => React.createElement('option', { key: f.value, value: f.value }, f.label))
      ),
      React.createElement('span', { key: 'sep', style: { flex: 1 } }),
      suiteButton('refresh', 'Refresh', 'neutral', this.load, this.state.busy)
    );

  // -------------------------------------------------------------------------------------------
  // bans
  // -------------------------------------------------------------------------------------------

  banActions = (b) => [
    React.createElement(
      'span',
      { key: 'extend', className: 'suite-meta', style: { marginRight: 6 } },
      'Extend'
    ),
    ...BAN_EXTENSIONS.map((ext) =>
      suiteButton(
        'ext-' + ext.label,
        ext.label,
        'neutral',
        () => this.act('/_extend', { ref: b.key, duration_seconds: ext.seconds }, 'Ban on ' + b.key + ' extended by ' + ext.label + '.'),
        this.state.busy
      )
    ),
    suiteButton(
      'unban',
      'Unban',
      'danger',
      () => this.act('/_unban', { ref: b.key }, b.key + ' unbanned.'),
      this.state.busy
    ),
    suiteButton(
      'allow',
      'Never ban this caller',
      'warning',
      () => this.ask('ban', 'allow', b.key),
      this.state.busy
    ),
  ];

  ask = (scope, kind, key, state) =>
    this.setState((prev) => {
      const open = Object.assign({}, prev.open);
      // opening the row too, so the form is never rendered somewhere the operator cannot see it
      open[scope + ':' + key] = true;
      return { pending: { scope: scope, kind: kind, key: key, state: state, value: '' }, open: open };
    });

  cancelPending = () => this.setState({ pending: null });

  confirmPending = () => {
    const p = this.state.pending;
    if (!p) return;
    // Enter would otherwise get past the disabled button
    if (p.kind === 'allow' && p.value.trim().length === 0) return;
    this.setState({ pending: null });
    if (p.kind === 'allow') {
      return this.act(
        '/_allow',
        { ref: p.key, reason: p.value, unban: true },
        p.key + ' is on the allowlist, and anything held against it has been lifted.'
      );
    }
    return this.act(
      '/_incident_state',
      { key: p.key, state: p.state, note: p.value },
      p.key + ' marked ' + p.state + '.'
    );
  };

  renderPending = (scope, key) => {
    const p = this.state.pending;
    if (!p || p.scope !== scope || p.key !== key) return null;
    const allow = p.kind === 'allow';
    return React.createElement(
      'div',
      { key: 'pending', style: { marginTop: 10 } },
      suiteNotice(
        'why',
        allow ? 'warning' : 'info',
        allow
          ? 'The fabric will never ban ' + key + ' again — fail2ban, the ledger and the response engine ' +
            'included. It does not switch off inspection: the WAF still inspects and a tier that denies ' +
            'still denies.'
          : 'A note for whoever reads this next. Optional.'
      ),
      React.createElement(
        'div',
        { style: { display: 'flex', gap: 8, alignItems: 'center' } },
        React.createElement('input', {
          type: 'text',
          className: 'form-control',
          autoFocus: true,
          style: { flex: 1 },
          placeholder: allow ? 'Why? — e.g. nightly integration suite' : 'e.g. confirmed false positive, tuning tracked in JIRA-123',
          value: p.value,
          onChange: (e) =>
            this.setState({ pending: Object.assign({}, this.state.pending, { value: e.target.value }) }),
          onKeyDown: (e) => {
            if (e.key === 'Enter') this.confirmPending();
            if (e.key === 'Escape') this.cancelPending();
          },
        }),
        suiteButton(
          'ok',
          allow ? 'Allowlist' : 'Mark ' + p.state,
          allow ? 'warning' : 'success',
          this.confirmPending,
          // a reason is required for an allowlist entry and optional for a note: one of them is
          // read months later by someone deciding whether to remove it
          this.state.busy || (allow && p.value.trim().length === 0)
        ),
        suiteButton('cancel', 'Cancel', 'neutral', this.cancelPending, false)
      )
    );
  };

  renderEvidence = (b) => {
    const timeline = b.timeline || [];
    const signals = Array.isArray(b.signals) ? b.signals : [];
    return React.createElement(
      'div',
      { key: 'ev-' + b.key, style: { padding: '8px 10px 12px 18px' } },
      suiteRows(
        [
          ['Identity', b.key],
          ['Reason', b.reason || '—'],
          ['Issued by', b.issued_by || '—'],
          ['Issued at', suiteWhen(b.issued_at)],
          ['Ends', suiteWhen(b.until) + ' (' + suiteRemaining(b.until) + ')'],
          ['Tags', (b.tags || []).join(', ') || '—'],
          b.last_action ? ['Last action', b.last_action + ' by ' + (b.last_action_by || '?') + ', ' + reputationAgo(b.last_action_at)] : null,
        ].filter((r) => r),
        140
      ),
      timeline.length > 0
        ? React.createElement(
            'div',
            { key: 'tl' },
            React.createElement('div', { className: 'suite-meta', style: { margin: '8px 0 4px' } }, 'What they did'),
            this.renderTimeline(timeline)
          )
        : null,
      signals.length > 0
        ? React.createElement(
            'div',
            { key: 'sig' },
            React.createElement('div', { className: 'suite-meta', style: { margin: '8px 0 4px' } }, 'Signals that scored it'),
            React.createElement('pre', { className: 'suite-code' }, JSON.stringify(signals, null, 2))
          )
        : null,
      timeline.length === 0 && signals.length === 0
        ? suiteNotice(
            'noev',
            'neutral',
            'No evidence was attached to this ban. Bans issued before the caller had an incident — a manual one, or one from a plugin that carries none — have nothing to show here.'
          )
        : null,
      React.createElement('div', { key: 'act', style: { marginTop: 10 } }, this.banActions(b)),
      this.renderPending('ban', b.key)
    );
  };

  renderTimeline = (events) =>
    React.createElement(
      'div',
      { className: 'suite-rows' },
      events.map((e, i) =>
        React.createElement(
          'div',
          { key: i, className: 'suite-row', style: { alignItems: 'baseline' } },
          React.createElement('span', { style: { width: 90 } }, reputationAgo(e.at)),
          suiteBadge('c-' + i, e.category, 'neutral'),
          suiteBadge('a-' + i, e.action + (e.enforced ? '' : ' (observed)'), e.enforced ? 'danger' : 'warning'),
          React.createElement('span', { style: { flex: 1 } }, e.message),
          e.route_name ? React.createElement('span', { className: 'suite-meta', style: { width: 150 } }, e.route_name) : null
        )
      )
    );

  renderBans = () => {
    const bans = this.state.bans.filter((b) => this.matches(b.key + ' ' + b.reason + ' ' + (b.tags || []).join(' ')));
    return suitePanel('bans', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'Active bans (' + bans.length + ')'),
      this.state.bans.length === 0
        ? suiteNotice('none', 'success', 'Nobody is banned right now.')
        : bans.length === 0
        ? suiteNotice('nomatch', 'neutral', 'No ban matches that filter.')
        : React.createElement(
            'div',
            { key: 'list' },
            bans.map((b) =>
              React.createElement(
                'div',
                { key: b.key, style: { borderBottom: '1px solid var(--border-color)' } },
                React.createElement(
                  'div',
                  { className: 'suite-row', style: { alignItems: 'center' } },
                  suiteDisclosure(this.state.open['ban:' + b.key], b.key, () => this.toggle('ban:' + b.key)),
                  React.createElement('span', { style: { flex: 1 } }, b.reason),
                  suiteBadge('score-' + b.key, 'score ' + b.score, b.score >= 90 ? 'danger' : 'warning'),
                  (b.timeline || []).length > 0
                    ? suiteBadge('ev-' + b.key, (b.timeline || []).length + ' events', 'neutral')
                    : null,
                  React.createElement(
                    'span',
                    { className: 'suite-meta', style: { width: 110, textAlign: 'right' } },
                    'ends ' + suiteRemaining(b.until)
                  )
                ),
                this.state.open['ban:' + b.key] ? this.renderEvidence(b) : null
              )
            )
          ),
      this.state.bans.length > 1
        ? React.createElement(
            'div',
            { key: 'all', style: { marginTop: 10 } },
            suiteButton(
              'unban-all',
              'Unban everyone',
              'danger',
              () => {
                if (window.confirm('Lift all ' + this.state.bans.length + ' bans?')) {
                  this.act('/_unban', { all: true }, 'Every ban has been lifted.');
                }
              },
              this.state.busy
            )
          )
        : null,
    ]);
  };

  // -------------------------------------------------------------------------------------------
  // incidents
  // -------------------------------------------------------------------------------------------

  incidentActions = (i) => [
    i.state !== 'acknowledged'
      ? suiteButton(
          'ack',
          'Acknowledge',
          'neutral',
          () => this.ask('inc', 'state', i.key, 'acknowledged'),
          this.state.busy
        )
      : null,
    i.state !== 'resolved'
      ? suiteButton('resolve', 'Resolve', 'success', () => this.ask('inc', 'state', i.key, 'resolved'), this.state.busy)
      : null,
    i.workflow
      ? suiteButton(
          'reopen',
          'Reopen',
          'neutral',
          () => this.act('/_incident_state', { key: i.key, state: 'open' }, i.key + ' is open again.'),
          this.state.busy
        )
      : null,
    !i.banned && !i.allowlisted
      ? suiteButton(
          'ban',
          'Ban for an hour',
          'danger',
          () =>
            this.act(
              '/_ban',
              { ref: i.key, duration_seconds: 3600, reason: 'banned from the incident console — ' + i.last_message },
              i.key + ' banned for an hour.'
            ),
          this.state.busy
        )
      : null,
    !i.allowlisted
      ? suiteButton('allow', 'Never ban this caller', 'warning', () => this.ask('inc', 'allow', i.key), this.state.busy)
      : null,
  ].filter((b) => b);



  visibleIncidents = () => {
    const filter = this.state.incidentFilter;
    return this.state.incidents
      .filter((i) => this.matches(i.key + ' ' + i.last_message + ' ' + (i.tags || []).join(' ')))
      .filter((i) => {
        if (filter === 'all') return true;
        if (filter === 'active') return i.state === 'open' || i.state === 'reopened';
        return i.state === filter;
      });
  };

  renderIncidentDetail = (i) =>
    React.createElement(
      'div',
      { key: 'd-' + i.key, style: { padding: '8px 10px 12px 18px' } },
      suiteRows(
        [
          ['Identity', i.key],
          ['First seen', suiteWhen(i.first_seen)],
          ['Last seen', suiteWhen(i.last_seen) + ' (' + reputationAgo(i.last_seen) + ')'],
          ['Events', i.count + ' recorded, ' + (i.enforced_count || 0) + ' enforced'],
          ['Categories', (i.categories || []).join(', ') || '—'],
          ['Actions taken', (i.actions || []).join(', ') || '—'],
          ['Tags', (i.tags || []).join(', ') || '—'],
          ['Routes', (i.routes || []).join(', ') || '—'],
          // only the merged view can answer this, and "one caller, three nodes" is a different
          // problem from "one caller, one node"
          ['Seen by', (i.nodes || []).join(', ') || '—'],
          i.workflow
            ? [
                'State',
                i.workflow.state + ' by ' + i.workflow.by + ', ' + reputationAgo(i.workflow.at) +
                  (i.workflow.note ? ' — ' + i.workflow.note : ''),
              ]
            : null,
          i.banned ? ['Currently', 'banned, ends ' + suiteRemaining(i.ban && i.ban.until)] : null,
          i.allowlisted ? ['Currently', 'allowlisted — the fabric will not ban this caller'] : null,
        ].filter((r) => r),
        140
      ),
      (i.timeline || []).length > 0
        ? React.createElement(
            'div',
            { key: 'tl' },
            React.createElement(
              'div',
              { className: 'suite-meta', style: { margin: '8px 0 4px' } },
              'The last ' + (i.timeline || []).length + ' of ' + i.count + ' events'
            ),
            this.renderTimeline(i.timeline)
          )
        : null,
      React.createElement('div', { key: 'act', style: { marginTop: 10 } }, this.incidentActions(i)),
      this.renderPending('inc', i.key)
    );

  renderIncidents = () => {
    const incidents = this.visibleIncidents();
    return suitePanel('incidents', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'Incidents (' + incidents.length + ')'),
      React.createElement(
        'div',
        { key: 'lede', className: 'suite-meta', style: { marginBottom: 10 } },
        'One run of activity from one caller, collapsed and merged across every node. Acknowledging or ' +
          'resolving one is shared with the rest of the team. A resolved caller who comes back reopens.'
      ),
      incidents.length === 0
        ? suiteNotice(
            'none',
            'success',
            this.state.incidents.length === 0
              ? 'Nothing correlated in the current window.'
              : 'Nothing matches that filter — ' + this.state.incidents.length + ' incidents are hidden by it.'
          )
        : React.createElement(
            'div',
            { key: 'list' },
            incidents.map((i) =>
              React.createElement(
                'div',
                { key: i.key, style: { borderBottom: '1px solid var(--border-color)' } },
                React.createElement(
                  'div',
                  { className: 'suite-row', style: { alignItems: 'center' } },
                  suiteDisclosure(this.state.open['inc:' + i.key], i.key, () => this.toggle('inc:' + i.key)),
                  suiteBadge('st-' + i.key, i.state, incidentTone(i.state)),
                  React.createElement('span', { style: { flex: 1 } }, i.last_message),
                  i.banned ? suiteBadge('b-' + i.key, 'banned', 'danger') : null,
                  i.allowlisted ? suiteBadge('a-' + i.key, 'allowlisted', 'info') : null,
                  suiteBadge('c-' + i.key, i.count + ' events', 'neutral'),
                  suiteBadge('s-' + i.key, 'max ' + i.max_score, i.max_score >= 90 ? 'danger' : 'warning'),
                  React.createElement(
                    'span',
                    { className: 'suite-meta', style: { width: 90, textAlign: 'right' } },
                    reputationAgo(i.last_seen)
                  )
                ),
                this.state.open['inc:' + i.key] ? this.renderIncidentDetail(i) : null
              )
            )
          ),
    ]);
  };

  // -------------------------------------------------------------------------------------------
  // allowlist and the manual ban
  // -------------------------------------------------------------------------------------------

  renderAllowlist = () => {
    const entries = this.state.allowlist.filter((e) => this.matches(e.key + ' ' + e.reason));
    return suitePanel('allowlist', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'Allowlist (' + entries.length + ')'),
      React.createElement(
        'div',
        { key: 'lede', className: 'suite-meta', style: { marginBottom: 10 } },
        'Identities the fabric will never ban, whichever module asks. It does not switch off inspection — ' +
          'the WAF and the response engine still apply. To exempt a caller from the fabric entirely, use a ' +
          "threat policy's exemptions instead."
      ),
      entries.length === 0
        ? suiteNotice('none', 'neutral', 'Nothing is allowlisted.')
        : React.createElement(
            'div',
            { key: 'list' },
            entries.map((e) =>
              React.createElement(
                'div',
                { key: e.key, className: 'suite-row', style: { alignItems: 'center', borderBottom: '1px solid var(--border-color)' } },
                React.createElement('span', { style: { width: 250, flex: 'none' } }, e.key),
                React.createElement('span', { style: { flex: 1 } }, e.reason),
                suiteBadge('p-' + e.key, e.permanent ? 'permanent' : 'until ' + suiteWhen(e.until), e.permanent ? 'info' : 'warning'),
                React.createElement(
                  'span',
                  { className: 'suite-meta', style: { width: 200 } },
                  'by ' + e.added_by + ', ' + reputationAgo(e.added_at)
                ),
                suiteButton(
                  'rm-' + e.key,
                  'Remove',
                  'danger',
                  () => this.act('/_disallow', { ref: e.key }, e.key + ' is no longer allowlisted.'),
                  this.state.busy
                )
              )
            )
          ),
    ]);
  };

  renderManualBan = () =>
    suitePanel('manual', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'Ban a caller'),
      React.createElement(
        'div',
        { key: 'lede', className: 'suite-meta', style: { marginBottom: 10 } },
        'An identity is a kind and a value: ip:1.2.3.4, apikey:client-id, user:someone@example.com, ' +
          'fingerprint:… — a bare address is read as an ip.'
      ),
      React.createElement(
        'div',
        { key: 'form', style: { display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' } },
        React.createElement('input', {
          key: 'ref',
          type: 'text',
          className: 'form-control',
          style: { width: 260 },
          placeholder: 'ip:1.2.3.4',
          value: this.state.banRef,
          onChange: (e) => this.setState({ banRef: e.target.value }),
        }),
        React.createElement(
          'select',
          {
            key: 'dur',
            className: 'form-select',
            style: { width: 160 },
            value: String(this.state.banDuration),
            onChange: (e) => this.setState({ banDuration: parseInt(e.target.value, 10) }),
          },
          [
            { v: 3600, l: 'for an hour' },
            { v: 86400, l: 'for a day' },
            { v: 604800, l: 'for a week' },
          ].map((o) => React.createElement('option', { key: o.v, value: String(o.v) }, o.l))
        ),
        React.createElement('input', {
          key: 'reason',
          type: 'text',
          className: 'form-control',
          style: { flex: 1, minWidth: 220 },
          placeholder: 'Why?',
          value: this.state.banReason,
          onChange: (e) => this.setState({ banReason: e.target.value }),
        }),
        suiteButton(
          'do',
          'Ban',
          'danger',
          () =>
            this.act(
              '/_ban',
              {
                ref: this.state.banRef.trim(),
                duration_seconds: this.state.banDuration,
                reason: this.state.banReason.trim() || 'banned from the incident console',
              },
              this.state.banRef.trim() + ' banned.'
            ).then(() => this.setState({ banRef: '', banReason: '' })),
          this.state.busy || this.state.banRef.trim().length === 0
        )
      ),
    ]);

  render() {
    return React.createElement(
      'div',
      {},
      this.state.error ? suiteNotice('e', 'danger', this.state.error) : null,
      this.state.boardError ? suiteNotice('be', 'warning', this.state.boardError) : null,
      this.state.notice ? suiteNotice('n', 'success', this.state.notice) : null,
      this.renderStatus(),
      this.renderControls(),
      this.renderBans(),
      this.renderIncidents(),
      this.renderAllowlist(),
      this.renderManualBan()
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
    description: 'Who is banned, on what evidence, and what to do about them',
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
