import { backend } from './backend';

// The incident console, route posture, the shared state, the reputation sources and the rule feeds, as the
// extension serves them for the whole gateway (core/ops.js, `globalOps`). None of these read analytics: they work
// before an exporter exists.

const g = (name) => (body) => backend.runGlobal(name, body === undefined ? {} : { body });

export const Security = {
  status: g('security.status'),
  posture: g('security.posture'),
  bans: g('security.bans'),
  incidents: g('security.incidents'),
  allowlist: g('security.allowlist'),
  botCatalog: g('security.botCatalog'),
  challengePresets: g('security.challengePresets'),
  ban: g('security.ban'),
  unban: g('security.unban'),
  extend: g('security.extend'),
  allow: g('security.allow'),
  disallow: g('security.disallow'),
  incidentState: g('security.incidentState'),
  ledger: g('security.ledger'),
  simulate: g('security.simulate'),
  robotsTxt: g('security.robotsTxt'),
  challengeFromPreset: g('security.challengeFromPreset'),
  alertTest: g('security.alertTest'),
  scannerTest: g('security.scannerTest'),
  contractCheck: g('security.contractCheck'),
  contractFetch: g('security.contractFetch'),
};

/** WAF-2, WAF-3: where each rule feed stands, and what an operator does about it. */
export const RuleFeeds = {
  status: g('feeds.status'),
  refresh: g('feeds.refresh'),
  promote: g('feeds.promote'),
  rollback: g('feeds.rollback'),
};

export const Reputation = {
  status: g('reputation.status'),
  catalog: g('reputation.catalog'),
  refresh: g('reputation.refresh'),
  rollback: g('reputation.rollback'),
  lookup: g('reputation.lookup'),
  geo: g('reputation.geo'),
  crowdsecSync: g('reputation.crowdsecSync'),
  template: g('reputation.template'),
};

/* ---------- what one workspace sees and does, through the operations of the studio admin api ---------- */

/** False positive candidates of the workspace's routes, and the exclusions they become on its own config. */
export const workspaceTuning = (wsId) => ({
  matches: () => backend.run('tuning.matches', wsId),
  propose: (body) => backend.run('tuning.propose', wsId, { body }),
  preview: (body) => backend.run('tuning.preview', wsId, { body }),
  apply: (body) => backend.run('tuning.apply', wsId, { body }),
});

/** Learning windows, on a config of the workspace. */
export const workspaceLearning = (wsId) => ({
  running: () => backend.run('learning.status', wsId),
  start: (body) => backend.run('learning.start', wsId, { body }),
  stop: (body) => backend.run('learning.stop', wsId, { body }),
  discard: (body) => backend.run('learning.discard', wsId, { body }),
  report: (body) => backend.run('learning.report', wsId, { body }),
  apply: (body) => backend.run('learning.apply', wsId, { body }),
});

/** The callers seen on the workspace's routes, and what it may do about them. */
export const workspaceSecurity = (wsId) => ({
  incidents: () => backend.run('incidents.list', wsId),
  bans: () => backend.run('bans.list', wsId),
  incidentState: (body) => backend.run('incidents.setState', wsId, { body }),
  ban: (body) => backend.run('bans.create', wsId, { body }),
  extend: (body) => backend.run('bans.extend', wsId, { body }),
  unban: (body) => backend.run('bans.remove', wsId, { body }),
  apiReport: (zombieDays) => backend.run('api.report', wsId, { query: { zombie_days: zombieDays || 90 } }),
  robotsTxt: (body) => backend.run('bots.robotsTxt', wsId, { body }),
  lookup: (body) => backend.run('reputation.lookup', wsId, { body }),
});
