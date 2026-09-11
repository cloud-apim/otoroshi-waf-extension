// ---------------------------------------------------------------------------------------------
// The suite's front door.
//
// Fifteen pages is too many to arrive at cold. This is not a list of links — the sidebar already
// is one, and a second copy of it would be worse. It answers two questions instead: what shape is
// this thing, and what should I do next *on this install*. The numbers are live, and the one line
// at the top is chosen from them.
// ---------------------------------------------------------------------------------------------

const SUITE_STAGES = [
  { n: '01', t: 'Identify', d: 'address, apikey, user, fingerprint' },
  { n: '02', t: 'Detect', d: 'reputation, bots, WAF, honeypots' },
  { n: '03', t: 'Score', d: 'one shared judgement, with attribution' },
  { n: '04', t: 'Decide', d: 'log · tarpit · challenge · deny · ban' },
  { n: '05', t: 'Remember', d: 'ledger and cluster-wide bans' },
];

const SUITE_TOOLS = [
  {
    group: 'Decide what happens',
    help: 'The fabric everything else plugs into. Start here.',
    items: [
      { t: 'Threat policies', d: 'Turn an accumulated score into one graded action', p: 'threatpolicies', i: 'sliders-h' },
      { t: 'Bans & incidents', d: 'Who is held right now, on what evidence, and what to do about them', p: 'security', i: 'gavel' },
    ],
  },
  {
    group: 'Inspect the payload',
    help: 'ModSecurity SecLang and the OWASP Core Rule Set.',
    items: [
      { t: 'WAF configs', d: 'Rules, body limits, and the CRS paranoia and threshold dials', p: 'wafconfigs', i: 'atom' },
      { t: 'WAF rulesets', d: 'Reusable bodies of SecLang, shared across configs', p: 'wafrulesets', i: 'layer-group' },
    ],
  },
  {
    group: 'Judge the caller',
    help: 'What can be decided before the payload is even parsed.',
    items: [
      { t: 'Threat feed catalog', d: 'Curated sources, ready to enable', p: 'threatfeedcatalog', i: 'book' },
      { t: 'Threat feeds', d: 'IP reputation feeds and their refresh', p: 'threatfeeds', i: 'shield-alt' },
      { t: 'ASN databases', d: 'Residential, hosting, VPN — a signal, never a rule on its own', p: 'asndatabases', i: 'project-diagram' },
      { t: 'CrowdSec bouncers', d: 'Consume community decisions, and report back', p: 'crowdsecbouncers', i: 'crow' },
    ],
  },
  {
    group: 'Tell humans from machines',
    help: 'The layer customers ask about by name.',
    items: [
      { t: 'Bot policies', d: 'Verify crawlers, and decide what AI agents may do', p: 'botpolicies', i: 'robot' },
      { t: 'Challenge providers', d: 'Proof of work, and vendor widgets for procurement', p: 'challengeproviders', i: 'puzzle-piece' },
      { t: 'Challenge presets', d: 'Ready-made vendor settings', p: 'challengepresets', i: 'store' },
      { t: 'Honeypots', d: 'Paths and canary tokens that prove intent', p: 'honeypots', i: 'bug' },
    ],
  },
  {
    group: 'Operate it',
    help: 'The half that decides whether you ever dare turn blocking on.',
    items: [
      { t: 'Route posture', d: 'Which routes are protected, in which mode — right now', p: 'posture', i: 'clipboard-check' },
      { t: 'WAF tuning', d: 'Turn one false positive into a verified exclusion', p: 'tuning', i: 'wand-magic-sparkles' },
      { t: 'WAF learning mode', d: 'Measure a window, then decide about arming with evidence', p: 'learning', i: 'graduation-cap' },
    ],
  },
];

function suiteLink(path) {
  return '/bo/dashboard/extensions/cloud-apim/waf/' + path;
}

class SecurityHomePage extends Component {
  state = { posture: null, status: null, tuning: null, learning: null, error: null };

