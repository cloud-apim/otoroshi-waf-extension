// Threat feeds, feed catalog and CrowdSec bouncers.
// Additive: nothing here touches the WAF configuration page.

const REPUTATION_BASE = '/extensions/cloud-apim/extensions/waf/reputation';

function reputationCall(path, body) {
  return fetch(REPUTATION_BASE + path, {
    method: body ? 'POST' : 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  }).then((r) => r.json());
}

function reputationAgo(millis) {
  if (!millis) return 'never';
  const seconds = Math.max(0, Math.round((Date.now() - millis) / 1000));
  if (seconds < 60) return seconds + 's ago';
  if (seconds < 3600) return Math.round(seconds / 60) + 'm ago';
  return Math.round(seconds / 3600) + 'h ago';
}

// Otoroshi themes its UI through css variables and flips them between dark (default) and
// [data-theme="light"]. Bootstrap's own colour utilities are NOT flipped — .card, .table and
// .badge bg-* keep a white background whatever the theme, which is why anything built with them
// is unreadable in the default dark UI. Everything below is driven by Otoroshi's variables so both
// themes follow for free, with the two accents that need a per-theme correction called out.
function ensureSuiteStyles() {
  if (document.getElementById('cloud-apim-suite-styles')) return;
  const style = document.createElement('style');
  style.id = 'cloud-apim-suite-styles';
  style.textContent = [
    '.suite-panel { background: var(--bg-color_level2); color: var(--color_level2);',
    '  border: 1px solid var(--border-color); border-radius: 4px; padding: 14px 16px; margin-bottom: 10px; }',
    '.suite-title { color: var(--color_level3); font-size: 17px; font-weight: 600; margin-bottom: 6px; }',
    // `overflow-wrap: anywhere` rather than `word-break: break-all`: both keep a long key or an
    // ipv6 address inside its column, but break-all also chops ordinary prose mid-word
    '.suite-meta { opacity: 0.65; font-size: 12px; overflow-wrap: anywhere; }',
    '.suite-badge { display: inline-block; border: 1px solid var(--suite-accent);',
    '  color: var(--suite-accent); background: transparent; border-radius: 3px; padding: 1px 7px;',
    '  margin-right: 6px; font-size: 11px; letter-spacing: 0.02em; white-space: nowrap; }',
    '.suite-badge.suite-neutral { color: var(--color_level2); }',
    '.suite-notice { background: var(--bg-color_level3); color: var(--color_level2);',
    '  border: 1px solid var(--border-color); border-left: 3px solid var(--suite-accent);',
    '  border-radius: 3px; padding: 8px 12px; margin-bottom: 8px; }',
    '.suite-rows { border: 1px solid var(--border-color); border-radius: 3px; overflow: hidden; margin-bottom: 8px; }',
    '.suite-row { display: flex; gap: 12px; padding: 5px 10px; }',
    '.suite-row:nth-child(even) { background: var(--bg-color_level3); }',
    '.suite-row > span:first-child { flex: none; opacity: 0.65; }',
    '.suite-row > span:last-child { color: var(--color_level3); word-break: break-all; }',
    '.suite-code { max-height: 260px; overflow: auto; background: var(--bg-color_level3);',
    '  color: var(--color_level2); border: 1px solid var(--border-color); border-radius: 3px;',
    '  padding: 8px 10px; margin-bottom: 0; }',
    '.suite-btn { font: inherit; font-size: 12px; line-height: 1.5; padding: 2px 10px;',
    '  border-radius: 3px; border: 1px solid var(--suite-accent); color: var(--suite-accent);',
    '  background: transparent; cursor: pointer; white-space: nowrap; }',
    '.suite-btn:hover:not(:disabled) { background: var(--suite-accent); color: var(--bg-color_level2); }',
    '.suite-btn:disabled { opacity: 0.35; cursor: not-allowed; }',
    '.suite-neutral { --suite-accent: var(--border-color-strong); }',
    '.suite-info { --suite-accent: var(--color-blue); }',
    '.suite-success { --suite-accent: var(--color-green); }',
    '.suite-warning { --suite-accent: var(--color-primary); }',
    '.suite-danger { --suite-accent: var(--color-red); }',
    '/* those three accents are tuned for a dark ground and lose too much contrast on a near-white one */',
    '[data-theme="light"] .suite-warning { --suite-accent: #8a5d00; }',
    '[data-theme="light"] .suite-success { --suite-accent: #2c7048; }',
    '[data-theme="light"] .suite-info { --suite-accent: #0b6b79; }',
  ].join('\n');
  document.head.appendChild(style);
}

ensureSuiteStyles();

const suiteTone = (tone) => 'suite-' + (tone || 'neutral');

/**
 * Otoroshi's own centred-page modifier, borrowed rather than reimplemented.
 *
 * `.page-container--centered > *` caps the scroll container's direct children at 1000px and centres
 * them — the treatment its entity forms already get. List pages keep the full width because a table
 * with eight columns needs it; the pages that are prose and panels read far better in a measure.
 *
 * Toggling the class rather than wrapping our own div means the page title is centred with the
 * content, and that we follow Otoroshi if it ever changes the number. Every caller must undo it on
 * unmount, or the next table page inherits a 1000px cap.
 */
