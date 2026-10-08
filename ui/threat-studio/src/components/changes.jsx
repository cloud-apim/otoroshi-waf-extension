import { Badge, useConfirm } from './ui';

/**
 * What a change of the table does to the routes, as the api previews it: every route whose
 * governance changes, from which workspace to which. A route that falls to no workspace at all is the
 * one to look at twice: whatever protected it stops.
 */
export function RouteChanges({ preview }) {
  const changes = preview.changes || [];
  if (changes.length === 0 && !preview.hidden) return <p className="muted small">No route changes workspace.</p>;
  return (
    <div className="stack" style={{ marginTop: 10 }}>
      {changes.length > 0 && (
        <div className="table-wrap" style={{ maxHeight: 280, overflow: 'auto' }}>
          <table className="table">
            <thead>
              <tr>
                <th>Route</th>
                <th>Now</th>
                <th>After</th>
              </tr>
            </thead>
            <tbody>
              {changes.map((c) => (
                <tr key={c.route.id}>
                  <td className="truncate" style={{ maxWidth: 220 }}>
                    {c.route.name || c.route.id}
                  </td>
                  <td>{c.before ? c.before.name : <span className="faint">no workspace</span>}</td>
                  <td>
                    {c.after ? c.after.name : <Badge kind="warning">no workspace</Badge>}
                    {c.self_managed && (
                      <Badge kind="info" style={{ marginLeft: 6 }}>
                        protected on the route
                      </Badge>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {preview.hidden > 0 && (
        <p className="muted small">
          And {preview.hidden} route{preview.hidden === 1 ? '' : 's'} you cannot read.
        </p>
      )}
    </div>
  );
}

/** How many routes a preview moves, readable or not. */
export const changedRoutes = (preview) => (preview.changes || []).length + (preview.hidden || 0);

/**
 * Asks before a change of the table that moves routes, with the routes it moves; `always` asks even when
 * none moves. A change the api would refuse is refused here, with its reason.
 */
export function useRouteChangesConfirm() {
  const confirm = useConfirm();
  return async (preview, { always = false, ...opts }) => {
    if (!preview.allowed) throw new Error(preview.reason || 'this change cannot be made');
    if (changedRoutes(preview) === 0 && !always) return true;
    return confirm({ ...opts, body: <RouteChanges preview={preview} /> });
  };
}
