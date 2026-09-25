import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { useWorkspace } from '../App';
import { AreaChart, StackedBars } from '../components/charts';
import { Icon } from '../components/icons';
import { IpAddress } from '../components/ip';
import { Card, Empty, ErrorAlert, Loading, PageHeader, Segmented, Tabs, useAsync } from '../components/ui';
import { DataTable, Donut, HourGrid, Kpi, NoExporter, PeriodPicker, Ranked, RefreshControl, ShareBar, useTimeView } from '../components/widgets';
import { fmtInt, fmtPercent } from '../lib/format';
import { countryName, flagOf, foldByCountry } from '../lib/geo';
import { Link, useQueryState } from '../lib/router';
import {
  bucketOf,
  compareOf,
  heatmapOf,
  itemsOf,
  NoExporterError,
  Q,
  runQueries,
  scalarOf,
  seriesOf,
} from '../lib/analytics';

const TABS = [
  { value: 'decisions', label: 'Decisions' },
  { value: 'sources', label: 'Sources' },
  { value: 'geography', label: 'Geography' },
  { value: 'detectors', label: 'Detectors' },
  { value: 'routes', label: 'Routes' },
  { value: 'waf', label: 'WAF' },
  { value: 'consumers', label: 'Consumers' },
];

// the most the query will rank
const GEO_SOURCES = 1000;

const WorldMap = lazy(() => import('../components/worldmap'));

const QUERIES = {
  decisions: {
    events: Q.events,
    enforced: Q.enforced,
    sources: Q.sources,
    outcome: Q.outcomeOverTime,
    actions: Q.actionsOverTime,
    byAction: Q.byAction,
    byOutcome: Q.byOutcome,
    score: Q.scoreDistribution,
    heatmap: Q.activityHeatmap,
  },
  sources: {
    sources: Q.sources,
    distinct: Q.distinctSourcesOverTime,
    top: Q.topSources,
    spread: Q.topSpreadSources,
    byScore: Q.topSourcesByScore,
  },
  geography: {
    events: Q.events,
    // the country is not a column: it is read from the asn databases, per address, like the flags
    // next to every address. So the map is drawn from the most active sources, and says how much of
    // the period they account for.
    bySource: { query: Q.topSourcesByScore, params: { top_n: GEO_SOURCES } },
  },
  detectors: {
    byCategory: Q.byCategory,
    tags: Q.topTags,
    tagEnforcement: Q.tagEnforcement,
    incidents: Q.incidentsOverTime,
    topIncidents: Q.topIncidents,
  },
  routes: {
    top: Q.topRoutes,
    enforcement: Q.routeEnforcement,
  },
  waf: {
    traffic: Q.wafRequestsOverTime,
    wouldBlock: Q.wafWouldHaveBlocked,
    rules: Q.wafTopRules,
    costly: Q.wafTopRulesWouldBlock,
    status: Q.wafBlockStatus,
    body: Q.wafBodyLimits,
  },
  consumers: {
    apikeys: Q.topApikeys,
    users: Q.topUsers,
    nodes: Q.byNode,
  },
};

const TOP = { params: { top_n: 15 } };

function withTop(spec) {
  return Object.fromEntries(Object.entries(spec).map(([k, q]) => [k, typeof q === 'string' ? { query: q, ...TOP } : q]));
}

