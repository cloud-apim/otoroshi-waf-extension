// The operations of Threat Studio. Each one names the access it needs and the route of the admin api that serves
// it: the studio admin api (studio/api.scala) for what is about one workspace, and for the rest the extension's
// module routes, the entities of the suite and the analytics, as the admin api serves them. The OSS studio calls
// them through the backoffice (lib/backend.js), with the rights of the signed-in user; another server can check
// the access of its own user first, then call the same route. Nothing here depends on the browser, so such a
// server can load this file as it is.

// where the studio admin api is served
export const STUDIO_ADMIN_PATH = '/api/extensions/cloud-apim/extensions/waf/studio';
// where the extension's module routes are served on the admin api
export const MODULES_ADMIN_PATH = '/api/extensions/cloud-apim/extensions/waf';
const ENTITIES = '/apis/waf.extensions.cloud-apim.com/v1';

// what can be done on a workspace
export const PERMISSIONS = [
  'workspace:read',
  'activity:read',
  'activity:details',
  'config:read',
  'incidents:respond',
  'config:write',
  'members:read',
  'members:manage',
];

// what can be done on the gateway as a whole: read everything, or change it
export const GLOBAL_PERMISSIONS = ['admin:read', 'admin'];

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

// An operation without a known access or a route fails the loading of the whole registry: nothing runs with an
// access nobody decided.
function define(ops, permissions) {
  Object.entries(ops).forEach(([name, op]) => {
    if (!permissions.includes(op.access)) throw new Error(`the operation '${name}' declares no known access`);
    if (!METHODS.includes(op.method) || typeof op.path !== 'string') throw new Error(`the operation '${name}' has no route`);
  });
  return Object.freeze(ops);
}

