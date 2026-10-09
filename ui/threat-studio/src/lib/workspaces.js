import { backend } from './backend';
import { randomId } from './entities';

/**
 * A workspace *is* a rule of the global preset table.
 *
 * There is no workspace entity anywhere: the studio reads the table off the global plugins, resolved
 * against the router by the extension (the selectors go through the expression language, so only the
 * gateway can answer which routes a rule claims), with only the routes the signed-in user may read
 * and what they may do on each rule.
 *
 * The table is ordered and the first matching rule wins, so its order is not a display preference —
 * it is the configuration. Each write below changes one thing of one rule, and the api relays it to
 * the table as it stands when it writes: a page never sends back a copy of the whole table it read.
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
  error_leakage: false,
  error_leakage_mode: 'mask',
  sensitive_data: false,
  sensitive_data_mode: 'enforce',
  sensitive_data_detectors: {},
  uploads: false,
  uploads_mode: 'enforce',
  uploads_allowed_extensions: [],
  uploads_scanner: null,
  uploads_scan_failure_action: 'reject',
  login: false,
  login_paths: [],
  traffic: false,
  traffic_sensitivity: 'medium',
  objects: false,
  objects_mode: 'alert',
  objects_paths: [],
  objects_budget: 0,
  api_contract: false,
  api_contract_id: null,
  api_contract_mode: 'monitor',
  include: [],
  exclude: [],
};

/** Where a route names its own API contract, for a workspace that leaves it to each route. */
export const CONTRACT_META = 'cloud-apim-api-contract';


/** The sections a preset expands into, in the order they run. */
export const SECTIONS = [
  { key: 'gate', label: 'Threat gate', help: 'Refuse callers that are already banned, before anything else runs' },
  { key: 'bots', label: 'Bot guard', help: 'Identify crawlers and verify the ones that publish a method' },
  { key: 'reputation', label: 'IP reputation', help: 'Score the caller against every enabled feed, CrowdSec bouncer and ASN database' },
  { key: 'fail2ban', label: 'Fail2ban', help: 'Ban callers that keep producing failed responses — the one detector your own clients can trip' },
  { key: 'traffic', label: 'Traffic guard', help: 'Learn the usual traffic of each route, source, api key and network, and score a surge away from it' },
  { key: 'api_contract', label: 'API contract', help: 'Check every request against an OpenAPI contract: paths, methods, parameters and bodies' },
  { key: 'waf', label: 'WAF', help: 'Run the rule engine. Needs a WAF config to expand at all.' },
  { key: 'uploads', label: 'Upload guard', help: 'Refuse uploaded files by what they are: disguised scripts and executables, polyglots, archive bombs and zip slips' },
  { key: 'login', label: 'Login guard', help: 'Score credential stuffing, password spraying and likely account takeovers on the login endpoints' },
  { key: 'objects', label: 'Object guard', help: "Watch each consumer's objects for enumeration and walks through identifiers, and budget the distinct objects it reads" },
  { key: 'response', label: 'Threat response', help: 'Read the accumulated score and apply one graded action. Without it nothing enforces the score.' },
  { key: 'error_leakage', label: 'Error leakage guard', help: 'Replace stack traces, SQL errors and debug pages in responses with a neutral error, before they reach the caller' },
  { key: 'sensitive_data', label: 'Sensitive data guard', help: 'Mask card numbers, IBANs, national identifiers and secrets in responses, or refuse the response' },
];

/**
 * What the sensitive data guard looks for, with the action each one takes when a workspace says
 * nothing. The ids and defaults are the extension's own (`Detectors.all`): a detector missing here
 * still runs, with its default, it just cannot be changed from the studio.
 */
export const SENSITIVE_DETECTORS = [
  { id: 'card', label: 'Payment card numbers', family: 'Payment', default: 'mask', help: 'Issuer prefix and Luhn checked. Masked to 4111 **** **** 1111' },
  { id: 'iban', label: 'IBANs', family: 'Banking', default: 'mask', help: 'Country length and mod 97 checked' },
  { id: 'fr_nir', label: 'French social security numbers', family: 'Identity', default: 'mask', help: 'NIR, key checked, Corsica included' },
  { id: 'us_ssn', label: 'US social security numbers', family: 'Identity', default: 'mask', help: 'Dashed form, numbers never issued left out' },
  { id: 'private_key', label: 'Private keys', family: 'Secret', default: 'block', help: 'PEM blocks: RSA, EC, OpenSSH, PGP' },
  { id: 'cloud_key', label: 'Cloud provider keys', family: 'Secret', default: 'mask', help: 'AWS access keys, Google API keys, Azure storage keys' },
  { id: 'service_token', label: 'Service tokens', family: 'Secret', default: 'mask', help: 'GitHub, GitLab, Slack, Stripe live keys' },
  { id: 'llm_key', label: 'LLM provider keys', family: 'Secret', default: 'mask', help: 'OpenAI and Anthropic API keys' },
  { id: 'jwt', label: 'JSON Web Tokens', family: 'Secret', default: 'log', help: 'Reported only by default: a login endpoint returns them on purpose' },
  { id: 'email_bulk', label: 'Email addresses in bulk', family: 'Contact', default: 'log', volume: true, help: 'Fifty distinct addresses or more in one response' },
];

export function loadTable() {
  return backend.workspaces.list();
}

export function loadWorkspaceRoutes(id) {
  return backend.run('workspace.routes', id);
}

/* ---------- writing one thing of one rule ---------- */

/** The fields of the protection a page edits; the others keep their value. */
export const savePreset = (wsId, patch) => backend.run('preset.save', wsId, { body: patch });

export const renameWorkspace = (wsId, name) => backend.run('workspace.rename', wsId, { body: { name } });

/** The contract a route of the workspace names, or none. */
export const setRouteContract = (wsId, routeId, contractId) =>
  backend.run('routes.setContract', wsId, { rid: routeId, body: { contract_id: contractId || null } });

// what changes which rule wins a route is the table's, and its administrators'

/** `{ targets, enabled, skip }`, any of them. */
export const saveScope = (wsId, scope) => backend.runGlobal('workspaces.setScope', { ws: wsId, body: scope });

export const moveWorkspaceTo = (wsId, to) => backend.runGlobal('workspaces.move', { ws: wsId, body: { to } });

/** `{ id, name, targets, position, preset }`: the api puts it above the first catch-all when no position is given. */
export const createWorkspace = (form) => backend.runGlobal('workspaces.create', { body: form });

export const deleteWorkspace = (wsId) => backend.runGlobal('workspaces.delete', { ws: wsId });

/** What saving a scope, moving or deleting a workspace would do to the routes: `{ changes, hidden, allowed, reason }`. */
export const previewScope = (wsId, scope) => backend.runGlobal('workspaces.previewScope', { ws: wsId, body: scope });

export const previewMove = (wsId, to) => backend.runGlobal('workspaces.previewMove', { ws: wsId, body: { to } });

export const previewDelete = (wsId) => backend.runGlobal('workspaces.previewDelete', { ws: wsId });

/** `{ enabled, skip_protected_routes }`, any of them: `enabled` is the slot of the global preset. */
export const saveTableSettings = (settings) => backend.runGlobal('table.settings', { body: settings });

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
  if (preset.api_contract && preset.api_contract_mode === 'enforce') reasons.push('the API contract refuses');
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