export function ActivityPage() {
  const { workspace } = useWorkspace();
  const [query, setQuery] = useQueryState();
  const { period, refresh, setPeriod, setRefresh } = useTimeView('activity', query, setQuery, '7d');
  const tab = TABS.some((t) => t.value === query.tab) ? query.tab : 'decisions';
  const scope = (workspace.claims || []).map((r) => r.id);

  const state = useAsync(
    () => runQueries(withTop(QUERIES[tab]), { period, scope, compare: tab === 'decisions' }),
    [workspace.id, tab, period, scope.join(',')]
  );

  const setTab = (value) => setQuery({ tab: value }, { push: true });

  return (
    <div className="content wide">
      <PageHeader
        title="Activity"
        description={
          scope.length === 0
            ? 'This workspace governs no route right now, so nothing here is scoped to anything.'
            : `Every number below is asked of the ${scope.length} route${scope.length === 1 ? '' : 's'} this workspace governs.`
        }
      >
        <PeriodPicker value={period} onChange={setPeriod} />
        <RefreshControl
          {...refresh}
          onChange={setRefresh}
          onRefresh={() => state.reload({ silent: true })}
          busy={state.loading || state.refreshing}
          loadedAt={state.loadedAt}
        />
      </PageHeader>

      <Tabs tabs={TABS} value={tab} onChange={setTab} />

      {state.error instanceof NoExporterError ? (
        <Card>
          <NoExporter />
        </Card>
      ) : state.loading ? (
        <Card>
          <Loading />
        </Card>
      ) : state.error ? (
        <ErrorAlert error={state.error} />
      ) : (
        <div style={{ marginTop: 16 }}>
          {tab === 'decisions' && <Decisions d={state.data} />}
          {tab === 'sources' && <Sources d={state.data} />}
          {tab === 'geography' && <Geography d={state.data} query={query} setQuery={setQuery} />}
          {tab === 'detectors' && <Detectors d={state.data} />}
          {tab === 'routes' && <RoutesTab d={state.data} workspace={workspace} />}
          {tab === 'waf' && <Waf d={state.data} />}
          {tab === 'consumers' && <Consumers d={state.data} />}
        </div>
      )}
    </div>
  );
}

const res = (d, key) => (d[key] && d[key].data) || null;
const err = (d, key) => (d[key] && d[key].error) || null;

function Block({ d, name, title, description, children, className }) {
  const e = err(d, name);
  return (
    <Card title={title} description={description} className={className}>
      {e ? <ErrorAlert error={e} /> : children(res(d, name))}
    </Card>
  );
}

function Decisions({ d }) {
  const events = scalarOf(res(d, 'events'));
  const enforced = scalarOf(res(d, 'enforced'));
  return (
    <>
      <div className="grid c4" style={{ marginBottom: 14 }}>
        <Card className="tight">
          <Kpi label="Security decisions" value={events} previous={compareOf(res(d, 'events'))} />
        </Card>
        <Card className="tight">
          <Kpi label="Actually enforced" value={enforced} previous={compareOf(res(d, 'enforced'))} />
        </Card>
        <Card className="tight">
          <Kpi label="Distinct sources" value={scalarOf(res(d, 'sources'))} previous={compareOf(res(d, 'sources'))} />
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Enforcement rate</span>
            <span className="value">{events > 0 ? fmtPercent(enforced / events, 0) : '—'}</span>
            <span className="vs">the rest were recorded and let through</span>
          </div>
        </Card>
      </div>
      <div className="grid c2">
        <Block d={d} name="outcome" title="Enforced versus observed" description="The dry-run gap as a trend. Arming moves one band into the other and leaves the total where it was.">
          {(r) => <AreaChart series={seriesOf(r)} bucket={bucketOf(r)} stacked format={fmtInt} height={220} />}
        </Block>
        <Block d={d} name="actions" title="Actions over time" description="The graded response as it moves: log, tarpit, challenge, deny, ban.">
          {(r) => <StackedBars series={seriesOf(r)} bucket={bucketOf(r)} format={fmtInt} height={220} />}
        </Block>
        <Block d={d} name="byAction" title="Decisions by action">
          {(r) => <Donut items={itemsOf(r)} />}
        </Block>
        <Block d={d} name="byOutcome" title="Blocked versus observed">
          {(r) => <Donut items={itemsOf(r)} />}
        </Block>
        <Block d={d} name="score" title="Threat score distribution" description="The tiers of a threat policy are thresholds on this number, so this is the picture to set them from.">
          {(r) => <Ranked items={itemsOf(r)} max={8} />}
        </Block>
        <Block d={d} name="heatmap" title="By hour and weekday" description="Folded over the period: a human audience has office hours, a scanner does not.">
          {(r) => <HourGrid data={heatmapOf(r)} />}
        </Block>
      </div>
    </>
  );
}

