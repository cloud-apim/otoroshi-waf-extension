import { useState } from 'react';
import { useWorkspace } from '../App';
import { AreaChart, StackedBars } from '../components/charts';
import { Card, ErrorAlert, Loading, PageHeader, Tabs, useAsync } from '../components/ui';
import { DataTable, Donut, HourGrid, Kpi, NoExporter, PeriodPicker, Ranked, ShareBar } from '../components/widgets';
import { fmtInt, fmtPercent } from '../lib/format';
import { useRouter } from '../lib/router';
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
  { value: 'detectors', label: 'Detectors' },
  { value: 'routes', label: 'Routes' },
  { value: 'waf', label: 'WAF' },
  { value: 'consumers', label: 'Consumers' },
];

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
  return Object.fromEntries(Object.entries(spec).map(([k, q]) => [k, { query: q, ...TOP }]));
}

export function ActivityPage() {
  const { workspace } = useWorkspace();
  const { query, navigate } = useRouter();
  const [period, setPeriod] = useState('7d');
  const tab = TABS.some((t) => t.value === query.tab) ? query.tab : 'decisions';
  const scope = (workspace.claims || []).map((r) => r.id);

  const state = useAsync(
    () => runQueries(withTop(QUERIES[tab]), { period, scope, compare: tab === 'decisions' }),
    [workspace.id, tab, period, scope.join(',')]
  );

  const setTab = (value) => navigate(`/workspaces/${workspace.id}/activity?tab=${value}`, { keepScroll: true });

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
        {(r) => <Ranked items={itemsOf(r)} max={12} />}
      </Block>
      <Block d={d} name="spread" title="Walking the surface" description="Sources ranked by how many different routes they touched. A scanner and a caller hammering one endpoint produce similar counts and mean different things." className="flush">
        {(r) => (
          <DataTable
            res={r}
            columns={[
              { key: 'key', label: 'Source' },
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
              { key: 'key', label: 'Source' },
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
              { key: 'source', label: 'Source' },
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