function suiteCenterPage(on) {
  const el = document.getElementById('content-scroll-container');
  if (el) el.classList.toggle('page-container--centered', !!on);
}

/** A panel, replacing .card — which paints itself white whatever the theme. */
function suitePanel(key, children, extraStyle) {
  return React.createElement('div', { key: key, className: 'suite-panel', style: extraStyle || {} }, children);
}

/** An outline pill: the accent carries both border and text, so it reads on any surface. */
function suiteBadge(key, label, tone) {
  return React.createElement('span', { key: key, className: 'suite-badge ' + suiteTone(tone) }, label);
}

/** A notice, replacing .alert — accent on the left edge instead of a light fill. */
function suiteNotice(key, tone, content) {
  return React.createElement('div', { key: key, className: 'suite-notice ' + suiteTone(tone) }, content);
}

/** Label/value rows, replacing .table — whose cells force a white background. */
function suiteRows(rows, labelWidth) {
  return React.createElement(
    'div',
    { className: 'suite-rows' },
    rows.map((row) =>
      React.createElement(
        'div',
        { key: row[0], className: 'suite-row' },
        React.createElement('span', { style: { width: labelWidth || 190 } }, row[0]),
        React.createElement('span', {}, row[1])
      )
    )
  );
}

function suiteCode(key, text) {
  return React.createElement('pre', { key: key, className: 'suite-code' }, text);
}

class ThreatFeedStatus extends Component {
  state = { status: null, refreshing: false, error: null };

  componentDidMount() {
    this.load();
  }

  load = () => {
    reputationCall('/_status').then((r) => {
      const feeds = (r && r.feeds) || [];
      const id = this.props.rawValue && this.props.rawValue.id;
      this.setState({ status: feeds.filter((f) => f.id === id)[0] || null });
    });
  };

  refresh = () => {
    const id = this.props.rawValue && this.props.rawValue.id;
    this.setState({ refreshing: true, error: null });
    reputationCall('/_refresh', { feed: id }).then((r) => {
      this.setState({ refreshing: false });
      if (!r.done) {
        this.setState({ error: r.error || 'refresh failed' });
      } else {
        const snapshot = (r.snapshots || [])[0];
        if (snapshot && snapshot.error) this.setState({ error: snapshot.error });
        this.load();
      }
    });
  };

  rollback = () => {
    const id = this.props.rawValue && this.props.rawValue.id;
    this.setState({ refreshing: true, error: null });
    reputationCall('/_rollback', { feed: id }).then((r) => {
      this.setState({ refreshing: false });
      if (!r.done) this.setState({ error: r.error || 'rollback failed' });
      this.load();
    });
  };

  render() {
    const snapshot = this.state.status && this.state.status.snapshot;
    const previous = this.state.status && this.state.status.previous;
    const rows = [];
    if (snapshot) {
      rows.push(['Entries', String(snapshot.entries)]);
      rows.push(['Merged ranges', String(snapshot.ranges)]);
      rows.push(['Rejected lines', String(snapshot.rejected)]);
      rows.push(['Last refresh', reputationAgo(snapshot.fetched_at) + (snapshot.not_modified ? ' (not modified)' : '')]);
      if (snapshot.etag) rows.push(['ETag', snapshot.etag]);
    } else {
      rows.push(['Status', 'never refreshed on this node yet']);
    }
    const error = this.state.error || (snapshot && snapshot.error);
    return [
      React.createElement(
        'div',
        { className: 'row mb-3', key: 'actions' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Snapshot'),
        React.createElement(
          'div',
          { className: 'col-sm-10' },
          suiteRows(rows, 190),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-success', type: 'button', onClick: this.refresh, disabled: this.state.refreshing },
            React.createElement('i', { className: 'fas fa-sync' }, null),
            this.state.refreshing ? ' Refreshing…' : ' Refresh now'
          ),
          previous
            ? React.createElement(
                'button',
                {
                  // btn-warning is dim grey on grey in the dark theme — see .suite-btn
                  className: 'suite-btn suite-warning',
                  type: 'button',
                  style: { marginLeft: 8 },
                  onClick: this.rollback,
                  disabled: this.state.refreshing,
                },
                React.createElement('i', { className: 'fas fa-undo' }, null),
                ' Roll back (' + previous.entries + ' entries)'
              )
            : null
        )
      ),
      error &&
        React.createElement(
          'div',
          { className: 'row mb-3', key: 'error' },
          React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
          React.createElement(
            'div',
            { className: 'col-sm-10' },
            suiteNotice('err', 'danger', error)
          )
        ),
    ];
  }
}

class ReputationLookup extends Component {
  state = { ip: '', result: null, calling: false };

  send = () => {
    this.setState({ calling: true, result: null });
    reputationCall('/_lookup', { ip: this.state.ip }).then((r) => {
      this.setState({ calling: false, result: r });
    });
  };

