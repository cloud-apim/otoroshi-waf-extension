// OPS-2 — the false-positive tuning assistant.
// Relies on the themed primitives defined in ReputationPages.js, which is injected first.

const TUNING_BASE = '/extensions/cloud-apim/extensions/waf/tuning';

function tuningCall(path, body) {
  return fetch(TUNING_BASE + path, {
    method: body ? 'POST' : 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  }).then((r) => r.json().then((json) => ({ status: r.status, json })));
}

function tuningBadge(label, kind) {
  return React.createElement('span', { className: 'suite-badge suite-' + kind }, label);
}

function tuningAgo(ts) {
  const s = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (s < 60) return s + 's ago';
  if (s < 3600) return Math.round(s / 60) + 'm ago';
  return Math.round(s / 3600) + 'h ago';
}

// narrowest first — the order the api returns them in, and the order they should be read in
const REACH_TONE = { 1: 'success', 2: 'success', 3: 'warning', 4: 'danger' };

class WafTuningPage extends Component {
  state = {
    selected: null,
    proposals: null,
    loading: false,
    error: null,
    reason: '',
    chosen: null,
    result: null,
    scope: null,
    distributed: true,
  };

  columns = [
    {
      title: 'Rule',
      filterId: 'rule_id',
      content: (item) => String(item.rule_id),
      cell: (v, item) =>
        React.createElement(
          'span',
          { title: item.msg || '' },
          React.createElement('strong', null, v),
          item.msg ? React.createElement('div', { className: 'suite-meta' }, item.msg) : null
        ),
      style: { width: 260 },
    },
    {
      title: 'Input',
      filterId: 'target',
      content: (item) => (item.target ? item.target.full : ''),
      cell: (v) => (v ? tuningBadge(v, 'info') : React.createElement('span', { className: 'suite-meta' }, '—')),
      style: { width: 200 },
    },
    {
      title: 'Endpoint',
      filterId: 'path',
      content: (item) => item.method + ' ' + item.path,
      cell: (v, item) =>
        React.createElement(
          'span',
          null,
          v,
          item.route_name ? React.createElement('div', { className: 'suite-meta' }, item.route_name) : null
        ),
    },
    {
      title: 'Seen',
      filterId: 'count',
      content: (item) => item.count,
      cell: (v, item) =>
        React.createElement('span', null, String(v) + '×', React.createElement('div', { className: 'suite-meta' }, tuningAgo(item.last_seen))),
      style: { width: 110, textAlign: 'center' },
    },
    {
      title: 'Effect',
      filterId: 'blocked',
      content: (item) => (item.blocked ? 'blocked' : 'observed'),
      cell: (v) => tuningBadge(v, v === 'blocked' ? 'danger' : 'warning'),
      style: { width: 110, textAlign: 'center' },
    },
    {
      // a title is not decoration here: Table derives the react-table column id from
      // `filterId || title`, and an empty id throws on a function accessor — which is every column
      title: 'Tune',
      filterId: 'tune',
      content: () => '',
      notFilterable: true,
      notSortable: true,
      cell: (_, item) =>
        React.createElement(
          'button',
          { type: 'button', className: 'btn btn-sm btn-primary', onClick: () => this.select(item) },
          'False positive'
        ),
      style: { width: 150, textAlign: 'center' },
    },
  ];

  componentDidMount() {
    ensureSuiteStyles();
    this.props.setTitle('WAF tuning');
  }

  fetch = () =>
    tuningCall('/_matches').then(({ json }) => {
      this.setState({ scope: json.scope || null, distributed: json.distributed !== false });
      return json.matches || [];
    });

  select = (item) => {
    this.setState({ selected: item, proposals: null, loading: true, error: null, chosen: null, result: null, reason: '' });
    tuningCall('/_propose', { sample_id: item.id })
      .then(({ status, json }) => {
        if (status >= 400) this.setState({ error: json.error, loading: false });
        else this.setState({ proposals: json.proposals || [], loading: false });
      })
      .catch((e) => this.setState({ error: String(e.message || e), loading: false }));
  };

