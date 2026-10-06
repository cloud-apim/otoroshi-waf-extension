import { api, EXT_API } from './api';

// The incident console, route posture and the shared state, as the extension already exposes them.
// None of these read analytics: they work before an exporter exists.

const SECURITY = `${EXT_API}/security`;
const REPUTATION = `${EXT_API}/reputation`;
const TUNING = `${EXT_API}/tuning`;
const LEARNING = `${EXT_API}/learning`;

export const Security = {
  status: () => api.get(`${SECURITY}/_status`),
  posture: () => api.get(`${SECURITY}/_posture`),
  bans: () => api.get(`${SECURITY}/_bans`),
  incidents: () => api.get(`${SECURITY}/_incidents`),
  allowlist: () => api.get(`${SECURITY}/_allowlist`),
  botCatalog: () => api.get(`${SECURITY}/_bot_catalog`),
  challengePresets: () => api.get(`${SECURITY}/_challenge_presets`),
  ban: (body) => api.post(`${SECURITY}/_ban`, body),
  unban: (body) => api.post(`${SECURITY}/_unban`, body),
  extend: (body) => api.post(`${SECURITY}/_extend`, body),
  allow: (body) => api.post(`${SECURITY}/_allow`, body),
  disallow: (body) => api.post(`${SECURITY}/_disallow`, body),
  incidentState: (body) => api.post(`${SECURITY}/_incident_state`, body),
  ledger: (body) => api.post(`${SECURITY}/_ledger`, body),
  simulate: (body) => api.post(`${SECURITY}/_simulate`, body),
  robotsTxt: (body) => api.post(`${SECURITY}/_robots_txt`, body),
  challengeFromPreset: (body) => api.post(`${SECURITY}/_challenge_from_preset`, body),
  alertTest: (body) => api.post(`${SECURITY}/_alert_test`, body),
  scannerTest: (body) => api.post(`${SECURITY}/_scanner_test`, body),
  contractCheck: (body) => api.post(`${SECURITY}/_contract_check`, body),
  contractFetch: (body) => api.post(`${SECURITY}/_contract_fetch`, body),
};

const FEEDS = `${EXT_API}/feeds`;

/** WAF-2, WAF-3: where each rule feed stands, and what an operator does about it. */
export const RuleFeeds = {
  status: () => api.get(`${FEEDS}/_status`),
  refresh: (body) => api.post(`${FEEDS}/_refresh`, body),
  promote: (body) => api.post(`${FEEDS}/_promote`, body),
  rollback: (body) => api.post(`${FEEDS}/_rollback`, body),
};

export const Reputation = {
  status: () => api.get(`${REPUTATION}/_status`),
  catalog: () => api.get(`${REPUTATION}/_catalog`),
  refresh: (body) => api.post(`${REPUTATION}/_refresh`, body),
  rollback: (body) => api.post(`${REPUTATION}/_rollback`, body),
  lookup: (body) => api.post(`${REPUTATION}/_lookup`, body),
  geo: (body) => api.post(`${REPUTATION}/_geo`, body),
  crowdsecSync: (body) => api.post(`${REPUTATION}/_crowdsec_sync`, body),
  template: (body) => api.post(`${REPUTATION}/_template`, body),
};

export const Tuning = {
  matches: () => api.get(`${TUNING}/_matches`),
  propose: (body) => api.post(`${TUNING}/_propose`, body),
  preview: (body) => api.post(`${TUNING}/_preview`, body),
  apply: (body) => api.post(`${TUNING}/_apply`, body),
};

export const Learning = {
  running: () => api.get(`${LEARNING}/_running`),
  start: (body) => api.post(`${LEARNING}/_start`, body),
  stop: (body) => api.post(`${LEARNING}/_stop`, body),
  discard: (body) => api.post(`${LEARNING}/_discard`, body),
  report: (body) => api.post(`${LEARNING}/_report`, body),
  apply: (body) => api.post(`${LEARNING}/_apply`, body),
};

export const Waf = {
  compile: (body) => api.post(`${EXT_API}/utils/_compile`, body),
  test: (body) => api.post(`${EXT_API}/utils/_test`, body),
};
