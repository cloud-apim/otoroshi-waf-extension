import { api } from './api';

/**
 * The analytics of the suite, always asked of a *set* of routes.
 *
 * The platform's own filters carry a single `route_id`, which is the wrong shape for a workspace: a
 * workspace is a rule of the global preset table and covers as many routes as its selectors claim.
 * Every query of the extension therefore takes a `route_ids` parameter, and an empty list means the
 * whole fleet — so the very same widgets serve a workspace and the install.
 */

export const PERIODS = [
  { value: '1h', label: 'Past hour', from: 'now-1h' },
  { value: '24h', label: 'Past 24 hours', from: 'now-24h' },
  { value: '7d', label: 'Past 7 days', from: 'now-7d' },
  { value: '30d', label: 'Past month', from: 'now-30d' },
  { value: '90d', label: 'Past 3 months', from: 'now-90d' },
];

/**
 * A period is either one of `PERIODS`, relative to now, or a fixed range written `<from>_<to>` in epoch
 * milliseconds — a single token, so it travels through the query string, the local storage and every
 * `runQuery` call exactly like a preset does.
 */
export function customRange(period) {
  const m = /^(\d+)_(\d+)$/.exec(period || '');
  if (!m) return null;
  const from = Number(m[1]);
  const to = Number(m[2]);
  return from < to ? { from, to } : null;
}

export function rangePeriod(from, to) {
  return `${from}_${to}`;
}

export function isPeriod(value) {
  return PERIODS.some((p) => p.value === value) || customRange(value) !== null;
}

export const Q = {
  // volume and enforcement
  events: 'cloudapim_security_events_total',
  enforced: 'cloudapim_security_enforced_total',
  sources: 'cloudapim_security_sources_total',
  eventsOverTime: 'cloudapim_security_events_over_time',
  enforcedOverTime: 'cloudapim_security_enforced_over_time',
  outcomeOverTime: 'cloudapim_security_outcome_over_time',
  actionsOverTime: 'cloudapim_security_actions_over_time',
  activityHeatmap: 'cloudapim_security_activity_heatmap',
  // breakdowns
  byAction: 'cloudapim_security_by_action',
  byCategory: 'cloudapim_security_by_category',
  byOutcome: 'cloudapim_security_by_outcome',
  byNode: 'cloudapim_security_by_node',
  scoreDistribution: 'cloudapim_security_score_distribution',
  // sources
  topSources: 'cloudapim_security_top_sources',
  distinctSourcesOverTime: 'cloudapim_security_distinct_sources_over_time',
  topSpreadSources: 'cloudapim_security_top_spread_sources',
  topSourcesByScore: 'cloudapim_security_top_sources_by_score',
  // detectors
  topTags: 'cloudapim_security_top_tags',
  tagEnforcement: 'cloudapim_security_tag_enforcement',
  // routes and consumers
  topRoutes: 'cloudapim_security_top_routes',
  routeEnforcement: 'cloudapim_security_route_enforcement',
  topApikeys: 'cloudapim_security_top_apikeys',
  topUsers: 'cloudapim_security_top_users',
  // incidents
  incidentsOverTime: 'cloudapim_security_incidents_over_time',
  topIncidents: 'cloudapim_security_top_incidents',
  // waf
  wafTopRules: 'cloudapim_waf_top_rules',
  wafWouldHaveBlocked: 'cloudapim_waf_would_have_blocked',
  wafTopRulesWouldBlock: 'cloudapim_waf_top_rules_would_block',
  wafRequestsOverTime: 'cloudapim_waf_requests_over_time',
  wafBlockStatus: 'cloudapim_waf_block_status',
  wafBodyLimits: 'cloudapim_waf_body_limits',
  // the rows themselves
  decisionsLog: 'cloudapim_security_decisions_log',
  decisionDetail: 'cloudapim_security_decision_detail',
  wafTrailLog: 'cloudapim_waf_trail_log',
  wafTrailDetail: 'cloudapim_waf_trail_detail',
};