  apply = (proposal, force) => {
    this.setState({ loading: true, error: null });
    tuningCall('/_apply', {
      sample_id: this.state.selected.id,
      seclang: proposal.seclang,
      kind: proposal.kind,
      reason: this.state.reason,
      force: !!force,
    })
      .then(({ status, json }) => {
        if (status >= 400) this.setState({ error: json.error, loading: false, chosen: proposal, result: json });
        else this.setState({ result: json, loading: false, error: null, chosen: proposal });
      })
      .catch((e) => this.setState({ error: String(e.message || e), loading: false }));
  };

  renderPreview = (preview) => {
    if (!preview) return null;
    if (!preview.compiles) {
      return React.createElement('div', { className: 'suite-notice suite-danger' }, 'Does not compile: ' + preview.error);
    }
    if (!preview.reproduced) {
      // a different claim than "it does not work": there was nothing to measure against
      return React.createElement(
        'div',
        { className: 'suite-notice suite-warning' },
        'Could not rebuild the match from what was recorded, so this cannot be verified. Applying it needs an override.'
      );
    }
    if (!preview.effective) {
      return React.createElement(
        'div',
        { className: 'suite-notice suite-danger' },
        'Ran it — the rule still fires. This would be saved and change nothing.'
      );
    }
    const items = [
      React.createElement('div', { key: 'ok', className: 'suite-row' },
        React.createElement('span', null, 'Verified'),
        React.createElement('span', null, 'the rule stops firing on this request')
      ),
    ];
    if ((preview.collateral_rules || []).length > 0) {
      items.push(
        React.createElement('div', { key: 'col', className: 'suite-row' },
          React.createElement('span', null, 'Also silenced'),
          React.createElement('span', null, 'rules ' + preview.collateral_rules.join(', '))
        )
      );
    }
    const regressions = preview.regressions || [];
    items.push(
      React.createElement('div', { key: 'reg', className: 'suite-row' },
        React.createElement('span', null, 'Attack corpus'),
        React.createElement(
          'span',
          null,
          regressions.length === 0
            ? tuningBadge('all ' + (preview.corpus || []).length + ' still caught', 'success')
            : tuningBadge(regressions.length + ' no longer caught: ' + regressions.map((r) => r.name).join(', '), 'danger')
        )
      )
    );
    return React.createElement('div', { className: 'suite-rows' }, items);
  };

  renderProposal = (p, idx) => {
    const preview = p.preview || {};
    // unverifiable is overridable; demonstrably useless is not
    const unverified = preview.compiles && !preview.reproduced;
    const blocked = !preview.compiles || (preview.reproduced && !preview.effective);
    const regressions = (preview.regressions || []).length > 0;
    return React.createElement(
      'div',
      { key: p.kind, className: 'suite-rows', style: { marginBottom: 12 } },
      React.createElement(
        'div',
        { className: 'suite-row' },
        React.createElement('span', null, idx === 0 ? 'Narrowest' : 'Option'),
        React.createElement(
          'span',
          null,
          React.createElement('strong', null, p.title),
          ' ',
          tuningBadge(p.reach, REACH_TONE[p.reach_rank] || 'neutral'),
          p.recommended ? ' ' : null,
          p.recommended ? tuningBadge('recommended', 'info') : null
        )
      ),
      React.createElement(
        'div',
        { className: 'suite-row' },
        React.createElement('span', null, 'Generated'),
        React.createElement('code', { style: { whiteSpace: 'pre-wrap', wordBreak: 'break-all' } }, p.seclang)
      ),
      React.createElement('div', { className: 'suite-row' },
        React.createElement('span', null, 'Still caught'),
        React.createElement('span', null, p.still_caught)
      ),
      React.createElement('div', { className: 'suite-row' },
        React.createElement('span', null, 'Given up'),
        React.createElement('span', null, p.no_longer_caught)
      ),
      React.createElement('div', { className: 'suite-row' },
        React.createElement('span', null, 'Written'),
        React.createElement('span', null, p.placement === 'before' ? 'before the ruleset' : 'after the ruleset')
      ),
      React.createElement('div', { style: { padding: '6px 10px' } }, this.renderPreview(preview)),
      React.createElement(
        'div',
        { style: { padding: '6px 10px' } },
        React.createElement(
          'button',
          {
            type: 'button',
            className: 'btn btn-sm ' + (regressions || unverified ? 'btn-danger' : 'btn-success'),
            disabled: blocked || this.state.loading,
            onClick: () => this.apply(p, regressions || unverified),
          },
          blocked
            ? 'Cannot be applied'
            : unverified
              ? 'Apply unverified'
              : regressions
                ? 'Apply anyway'
                : 'Apply'
        )
      )
    );
  };