  render() {
    const verdict = this.state.result && this.state.result.verdict;
    return [
      React.createElement(
        'div',
        { className: 'row mb-3', key: 'input' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Test an address'),
        React.createElement(
          'div',
          { className: 'col-sm-10', style: { display: 'flex', gap: 8 } },
          React.createElement('input', {
            type: 'text',
            className: 'form-control',
            placeholder: '1.2.3.4',
            value: this.state.ip,
            onChange: (e) => this.setState({ ip: e.target.value }),
          }),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-success', type: 'button', onClick: this.send, disabled: this.state.calling },
            React.createElement('i', { className: 'fas fa-search' }, null),
            ' Look up'
          )
        )
      ),
      verdict &&
        React.createElement(
          'div',
          { className: 'row mb-3', key: 'result' },
          React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
          React.createElement(
            'div',
            { className: 'col-sm-10' },
            suiteNotice(
              'verdict',
              verdict.hits.length ? (verdict.blocking ? 'danger' : 'warning') : 'success',
              verdict.hits.length
                ? 'score ' + verdict.score + (verdict.blocking ? ' — would be denied' : ' — would be reported only')
                : 'no match in any enabled source'
            ),
            verdict.hits.length ? suiteCode('hits', JSON.stringify(verdict.hits, null, 2)) : null
          )
        ),
    ];
  }
}

class ThreatFeedCatalogPage extends Component {
  state = { entries: [], creating: null, created: {}, error: null };

  componentDidMount() {
    suiteCenterPage(true);
    this.props.setTitle('Threat feed catalog');
    reputationCall('/_catalog').then((r) => this.setState({ entries: (r && r.entries) || [] }));
  }

  componentWillUnmount() {
    suiteCenterPage(false);
  }

  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'threat-feeds');

  create = (entry) => {
    this.setState({ creating: entry.id, error: null });
    reputationCall('/_template', { entry: entry.id })
      .then((r) => {
        if (!r.done) throw new Error(r.error || 'could not build the feed');
        return this.client.create(r.feed);
      })
      .then((created) => {
        const marks = Object.assign({}, this.state.created);
        marks[entry.id] = created.id;
        this.setState({ creating: null, created: marks });
      })
      .catch((e) => this.setState({ creating: null, error: String(e.message || e) }));
  };

  renderEntry = (entry) => {
    const createdId = this.state.created[entry.id];
    const badges = [suiteBadge('cat', entry.category, 'neutral')];
    badges.push(
      suiteBadge('act', entry.action + ' · weight ' + entry.weight, entry.action === 'block' ? 'danger' : 'info')
    );
    if (entry.requires_auth) badges.push(suiteBadge('auth', 'needs credentials', 'warning'));
    if (entry.manual_url) badges.push(suiteBadge('url', 'url must be set manually', 'warning'));

    return suitePanel(entry.id, [
      React.createElement('div', { key: 'head', className: 'suite-title' }, entry.name),
      React.createElement('div', { key: 'badges', style: { marginBottom: 8 } }, badges),
      React.createElement('div', { key: 'desc', style: { marginBottom: 8 } }, entry.description),
      entry.notes ? suiteNotice('notes', 'warning', entry.notes) : null,
      entry.auth_hint ? suiteNotice('auth', 'info', entry.auth_hint) : null,
      React.createElement(
        'div',
        { key: 'meta', className: 'suite-meta', style: { marginBottom: 10 } },
        (entry.url || 'no url — set it on the feed') + ' · ' + entry.format + ' · ' + entry.licence
      ),
      createdId
        ? React.createElement(
            'a',
            {
              key: 'open',
              className: 'btn btn-sm btn-success',
              href: '/bo/dashboard/extensions/cloud-apim/waf/threatfeeds/edit/' + createdId,
            },
            React.createElement('i', { className: 'fas fa-arrow-right' }, null),
            ' Open the created feed'
          )
        : React.createElement(
            'button',
            {
              key: 'create',
              className: 'btn btn-sm btn-success',
              type: 'button',
              disabled: this.state.creating === entry.id,
              onClick: () => this.create(entry),
            },
            React.createElement('i', { className: 'fas fa-plus' }, null),
            this.state.creating === entry.id ? ' Creating…' : ' Create a feed from this source'
          ),
    ]);
  };

  render() {
    return React.createElement(
      'div',
      {},
      suiteNotice(
        'intro',
        'info',
        'Creating a feed copies this source into an editable Threat feed entity. Sources marked as needing credentials or a manual url are created disabled — fill in what is missing, then enable them. Verify every url and licence against the provider before enabling in production.'
      ),
      this.state.error ? suiteNotice('error', 'danger', this.state.error) : null,
      this.state.entries.map(this.renderEntry)
    );
  }
}

