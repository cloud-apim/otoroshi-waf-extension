import { api, ENTITIES_API } from './api';

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

function resource(plural, idField = 'id') {
  const base = `${ENTITIES_API}/${plural}`;
  return {
    plural,
    idField,
    // filters is a map of json path -> value, e.g. { 'metadata.threat_studio_workspace': 'rule_1' }
    list(filters = {}) {
      const params = new URLSearchParams({ in_mem: inMem() });
      Object.entries(filters).forEach(([k, v]) => params.append(`filter.${k}`, String(v)));
      return api.get(`${base}?${params.toString()}`).then((r) => (Array.isArray(r) ? r : []));
    },
    get(id) {
      return api.get(`${base}/${encodeURIComponent(id)}?in_mem=${inMem()}`);
    },
    template(params = {}) {
      const qs = new URLSearchParams(params).toString();
      return api.get(`${base}/_template${qs ? '?' + qs : ''}`);
    },
    create(entity) {
      return written(api.post(base, entity));
    },
    update(entity) {
      return written(api.put(`${base}/${encodeURIComponent(entity[idField])}`, entity));
    },
    delete(id) {
      return written(api.delete(`${base}/${encodeURIComponent(id)}`));
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
  routes: { list: (filters = {}) => {
    const params = new URLSearchParams({ in_mem: inMem() });
    Object.entries(filters).forEach(([k, v]) => params.append(`filter.${k}`, String(v)));
    return api.get(`/bo/api/proxy/apis/proxy.otoroshi.io/v1/routes?${params.toString()}`).then((r) => (Array.isArray(r) ? r : []));
  } },
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
