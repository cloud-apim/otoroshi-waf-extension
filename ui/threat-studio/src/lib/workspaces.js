import { api, STUDIO_API } from './api';
import { randomId } from './entities';

/**
 * A workspace *is* a rule of the global preset table.
 *
 * There is no workspace entity anywhere: the studio reads the table off the global plugins, resolved
 * against the router by the extension (the selectors go through the expression language, so only the
 * gateway can answer which routes a rule claims), and writes it back whole.
 *
 * The table is ordered and the first matching rule wins, so the order of this array is not a display
 * preference — it is the configuration. Everything that reorders goes through `move`.
 */

export const PRESET_DEFAULTS = {
  threat_policy: null,
  bot_policy: null,
  waf_config: null,
  gate: true,
  bots: true,
  reputation: true,
  waf: true,
  fail2ban: false,
  response: true,
  reputation_mode: 'block',
  fail2ban_dry_run: true,
  include: [],
  exclude: [],
};

/** The sections a preset expands into, in the order they run. */
export const SECTIONS = [
  { key: 'gate', label: 'Threat gate', help: 'Refuse callers that are already banned, before anything else runs' },
  { key: 'bots', label: 'Bot guard', help: 'Identify crawlers and verify the ones that publish a method' },
  { key: 'reputation', label: 'IP reputation', help: 'Score the caller against every enabled feed, CrowdSec bouncer and ASN database' },
  { key: 'fail2ban', label: 'Fail2ban', help: 'Ban callers that keep producing failed responses — the one detector your own clients can trip' },
  { key: 'waf', label: 'WAF', help: 'Run the rule engine. Needs a WAF config to expand at all.' },
  { key: 'response', label: 'Threat response', help: 'Read the accumulated score and apply one graded action. Without it nothing enforces the score.' },
];

export function loadTable() {
  return api.get(`${STUDIO_API}/workspaces`);
}

export function loadWorkspaceRoutes(id) {
  return api.get(`${STUDIO_API}/workspaces/${encodeURIComponent(id)}/routes`);
}

/** The payload the extension expects: the table, whole, in order. */
function payloadOf(table, { slotEnabled } = {}) {
  return {
    skip_protected_routes: table.skip_protected_routes !== false,
    ...(slotEnabled === undefined ? {} : { slot_enabled: slotEnabled }),
    rules: (table.workspaces || []).map((w) => ({
      id: w.id,
      name: w.name,
      enabled: w.enabled !== false,
      skip: !!w.skip,
      targets: w.targets || [],
      preset: { ...PRESET_DEFAULTS, ...(w.preset || {}) },
    })),
  };
}

/** Saves and returns the table as the extension resolved it again — counts included. */
export function saveTable(table, opts) {
  return api.put(`${STUDIO_API}/workspaces`, payloadOf(table, opts));
}

export function emptyWorkspace(name) {
  return {
    id: `ws_${randomId(10)}`,
    name: name || 'New workspace',
    enabled: true,
    skip: false,
    targets: [],
    preset: { ...PRESET_DEFAULTS },
  };
}

export function emptyTarget() {
  return { path: '$.tags', expression: null, value: 'Contains(something)' };
}

/* ---------- pure table transformations ---------- */

export function replaceWorkspace(table, id, fn) {
  return { ...table, workspaces: table.workspaces.map((w) => (w.id === id ? fn(w) : w)) };
}

export function addWorkspace(table, workspace) {
  // new rules land above the catch-all, which is the only place a narrow rule can ever win
  const catchAll = table.workspaces.findIndex((w) => w.enabled !== false && (w.targets || []).length === 0);
  const at = catchAll < 0 ? table.workspaces.length : catchAll;
  const next = table.workspaces.slice();
  next.splice(at, 0, workspace);
  return { ...table, workspaces: next };
}

export function removeWorkspace(table, id) {
  return { ...table, workspaces: table.workspaces.filter((w) => w.id !== id) };
}