class ThreatFeedsPage extends Component {
  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: { type: 'string', props: { label: 'Name', placeholder: 'My threat feed' } },
    description: { type: 'string', props: { label: 'Description', placeholder: 'Description of the feed' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    url: { type: 'string', props: { label: 'Url', placeholder: 'https://example.com/blocklist.txt' } },
    method: { type: 'string', props: { label: 'Method', placeholder: 'GET' } },
    headers: { type: 'object', props: { label: 'Headers', help: 'Use a vault reference for any api key' } },
    follow_redirects: { type: 'bool', props: { label: 'Follow redirects' } },
    format: {
      type: 'select',
      props: {
        label: 'Format',
        possibleValues: [
          { label: 'One address or CIDR per line', value: 'cidr_lines' },
          { label: 'CSV', value: 'csv' },
          { label: 'JSON array', value: 'json_array' },
          { label: 'JSON path', value: 'json_path' },
          { label: 'MISP', value: 'misp' },
        ],
      },
    },
    options: {
      type: 'object',
      props: {
        label: 'Format options',
        help: "csv: column, separator, skip_header — json_array: field — json_path: path (e.g. prefixes[].ip_prefix)",
      },
    },
    refresh_interval_seconds: { type: 'number', props: { label: 'Refresh interval', suffix: 'seconds' } },
    timeout_millis: { type: 'number', props: { label: 'Timeout', suffix: 'ms' } },
    max_entries: { type: 'number', props: { label: 'Max entries', help: 'Guard against a feed that suddenly grows' } },
    weight: { type: 'number', props: { label: 'Weight', help: 'Contribution to the reputation score, 0 to 100' } },
    action: {
      type: 'select',
      props: {
        label: 'Action',
        help: "'block' lets this feed deny on its own — reserve it for sources you trust",
        possibleValues: [
          { label: 'Block', value: 'block' },
          { label: 'Monitor', value: 'monitor' },
        ],
      },
    },
    tag: { type: 'string', props: { label: 'Tag', placeholder: 'feed:my-list' } },
    status: { type: ThreatFeedStatus, props: {} },
    lookup: { type: ReputationLookup, props: {} },
  };

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'Enabled', filterId: 'enabled', content: (item) => (item.enabled ? 'Yes' : 'No'), style: { textAlign: 'center', width: 80 } },
    { title: 'Action', filterId: 'action', content: (item) => item.action, style: { textAlign: 'center', width: 90 } },
    { title: 'Weight', filterId: 'weight', content: (item) => item.weight, style: { textAlign: 'center', width: 80 } },
    { title: 'Format', filterId: 'format', content: (item) => item.format, style: { width: 130 } },
  ];

  formFlow = [
    '_loc',
    'id',
    'name',
    'description',
    '>>>Metadata and tags',
    'tags',
    'metadata',
    '<<<Source',
    'enabled',
    'url',
    'method',
    'headers',
    'follow_redirects',
    '>>>Parsing',
    'format',
    'options',
    'max_entries',
    '>>>Scoring',
    'action',
    'weight',
    'tag',
    '>>>Refresh',
    'refresh_interval_seconds',
    'timeout_millis',
    '<<<Status',
    'status',
    '>>>Test',
    'lookup',
  ];

  componentDidMount() {
    this.props.setTitle('Threat feeds');
  }

  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'threat-feeds');

  render() {
    return React.createElement(
      Table,
      {
        parentProps: this.props,
        selfUrl: 'extensions/cloud-apim/waf/threatfeeds',
        defaultTitle: 'All threat feeds',
        defaultValue: () => ({
          id: 'threat-feed_' + uuid(),
          name: 'New threat feed',
          description: 'A threat intelligence feed',
          tags: [],
          metadata: {},
          enabled: true,
          url: '',
          method: 'GET',
          headers: {},
          follow_redirects: true,
          format: 'cidr_lines',
          options: {},
          refresh_interval_seconds: 3600,
          timeout_millis: 30000,
          max_entries: 2000000,
          weight: 50,
          action: 'monitor',
          tag: '',
        }),
        itemName: 'Threat feed',
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/waf/threatfeeds/edit/${item.id}`;
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/waf/threatfeeds/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: 'waf.extensions.cloud-apim.com/ThreatFeed',
      },
      null
    );
  }
}

class CrowdSecStatus extends Component {
  state = { status: null, syncing: false, error: null };

  componentDidMount() {
    this.load();
  }

  load = () => {
    reputationCall('/_status').then((r) => {
      const all = (r && r.crowdsec) || [];
      const id = this.props.rawValue && this.props.rawValue.id;
      this.setState({ status: all.filter((b) => b.id === id)[0] || null });
    });
  };

  sync = () => {
    const id = this.props.rawValue && this.props.rawValue.id;
    this.setState({ syncing: true, error: null });
    reputationCall('/_crowdsec_sync', { bouncer: id }).then((r) => {
      this.setState({ syncing: false });
      const result = ((r && r.results) || [])[0];
      if (!r.done) this.setState({ error: r.error || 'sync failed' });
      else if (result && !result.ok) this.setState({ error: result.error });
      this.load();
    });
  };

  render() {
    const store = this.state.status && this.state.status.store;
    const rows = [];
    if (store) {
      rows.push(['Decisions held', String(store.decisions)]);
      rows.push(['Single addresses', String(store.exact)]);
      rows.push(['Ranges', String(store.ranges)]);
      rows.push(['Last sync', reputationAgo(store.last_sync)]);
      rows.push(['Initial sync done', store.initialized ? 'Yes' : 'No']);
    } else {
      rows.push(['Status', 'never synced on this node yet']);
    }
    if (this.state.status) rows.push(['Alerts waiting to be pushed', String(this.state.status.pending_push)]);
    const error = this.state.error || (store && store.last_error);
    return [
      React.createElement(
        'div',
        { className: 'row mb-3', key: 'status' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Local mirror'),
        React.createElement(
          'div',
          { className: 'col-sm-10' },
          suiteRows(rows, 220),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-success', type: 'button', onClick: this.sync, disabled: this.state.syncing },
            React.createElement('i', { className: 'fas fa-sync' }, null),
            this.state.syncing ? ' Syncing…' : ' Sync now'
          )
        )
      ),
      error &&
        React.createElement(
          'div',
          { className: 'row mb-3', key: 'error' },
          React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
          React.createElement(
            'div',
            { className: 'col-sm-10' },
            suiteNotice('err', 'danger', error)
          )
        ),
    ];
  }
}

class CrowdSecBouncersPage extends Component {
  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: { type: 'string', props: { label: 'Name', placeholder: 'My CrowdSec bouncer' } },
    description: { type: 'string', props: { label: 'Description' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    lapi_url: { type: 'string', props: { label: 'LAPI url', placeholder: 'http://127.0.0.1:8080' } },
    api_key: {
      type: 'password',
      props: { label: 'Bouncer api key', help: 'From `cscli bouncers add`. Use a vault reference rather than the raw key.' },
    },
    poll_interval_seconds: { type: 'number', props: { label: 'Poll interval', suffix: 'seconds' } },
    timeout_millis: { type: 'number', props: { label: 'Timeout', suffix: 'ms' } },
    scopes: { type: 'array', props: { label: 'Decision scopes', help: 'ip, range — others are ignored' } },
    origins_filter: { type: 'array', props: { label: 'Origins filter', help: 'Empty accepts every origin (crowdsec, cscli, CAPI, …)' } },
    action: {
      type: 'select',
      props: {
        label: 'Action',
        possibleValues: [
          { label: 'Block', value: 'block' },
          { label: 'Monitor', value: 'monitor' },
        ],
      },
    },
    weight: { type: 'number', props: { label: 'Weight' } },
    tag: { type: 'string', props: { label: 'Tag' } },
    push_enabled: { type: 'bool', props: { label: 'Report detections back', help: 'Turns Otoroshi into a CrowdSec detector' } },
    push_machine_id: { type: 'string', props: { label: 'Machine id', help: 'From `cscli machines add` — a bouncer key cannot write alerts' } },
    push_password: { type: 'password', props: { label: 'Machine password' } },
    push_scenario: { type: 'string', props: { label: 'Scenario name' } },
    push_interval_seconds: { type: 'number', props: { label: 'Push interval', suffix: 'seconds' } },
    push_max_batch: { type: 'number', props: { label: 'Max alerts per push' } },
    push_with_decision: {
      type: 'bool',
      props: {
        label: 'Attach a ban decision',
        help: 'Off: CrowdSec scenarios decide what to do with the signal. On: the ban is immediate and unconditional.',
      },
    },
    push_decision_duration: { type: 'string', props: { label: 'Ban duration', placeholder: '4h' } },
    push_waf_detections: {
      type: 'bool',
      props: {
        label: 'Report WAF detections',
        help: 'Report CRS and custom rule matches from the WAF plugins, not just reputation denials',
      },
    },
    push_waf_monitored: {
      type: 'bool',
      props: {
        label: 'Include monitored matches',
        help: 'Off: only enforced blocks are reported. On: matches a WAF in monitoring mode let through are reported too.',
      },
    },
    status: { type: CrowdSecStatus, props: {} },
    lookup: { type: ReputationLookup, props: {} },
  };

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'Enabled', filterId: 'enabled', content: (item) => (item.enabled ? 'Yes' : 'No'), style: { textAlign: 'center', width: 80 } },
    { title: 'LAPI', filterId: 'lapi_url', content: (item) => item.lapi_url },
    { title: 'Action', filterId: 'action', content: (item) => item.action, style: { textAlign: 'center', width: 90 } },
    { title: 'Reports back', filterId: 'push_enabled', content: (item) => (item.push_enabled ? 'Yes' : 'No'), style: { textAlign: 'center', width: 110 } },
  ];

  formFlow = [
    '_loc',
    'id',
    'name',
    'description',
    '>>>Metadata and tags',
    'tags',
    'metadata',
    '<<<Local API',
    'enabled',
    'lapi_url',
    'api_key',
    'poll_interval_seconds',
    'timeout_millis',
    '>>>Decisions',
    'scopes',
    'origins_filter',
    'action',
    'weight',
    'tag',
    '>>>Report detections back to CrowdSec',
    'push_enabled',
    'push_machine_id',
    'push_password',
    'push_scenario',
    'push_interval_seconds',
    'push_max_batch',
    'push_with_decision',
    'push_decision_duration',
    'push_waf_detections',
    'push_waf_monitored',
    '<<<Status',
    'status',
    '>>>Test',
    'lookup',
  ];

  componentDidMount() {
    this.props.setTitle('CrowdSec bouncers');
  }

  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'crowdsec-bouncers');

  render() {
    return React.createElement(
      Table,
      {
        parentProps: this.props,
        selfUrl: 'extensions/cloud-apim/waf/crowdsecbouncers',
        defaultTitle: 'All CrowdSec bouncers',
        defaultValue: () => ({
          id: 'crowdsec-bouncer_' + uuid(),
          name: 'New CrowdSec bouncer',
          description: 'A CrowdSec Local API connection',
          tags: [],
          metadata: {},
          enabled: true,
          lapi_url: 'http://127.0.0.1:8080',
          api_key: '',
          poll_interval_seconds: 10,
          timeout_millis: 10000,
          scopes: ['ip', 'range'],
          origins_filter: [],
          weight: 90,
          action: 'block',
          tag: 'crowdsec',
          push_enabled: false,
          push_machine_id: '',
          push_password: '',
          push_scenario: 'cloud-apim/otoroshi-reputation',
          push_interval_seconds: 10,
          push_max_batch: 50,
          push_with_decision: false,
          push_decision_duration: '4h',
          push_waf_detections: false,
          push_waf_monitored: false,
        }),
        itemName: 'CrowdSec bouncer',
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/waf/crowdsecbouncers/edit/${item.id}`;
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/waf/crowdsecbouncers/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: 'waf.extensions.cloud-apim.com/CrowdSecBouncer',
      },
      null
    );
  }
}


