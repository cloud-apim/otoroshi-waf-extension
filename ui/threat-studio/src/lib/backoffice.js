/**
 * Where an entity's full form lives in the Otoroshi admin console.
 *
 * Kept apart from the components so the list, the editor and the creator can all reach it without
 * importing each other — the three of them referring to one another is a cycle, and a cycle in es
 * modules fails at render rather than at build.
 */
export const BO_PATHS = {
  'waf-configs': 'wafconfigs',
  'waf-rulesets': 'wafrulesets',
  'threat-policies': 'threatpolicies',
  'bot-policies': 'botpolicies',
  'challenge-providers': 'challengeproviders',
  'honeypot-policies': 'honeypots',
  'threat-feeds': 'threatfeeds',
  'crowdsec-bouncers': 'crowdsecbouncers',
  'asn-databases': 'asndatabases',
};

export function boUrl(plural, id) {
  const path = BO_PATHS[plural];
  if (!path) return '/bo/dashboard';
  return id
    ? `/bo/dashboard/extensions/cloud-apim/waf/${path}/edit/${encodeURIComponent(id)}`
    : `/bo/dashboard/extensions/cloud-apim/waf/${path}`;
}