// `path` is relative to the workspace (`/workspaces/:ws` of the studio admin api), its `:params` come from the
// input of the call. `audit` marks the writes worth recording, `reveals` gives a secret that is masked everywhere
// else.
export const ops = define(
  {
    // the workspace, as the table resolves it
    'workspace.get': { access: 'workspace:read', method: 'GET', path: '' },
    'workspace.routes': { access: 'workspace:read', method: 'GET', path: '/routes' },
    'workspace.rename': { access: 'config:write', method: 'PATCH', path: '', audit: true },

    // its protection: the fields a page edits, the others are kept
    'preset.save': { access: 'config:write', method: 'PATCH', path: '/preset', audit: true },

    // the entities it may see: its own, and the ones that belong to no workspace
    'entities.list': { access: 'config:read', method: 'GET', path: '/entities/:kind' },
    'entities.template': { access: 'config:read', method: 'GET', path: '/entities/:kind/_template' },
    'entities.get': { access: 'config:read', method: 'GET', path: '/entities/:kind/:eid' },
    'entities.usage': { access: 'config:read', method: 'GET', path: '/entities/:kind/:eid/_usage' },
    'entities.create': { access: 'config:write', method: 'POST', path: '/entities/:kind', audit: true },
    'entities.update': { access: 'config:write', method: 'PUT', path: '/entities/:kind/:eid', audit: true },
    'entities.delete': { access: 'config:write', method: 'DELETE', path: '/entities/:kind/:eid', audit: true },
    'entities.fork': { access: 'config:write', method: 'POST', path: '/entities/:kind/:eid/_fork', audit: true },

    // the contract each of its routes names
    'routes.setContract': { access: 'config:write', method: 'PUT', path: '/routes/:rid/contract', audit: true },

    // computations its pages make, which read nothing of another workspace
    'waf.compile': { access: 'config:read', method: 'POST', path: '/waf/_compile' },
    'rules.describe': { access: 'workspace:read', method: 'POST', path: '/rules/_describe' },
    'contracts.check': { access: 'config:read', method: 'POST', path: '/contracts/_check' },
    'bots.robotsTxt': { access: 'config:read', method: 'POST', path: '/bots/_robots_txt' },
    'bots.catalog': { access: 'config:read', method: 'GET', path: '/bots/catalog' },
    'challenges.presets': { access: 'config:read', method: 'GET', path: '/challenge-presets' },
    'challenges.build': { access: 'config:read', method: 'POST', path: '/challenge-presets/_build' },

    // false positives: the candidates are fragments of requests
    'tuning.matches': { access: 'activity:details', method: 'GET', path: '/tuning/matches' },
    'tuning.propose': { access: 'activity:details', method: 'POST', path: '/tuning/_propose' },
    'tuning.preview': { access: 'activity:details', method: 'POST', path: '/tuning/_preview' },
    'tuning.apply': { access: 'config:write', method: 'POST', path: '/tuning/_apply', audit: true },

    // learning, on a configuration of the workspace
    'learning.status': { access: 'config:read', method: 'GET', path: '/learning' },
    'learning.report': { access: 'activity:details', method: 'POST', path: '/learning/_report' },
    'learning.start': { access: 'config:write', method: 'POST', path: '/learning/_start', audit: true },
    'learning.stop': { access: 'config:write', method: 'POST', path: '/learning/_stop', audit: true },
    'learning.discard': { access: 'config:write', method: 'POST', path: '/learning/_discard', audit: true },
    'learning.apply': { access: 'config:write', method: 'POST', path: '/learning/_apply', audit: true },

    // its traffic, always narrowed to its routes by the api; a detail is one event, raw
    'analytics.query': { access: 'activity:read', method: 'POST', path: '/analytics/_query' },
    'analytics.detail': { access: 'activity:details', method: 'POST', path: '/analytics/_query' },
    'api.report': { access: 'activity:read', method: 'GET', path: '/api-report' },
    'reputation.lookup': { access: 'activity:read', method: 'POST', path: '/reputation/_lookup' },
    'geo.lookup': { access: 'workspace:read', method: 'POST', path: '/reputation/_geo' },

    // its callers
    'incidents.list': { access: 'activity:read', method: 'GET', path: '/incidents' },
    'incidents.setState': { access: 'incidents:respond', method: 'POST', path: '/incidents/_state', audit: true },
    'bans.list': { access: 'activity:read', method: 'GET', path: '/bans' },
    'bans.create': { access: 'incidents:respond', method: 'POST', path: '/bans', audit: true },
    'bans.extend': { access: 'incidents:respond', method: 'POST', path: '/bans/_extend', audit: true },
    'bans.remove': { access: 'incidents:respond', method: 'POST', path: '/bans/_unban', audit: true },
  },
  PERMISSIONS
);

const security = (action) => `${MODULES_ADMIN_PATH}/security/${action}`;
const reputation = (action) => `${MODULES_ADMIN_PATH}/reputation/${action}`;
const feeds = (action) => `${MODULES_ADMIN_PATH}/feeds/${action}`;