class AsnStatus extends Component {
  state = { status: null, refreshing: false, error: null };

  componentDidMount() {
    this.load();
  }

  load = () => {
    reputationCall('/_status').then((r) => {
      const all = (r && r.asn) || [];
      const id = this.props.rawValue && this.props.rawValue.id;
      this.setState({ status: all.filter((d) => d.id === id)[0] || null });
    });
  };

  refresh = () => {
    this.setState({ refreshing: true, error: null });
    reputationCall('/_refresh', { asn: true }).then((r) => {
      this.setState({ refreshing: false });
      if (!r.done) this.setState({ error: r.error || 'refresh failed' });
      this.load();
    });
  };

  render() {
    const snap = this.state.status && this.state.status.snapshot;
    const rows = [];
    if (snap) {
      rows.push(['Networks indexed', String(snap.entries)]);
      rows.push(['Rejected rows', String(snap.rejected)]);
      rows.push(['Last refresh', reputationAgo(snap.fetched_at)]);
    } else {
      rows.push(['Status', 'never refreshed on this node yet']);
    }
    const error = this.state.error || (snap && snap.error);
    return [
      React.createElement(
        'div',
        { className: 'row mb-3', key: 'status' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Table'),
        React.createElement(
          'div',
          { className: 'col-sm-10' },
          suiteRows(rows, 190),
          suiteNotice(
            'cost',
            'info',
            'The table is a few hundred thousand networks and is fetched in full. Once a day is plenty — it changes slowly.'
          ),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-success', type: 'button', onClick: this.refresh, disabled: this.state.refreshing },
            React.createElement('i', { className: 'fas fa-sync' }, null),
            this.state.refreshing ? ' Refreshing…' : ' Refresh now'
          )
        )
      ),
      error &&
        React.createElement(
          'div',
          { className: 'row mb-3', key: 'error' },
          React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
          React.createElement('div', { className: 'col-sm-10' }, suiteNotice('err', 'danger', error))
        ),
    ];
  }
}