  componentDidMount() {
    ensureSuiteStyles();
    suiteCenterPage(true);
    this.props.setTitle('Cloud APIM - Security Suite');
    this.load();
  }

  componentWillUnmount() {
    suiteCenterPage(false);
  }

  load = () => {
    const get = (p) =>
      fetch(p, { credentials: 'include', headers: { Accept: 'application/json' } })
        .then((r) => r.json())
        // one endpoint being unavailable must not blank the whole page
        .catch(() => null);
    Promise.all([
      get('/extensions/cloud-apim/extensions/waf/security/_posture'),
      get('/extensions/cloud-apim/extensions/waf/security/_status'),
      get('/extensions/cloud-apim/extensions/waf/tuning/_matches'),
      get('/extensions/cloud-apim/extensions/waf/learning/_running'),
    ]).then(([posture, status, tuning, learning]) =>
      this.setState({ posture: posture, status: status, tuning: tuning, learning: learning })
    );
  };

  /**
   * The one thing worth saying, picked from what is actually true here.
   *
   * Ordered by what blocks what: an unprotected route beats a tuning backlog, and a shared state
   * that is not shared beats both because it makes the other numbers unreliable.
   */
  nextStep = () => {
    const s = this.state;
    const summary = (s.posture && s.posture.summary) || null;
    const shared = (s.status && s.status.shared_state) || {};
    const incidents = (s.status && s.status.incidents) || {};
    const bans = (s.status && s.status.bans) || {};
    const candidates = ((s.tuning && s.tuning.matches) || []).length;
    const running = ((s.learning && s.learning.running) || []).length;

    if (summary && summary.total === 0) {
      return { tone: 'neutral', text: 'No routes yet. Create one in Otoroshi, then come back and attach the preset.', to: null };
    }
    if (summary && summary.uncovered > 0) {
      return {
        tone: 'danger',
        text:
          summary.uncovered + ' of ' + summary.total + ' routes have nothing attached. That is the number to move first.',
        to: 'posture',
        cta: 'Open route posture',
      };
    }
    if (shared.warning) {
      return { tone: 'warning', text: shared.warning, to: 'security', cta: 'See the node state' };
    }
    if (summary && summary.enforcing === 0 && summary.covered > 0) {
      return {
        tone: 'warning',
        text:
          'Every protected route is in observation — nothing can stop a request yet. That is the right place to start, and the wrong place to stay.',
        to: 'learning',
        cta: 'Measure before arming',
      };
    }
    if (candidates > 0) {
      return {
        tone: 'warning',
        text: candidates + ' false-positive candidates are waiting. Each one left alone is a reason someone gives for switching the WAF off.',
        to: 'tuning',
        cta: 'Open the tuning assistant',
      };
    }
    if (running > 0) {
      return { tone: 'info', text: running + ' learning window(s) open. Read the report when you have enough traffic.', to: 'learning', cta: 'Open learning mode' };
    }
    if ((incidents.held || 0) > 0 || (bans.bans || 0) > 0) {
      return { tone: 'info', text: 'Something is being held right now.', to: 'security', cta: 'Open bans & incidents' };
    }
    return { tone: 'success', text: 'Nothing is asking for your attention.', to: null };
  };

