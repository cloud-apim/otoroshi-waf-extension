class SecurityPosturePage extends Component {
  state = { routes: [], summary: null, error: null, onlyGaps: false };

  componentDidMount() {
    ensureSuiteStyles();
    this.props.setTitle('Route posture');
    this.load();
  }

  load = () => {
    securityCall('/_posture')
      .then((r) => this.setState({ routes: r.routes || [], summary: r.summary || null, error: null }))
      .catch((e) => this.setState({ error: String(e.message || e) }));
  };

  badge = (label, kind) =>
    React.createElement('span', { className: 'suite-badge suite-' + kind, key: label }, label);

  cell = (route) => {
    // the point of the page: an empty row is the finding, so it must not look like an empty cell
    if (!route.covered) return this.badge('unprotected', 'danger');
    const badges = [];
    if (route.waf) badges.push(this.badge('waf ' + (route.waf_blocking ? 'block' : 'monitor'), route.waf_blocking ? 'success' : 'warning'));
    if (route.reputation) badges.push(this.badge('reputation ' + route.reputation, route.reputation === 'block' ? 'success' : 'warning'));
    if (route.bots) badges.push(this.badge('bots', 'info'));
    if (route.fail2ban) badges.push(this.badge('fail2ban ' + route.fail2ban, route.fail2ban === 'armed' ? 'success' : 'warning'));
    if (route.response) badges.push(this.badge('fabric ' + (route.policy_dry_run ? 'dry run' : 'armed'), route.policy_dry_run ? 'warning' : 'success'));
    return badges;
  };

  render() {
    const s = this.state.summary;
    const rows = this.state.onlyGaps ? this.state.routes.filter((r) => !r.covered || !r.enforcing) : this.state.routes;
    return React.createElement('div', { className: 'suite-panel' },
      this.state.error && React.createElement('div', { className: 'suite-notice' }, this.state.error),
      s && React.createElement('div', { className: 'suite-rows' },
        React.createElement('div', { className: 'suite-row' },
          React.createElement('span', null, 'Routes'), React.createElement('span', null, String(s.total))),
        React.createElement('div', { className: 'suite-row' },
          React.createElement('span', null, 'Covered'), React.createElement('span', null, String(s.covered))),
        React.createElement('div', { className: 'suite-row' },
          React.createElement('span', null, 'Enforcing'), React.createElement('span', null, String(s.enforcing))),
        React.createElement('div', { className: 'suite-row' },
          React.createElement('span', null, 'Unprotected'), React.createElement('span', null, String(s.uncovered)))
      ),
      React.createElement('div', { style: { marginBottom: 10, display: 'flex', gap: 8 } },
        React.createElement('button', {
          className: 'btn btn-sm ' + (this.state.onlyGaps ? 'btn-primary' : 'btn-secondary'),
          type: 'button',
          onClick: () => this.setState({ onlyGaps: !this.state.onlyGaps }),
        }, this.state.onlyGaps ? 'Showing gaps only' : 'Show gaps only'),
        React.createElement('button', { className: 'btn btn-sm btn-secondary', type: 'button', onClick: this.load }, 'Refresh')
      ),
      React.createElement('div', { className: 'suite-rows' },
        rows.map((r) =>
          React.createElement('div', { className: 'suite-row', key: r.route_id },
            React.createElement('span', { style: { flex: '0 0 240px' } },
              React.createElement('a', { href: '/bo/dashboard/routes/' + r.route_id + '?tab=flow' }, r.route_name)),
            React.createElement('span', { style: { flex: 1, display: 'flex', gap: 6, flexWrap: 'wrap' } },
              this.cell(r),
              r.via_preset && this.badge('preset', 'neutral'))
          )
        )
      ),
      rows.length === 0 && React.createElement('div', { className: 'suite-meta' },
        this.state.onlyGaps ? 'Every route is covered and enforcing.' : 'No routes yet.')
    );
  }
}