class AsnCategoriesEditor extends Component {
  change = (idx, field, value) => {
    this.props.onChange((this.props.value || []).map((c, i) => (i === idx ? Object.assign({}, c, { [field]: value }) : c)));
  };

  move = (idx, delta) => {
    const cats = (this.props.value || []).slice();
    const to = idx + delta;
    if (to < 0 || to >= cats.length) return;
    const tmp = cats[idx];
    cats[idx] = cats[to];
    cats[to] = tmp;
    this.props.onChange(cats);
  };

  add = () =>
    this.props.onChange(
      (this.props.value || []).concat([{ name: 'new-category', weight: 10, action: 'monitor', org_contains: [], asns: [] }])
    );

  remove = (idx) => this.props.onChange((this.props.value || []).filter((_, i) => i !== idx));

  list = (idx, field, label, help, parse) =>
    React.createElement(
      'div',
      { key: field, style: { display: 'flex', flexDirection: 'column', gap: 2, flex: 1, minWidth: 260 } },
      React.createElement('span', { className: 'suite-meta' }, label),
      React.createElement('input', {
        type: 'text',
        className: 'form-control',
        placeholder: help,
        value: ((this.props.value || [])[idx][field] || []).join(', '),
        onChange: (e) =>
          this.change(
            idx,
            field,
            e.target.value
              .split(',')
              .map((v) => v.trim())
              .filter((v) => v.length)
              .map(parse)
              .filter((v) => v === 0 || v)
          ),
      })
    );

