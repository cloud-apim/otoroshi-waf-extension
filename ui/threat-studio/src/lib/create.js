import { api, EXT_API } from './api';
import { Resources, tagFor } from './entities';
import { schemaOf } from './schemas';

/**
 * Seeding a new entity.
 *
 * Always from the entity's own `_template` rather than from an object built here: the template is
 * what the extension considers a sane empty entity, it moves when the entity gains a field, and a
 * studio that built its own would quietly start writing entities missing whatever was added last.
 *
 * Nothing is written by these: they return what the editor opens on, so a creation is reviewed in
 * the same form it will later be edited in.
 */

const RESOURCES = {
  'waf-configs': () => Resources.wafConfigs,
  'waf-rulesets': () => Resources.wafRulesets,
  'threat-policies': () => Resources.threatPolicies,
  'bot-policies': () => Resources.botPolicies,
  'challenge-providers': () => Resources.challengeProviders,
  'honeypot-policies': () => Resources.honeypotPolicies,
  'threat-feeds': () => Resources.threatFeeds,
  'crowdsec-bouncers': () => Resources.crowdsecBouncers,
  'asn-databases': () => Resources.asnDatabases,
};

/**
 * Where the studio's default differs from the entity template, and why.
 *
 * Only two, and both go the same way: a thing that can refuse traffic starts by observing. Arming a
 * ruleset that has never seen this traffic, or enabling a provider that cannot verify anything, are
 * the two moves worth doing in the other order.
 */
const STUDIO_DEFAULTS = {
  'waf-configs': {
    block: false,
    rules: ['@import_preset crs', 'SecRuleEngine On'],
    description: 'OWASP Core Rule Set, in monitoring mode',
  },
};

export async function seedFor(plural, { workspaceId, kind, patch } = {}) {
  const resource = RESOURCES[plural]();
  const template = await resource.template();
  return {
    ...template,
    ...(STUDIO_DEFAULTS[plural] || {}),
    ...(patch || {}),
    metadata: { ...(template.metadata || {}), ...(workspaceId ? tagFor(workspaceId, kind) : {}) },
  };
}

export function createEntity(plural, entity) {
  return RESOURCES[plural]().create(entity);
}

export function resourceOf(plural) {
  const schema = schemaOf(plural);
  return schema ? schema.resource() : RESOURCES[plural]();
}

/** Compiles what a config would run, so nothing is saved that the engine cannot parse. */
export function compileConfig({ rules = [], rulesets = [], crs = {} }) {
  return api.post(`${EXT_API}/utils/_compile`, { rules, rulesets, crs });
}

/**
 * A feed seeded from the catalog.
 *
 * The extension builds it — url, parser, refresh interval, weight and tag all come from the entry —
 * so the studio opens the editor on what it is given rather than reproducing fourteen sources. An
 * entry needing a key or a manual url comes back disabled, which is the honest state for a feed that
 * cannot fetch anything yet.
 */
export async function seedFeedFromCatalog(entryId) {
  const res = await api.post(`${EXT_API}/reputation/_template`, { entry: entryId });
  if (!res || !res.done) throw new Error((res && res.error) || 'unknown catalog entry');
  return res.feed;
}

/**
 * A challenge provider seeded from a preset.
 *
 * A vendor preset arrives with its widget and verification urls filled in and its keys empty, so it
 * is seeded disabled: enabling a provider that cannot verify anything would serve a challenge nobody
 * can pass.
 */
export async function seedChallengeFromPreset(presetId) {
  const res = await api.post(`${EXT_API}/security/_challenge_from_preset`, { preset: presetId });
  if (!res || !res.done) throw new Error((res && res.error) || 'unknown preset');
  return { ...res.provider, enabled: false };
}

export function listFeedCatalog() {
  return api.get(`${EXT_API}/reputation/_catalog`);
}

export function listChallengePresets() {
  return api.get(`${EXT_API}/security/_challenge_presets`);
}