export class NoExporterError extends Error {}

/**
 * `scope` is the list of route ids the view is about — a workspace's claimed routes, or nothing at
 * all for the whole install.
 */
export async function runQuery(query, { period = '7d', from, scope = [], params = {}, compare = false, bucket, signal } = {}) {
  const range = customRange(period);
  const p = PERIODS.find((x) => x.value === period) || PERIODS[2];
  const filters = range
    ? { from: from || new Date(range.from).toISOString(), to: new Date(range.to).toISOString() }
    : { from: from || p.from, to: 'now' };
  const allParams = { ...params, ...(scope && scope.length > 0 ? { route_ids: scope } : {}) };
  try {
    return await api.post(
      '/bo/api/proxy/api/analytics/_query',
      { query, params: allParams, filters, compare, ...(bucket ? { bucket } : {}) },
      { signal }
    );
  } catch (e) {
    if (e.status === 412) throw new NoExporterError('no active user analytics exporter');
    throw e;
  }
}

/** Several queries at once, as a map. One failing does not take the others down with it. */
export async function runQueries(queries, opts = {}) {
  const entries = Object.entries(queries);
  const results = await Promise.all(
    entries.map(([, spec]) => {
      const { query, ...rest } = typeof spec === 'string' ? { query: spec } : spec;
      return runQuery(query, { ...opts, ...rest }).then(
        (data) => ({ data }),
        (error) => {
          if (error instanceof NoExporterError) throw error;
          return { error };
        }
      );
    })
  );
  return Object.fromEntries(entries.map(([key], i) => [key, results[i]]));
}

/* ---------- reading results ---------- */

/**
 * The useful half of a query result.
 *
 * A result is `{ shape, data: {...}, raw, meta }` and everything worth reading — the value, the
 * points, the items — is one level down under `data`. Unwrapping it here, once, is what keeps every
 * reader below from having to remember whether it was handed the whole result or already its body
 * (and getting it wrong was exactly the bug that made every panel read zero).
 */
function body(res) {
  if (!res) return null;
  return res.data !== undefined ? res.data : res;
}

export function scalarOf(res) {
  const d = body(res);
  return d ? Number(d.value) || 0 : 0;
}

// the comparison sits on the result, beside `data`, not inside it
export function compareOf(res) {
  return res && res.compare && res.compare.data ? Number(res.compare.data.value) || 0 : null;
}

export function itemsOf(res) {
  const d = body(res);
  return (d && d.items) || [];
}

export function bucketOf(res) {
  const d = body(res);
  return (d && d.bucket) || null;
}

/** A timeseries result as a list of series, whatever its form (`points` or `series`). */
export function seriesOf(res) {
  const d = body(res);
  if (!d) return [];
  if (d.series) return d.series.map((sr) => ({ name: sr.name, points: sr.points }));
  if (d.points) return [{ name: 'value', points: d.points }];
  return [];
}

export function totalPoints(res) {
  const series = seriesOf(res);
  const acc = new Map();
  series.forEach((sr) => sr.points.forEach((p) => acc.set(p.ts, (acc.get(p.ts) || 0) + (Number(p.value) || 0))));
  return [...acc.entries()].sort((a, b) => a[0] - b[0]).map(([ts, value]) => ({ ts, value }));
}

export function sumOf(res) {
  return totalPoints(res).reduce((a, p) => a + (Number(p.value) || 0), 0);
}

export function heatmapOf(res) {
  const d = body(res) || {};
  return { x: d.xBuckets || [], y: d.yBuckets || [], values: d.values || [] };
}

/** The share of decisions that actually did something, as a ratio, or null when nothing happened. */
export function enforcementRate(events, enforced) {
  const total = scalarOf(events);
  if (!total) return null;
  return scalarOf(enforced) / total;
}
