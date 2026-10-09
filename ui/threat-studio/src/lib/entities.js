import { backend } from './backend';

// Threat Studio stores nothing of its own. Every entity it creates is a plain otoroshi entity of the
// Threat Protection extension, tagged with the metadata below so the studio can find back the ones a
// workspace owns using the in-memory filters of the admin api.
export const META = {
  // the workspace an entity was created for: the id of a rule of the global preset table
  workspace: 'threat_studio_workspace',
  // what the entity is for inside that workspace, when a workspace can own several of a kind
  kind: 'threat_studio_kind',
};

// Reads use the in-memory state of otoroshi (`in_mem=true`). That state is refreshed periodically, so
// right after a write the studio reads the datastore instead to show what was just created.
const FRESH_READS_MS = 20000;
let lastWrite = 0;
const inMem = () => (Date.now() - lastWrite > FRESH_READS_MS ? 'true' : 'false');
const written = (p) => {
  lastWrite = Date.now();
  return p.finally(() => (lastWrite = Date.now()));
};

// Every entity of a kind, the gateway's (`entities.*` of `globalOps`, core/ops.js).
function resource(plural, idField = 'id') {
  const run = (name, input = {}) => backend.runGlobal(name, { kind: plural, ...input });
  return {
    plural,
    idField,
    list() {
      return run('entities.list', { query: { in_mem: inMem() } }).then((r) => (Array.isArray(r) ? r : []));
    },
    get(id) {
      return run('entities.get', { eid: id, query: { in_mem: inMem() } });
    },
    template() {
      return run('entities.template');
    },
    create(entity) {
      return written(run('entities.create', { body: entity }));
    },
    update(entity) {
      return written(run('entities.update', { eid: entity[idField], body: entity }));
    },
    delete(id) {
      return written(run('entities.delete', { eid: id }));
    },
  };
}

export const Resources = {
  wafConfigs: resource('waf-configs'),
  wafRulesets: resource('waf-rulesets'),
  threatPolicies: resource('threat-policies'),
  botPolicies: resource('bot-policies'),
  challengeProviders: resource('challenge-providers'),
  honeypotPolicies: resource('honeypot-policies'),
  threatFeeds: resource('threat-feeds'),
  crowdsecBouncers: resource('crowdsec-bouncers'),
  asnDatabases: resource('asn-databases'),
  geoDatabases: resource('geo-databases'),
  alertRules: resource('alert-rules'),
  malwareScanners: resource('malware-scanners'),
  ruleFeeds: resource('rule-feeds'),
  apiContracts: resource('api-contracts'),
  routes: {
    list: () => backend.runGlobal('routes.list', { query: { in_mem: inMem() } }).then((r) => (Array.isArray(r) ? r : [])),
    // a JSON patch on one route: the studio only ever touches a metadata entry of its own
    patch: (id, ops) => written(backend.runGlobal('routes.patch', { rid: id, body: ops })),
  },
};

/** The entities of a kind that were created for a workspace. */
export function ownedBy(workspaceId, kind) {
  const f = { [`metadata.${META.workspace}`]: workspaceId };
  if (kind) f[`metadata.${META.kind}`] = kind;
  return f;
}

export function tagFor(workspaceId, kind) {
  const metadata = { [META.workspace]: workspaceId };
  if (kind) metadata[META.kind] = kind;
  return metadata;
}

export function randomId(size = 12) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  const bytes = new Uint8Array(size);
  window.crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join('');
}

export function slugify(value) {
  return (value || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .substring(0, 48);
}
