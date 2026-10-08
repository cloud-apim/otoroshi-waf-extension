import { globalOps, ops } from './ops.js';

// The secrets of the suite's entities: the keys of the reputation sources, the credentials of a geolocation
// database, the channels of the alerts, the secrets of a challenge and the honeytokens of a honeypot. A server
// that never sends them to a browser replaces them by this sentinel, and a write sending the sentinel back keeps
// the stored value (the studio admin api reads it as "unchanged", in a query parameter of a url too). The OSS
// studio shows the entities as they are, its users being admins of the gateway.
export const SECRET_SENTINEL = '__threat_studio_secret__';

// a vault reference is not a secret, it only names where one is
const isVaultRef = (value) => typeof value === 'string' && value.startsWith('${vault://');

// The secret fields of each kind: a dotted path, `*` for every field of an object, `[]` for every item of a list,
// and a trailing `?` for a url whose query parameters carry the secret (a licence key, a token).
export const SECRET_FIELDS_BY_KIND = {
  'challenge-providers': ['secret', 'secret_key'],
  // a webhook url is the secret itself: whoever has it posts to the channel
  'alert-rules': ['channel.url', 'channel.routing_key', 'channel.headers.*'],
  'crowdsec-bouncers': ['api_key', 'push_password'],
  'geo-databases': ['password', 'headers.*', 'url?'],
  'threat-feeds': ['headers.*', 'url?'],
  'rule-feeds': ['headers.*', 'url?'],
  // a honeytoken that leaked is worth nothing
  'honeypot-policies': ['canaries.[].value'],
};

// what each operation returns, as a path to the entities in it
const ENTITY_RESULTS = {
  'entities.list': '[].entity',
  'entities.get': 'entity',
  'entities.create': 'entity',
  'entities.update': 'entity',
  'entities.fork': 'entity',
  'entities.template': '',
};
const GLOBAL_ENTITY_RESULTS = {
  'entities.list': '[]',
  'entities.get': '',
  'entities.create': '',
  'entities.update': '',
  'entities.template': '',
};

// A second pass after the declared fields, on what reads the configuration: any field whose name says it holds a
// secret. It keeps a field added to an entity later from leaking before it is declared.
const SECRET_NAMES = /token|secret|password|api_?key|authorization|routing_key|license/i;

function maskUrl(value) {
  if (typeof value !== 'string' || !value.includes('?') || isVaultRef(value)) return value;
  const [base, query] = value.split('?', 2);
  const masked = query
    .split('&')
    .filter(Boolean)
    .map((p) => {
      const i = p.indexOf('=');
      return i < 0 ? p : `${p.substring(0, i)}=${SECRET_SENTINEL}`;
    });
  return `${base}?${masked.join('&')}`;
}

function maskPath(value, segments) {
  if (value === null || value === undefined) return value;
  if (segments.length === 0) return value;
  const [head, ...rest] = segments;
  if (head === '[]') return Array.isArray(value) ? value.map((item) => maskPath(item, rest)) : value;
  if (typeof value !== 'object' || Array.isArray(value)) return value;
  const url = rest.length === 0 && head.endsWith('?');
  const name = url ? head.slice(0, -1) : head;
  const keys = name === '*' ? Object.keys(value) : [name];
  const next = { ...value };
  keys.forEach((key) => {
    if (!(key in next)) return;
    if (rest.length > 0) next[key] = maskPath(next[key], rest);
    else if (url) next[key] = maskUrl(next[key]);
    else if (next[key] !== null && next[key] !== '' && !isVaultRef(next[key])) next[key] = SECRET_SENTINEL;
  });
  return next;
}

function maskByName(value) {
  if (Array.isArray(value)) return value.map(maskByName);
  if (value === null || typeof value !== 'object') return value;
  return Object.fromEntries(
    Object.entries(value).map(([key, v]) => {
      if (SECRET_NAMES.test(key) && typeof v === 'string' && v !== '' && !isVaultRef(v)) return [key, SECRET_SENTINEL];
      if (SECRET_NAMES.test(key) && v && typeof v === 'object' && !Array.isArray(v)) return [key, maskPath(v, ['*'])];
      return [key, maskByName(v)];
    })
  );
}

function maskEntities(result, at, kind) {
  const fields = SECRET_FIELDS_BY_KIND[kind] || [];
  const prefix = at ? at.split('.') : [];
  return fields.reduce((acc, path) => maskPath(acc, [...prefix, ...path.split('.')]), result);
}

/**
 * The result of an operation as it can be sent to a browser: the secret fields of the entities it returns replaced
 * by the sentinel, then, for what reads the configuration, any field named like a secret. `input` is the input of
 * the call, which says which kind of entity it returns. The result of an operation revealing a secret is left as
 * it is.
 */
export function maskSecrets(name, result, input = {}, { global = false } = {}) {
  const registry = global ? globalOps : ops;
  const op = Object.hasOwn(registry, name) ? registry[name] : null;
  if (!op || op.reveals) return result;
  const at = (global ? GLOBAL_ENTITY_RESULTS : ENTITY_RESULTS)[name];
  const declared = at === undefined ? result : maskEntities(result, at, input.kind);
  return op.access === 'config:read' || op.access === 'admin:read' ? maskByName(declared) : declared;
}
