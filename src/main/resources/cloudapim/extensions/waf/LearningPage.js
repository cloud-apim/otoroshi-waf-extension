// OPS-3 — learning mode.
// Relies on the themed primitives defined in ReputationPages.js, which is injected first.

const LEARNING_BASE = '/extensions/cloud-apim/extensions/waf/learning';

function learningCall(path, body) {
  return fetch(LEARNING_BASE + path, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  }).then((r) => r.json().then((json) => ({ status: r.status, json })));
}

function learnBadge(label, kind) {
  return React.createElement('span', { className: 'suite-badge suite-' + kind }, label);
}

function learnRow(label, value, key) {
  return React.createElement(
    'div',
    { className: 'suite-row', key: key || label },
    React.createElement('span', null, label),
    React.createElement('span', null, value)
  );
}

function learnDuration(ms) {
  const h = Math.floor(ms / 3600000);
  if (h < 1) return Math.max(1, Math.round(ms / 60000)) + 'm';
  if (h < 48) return h + 'h';
  return Math.round(h / 24) + 'd';
}

class WafLearningPage extends Component {
  state = { configs: [], configRef: null, report: null, running: false, loading: false, error: null, selected: {}, reason: '', applied: null };

  componentDidMount() {
    ensureSuiteStyles();
    suiteCenterPage(true);
    this.props.setTitle('WAF learning mode');
    // the same client every other page in this extension uses
    BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'waf-configs')
      .findAll()
      .then((cs) => {
        const configs = Array.isArray(cs) ? cs : cs && cs.data ? cs.data : [];
        // a page that opens on nothing and needs a dropdown before it says anything is a page people
        // bounce off. the url carries the choice, so a report can also be linked to.
        const wanted = new URLSearchParams(window.location.search).get('config');
        const chosen = configs.find((c) => c.id === wanted) || configs[0];
        this.setState({ configs, configRef: chosen ? chosen.id : null }, () => {
          if (chosen) this.refresh();
        });
      })
      .catch((e) => this.setState({ error: 'could not list the waf configs: ' + String(e.message || e) }));
  }

  componentWillUnmount() {
    suiteCenterPage(false);
  }

  selectConfig = (v) => {
    const url = new URL(window.location.href);
    if (v) url.searchParams.set('config', v);
    else url.searchParams.delete('config');
    window.history.replaceState({}, '', url.toString());
    this.setState({ configRef: v, report: null, selected: {}, applied: null }, this.refresh);
  };

  refresh = () => {
    if (!this.state.configRef) return;
    this.setState({ loading: true, error: null });
    learningCall('/_report', { config_ref: this.state.configRef })
      .then(({ status, json }) => {
        if (status >= 400) this.setState({ error: json.error, loading: false, report: null });
        else this.setState({ report: json, running: !!json.running, loading: false, applied: null });
      })
      .catch((e) => this.setState({ error: String(e.message || e), loading: false }));
  };

  act = (what) => {
    this.setState({ loading: true });
    learningCall('/_' + what, { config_ref: this.state.configRef }).then(() => this.refresh());
  };

  apply = () => {
    const keys = Object.keys(this.state.selected).filter((k) => this.state.selected[k]);
    this.setState({ loading: true, error: null });
    learningCall('/_apply', { config_ref: this.state.configRef, keys: keys, reason: this.state.reason })
      .then(({ status, json }) => {
        if (status >= 400) this.setState({ error: json.error, loading: false });
        else this.setState({ applied: json, loading: false, selected: {} }, this.refresh);
      })
      .catch((e) => this.setState({ error: String(e.message || e), loading: false }));
  };

  renderControls = () => {
    const options = this.state.configs.map((c) => ({ label: c.name, value: c.id }));
    return React.createElement(
      'div',
      { className: 'row mb-3' },
      React.createElement(
        'div',
        { className: 'col-sm-6' },
        React.createElement(SelectInput, {
          label: 'WAF config',
          value: this.state.configRef,
          onChange: this.selectConfig,
          possibleValues: options,
        })
      ),
      React.createElement(
        'div',
        { className: 'col-sm-6', style: { paddingTop: 24 } },
        React.createElement(
          'button',
          {
            type: 'button',
            className: 'btn btn-sm ' + (this.state.running ? 'btn-danger' : 'btn-success'),
            disabled: !this.state.configRef || this.state.loading,
            onClick: () => this.act(this.state.running ? 'stop' : 'start'),
          },
          this.state.running ? 'Stop the window' : 'Start a window'
        ),
        ' ',
        React.createElement(
          'button',
          { type: 'button', className: 'btn btn-sm btn-secondary', disabled: !this.state.configRef, onClick: this.refresh },
          'Refresh'
        ),
        ' ',
        React.createElement(
          'button',
          {
            type: 'button',
            className: 'btn btn-sm btn-secondary',
            disabled: !this.state.configRef || this.state.loading,
            onClick: () => this.act('discard'),
          },
          'Discard'
        )
      )
    );
  };

  renderDistribution = () => {
    const r = this.state.report;
    if (!r || r.distributed !== false) return null;
    return React.createElement(
      'div',
      { className: 'suite-notice suite-danger' },
      'The shared state is not reaching the other nodes, so this window only counts what this node served. ' +
        'On a leader/worker cluster the workers serve the traffic and this node serves this page, which means ' +
        'these numbers are not measuring your traffic. Point `security.redis-uri` at a redis before trusting them.'
    );
  };

  renderVerdict = () => {
    const r = this.state.report;
    if (!r) return null;
    const good = /safe to arm|account for every sampled/.test(r.verdict);
    return React.createElement(
      'div',
      { className: 'suite-notice ' + (good ? 'suite-success' : 'suite-warning'), style: { fontSize: 14 } },
      r.verdict
    );
  };

  renderWindow = () => {
    const r = this.state.report;
    if (!r) return null;
    const run = r.run;
    const rows = [
      learnRow('Window', learnDuration(run.duration_millis) + (run.running ? ' and counting' : ' (stopped)')),
      learnRow('Requests seen', String(run.requests)),
      learnRow('Requests that matched', String(run.matched)),
      learnRow('Nodes reporting', String(r.nodes)),
    ];
    if (r.mode.arming_estimate_reliable) {
      rows.push(
        learnRow(
          'Would be denied if armed',
          run.would_block > 0
            ? learnBadge(run.would_block + ' (' + (run.would_block_rate * 100).toFixed(2) + '% of traffic)', 'danger')
            : learnBadge('none', 'success')
        )
      );
    } else {
      rows.push(learnRow('Would be denied if armed', learnBadge('not measurable in this mode', 'neutral')));
    }
    return React.createElement(
      'div',
      null,
      React.createElement('div', { className: 'suite-notice' }, r.mode.note),
      React.createElement('div', { className: 'suite-rows' }, rows)
    );
  };

  renderAdvice = () => {
    const r = this.state.report;
    if (!r) return null;
    const p = r.paranoia;
    const t = r.threshold;
    const i = r.impact;
    return React.createElement(
      'div',
      null,
      React.createElement('h4', null, 'Paranoia level'),
      React.createElement(
        'div',
        { className: 'suite-rows' },
        learnRow('Running at', String(p.current)),
        learnRow(
          'Matches by level',
          Object.keys(p.by_level)
            .sort()
            .map((k) => 'PL' + k + ': ' + p.by_level[k])
            .join(' · ') || '—'
        ),
        learnRow('Recommendation', p.recommended ? learnBadge('drop to ' + p.recommended, 'warning') : learnBadge('keep', 'neutral')),
        learnRow('Why', p.rationale)
      ),
      React.createElement('h4', null, 'Anomaly threshold'),
      React.createElement(
        'div',
        { className: 'suite-rows' },
        learnRow('Running at', String(t.current)),
        learnRow(
          'Still denied at',
          (t.curve || []).map((c) => c.threshold + ' → ' + c.still_denied).join(' · ') || '—'
        ),
        learnRow('Recommendation', t.recommended ? learnBadge('raise to ' + t.recommended, 'warning') : learnBadge('keep', 'neutral')),
        learnRow('Why', t.rationale)
      ),
      React.createElement('h4', null, 'What arming would still break'),
      React.createElement(
        'div',
        { className: 'suite-rows' },
        learnRow('Sampled denials', String(i.sampled)),
        learnRow('Accounted for by the exclusions', String(i.resolved)),
        learnRow('Still unexplained', i.residual > 0 ? learnBadge(String(i.residual), 'danger') : learnBadge('none', 'success')),
        learnRow('Why', i.rationale)
      )
    );
  };

  renderProposals = () => {
    const r = this.state.report;
    if (!r || !r.exclusions || r.exclusions.length === 0) return null;
    const accepted = r.exclusions.filter((e) => e.accepted);
    const chosen = Object.keys(this.state.selected).filter((k) => this.state.selected[k]).length;
    return React.createElement(
      'div',
      null,
      React.createElement('h4', null, 'Proposed exclusions'),
      React.createElement(
        'div',
        { className: 'suite-notice' },
        'Each one was run against this configuration before it appeared here, and is run again before it is written. ' +
          'Only the verified ones can be applied.'
      ),
      r.exclusions.map((e) =>
        React.createElement(
          'div',
          { className: 'suite-rows', key: e.entry.key, style: { marginBottom: 10 } },
          React.createElement(
            'div',
            { className: 'suite-row' },
            React.createElement(
              'span',
              null,
              e.accepted
                ? React.createElement('input', {
                    type: 'checkbox',
                    checked: !!this.state.selected[e.entry.key],
                    onChange: (ev) =>
                      this.setState({ selected: { ...this.state.selected, [e.entry.key]: ev.target.checked } }),
                  })
                : learnBadge('needs a decision', 'warning')
            ),
            React.createElement(
              'span',
              null,
              React.createElement('strong', null, 'Rule ' + e.entry.rule_id),
              ' on ',
              learnBadge(e.entry.target ? e.entry.target.full : '—', 'info'),
              ' at ',
              e.entry.path,
              ' — ',
              e.entry.count + ' matches',
              e.entry.msg ? React.createElement('div', { className: 'suite-meta' }, e.entry.msg) : null
            )
          ),
          learnRow('Generated', React.createElement('code', { style: { wordBreak: 'break-all' } }, e.proposal.seclang), 'g'),
          learnRow('Given up', e.proposal.no_longer_caught, 'u'),
          learnRow('Verdict', e.accepted ? learnBadge(e.note, 'success') : learnBadge(e.note, 'danger'), 'v')
        )
      ),
      React.createElement(
        'div',
        { style: { margin: '10px 0' } },
        React.createElement('input', {
          type: 'text',
          className: 'form-control',
          value: this.state.reason,
          placeholder: 'why these are false positives — kept next to each generated rule',
          onChange: (e) => this.setState({ reason: e.target.value }),
          style: { marginBottom: 8 },
        }),
        React.createElement(
          'button',
          {
            type: 'button',
            className: 'btn btn-sm btn-success',
            disabled: accepted.length === 0 || this.state.loading,
            onClick: this.apply,
          },
          chosen > 0 ? 'Apply the ' + chosen + ' selected' : 'Apply all ' + accepted.length + ' verified'
        )
      ),
      this.state.applied
        ? React.createElement(
            'div',
            { className: 'suite-notice suite-success' },
            'Wrote ' + this.state.applied.count + ' exclusion' + (this.state.applied.count > 1 ? 's' : '') + '.'
          )
        : null
    );
  };

  render() {
    return React.createElement(
      'div',
      null,
      React.createElement(
        'div',
        { className: 'suite-notice' },
        'Run a configuration in monitoring for a window, then read what it would cost to arm it. ' +
          'Nothing here is applied on its own.'
      ),
      this.renderControls(),
      this.state.error ? React.createElement('div', { className: 'suite-notice suite-danger' }, this.state.error) : null,
      this.renderDistribution(),
      this.renderVerdict(),
      this.renderWindow(),
      this.renderAdvice(),
      this.renderProposals()
    );
  }
}