function Sources({ d }) {
  return (
    <div className="grid c2">
      <Block d={d} name="distinct" title="Distinct sources over time" description="Breadth rather than volume: a flat source count under a rising decision count is one caller trying harder.">
        {(r) => <AreaChart series={seriesOf(r)} bucket={bucketOf(r)} format={fmtInt} height={220} />}
      </Block>
      <Block d={d} name="top" title="Most decided against" description="Ranked by how often the fabric acted on them.">
        {(r) => <Ranked items={itemsOf(r)} max={12} renderLabel={(row) => <IpAddress value={row.label} />} />}
      </Block>
      <Block d={d} name="spread" title="Walking the surface" description="Sources ranked by how many different routes they touched. A scanner and a caller hammering one endpoint produce similar counts and mean different things." className="flush">
        {(r) => (
          <DataTable
            res={r}
            columns={[
              { key: 'key', label: 'Source', render: (row) => <IpAddress value={row.key} /> },
              { key: 'routes', label: 'Routes', align: 'right', format: fmtInt },
              { key: 'decisions', label: 'Decisions', align: 'right', format: fmtInt },
              { key: 'max_score', label: 'Max score', align: 'right', format: fmtInt },
            ]}
          />
        )}
      </Block>
      <Block d={d} name="byScore" title="Most dangerous" description="One request scoring 90 outranks two hundred scoring 5." className="flush">
        {(r) => (
          <DataTable
            res={r}
            columns={[
              { key: 'key', label: 'Source', render: (row) => <IpAddress value={row.key} /> },
              { key: 'max_score', label: 'Max', align: 'right', format: fmtInt },
              { key: 'avg_score', label: 'Average', align: 'right', format: fmtInt },
              { key: 'decisions', label: 'Decisions', align: 'right', format: fmtInt },
              { key: 'enforced', label: 'Enforced', align: 'right', format: fmtInt },
            ]}
          />
        )}
      </Block>
    </div>
  );
}

const METRICS = [
  { value: 'decisions', label: 'Decisions', scale: 'rank', title: 'Security decisions' },
  { value: 'enforced', label: 'Enforced', scale: 'rank', title: 'Actually enforced' },
  { value: 'sources', label: 'Sources', scale: 'rank', title: 'Distinct sources' },
  { value: 'max_score', label: 'Max score', scale: 'linear', title: 'Highest threat score' },
];

// turns a minute, slowest first
const SPEEDS = [0.25, 0.5, 1, 2, 4, 8];
const speedLabel = (v) => `×${v === 0.25 ? '¼' : v === 0.5 ? '½' : v}`;

const PROJECTIONS = [
  { value: 'mercator', label: 'Flat' },
  { value: 'globe', label: 'Globe' },
];

