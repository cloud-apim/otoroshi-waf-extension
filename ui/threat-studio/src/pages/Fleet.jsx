import { useMemo, useState } from 'react';
import { useStudio } from '../App';
import { Icon } from '../components/icons';
import { CoverageBadge, PostureMarks, SourceBadge } from '../components/posture';
import { Card, ErrorAlert, Loading, PageHeader, Segmented, TextInput, useAsync } from '../components/ui';
import { fmtInt } from '../lib/format';
import { Link } from '../lib/router';
import { Security } from '../lib/security';

const FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'unprotected', label: 'Protected by nothing' },
  { value: 'observing', label: 'Observing only' },
  { value: 'enforcing', label: 'Enforcing' },
  { value: 'own', label: 'Own slots' },
];

/**
 * Every route of the install, and where its protection comes from.
 *
 * The row that earns the page is the one with nothing on it, so the gaps sort first and the filter
 * defaults to showing them beside everything else rather than hiding them behind a tab.
 */
export function FleetPage() {
  const studio = useStudio();
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState('all');
  const state = useAsync(() => Security.posture(), []);

  const rows = useMemo(() => {
    const all = (state.data && state.data.routes) || [];
    const needle = q.trim().toLowerCase();
    return all
      .filter((r) => {
        if (filter === 'unprotected') return !r.covered;
        if (filter === 'observing') return r.covered && !r.enforcing;
        if (filter === 'enforcing') return r.enforcing;
        if (filter === 'own') return r.self_managed || (r.covered && !r.workspace);
        return true;
      })
      .filter((r) => !needle || (r.route_name || '').toLowerCase().includes(needle) || (r.route_id || '').includes(needle))
      .sort((a, b) => Number(a.covered) - Number(b.covered) || (a.route_name || '').localeCompare(b.route_name || ''));
  }, [state.data, q, filter]);

  const summary = (state.data && state.data.summary) || {};
  const governance = (state.data && state.data.governance) || {};

  return (
    <div className="content wide">
      <PageHeader
        title="Fleet"
        description="Every route the router knows, and what actually protects it — its own slots, a workspace of the table, or nothing."
      >
        <TextInput value={q} onChange={setQ} placeholder="Filter" style={{ maxWidth: 200 }} />
      </PageHeader>

      {state.loading ? (
        <Card>
          <Loading />
        </Card>
      ) : state.error ? (
        <ErrorAlert error={state.error} />
      ) : (
        <>
          <div className="grid c4" style={{ marginBottom: 18 }}>
            <Card className="tight">
              <div className="kpi">
                <span className="label">Routes</span>
                <span className="value">{fmtInt(summary.total)}</span>
              </div>
            </Card>
            <Card className="tight">
              <div className="kpi">
                <span className="label">Covered</span>
                <span className="value">{fmtInt(summary.covered)}</span>
                <span className="vs">{fmtInt(governance.governed)} by the table</span>
              </div>
            </Card>
            <Card className="tight">
              <div className="kpi">
                <span className="label">Enforcing</span>
                <span className="value">{fmtInt(summary.enforcing)}</span>
                <span className="vs">the rest only observe</span>
              </div>
            </Card>
            <Card className="tight">
              <div className="kpi">
                <span className="label">Protected by nothing</span>
                <span className="value" style={summary.uncovered > 0 ? { color: 'var(--negative-text)' } : undefined}>
                  {fmtInt(summary.uncovered)}
                </span>
              </div>
            </Card>
          </div>

          {governance.installed === false && (
            <div className="alert" style={{ marginBottom: 14 }}>
              No global preset table is installed, so every covered route above is covered by its own slots.{' '}
              <Link to="/">Create a workspace</Link> to govern them centrally.
            </div>
          )}
          {governance.dynamic && (
            <div className="alert" style={{ marginBottom: 14 }}>
              A selector of the table reads the request, so the workspace attributed to each route below is what the
              table resolves with no request in flight.
            </div>
          )}

          <div style={{ marginBottom: 14 }}>
            <Segmented options={FILTERS} value={filter} onChange={setFilter} />
          </div>

          <Card className="flush">
            {rows.length === 0 ? (
              <div className="chart-empty" style={{ height: 140 }}>
                No route matches this filter
              </div>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Route</th>
                      <th>Status</th>
                      <th>Protected by</th>
                      <th>What runs on it</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r) => (
                      <tr key={r.route_id}>
                        <td>
                          <div className="truncate" style={{ fontWeight: 500 }}>{r.route_name}</div>
                          <div className="mono faint small truncate">{r.route_id}</div>
                        </td>
                        <td>
                          <CoverageBadge posture={r} />
                        </td>
                        <td>
                          {r.workspace ? (
                            <Link to={`/workspaces/${r.workspace}/overview`}>
                              <SourceBadge posture={r} />
                            </Link>
                          ) : (
                            <SourceBadge posture={r} />
                          )}
                        </td>
                        <td>
                          <PostureMarks posture={r} />
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <a
                            className="copy-btn"
                            href={`/bo/dashboard/routes/${r.route_id}?tab=flow`}
                            target="_blank"
                            rel="noreferrer"
                            title="Open this route in Otoroshi"
                          >
                            <Icon name="external" />
                          </a>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