  renderAssistant = () => {
    const s = this.state.selected;
    if (!s) return null;
    const applied = this.state.result && this.state.result.done;
    return React.createElement(
      'div',
      { style: { marginTop: 16 } },
      React.createElement('h4', null, 'Rule ' + s.rule_id + ' on ' + (s.target ? s.target.full : 'this request')),
      s.matched_value
        ? React.createElement(
            'div',
            { className: 'suite-rows' },
            React.createElement('div', { className: 'suite-row' },
              React.createElement('span', null, 'It matched'),
              React.createElement('code', { style: { wordBreak: 'break-all' } }, s.matched_value)
            )
          )
        : null,
      this.state.error ? React.createElement('div', { className: 'suite-notice suite-danger' }, this.state.error) : null,
      applied
        ? React.createElement(
            'div',
            { className: 'suite-notice suite-success' },
            'Written to ruleset ' + this.state.result.applied.ruleset_name +
              (this.state.result.applied.ruleset_created ? ' (created)' : '') + '.'
          )
        : React.createElement(
            'div',
            null,
            React.createElement(
              'div',
              { style: { margin: '8px 0' } },
              React.createElement('label', { style: { display: 'block', marginBottom: 4 } }, 'Why is this a false positive?'),
              React.createElement('input', {
                type: 'text',
                className: 'form-control',
                value: this.state.reason,
                placeholder: 'kept next to the generated rule, for whoever reviews it later',
                onChange: (e) => this.setState({ reason: e.target.value }),
              })
            ),
            this.state.loading && !this.state.proposals
              ? React.createElement('div', { className: 'suite-meta' }, 'running each option against the ruleset…')
              : (this.state.proposals || []).map((p, i) => this.renderProposal(p, i))
          )
    );
  };

  render() {
    return React.createElement(
      'div',
      null,
      React.createElement(
        'div',
        { className: 'suite-notice' },
        'Every option below is run against this config before it is offered, and again before it is written. ' +
          (this.state.scope ? 'Candidates cover ' + this.state.scope + '.' : '')
      ),
      // an empty page has two very different causes, and only one of them is good news
      this.state.distributed === false
        ? React.createElement(
            'div',
            { className: 'suite-notice suite-warning' },
            'The shared state is not reaching the other nodes, so this page only sees what this node served. ' +
              'On a leader/worker cluster that is usually nothing at all — point `security.redis-uri` at a redis.'
          )
        : null,
      React.createElement(
        Table,
        {
          parentProps: this.props,
          selfUrl: 'extensions/cloud-apim/waf/tuning',
          defaultTitle: 'WAF tuning',
          itemName: 'Match',
          columns: this.columns,
          fetchItems: () => this.fetch(),
          showActions: false,
          showLink: false,
          hideAllActions: true,
          hideAddItemAction: true,
          hideEditButton: true,
          rowNavigation: false,
          extractKey: (item) => item.key,
          defaultValue: () => ({}),
          formSchema: {},
          formFlow: [],
          export: false,
        },
        null
      ),
      this.renderAssistant()
    );
  }
}