export function moveWorkspace(table, id, delta) {
  const idx = table.workspaces.findIndex((w) => w.id === id);
  const to = idx + delta;
  if (idx < 0 || to < 0 || to >= table.workspaces.length) return table;
  const next = table.workspaces.slice();
  const [item] = next.splice(idx, 1);
  next.splice(to, 0, item);
  return { ...table, workspaces: next };
}

/* ---------- reading a workspace ---------- */

export function isCatchAll(ws) {
  return (ws.targets || []).length === 0;
}

/** What the workspace lays down, as short labels, in expansion order. */
export function armedSections(ws) {
  if (ws.skip) return [];
  const preset = { ...PRESET_DEFAULTS, ...(ws.preset || {}) };
  return SECTIONS.filter((s) => preset[s.key] && (s.key !== 'waf' || !!preset.waf_config)).map((s) => s.label);
}

/**
 * Whether anything this workspace lays down can actually stop a request.
 *
 * The same distinction route posture makes, asked of the configuration alone: a workspace can arm
 * every section and enforce nothing, which is exactly what a dry run is for and exactly what makes a
 * rollout stall unnoticed.
 */
export function enforcementOf(ws, { policies = [], configs = [] } = {}) {
  const preset = { ...PRESET_DEFAULTS, ...(ws.preset || {}) };
  if (ws.skip) return { armed: false, reason: 'this workspace lays down nothing' };
  const policy = policies.find((p) => p.id === preset.threat_policy);
  const config = configs.find((c) => c.id === preset.waf_config);
  const reasons = [];
  if (preset.waf && config && config.block) reasons.push('the WAF blocks');
  if (preset.response && policy && !policy.dry_run) reasons.push('the threat response enforces');
  if (preset.reputation && preset.reputation_mode === 'block') reasons.push('IP reputation blocks');
  if (preset.fail2ban && !preset.fail2ban_dry_run) reasons.push('fail2ban bans');
  if (reasons.length > 0) return { armed: true, reason: reasons.join(', ') };
  if (preset.response && !preset.threat_policy) return { armed: false, reason: 'no threat policy: the built-in one is dry run' };
  if (preset.response && policy && policy.dry_run) return { armed: false, reason: `${policy.name} is in dry run` };
  return { armed: false, reason: 'everything here only observes' };
}

/** Warnings worth showing beside a workspace, ordered by what blocks what. */
export function lintWorkspace(ws, table) {
  const preset = { ...PRESET_DEFAULTS, ...(ws.preset || {}) };
  const out = [];
  if (ws.unreachable) {
    out.push({ kind: 'error', text: 'Unreachable: a rule above this one claims every route, so this one is never reached.' });
  }
  if (ws.enabled === false) out.push({ kind: 'warning', text: 'Disabled: this rule is skipped entirely.' });
  if (!ws.skip && (ws.claims || []).length === 0 && (ws.matches || []).length > 0) {
    out.push({ kind: 'warning', text: 'Every route it matches is already claimed by a rule above it.' });
  }
  if (!ws.skip && (ws.matches || []).length === 0 && !ws.unreachable) {
    out.push({ kind: 'warning', text: 'No route matches its targets right now.' });
  }
  if (!ws.skip && preset.waf && !preset.waf_config) {
    out.push({ kind: 'warning', text: 'The WAF section is on but no WAF config is selected, so no rule engine runs.' });
  }
  if (!ws.skip && !preset.response) {
    out.push({ kind: 'warning', text: 'No threat response: the score is accumulated and never acted on.' });
  }
  if (!ws.route_only) {
    out.push({
      kind: 'info',
      text: 'A selector reads the request, so the routes below are what the table resolves with no request in flight.',
    });
  }
  if (table && table.enabled === false) {
    out.push({ kind: 'error', text: 'The global preset plugin is disabled: nothing in this table applies.' });
  }
  return out;
}
