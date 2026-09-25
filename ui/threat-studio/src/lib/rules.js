import { api, EXT_API } from './api';

// What a rule id means, as the rulesets of the install define it: its message, or what the rule is
// when it has none (a CRS setup rule, a paranoia level gate…), its family and whether it is plumbing
// — the machinery present on every request rather than a detection.
//
// The same shape as the geo lookup: every id asked for in the same tick goes out in one call, the
// answer is kept for the session, and an id no ruleset defines is cached as `null` too.

const cache = new Map(); // id -> Promise<rule | null>
let pending = new Map(); // id -> resolve
let timer = null;

const MAX_PER_CALL = 1000;

function flush() {
  const batch = [...pending.entries()];
  pending = new Map();
  timer = null;
  for (let i = 0; i < batch.length; i += MAX_PER_CALL) ask(batch.slice(i, i + MAX_PER_CALL));
}

function ask(chunk) {
  api
    .post(`${EXT_API}/utils/_rules`, { ids: chunk.map(([id]) => id) })
    .then((res) => {
      const results = (res && res.results) || {};
      chunk.forEach(([id, resolve]) => resolve(results[id] || null));
    })
    .catch(() => {
      chunk.forEach(([id, resolve]) => {
        cache.delete(id);
        resolve(null);
      });
    });
}

export function lookupRule(id) {
  const key = Number(id);
  if (!Number.isFinite(key)) return Promise.resolve(null);
  if (!cache.has(key)) {
    cache.set(key, new Promise((resolve) => pending.set(key, resolve)));
    if (!timer) timer = setTimeout(flush, 20);
  }
  return cache.get(key);
}

/** The rule's own message when the event carried one, the catalog's label otherwise. */
export function labelOf(rule, msg) {
  const own = msg && msg !== '--' ? msg : null;
  return own || (rule && rule.label) || null;
}

/** "SQL injection · critical · PL1 · REQUEST-942-APPLICATION-ATTACK-SQLI" */
export function describeRule(rule) {
  if (!rule) return '';
  return [
    rule.category,
    rule.severity,
    rule.paranoia_level ? `PL${rule.paranoia_level}` : null,
    rule.source !== rule.category ? rule.source : null,
  ]
    .filter(Boolean)
    .join(' · ');
}