function Geography({ d, query, setQuery }) {
  const rows = itemsOf(res(d, 'bySource'));
  const events = scalarOf(res(d, 'events'));
  const metric = METRICS.find((m) => m.value === query.metric) || METRICS[0];
  const projection = PROJECTIONS.some((p) => p.value === query.projection) ? query.projection : 'mercator';
  const selected = query.country || null;
  const rotate = projection === 'globe' && query.rotate === '1';
  const speed = SPEEDS.includes(Number(query.speed)) ? Number(query.speed) : 1;
  const speedTo = (delta) => {
    const next = SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, SPEEDS.indexOf(speed) + delta))];
    setQuery({ speed: next === 1 ? null : String(next) });
  };

  const [folded, setFolded] = useState(null);
  useEffect(() => {
    let alive = true;
    // the previous fold stays up while the new one resolves: an auto refresh must not unmount the
    // map, which would reset the view and the rotation every few seconds
    foldByCountry(rows).then((f) => alive && setFolded(f));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [res(d, 'bySource')]);

  const values = useMemo(
    () => new Map(((folded && folded.countries) || []).map((c) => [c.country, c[metric.value]]).filter(([, v]) => v > 0)),
    [folded, metric.value]
  );

  if (err(d, 'bySource')) return <ErrorAlert error={err(d, 'bySource')} />;
  if (rows.length === 0) {
    return (
      <Card>
        <div className="chart-empty" style={{ height: 160 }}>
          Nothing in this period
        </div>
      </Card>
    );
  }
  if (!folded) {
    return (
      <Card>
        <Loading label={`Locating ${fmtInt(rows.length)} sources…`} />
      </Card>
    );
  }

  const { countries, unknown } = folded;
  const placed = countries.reduce((a, c) => a + c.decisions, 0);
  const covered = placed + unknown.decisions;
  const truncated = rows.length >= GEO_SOURCES;
  const current = selected ? countries.find((c) => c.country === selected) : null;
  const pick = (iso) => setQuery({ country: iso && iso !== selected ? iso : null });
  const label = (iso) => `${flagOf(iso)} ${countryName(iso)}`.trim();

  if (countries.length === 0) {
    return (
      <Card>
        <Empty title="No source could be placed on a map">
          The country of an address comes from the <Link to="/asn">ASN databases</Link>. None of the {fmtInt(rows.length)}{' '}
          sources of this period is known to an enabled one.
        </Empty>
      </Card>
    );
  }

  return (
    <div className="stack">
      <div className="grid c4">
        <Card className="tight">
          <Kpi label="Countries" value={countries.length} />
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Top country</span>
            <span className="value truncate">{label(countries[0].country)}</span>
            <span className="vs">{fmtPercent(countries[0].decisions / Math.max(1, covered), 0)} of the decisions located</span>
          </div>
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Placed on the map</span>
            <span className="value">{events > 0 ? fmtPercent(Math.min(1, placed / events), 0) : '—'}</span>
            <span className="vs">
              {truncated ? `the ${fmtInt(rows.length)} most active sources` : `all ${fmtInt(rows.length)} sources`} of {fmtInt(events)} decisions
            </span>
          </div>
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Unknown network</span>
            <span className="value">{fmtInt(unknown.sources)}</span>
            <span className="vs">
              sources no <Link to="/asn">ASN database</Link> knows
            </span>
          </div>
        </Card>
      </div>

      <Card
        title="Where the decisions come from"
        description="The country a caller's network is registered in, as the ASN databases know it — close enough to tell a french isp from a us cloud, not a geolocation of the address itself. Click a country to see its sources."
      >
        <div className="geo-toolbar">
          <Segmented options={METRICS} value={metric.value} onChange={(v) => setQuery({ metric: v === 'decisions' ? null : v })} />
          <div className="row">
            {projection === 'globe' && (
              <>
                {rotate && (
                  <div className="speed" role="group" aria-label="Rotation speed">
                    <button className="btn sm icon" title="Slower" disabled={speed === SPEEDS[0]} onClick={() => speedTo(-1)}>
                      −
                    </button>
                    <span className="speed-value" title={`${speed} turn${speed > 1 ? 's' : ''} a minute`}>
                      {speedLabel(speed)}
                    </span>
                    <button className="btn sm icon" title="Faster" disabled={speed === SPEEDS[SPEEDS.length - 1]} onClick={() => speedTo(1)}>
                      +
                    </button>
                  </div>
                )}
                <button
                  className={`btn sm${rotate ? ' active-toggle' : ''}`}
                  title={rotate ? 'Stop the rotation' : 'Turn the globe slowly'}
                  aria-pressed={rotate}
                  onClick={() => setQuery({ rotate: rotate ? null : '1' })}
                >
                  <Icon name={rotate ? 'stop' : 'play'} size={14} />
                  {rotate ? 'Stop' : 'Rotate'}
                </button>
              </>
            )}
            <Segmented
              options={PROJECTIONS}
              value={projection}
              onChange={(v) => setQuery({ projection: v === 'mercator' ? null : v, rotate: v === 'globe' ? query.rotate : null })}
            />
          </div>
        </div>
        <Suspense fallback={<Loading label="Loading the map…" />}>
          <WorldMap
            values={values}
            scale={metric.scale}
            format={fmtInt}
            label={label}
            selected={selected}
            onSelect={pick}
            projection={projection}
            spin={rotate}
            speed={speed}
          />
        </Suspense>
      </Card>

      <div className="grid c2">
        <Card title="By country" description={`Ranked by ${metric.title.toLowerCase()}.`} className="flush">
          <DataTable
            res={[...countries].sort((a, b) => b[metric.value] - a[metric.value] || b.decisions - a.decisions)}
            onPick={(row) => pick(row.country)}
            isSelected={(row) => row.country === selected}
            columns={[
              { key: 'country', label: 'Country', render: (row) => label(row.country) },
              { key: 'decisions', label: 'Decisions', align: 'right', format: fmtInt },
              { key: 'enforced', label: 'Enforced', align: 'right', format: fmtInt },
              { key: 'sources', label: 'Sources', align: 'right', format: fmtInt },
              { key: 'max_score', label: 'Max score', align: 'right', format: fmtInt },
            ]}
          />
        </Card>
        {current ? (
          <Card
            title={`Sources from ${label(current.country)}`}
            description={`${fmtInt(current.sources)} source${current.sources === 1 ? '' : 's'}, ${fmtInt(current.decisions)} decisions, ${current.decisions > 0 ? fmtPercent(current.enforced / current.decisions, 0) : '—'} enforced.`}
            className="flush"
          >
            <DataTable
              res={current.top}
              columns={[
                { key: 'key', label: 'Source', render: (row) => <IpAddress value={row.key} /> },
                { key: 'network', label: 'Network', render: (row) => <span className="truncate muted">{row.geo.org || (row.geo.asn ? `AS${row.geo.asn}` : '—')}</span> },
                { key: 'decisions', label: 'Decisions', align: 'right', format: fmtInt },
                { key: 'max_score', label: 'Max score', align: 'right', format: fmtInt },
              ]}
            />
          </Card>
        ) : (
          <Card title="Networks" description="The organisations behind the located sources, by decisions.">
            <Ranked items={networksOf(countries)} max={12} />
          </Card>
        )}
      </div>
    </div>
  );
}