  renderPipeline = () =>
    suitePanel('pipeline', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'How the suite is put together'),
      React.createElement(
        'div',
        { key: 'l', className: 'suite-meta', style: { marginBottom: 12, maxWidth: '70ch' } },
        'Many independent detectors, one shared judgement, one component that acts on it. No detector ' +
          'blocks on its own — which is what makes it safe to turn them all on and decide later how much ' +
          'any of them is worth.'
      ),
      React.createElement(
        'div',
        { key: 's', style: { display: 'flex', gap: 8, flexWrap: 'wrap' } },
        SUITE_STAGES.map((s) =>
          React.createElement(
            'div',
            {
              key: s.n,
              style: {
                flex: '1 1 150px',
                minWidth: 150,
                border: '1px solid var(--border-color)',
                borderRadius: 3,
                padding: '8px 10px',
                background: 'var(--bg-color_level3)',
              },
            },
            React.createElement('div', { className: 'suite-meta', style: { letterSpacing: '.08em' } }, s.n),
            React.createElement('div', { style: { color: 'var(--color_level3)', fontWeight: 600 } }, s.t),
            React.createElement('div', { className: 'suite-meta' }, s.d)
          )
        )
      ),
    ]);

  renderState = () => {
    const s = this.state;
    const summary = (s.posture && s.posture.summary) || null;
    const shared = (s.status && s.status.shared_state) || {};
    const incidents = (s.status && s.status.incidents) || {};
    const bans = (s.status && s.status.bans) || {};
    const candidates = ((s.tuning && s.tuning.matches) || []).length;
    const running = ((s.learning && s.learning.running) || []).length;
    const step = this.nextStep();

    const rows = [
      ['Routes', summary ? summary.covered + ' of ' + summary.total + ' protected, ' + summary.enforcing + ' enforcing' : '—'],
      ['Held right now', (bans.bans || 0) + ' bans, ' + (incidents.held || 0) + ' incidents'],
      ['Tuning candidates', String(candidates)],
      ['Learning windows open', String(running)],
      ['Shared state', shared.dedicated_redis ? 'dedicated redis' : 'otoroshi storage'],
    ];

    return suitePanel('state', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'Where this install stands'),
      suiteNotice(
        'next',
        step.tone,
        step.to
          ? React.createElement(
              'span',
              null,
              step.text + ' ',
              React.createElement('a', { href: suiteLink(step.to) }, step.cta + ' →')
            )
          : step.text
      ),
      suiteRows(rows, 200),
    ]);
  };

  renderTools = () =>
    SUITE_TOOLS.map((group) =>
      suitePanel('g-' + group.group, [
        React.createElement('div', { key: 't', className: 'suite-title' }, group.group),
        React.createElement('div', { key: 'h', className: 'suite-meta', style: { marginBottom: 10 } }, group.help),
        React.createElement(
          'div',
          { key: 'i', style: { display: 'flex', gap: 8, flexWrap: 'wrap' } },
          group.items.map((item) =>
            React.createElement(
              'a',
              {
                key: item.p,
                href: suiteLink(item.p),
                style: {
                  flex: '1 1 260px',
                  minWidth: 260,
                  border: '1px solid var(--border-color)',
                  borderRadius: 3,
                  padding: '10px 12px',
                  background: 'var(--bg-color_level3)',
                  textDecoration: 'none',
                  display: 'block',
                },
              },
              React.createElement(
                'div',
                { style: { color: 'var(--color_level3)', fontWeight: 600, marginBottom: 2 } },
                React.createElement('i', { className: 'fas fa-' + item.i, style: { marginRight: 8, opacity: 0.7 } }),
                item.t
              ),
              React.createElement('div', { className: 'suite-meta' }, item.d)
            )
          )
        ),
      ])
    );

  renderDocs = () =>
    suitePanel('docs', [
      React.createElement('div', { key: 't', className: 'suite-title' }, 'If you are starting from nothing'),
      React.createElement(
        'div',
        { key: 'b', className: 'suite-meta', style: { maxWidth: '72ch' } },
        'One plugin slot — Cloud APIM Security Suite - Preset — expands into the five plugins in the ' +
          'right order, which is the part that is easy to get wrong by hand. Attach it to a route, leave ' +
          'everything in observation, and read what it says before arming anything.'
      ),
    ]);

  render() {
    return React.createElement(
      'div',
      null,
      this.state.error ? suiteNotice('e', 'danger', this.state.error) : null,
      this.renderState(),
      this.renderPipeline(),
      this.renderDocs(),
      ...this.renderTools()
    );
  }
}