  renderCategory = (cat, idx, all) =>
    suitePanel('cat-' + idx, [
      React.createElement(
        'div',
        { key: 'top', style: { display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 8 } },
        React.createElement(
          'div',
          { style: { display: 'flex', flexDirection: 'column', gap: 2 } },
          React.createElement('span', { className: 'suite-meta' }, 'Category'),
          React.createElement('input', {
            type: 'text',
            className: 'form-control',
            style: { width: 150 },
            value: cat.name,
            onChange: (e) => this.change(idx, 'name', e.target.value),
          })
        ),
        React.createElement(
          'div',
          { style: { display: 'flex', flexDirection: 'column', gap: 2 } },
          React.createElement('span', { className: 'suite-meta' }, 'Weight'),
          React.createElement('input', {
            type: 'number',
            className: 'form-control',
            style: { width: 100 },
            value: cat.weight,
            onChange: (e) => this.change(idx, 'weight', parseInt(e.target.value || '0', 10)),
          })
        ),
        React.createElement(
          'div',
          { style: { display: 'flex', flexDirection: 'column', gap: 2 } },
          React.createElement('span', { className: 'suite-meta' }, 'Action'),
          React.createElement(
            'select',
            {
              className: 'form-control',
              style: { width: 130 },
              value: cat.action || 'monitor',
              onChange: (e) => this.change(idx, 'action', e.target.value),
            },
            React.createElement('option', { value: 'monitor' }, 'Monitor'),
            React.createElement('option', { value: 'block' }, 'Block')
          )
        ),
        React.createElement(
          'div',
          { style: { display: 'flex', gap: 4, marginLeft: 'auto' } },
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-primary', type: 'button', disabled: idx === 0, onClick: () => this.move(idx, -1) },
            React.createElement('i', { className: 'fas fa-arrow-up' }, null)
          ),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-primary', type: 'button', disabled: idx === all.length - 1, onClick: () => this.move(idx, 1) },
            React.createElement('i', { className: 'fas fa-arrow-down' }, null)
          ),
          React.createElement(
            'button',
            { className: 'btn btn-sm btn-danger', type: 'button', onClick: () => this.remove(idx) },
            React.createElement('i', { className: 'fas fa-trash' }, null)
          )
        )
      ),
      React.createElement(
        'div',
        { key: 'match', style: { display: 'flex', gap: 12, flexWrap: 'wrap' } },
        this.list(idx, 'org_contains', 'Organisation name contains', 'amazon, ovh, hosting', (v) => v),
        this.list(idx, 'asns', 'AS numbers', '16509, 24940', (v) => parseInt(v, 10))
      ),
      cat.action === 'block'
        ? suiteNotice(
            'warn-' + idx,
            'warning',
            'Blocking on a network class refuses every legitimate server-to-server caller from it too. Reserve it for classes you are sure about.'
          )
        : null,
    ]);

  render() {
    const cats = this.props.value || [];
    return React.createElement(
      'div',
      { className: 'row mb-3' },
      React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Categories'),
      React.createElement(
        'div',
        { className: 'col-sm-10' },
        suiteNotice(
          'order',
          'info',
          'Order matters: the first category that matches wins. "CLOUDFLARENET" contains "cloud", which is why the CDN category has to sit above hosting.'
        ),
        cats.map((c, i) => this.renderCategory(c, i, cats)),
        React.createElement(
          'button',
          { className: 'btn btn-sm btn-success', type: 'button', onClick: this.add },
          React.createElement('i', { className: 'fas fa-plus' }, null),
          ' Add a category'
        )
      )
    );
  }
}