function networksOf(countries) {
  const acc = new Map();
  countries.forEach((c) =>
    c.top.forEach((row) => {
      const name = row.geo.org || (row.geo.asn ? `AS${row.geo.asn}` : null);
      if (!name) return;
      const key = `${c.flag} ${name}`;
      acc.set(key, (acc.get(key) || 0) + (Number(row.decisions) || 0));
    })
  );
  return [...acc.entries()].sort((a, b) => b[1] - a[1]).map(([key, value]) => ({ key, label: key, value }));
}

function Detectors({ d }) {
  return (
    <div className="grid c2">
      <Block d={d} name="byCategory" title="Which component decided">
        {(r) => <Donut items={itemsOf(r)} />}
      </Block>
      <Block d={d} name="tags" title="Top signals" description="Feed name, ASN category, bot category, WAF match — all directly comparable.">
        {(r) => <Ranked items={itemsOf(r)} max={12} />}
      </Block>
      <Block
        d={d}
        name="tagEnforcement"
        title="Signals, and whether they change anything"
        description="The question actually asked of a feed. One that fires constantly and never changes an outcome is noise being paid for."
        className="flush"
      >
        {(r) => (
          <DataTable
            res={r}
            columns={[
              { key: 'key', label: 'Signal' },
              { key: 'decisions', label: 'Fired', align: 'right', format: fmtInt },
              { key: 'enforced', label: 'Enforced', align: 'right', format: fmtInt },
              {
                key: 'rate',
                label: 'Share',
                align: 'right',
                render: (row) => (row.decisions > 0 ? fmtPercent(row.enforced / row.decisions, 0) : '—'),
              },
              { key: 'sources', label: 'Sources', align: 'right', format: fmtInt },
              { key: 'avg_score', label: 'Avg score', align: 'right', format: fmtInt },
            ]}
          />
        )}
      </Block>
      <Block d={d} name="incidents" title="Incidents over time" description="Correlated incidents rather than raw decisions — the count a human is expected to work through.">
        {(r) => <AreaChart series={seriesOf(r)} bucket={bucketOf(r)} format={fmtInt} height={200} />}
      </Block>
      <Block d={d} name="topIncidents" title="Largest incidents" className="flush">
        {(r) => (
          <DataTable
            res={r}
            columns={[
              { key: 'source', label: 'Source', render: (row) => <IpAddress value={row.source} /> },
              { key: 'events', label: 'Evidence', align: 'right', format: fmtInt },
              { key: 'decisions', label: 'Decisions', align: 'right', format: fmtInt },
              { key: 'enforced', label: 'Enforced', align: 'right', format: fmtInt },
              { key: 'max_score', label: 'Max score', align: 'right', format: fmtInt },
            ]}
          />
        )}
      </Block>
    </div>
  );
}

