import { useState } from 'react';
import { useWorkspace } from '../App';
import { Icon } from '../components/icons';
import { CoverageBadge, PostureMarks } from '../components/posture';
import { Badge, Card, ErrorAlert, Loading, PageHeader, TextInput, useAsync } from '../components/ui';
import { loadWorkspaceRoutes } from '../lib/workspaces';

function RouteTable({ routes, empty }) {
  if (routes.length === 0) return <div className="chart-empty" style={{ height: 120 }}>{empty}</div>;
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>Route</th>
            <th>Status</th>
            <th>What runs on it</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {routes.map((r) => (
            <tr key={r.route_id}>
              <td>
                <div className="truncate" style={{ fontWeight: 500 }}>{r.route_name}</div>
                <div className="mono faint small truncate">{r.route_id}</div>
              </td>
              <td>
                <CoverageBadge posture={r} />
              </td>
              <td>
                <PostureMarks posture={r} />
              </td>
              <td style={{ textAlign: 'right' }}>
                <a
                  className="copy-btn"
                  title="Open this route in Otoroshi"
                  href={`/bo/dashboard/routes/${r.route_id}?tab=flow`}
                  target="_blank"
                  rel="noreferrer"
                >
                  <Icon name="external" />
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Which routes this workspace governs, and what each of them actually ends up with.
 *
 * The second table is the one that explains a workspace that looks empty: a route matched by these
 * selectors but claimed by a rule sitting above.
 */
export function RoutesPage() {
  const { workspace } = useWorkspace();
  const [q, setQ] = useState('');
  const state = useAsync(() => loadWorkspaceRoutes(workspace.id), [workspace.id]);

  const filter = (rows) => {
    const needle = q.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter((r) => (r.route_name || '').toLowerCase().includes(needle) || (r.route_id || '').includes(needle));
  };

  return (
    <div className="content wide">
      <PageHeader
        title="Routes"
        description="Resolved by the gateway from this workspace's selectors, with the posture each route actually ends up with."
      >
        <TextInput value={q} onChange={setQ} placeholder="Filter" style={{ maxWidth: 220 }} />
      </PageHeader>

      {state.loading ? (
        <Card>
          <Loading />
        </Card>
      ) : state.error ? (
        <ErrorAlert error={state.error} />
      ) : (
        <>
          <Card
            className="flush"
            style={{ marginBottom: 18 }}
            title={`Governed by ${workspace.name}`}
            description={`${(state.data.routes || []).length} route${(state.data.routes || []).length === 1 ? '' : 's'}. This workspace is the first rule of the table whose targets they all match.`}
          >
            <RouteTable
              routes={filter(state.data.routes || [])}
              empty="No route matches this workspace right now."
            />
          </Card>

          {(state.data.also_matched || []).length > 0 && (
            <Card
              className="flush"
              title="Matched, but taken by a rule above"
              description="These routes match this workspace's targets too. The table is read top to bottom and the first match wins, so a rule sitting higher governs them."
            >
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Route</th>
                      <th>Governed by</th>
                      <th>Status</th>
                      <th>What runs on it</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filter(state.data.also_matched).map((r) => (
                      <tr key={r.route_id}>
                        <td className="truncate">{r.route_name}</td>
                        <td>
                          {r.self_managed ? (
                            <Badge title="This route carries its own fabric, so the table stands down on it">its own slots</Badge>
                          ) : (
                            <Badge kind="info">{r.workspace_name || r.workspace || '—'}</Badge>
                          )}
                        </td>
                        <td>
                          <CoverageBadge posture={r} />
                        </td>
                        <td>
                          <PostureMarks posture={r} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