class AsnDatabasesPage extends Component {
  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: { type: 'string', props: { label: 'Name' } },
    description: { type: 'string', props: { label: 'Description' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    url: { type: 'string', props: { label: 'Url' } },
    gzip: { type: 'bool', props: { label: 'Gzipped payload', help: 'The published tables ship as .tsv.gz' } },
    format: {
      type: 'select',
      props: { label: 'Format', possibleValues: [{ label: 'iptoasn TSV', value: 'iptoasn_tsv' }] },
    },
    refresh_interval_seconds: { type: 'number', props: { label: 'Refresh interval', suffix: 'seconds' } },
    timeout_millis: { type: 'number', props: { label: 'Timeout', suffix: 'ms' } },
    max_entries: { type: 'number', props: { label: 'Max entries' } },
    categories: { type: AsnCategoriesEditor, props: {} },
    status: { type: AsnStatus, props: {} },
    lookup: { type: ReputationLookup, props: {} },
  };

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'Enabled', filterId: 'enabled', content: (item) => (item.enabled ? 'Yes' : 'No'), style: { textAlign: 'center', width: 80 } },
    { title: 'Categories', content: (item) => (item.categories || []).map((c) => c.name).join(', ') },
  ];

  formFlow = [
    '_loc', 'id', 'name', 'description',
    '>>>Metadata and tags', 'tags', 'metadata',
    '<<<Source', 'enabled', 'url', 'gzip', 'format', 'refresh_interval_seconds', 'timeout_millis', 'max_entries',
    '<<<Classification', 'categories',
    '<<<Status', 'status',
    '>>>Test', 'lookup',
  ];

  componentDidMount() {
    this.props.setTitle('ASN databases');
  }

  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'asn-databases');

  render() {
    return React.createElement(
      Table,
      {
        parentProps: this.props,
        selfUrl: 'extensions/cloud-apim/waf/asndatabases',
        defaultTitle: 'All ASN databases',
        defaultValue: () => ({
          id: 'asn-database_' + uuid(),
          name: 'ASN database',
          description: 'Resolves callers to their network, and classifies that network',
          tags: [],
          metadata: {},
          enabled: true,
          url: 'https://iptoasn.com/data/ip2asn-v4.tsv.gz',
          gzip: true,
          format: 'iptoasn_tsv',
          refresh_interval_seconds: 86400,
          timeout_millis: 120000,
          max_entries: 1500000,
          categories: [
            { name: 'cdn', weight: 0, action: 'monitor', org_contains: ['cloudflare', 'fastly', 'akamai'], asns: [13335, 54113, 20940] },
            { name: 'vpn', weight: 30, action: 'monitor', org_contains: ['nordvpn', 'mullvad', 'm247', 'vpn'], asns: [9009] },
            { name: 'hosting', weight: 15, action: 'monitor', org_contains: ['amazon', 'google', 'ovh', 'hetzner', 'hosting', 'cloud'], asns: [16509, 15169, 16276, 24940, 14061] },
          ],
        }),
        itemName: 'ASN database',
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/waf/asndatabases/edit/${item.id}`;
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/waf/asndatabases/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: 'waf.extensions.cloud-apim.com/AsnDatabase',
      },
      null
    );
  }
}

const ReputationFeatures = [
  {
    title: 'Threat feeds',
    description: 'IP reputation feeds consulted before a request is processed',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/threatfeeds',
    display: () => true,
    icon: () => 'fa-shield-alt',
  },
  {
    title: 'Threat feed catalog',
    description: 'Curated sources, ready to enable',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/threatfeedcatalog',
    display: () => true,
    icon: () => 'fa-book',
  },
  {
    title: 'ASN databases',
    description: 'Resolve callers to their network, and classify it',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/asndatabases',
    display: () => true,
    icon: () => 'fa-project-diagram',
  },
  {
    title: 'CrowdSec bouncers',
    description: 'CrowdSec Local API connections, in both directions',
    absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg',
    link: '/extensions/cloud-apim/waf/crowdsecbouncers',
    display: () => true,
    icon: () => 'fa-crow',
  },
];

const ReputationSidebarItems = [
  { title: 'Threat feeds', text: 'IP reputation feeds', path: 'extensions/cloud-apim/waf/threatfeeds', icon: 'shield-alt' },
  { title: 'Threat feed catalog', text: 'Curated sources', path: 'extensions/cloud-apim/waf/threatfeedcatalog', icon: 'book' },
  { title: 'ASN databases', text: 'Network classification', path: 'extensions/cloud-apim/waf/asndatabases', icon: 'project-diagram' },
  { title: 'CrowdSec bouncers', text: 'CrowdSec connections', path: 'extensions/cloud-apim/waf/crowdsecbouncers', icon: 'crow' },
];

const ReputationSearchItems = [
  {
    action: () => {
      window.location.href = '/bo/dashboard/extensions/cloud-apim/waf/threatfeeds';
    },
    env: React.createElement('span', { className: 'fas fa-shield-alt' }, null),
    label: 'Cloud APIM Security Suite - Threat feeds',
    value: 'threatfeeds',
  },
  {
    action: () => {
      window.location.href = '/bo/dashboard/extensions/cloud-apim/waf/threatfeedcatalog';
    },
    env: React.createElement('span', { className: 'fas fa-book' }, null),
    label: 'Cloud APIM Security Suite - Threat feed catalog',
    value: 'threatfeedcatalog',
  },
  {
    action: () => {
      window.location.href = '/bo/dashboard/extensions/cloud-apim/waf/crowdsecbouncers';
    },
    env: React.createElement('span', { className: 'fas fa-crow' }, null),
    label: 'Cloud APIM Security Suite - CrowdSec bouncers',
    value: 'crowdsecbouncers',
  },
];

const ReputationRoutes = [
  {
    path: '/extensions/cloud-apim/waf/asndatabases/:taction/:titem',
    component: (props) => React.createElement(AsnDatabasesPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/asndatabases/:taction',
    component: (props) => React.createElement(AsnDatabasesPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/asndatabases',
    component: (props) => React.createElement(AsnDatabasesPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/threatfeedcatalog',
    component: (props) => React.createElement(ThreatFeedCatalogPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/threatfeeds/:taction/:titem',
    component: (props) => React.createElement(ThreatFeedsPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/threatfeeds/:taction',
    component: (props) => React.createElement(ThreatFeedsPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/threatfeeds',
    component: (props) => React.createElement(ThreatFeedsPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/crowdsecbouncers/:taction/:titem',
    component: (props) => React.createElement(CrowdSecBouncersPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/crowdsecbouncers/:taction',
    component: (props) => React.createElement(CrowdSecBouncersPage, props, null),
  },
  {
    path: '/extensions/cloud-apim/waf/crowdsecbouncers',
    component: (props) => React.createElement(CrowdSecBouncersPage, props, null),
  },
];