function RoutesTab({ d, workspace }) {
  return (
    <div className="grid c2">
      <Block d={d} name="top" title="Where decisions are taken">
        {(r) => <Ranked items={itemsOf(r)} max={12} />}
      </Block>
      <Block
        d={d}
        name="enforcement"
        title="What each route actually enforced"
        description="Configuration says what a route should do; this says what it did. A route deciding constantly and enforcing nothing is still in dry run."
        className="flush"
      >
        {(r) => (
          <DataTable
            res={r}
            columns={[
              { key: 'route', label: 'Route', render: (row) => row.route || row.key },
              { key: 'decisions', label: 'Decisions', align: 'right', format: fmtInt },
              { key: 'enforced', label: 'Enforced', align: 'right', format: fmtInt },
              {
                key: 'rate',
                label: 'Share',
                align: 'right',
                render: (row) => (row.decisions > 0 ? fmtPercent(row.enforced / row.decisions, 0) : '—'),
              },
              { key: 'sources', label: 'Sources', align: 'right', format: fmtInt },
            ]}
          />
        )}
      </Block>
      <Card title="Coverage" description="What this workspace is configured to do, for comparison with what it did.">
        <ShareBar
          part={(workspace.summary || {}).enforcing || 0}
          total={(workspace.claims || []).length}
          label="Routes that can actually stop a request"
        />
      </Card>
    </div>
  );
}

function Waf({ d }) {
  return (
    <div className="grid c2">
      <Block d={d} name="traffic" title="Inspected, blocked, would have blocked" description="The gap between the last two is exactly what arming closes.">
        {(r) => <AreaChart series={seriesOf(r)} bucket={bucketOf(r)} format={fmtInt} height={220} />}
      </Block>
      <Block d={d} name="wouldBlock" title="Would have blocked" description="Counting these over real traffic is the honest measure of what arming will cost.">
        {(r) => <AreaChart series={seriesOf(r)} bucket={bucketOf(r)} format={fmtInt} height={220} />}
      </Block>
      <Block d={d} name="rules" title="Top triggered rules" description="The starting point of every tuning session.">
        {(r) => <Ranked items={itemsOf(r)} max={12} />}
      </Block>
      <Block d={d} name="costly" title="Rules behind the blocks" description="Ranked over the requests a monitoring ruleset reached a deny on — the rules arming would actually cost you.">
        {(r) => <Ranked items={itemsOf(r)} max={12} />}
      </Block>
      <Block d={d} name="status" title="Block statuses" description="What a blocked caller was answered.">
        {(r) => <Donut items={itemsOf(r)} />}
      </Block>
      <Block d={d} name="body" title="Body inspection limits" description="A verdict on a truncated body is weaker than one on a whole body.">
        {(r) => <Donut items={itemsOf(r)} />}
      </Block>
    </div>
  );
}

function Consumers({ d }) {
  return (
    <div className="grid c2">
      <Block d={d} name="apikeys" title="Api keys decided against" description="An attack from a valid key is the one a perimeter view never shows.">
        {(r) => <Ranked items={itemsOf(r)} max={12} />}
      </Block>
      <Block d={d} name="users" title="Users decided against">
        {(r) => <Ranked items={itemsOf(r)} max={12} />}
      </Block>
      <Block d={d} name="nodes" title="Decisions by node" description="An uneven spread across a cluster is usually a shared state that is not shared.">
        {(r) => <Donut items={itemsOf(r)} />}
      </Block>
    </div>
  );
}