// What is about the gateway as a whole: the table and the scope of the workspaces, the entities of the suite, the
// shared state, the reputation sources and the rule feeds. `path` is a path of the admin api.
export const globalOps = define(
  {
    // the table
    'table.get': { access: 'admin:read', method: 'GET', path: `${STUDIO_ADMIN_PATH}/workspaces` },
    'table.preview': { access: 'admin:read', method: 'POST', path: `${STUDIO_ADMIN_PATH}/table/_preview` },
    'table.settings': { access: 'admin', method: 'PUT', path: `${STUDIO_ADMIN_PATH}/table/settings`, audit: true },
    'workspaces.create': { access: 'admin', method: 'POST', path: `${STUDIO_ADMIN_PATH}/workspaces`, audit: true },
    'workspaces.delete': { access: 'admin', method: 'DELETE', path: `${STUDIO_ADMIN_PATH}/workspaces/:ws`, audit: true },
    'workspaces.setScope': { access: 'admin', method: 'PUT', path: `${STUDIO_ADMIN_PATH}/workspaces/:ws/scope`, audit: true },
    'workspaces.move': { access: 'admin', method: 'POST', path: `${STUDIO_ADMIN_PATH}/workspaces/:ws/_move`, audit: true },
    // what a change of one rule would do to the routes, before it is made
    'workspaces.previewScope': { access: 'admin:read', method: 'POST', path: `${STUDIO_ADMIN_PATH}/workspaces/:ws/scope/_preview` },
    'workspaces.previewMove': { access: 'admin:read', method: 'POST', path: `${STUDIO_ADMIN_PATH}/workspaces/:ws/_move/_preview` },
    'workspaces.previewDelete': { access: 'admin:read', method: 'POST', path: `${STUDIO_ADMIN_PATH}/workspaces/:ws/_delete/_preview` },

    // every entity of the suite
    'entities.list': { access: 'admin:read', method: 'GET', path: `${ENTITIES}/:kind` },
    'entities.template': { access: 'admin:read', method: 'GET', path: `${ENTITIES}/:kind/_template` },
    'entities.get': { access: 'admin:read', method: 'GET', path: `${ENTITIES}/:kind/:eid` },
    'entities.reveal': { access: 'admin', method: 'GET', path: `${ENTITIES}/:kind/:eid`, reveals: true, audit: true },
    'entities.create': { access: 'admin', method: 'POST', path: `${ENTITIES}/:kind`, audit: true },
    'entities.update': { access: 'admin', method: 'PUT', path: `${ENTITIES}/:kind/:eid`, audit: true },
    'entities.delete': { access: 'admin', method: 'DELETE', path: `${ENTITIES}/:kind/:eid`, audit: true },
    // who the entities a workspace may own belong to, and an entity given to a workspace or to none
    'entities.ownership': { access: 'admin:read', method: 'GET', path: `${STUDIO_ADMIN_PATH}/entities/_ownership` },
    'entities.assign': { access: 'admin', method: 'POST', path: `${STUDIO_ADMIN_PATH}/entities/:kind/:eid/_assign`, audit: true },

    // the routes of the gateway, and the one metadata entry the studio writes on them
    'routes.list': { access: 'admin:read', method: 'GET', path: '/apis/proxy.otoroshi.io/v1/routes' },
    'routes.patch': { access: 'admin', method: 'PATCH', path: '/apis/proxy.otoroshi.io/v1/routes/:rid', audit: true },

    // the analytics of the whole gateway
    'analytics.query': { access: 'admin:read', method: 'POST', path: '/api/analytics/_query' },

    // the shared state and the posture of the fleet
    'security.status': { access: 'admin:read', method: 'GET', path: security('_status') },
    'security.posture': { access: 'admin:read', method: 'GET', path: security('_posture') },
    'security.bans': { access: 'admin:read', method: 'GET', path: security('_bans') },
    'security.incidents': { access: 'admin:read', method: 'GET', path: security('_incidents') },
    'security.allowlist': { access: 'admin:read', method: 'GET', path: security('_allowlist') },
    'security.apiReport': { access: 'admin:read', method: 'GET', path: security('_api_report') },
    'security.ban': { access: 'admin', method: 'POST', path: security('_ban'), audit: true },
    'security.unban': { access: 'admin', method: 'POST', path: security('_unban'), audit: true },
    'security.extend': { access: 'admin', method: 'POST', path: security('_extend'), audit: true },
    'security.allow': { access: 'admin', method: 'POST', path: security('_allow'), audit: true },
    'security.disallow': { access: 'admin', method: 'POST', path: security('_disallow'), audit: true },
    'security.incidentState': { access: 'admin', method: 'POST', path: security('_incident_state'), audit: true },
    'security.ledger': { access: 'admin', method: 'POST', path: security('_ledger'), audit: true },
    'security.simulate': { access: 'admin:read', method: 'POST', path: security('_simulate') },
    'security.botCatalog': { access: 'admin:read', method: 'GET', path: security('_bot_catalog') },
    'security.robotsTxt': { access: 'admin:read', method: 'POST', path: security('_robots_txt') },
    'security.challengePresets': { access: 'admin:read', method: 'GET', path: security('_challenge_presets') },
    'security.challengeFromPreset': { access: 'admin:read', method: 'POST', path: security('_challenge_from_preset') },
    'security.contractCheck': { access: 'admin:read', method: 'POST', path: security('_contract_check') },
    // these three have the gateway reach an address
    'security.alertTest': { access: 'admin', method: 'POST', path: security('_alert_test'), audit: true },
    'security.scannerTest': { access: 'admin', method: 'POST', path: security('_scanner_test'), audit: true },
    'security.contractFetch': { access: 'admin', method: 'POST', path: security('_contract_fetch'), audit: true },

    // reputation sources
    'reputation.status': { access: 'admin:read', method: 'GET', path: reputation('_status') },
    'reputation.catalog': { access: 'admin:read', method: 'GET', path: reputation('_catalog') },
    'reputation.template': { access: 'admin:read', method: 'POST', path: reputation('_template') },
    'reputation.lookup': { access: 'admin:read', method: 'POST', path: reputation('_lookup') },
    'reputation.geo': { access: 'admin:read', method: 'POST', path: reputation('_geo') },
    'reputation.refresh': { access: 'admin', method: 'POST', path: reputation('_refresh'), audit: true },
    'reputation.rollback': { access: 'admin', method: 'POST', path: reputation('_rollback'), audit: true },
    'reputation.crowdsecSync': { access: 'admin', method: 'POST', path: reputation('_crowdsec_sync'), audit: true },

    // rule feeds
    'feeds.status': { access: 'admin:read', method: 'GET', path: feeds('_status') },
    'feeds.check': { access: 'admin:read', method: 'POST', path: feeds('_check') },
    'feeds.refresh': { access: 'admin', method: 'POST', path: feeds('_refresh'), audit: true },
    'feeds.promote': { access: 'admin', method: 'POST', path: feeds('_promote'), audit: true },
    'feeds.rollback': { access: 'admin', method: 'POST', path: feeds('_rollback'), audit: true },

    // the engine
    'utils.compile': { access: 'admin:read', method: 'POST', path: `${MODULES_ADMIN_PATH}/utils/_compile` },
    'utils.rules': { access: 'admin:read', method: 'POST', path: `${MODULES_ADMIN_PATH}/utils/_rules` },
  },
  GLOBAL_PERMISSIONS
);

