function postureBadge(label, kind) {
  return React.createElement('span', { className: 'suite-badge suite-' + kind }, label);
}

function postureDash() {
  return React.createElement('span', { className: 'suite-meta' }, '—');
}

class SecurityPosturePage extends Component {
  state = { summary: null, error: null };

  columns = [
    {
      title: 'Route',
      filterId: 'route_name',
      content: (item) => item.route_name,
      cell: (v, item) =>
        React.createElement(
          'a',
          { href: '/bo/dashboard/routes/' + item.route_id + '?tab=flow' },
          v || item.route_id
        ),
    },
    {
      title: 'Status',
      filterId: 'status',
      // the whole point of the page is this column, and its worst value sorts first
      content: (item) => (!item.covered ? 'unprotected' : item.enforcing ? 'enforcing' : 'observing'),
      cell: (v) => postureBadge(v, v === 'unprotected' ? 'danger' : v === 'enforcing' ? 'success' : 'warning'),
      style: { width: 120, textAlign: 'center' },
    },
    {
      title: 'WAF',
      filterId: 'waf',
      content: (item) => (item.waf ? (item.waf_blocking ? 'block' : 'monitor') : ''),
      cell: (v, item) => (v ? postureBadge(item.waf + ' · ' + v, v === 'block' ? 'success' : 'warning') : postureDash()),
      style: { width: 200 },
    },
    {
      title: 'Reputation',
      filterId: 'reputation',
      content: (item) => item.reputation || '',
      cell: (v) => (v ? postureBadge(v, v === 'block' ? 'success' : 'warning') : postureDash()),
      style: { width: 120, textAlign: 'center' },
    },
    {
      title: 'Bots',
      filterId: 'bots',
      content: (item) => (item.bots ? 'on' : ''),
      cell: (v) => (v ? postureBadge('on', 'info') : postureDash()),
      style: { width: 90, textAlign: 'center' },
    },
    {
      title: 'Fail2ban',
      filterId: 'fail2ban',
      content: (item) => item.fail2ban || '',
      cell: (v) => (v ? postureBadge(v, v === 'armed' ? 'success' : 'warning') : postureDash()),
      style: { width: 110, textAlign: 'center' },
    },
    {
      title: 'Fabric',
      filterId: 'fabric',
      content: (item) => (item.response ? (item.policy_dry_run ? 'dry run' : 'armed') : ''),
      cell: (v, item) =>
        v
          ? React.createElement(
              'span',
              { title: item.policy || 'built-in policy' },
              postureBadge(v, v === 'armed' ? 'success' : 'warning')
            )
          : postureDash(),
      style: { width: 110, textAlign: 'center' },
    },
    {
      title: 'Via preset',
      filterId: 'via_preset',
      content: (item) => (item.via_preset ? 'yes' : 'no'),
      cell: (v) => (v === 'yes' ? postureBadge('preset', 'neutral') : postureDash()),
      style: { width: 110, textAlign: 'center' },
    },
  ];

  componentDidMount() {
    ensureSuiteStyles();
    this.props.setTitle('Route posture');
    securityCall('/_posture')
      .then((r) => this.setState({ summary: r.summary || null, error: null }))
      .catch((e) => this.setState({ error: String(e.message || e) }));
  }

  fetch = () =>
    securityCall('/_posture').then((r) => {
      this.setState({ summary: r.summary || null });
      return r.routes || [];
    });

  renderSummary = () => {
    const s = this.state.summary;
    if (!s) return null;
    return React.createElement(
      'div',
      { className: 'suite-rows', style: { marginBottom: 12 } },
      React.createElement(
        'div',
        { className: 'suite-row' },
        React.createElement('span', null, 'Routes'),
        React.createElement('span', null, String(s.total))
      ),
      React.createElement(
        'div',
        { className: 'suite-row' },
        React.createElement('span', null, 'Covered'),
        React.createElement('span', null, s.covered + ' of ' + s.total)
      ),
      React.createElement(
        'div',
        { className: 'suite-row' },
        React.createElement('span', null, 'Enforcing'),
        React.createElement('span', null, s.enforcing + ' of ' + s.total)
      ),
      React.createElement(
        'div',
        { className: 'suite-row' },
        React.createElement('span', null, 'Unprotected'),
        React.createElement(
          'span',
          null,
          s.uncovered > 0
            ? postureBadge(s.uncovered + ' route' + (s.uncovered > 1 ? 's' : ''), 'danger')
            : postureBadge('none', 'success')
        )
      )
    );
  };

  render() {
    return React.createElement(
      'div',
      null,
      this.state.error && React.createElement('div', { className: 'suite-notice' }, this.state.error),
      this.renderSummary(),
      React.createElement(
        Table,
        {
          parentProps: this.props,
          selfUrl: 'extensions/cloud-apim/waf/posture',
          defaultTitle: 'Route posture',
          itemName: 'Route',
          columns: this.columns,
          fetchItems: () => this.fetch(),
          // derived from live state: there is nothing here to create, edit or delete
          showActions: false,
          showLink: false,
          hideAllActions: true,
          hideAddItemAction: true,
          hideEditButton: true,
          rowNavigation: false,
          extractKey: (item) => item.route_id,
          defaultValue: () => ({}),
          formSchema: {},
          formFlow: [],
          export: false,
        },
        null
      )
    );
  }
}
