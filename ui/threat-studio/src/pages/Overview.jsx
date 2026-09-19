import { useWorkspace } from '../App';
import { AreaChart } from '../components/charts';
import { Icon } from '../components/icons';
import { Lint } from '../components/posture';
import { Badge, Card, ErrorAlert, Loading, PageHeader, useAsync } from '../components/ui';
import { Donut, Kpi, NoExporter, Ranked, ShareBar } from '../components/widgets';
import { fmtInt, fmtPercent } from '../lib/format';
import { Link } from '../lib/router';
import { itemsOf, NoExporterError, Q, runQueries, scalarOf, seriesOf, bucketOf } from '../lib/analytics';
import { Resources } from '../lib/entities';
import { armedSections, enforcementOf, isCatchAll, lintWorkspace, PRESET_DEFAULTS, SECTIONS } from '../lib/workspaces';

/**
 * What this workspace covers, what it is armed to do, and what the traffic actually did.
 *
 * In that order on purpose: the coverage half works with no analytics at all, so the page is useful
 * on an install that has never exported an event.
 */
export function OverviewPage() {
  const { workspace, table } = useWorkspace();
  const scope = (workspace.claims || []).map((r) => r.id);
  const preset = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };

  const refs = useAsync(
    () => Promise.all([Resources.threatPolicies.list(), Resources.wafConfigs.list()]).then(([policies, configs]) => ({ policies, configs })),
    []
  );
  const stats = useAsync(
    () =>
      runQueries(
        {
          events: Q.events,
          enforced: Q.enforced,
          sources: Q.sources,
          outcome: Q.outcomeOverTime,
          category: Q.byCategory,
          tags: Q.topTags,
        },
        { period: '7d', scope, compare: true }
      ),
    [workspace.id, scope.join(',')]
  );

  const enforcement = enforcementOf(workspace, refs.data || {});
  const armed = armedSections(workspace);
  const lint = lintWorkspace(workspace, table);
  const claims = (workspace.claims || []).length;
  const summary = workspace.summary || {};

  const noExporter = stats.error instanceof NoExporterError;

  return (
    <div className="content">
      <PageHeader title={workspace.name} description={isCatchAll(workspace) ? 'Claims every route the rules above it have not taken.' : 'Claims the routes its selectors match, unless a rule above it took them first.'}>
        <Link className="btn" to={`/workspaces/${workspace.id}/protection`}>
          <Icon name="sliders" />
          Protection
        </Link>
        <Link className="btn" to={`/workspaces/${workspace.id}/scope`}>
          <Icon name="target" />
          Scope
        </Link>
      </PageHeader>

      {lint.length > 0 && (
        <Card className="tight" style={{ marginBottom: 18 }}>
          <Lint items={lint} />
        </Card>
      )}

      {/* ---------- coverage: true with no analytics at all ---------- */}
      <div className="grid c4" style={{ marginBottom: 18 }}>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Routes governed</span>
            <span className="value">{fmtInt(claims)}</span>
            {(workspace.matches || []).length > claims && (
              <span className="vs">{(workspace.matches || []).length - claims} taken by a rule above</span>
            )}
          </div>
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Enforcing</span>
            <span className="value">{fmtInt(summary.enforcing || 0)}</span>
            <span className="vs">of {fmtInt(summary.total || 0)} covered</span>
          </div>
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Sections armed</span>
            <span className="value">{armed.length}</span>
            <span className="vs">of {SECTIONS.length}</span>
          </div>
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Stance</span>
            <span className="value" style={{ fontSize: 17, marginTop: 6 }}>
              {workspace.skip ? (
                <Badge>opt-out</Badge>
              ) : enforcement.armed ? (
                <Badge kind="positive">enforcing</Badge>
              ) : (
                <Badge kind="warning">observing</Badge>
              )}
            </span>
            <span className="vs">{enforcement.reason}</span>
          </div>
        </Card>
      </div>

      <div className="grid c2" style={{ marginBottom: 18 }}>
        <Card title="What it lays down" description="A section switched off expands into nothing at all, not into a disabled plugin.">
          <div className="posture">
            {SECTIONS.map((s) => {
              const on = preset[s.key] && (s.key !== 'waf' || !!preset.waf_config);
              return (
                <span key={s.key} className={`mark ${on ? 'on' : ''}`} title={s.help}>
                  {s.label}
                </span>
              );
            })}
          </div>
          {preset.waf && !preset.waf_config && (
            <p className="muted small" style={{ marginTop: 12 }}>
              The WAF section is on but no config is selected, so no rule engine runs.{' '}
              <Link to={`/workspaces/${workspace.id}/waf`}>Pick one</Link>.
            </p>
          )}
          {!preset.response && (
            <p className="muted small" style={{ marginTop: 12 }}>
              Without the threat response, every detector still contributes to the score and nothing ever acts on it.
            </p>
          )}
        </Card>
        <Card title="Routes" description="Resolved against the router, with no request in flight.">
          {claims === 0 ? (
            <p className="muted">
              No route right now.{' '}
              <Link to={`/workspaces/${workspace.id}/scope`}>Check its selectors</Link>.
            </p>
          ) : (
            <>
              <ShareBar part={summary.enforcing || 0} total={claims} label="Routes that can actually stop a request" />
              <ul style={{ marginTop: 14, listStyle: 'none', padding: 0 }}>
                {(workspace.claims || []).slice(0, 6).map((r) => (
                  <li key={r.id} className="truncate" style={{ padding: '3px 0' }}>
                    <span className="muted">{r.name}</span>
                  </li>
                ))}
              </ul>
              {claims > 6 && (
                <Link className="small" to={`/workspaces/${workspace.id}/routes`}>
                  and {claims - 6} more
                </Link>
              )}
            </>
          )}
        </Card>
      </div>

      {/* ---------- what the traffic did ---------- */}
      {noExporter ? (
        <Card>
          <NoExporter />
        </Card>
      ) : stats.loading ? (
        <Card>
          <Loading />
        </Card>
      ) : stats.error ? (
        <ErrorAlert error={stats.error} />
      ) : (
        <>
          <div className="row between" style={{ marginBottom: 12 }}>
            <h2 style={{ fontSize: 17 }}>Past 7 days</h2>
            <Link className="btn sm" to={`/workspaces/${workspace.id}/activity`}>
              Full activity
            </Link>
          </div>
          <div className="grid c3" style={{ marginBottom: 18 }}>
            <Card className="tight">
              <Kpi
                label="Security decisions"
                value={scalarOf(stats.data.events.data)}
                previous={stats.data.events.data && stats.data.events.data.compare ? Number(stats.data.events.data.compare.data.value) : null}
              />
            </Card>
            <Card className="tight">
              <Kpi
                label="Actually enforced"
                value={scalarOf(stats.data.enforced.data)}
                previous={stats.data.enforced.data && stats.data.enforced.data.compare ? Number(stats.data.enforced.data.compare.data.value) : null}
                hint="The rest were recorded and let through"
              />
            </Card>
            <Card className="tight">
              <Kpi label="Distinct sources" value={scalarOf(stats.data.sources.data)} />
            </Card>
          </div>
          <div className="grid c2">
            <Card title="Enforced versus observed" description="Arming moves the observed band into the enforced one and leaves the total where it was.">
              <AreaChart series={seriesOf(stats.data.outcome.data)} bucket={bucketOf(stats.data.outcome.data)} stacked format={fmtInt} height={200} />
            </Card>
            <Card title="Which detector decided">
              <Donut items={itemsOf(stats.data.category.data)} />
            </Card>
            <Card title="What is catching things" description="Ranked by signal: a feed, an ASN category, a bot category, a WAF match.">
              <Ranked items={itemsOf(stats.data.tags.data)} max={8} />
            </Card>
            <Card title="Enforcement rate">
              {scalarOf(stats.data.events.data) === 0 ? (
                <div className="chart-empty" style={{ height: 120 }}>
                  Nothing decided in this period
                </div>
              ) : (
                <>
                  <div className="stat">
                    <span className="value">
                      {fmtPercent(scalarOf(stats.data.enforced.data) / scalarOf(stats.data.events.data), 0)}
                    </span>
                    <span className="label">of the decisions actually stopped something</span>
                  </div>
                  <p className="muted small" style={{ marginTop: 12 }}>
                    {enforcement.armed
                      ? 'This workspace is armed, so this number is what it is doing.'
                      : 'This workspace only observes, so the rest is what it would have done.'}
                  </p>
                </>
              )}
            </Card>
          </div>
        </>
      )}
    </div>
  );
}