// One segment of a path. `encodeURIComponent` leaves `.` and `..` as they are, and a url resolves them: an id of
// `..` would call the route above the one of the operation.
function segment(name, param, value) {
  const text = String(value);
  if (text === '.' || text === '..') throw new Error(`the operation '${name}' got an invalid '${param}'`);
  return encodeURIComponent(text);
}

function fill(name, path, input) {
  return path.replace(/:([a-z]+)/g, (_, param) => {
    const value = input[param];
    if (value === undefined || value === null || value === '') throw new Error(`the operation '${name}' needs '${param}'`);
    return segment(name, param, value);
  });
}

function queryOf(input) {
  const query = Object.entries(input.query || {})
    .filter(([, value]) => value !== undefined && value !== null && value !== false)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
  return query ? `?${query}` : '';
}

/**
 * The http call of an operation on a workspace: `input` gives the `:params` of its path by name, `query` (values
 * left out when null, undefined or false) and `body`. Never an entity the api should trust: it reads what it needs.
 */
export function routeOf(name, wsId, input = {}) {
  const op = Object.hasOwn(ops, name) ? ops[name] : null;
  if (!op) throw new Error(`unknown operation '${name}'`);
  return {
    method: op.method,
    path: `/workspaces/${segment(name, 'workspace', wsId)}${fill(name, op.path, input)}${queryOf(input)}`,
    body: input.body,
  };
}

/** The same, for an operation on the gateway: its path is a path of the admin api. */
export function globalRouteOf(name, input = {}) {
  const op = Object.hasOwn(globalOps, name) ? globalOps[name] : null;
  if (!op) throw new Error(`unknown operation '${name}'`);
  return { method: op.method, path: `${fill(name, op.path, input)}${queryOf(input)}`, body: input.body };
}
