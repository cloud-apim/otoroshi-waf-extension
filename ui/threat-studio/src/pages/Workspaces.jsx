import { useState } from 'react';
import { useStudio } from '../App';
import { Icon } from '../components/icons';
import { Lint } from '../components/posture';
import {
  Badge,
  Card,
  Empty,
  ErrorAlert,
  Field,
  Loading,
  Modal,
  PageHeader,
  TextInput,
  useConfirm,
  useToast,
} from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { Link, useRouter } from '../lib/router';
import { fmtInt } from '../lib/format';
import {
  addWorkspace,
  armedSections,
  emptyWorkspace,
  isCatchAll,
  lintWorkspace,
  moveWorkspace,
  removeWorkspace,
  saveTable,
} from '../lib/workspaces';

function targetSummary(ws) {
  if (isCatchAll(ws)) return 'every route';
  return (ws.targets || [])
    .map((t) => `${t.path || t.expression} ${typeof t.value === 'string' ? t.value : JSON.stringify(t.value)}`)
    .join(' and ');
}

function WorkspaceRow({ ws, table, onMove, onDelete, busy }) {
  const { navigate } = useRouter();
  const armed = armedSections(ws);
  const lint = lintWorkspace(ws, table);
  const claims = (ws.claims || []).length;
  const open = (e) => {
    // the row is the target, but an anchor, a button or a text selection inside it is not
    if (e.target.closest('a, button')) return;
    if (window.getSelection && String(window.getSelection())) return;
    navigate(`/workspaces/${ws.id}/overview`);
  };
  return (
    <div className="ws-row clickable" style={{ padding: '16px 0' }} onClick={open}>
      <span className="rank" title="The table is read top to bottom and the first match wins">
        {ws.index + 1}
      </span>
      <div style={{ minWidth: 0 }}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <Link to={`/workspaces/${ws.id}/overview`} style={{ fontWeight: 600 }}>
            {ws.name}
          </Link>
          {ws.skip && <Badge title="Matches, lays down nothing, and stops the search">opt-out</Badge>}
          {ws.enabled === false && <Badge kind="negative">disabled</Badge>}
          {ws.unreachable && <Badge kind="negative">unreachable</Badge>}
          {isCatchAll(ws) && !ws.skip && <Badge kind="info">catch-all</Badge>}
        </div>
        <div className="muted small" style={{ marginTop: 3 }}>
          {targetSummary(ws)}
        </div>
        <div className="row small" style={{ gap: 10, marginTop: 8, flexWrap: 'wrap' }}>
          <span>
            <b>{fmtInt(claims)}</b> <span className="faint">route{claims === 1 ? '' : 's'}</span>
          </span>
          {ws.summary && ws.summary.total > 0 && (
            <span className="faint">
              {ws.summary.enforcing} enforcing · {ws.summary.covered - ws.summary.enforcing} observing
            </span>
          )}
          {armed.length > 0 && <span className="faint">· {armed.join(', ')}</span>}
          {ws.skip && <span className="faint">· lays down nothing</span>}
        </div>
        {lint.length > 0 && (
          <div style={{ marginTop: 8 }}>
            <Lint items={lint} />
          </div>
        )}
      </div>
      <div className="row" style={{ gap: 4 }} onClick={(e) => e.stopPropagation()}>
        <button className="copy-btn" title="Move up" disabled={busy || ws.index === 0} onClick={() => onMove(ws.id, -1)}>
          <Icon name="arrowUp" />
        </button>
        <button
          className="copy-btn"
          title="Move down"
          disabled={busy || ws.index === table.workspaces.length - 1}
          onClick={() => onMove(ws.id, 1)}
        >
          <Icon name="arrowDown" />
        </button>
        <button className="copy-btn" title="Delete" disabled={busy} onClick={() => onDelete(ws)}>
          <Icon name="trash" />
        </button>
      </div>
    </div>
  );
}

export function WorkspacesPage() {
  const studio = useStudio();
  const toast = useToast();
  const confirm = useConfirm();
  const { navigate } = useRouter();
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');

  const table = studio.table;
  const writable = canWrite();

  const apply = (next, message) => {
    setBusy(true);
    return saveTable(next)
      .then(() => {
        studio.reload();
        if (message) toast.success(message);
      })
      .catch(toast.error)
      .finally(() => setBusy(false));
  };

  const create = () => {
    const ws = emptyWorkspace(name.trim() || 'New workspace');
    setCreating(false);
    setName('');
    apply(addWorkspace(table, ws)).then(() => navigate(`/workspaces/${ws.id}/scope`));
  };

  const remove = async (ws) => {
    const ok = await confirm({
      title: `Delete ${ws.name}?`,
      message: `The routes it governs fall through to the rules below it, and to nothing at all if there are none. The entities it uses are not deleted.`,
      danger: true,
      confirmLabel: 'Delete',
    });
    if (ok) apply(removeWorkspace(table, ws.id), 'Workspace deleted');
  };

  if (studio.loading && !studio.loaded) return <div className="content"><Loading /></div>;
  if (studio.error) return <div className="content"><ErrorAlert error={studio.error} /></div>;

  const fleet = table.fleet || {};
  const summary = fleet.summary || {};
  const unclaimed = fleet.unclaimed || [];
  const workspaces = table.workspaces || [];

  return (
    <div className="content">
      <PageHeader
        title="Workspaces"
        description="A workspace is a rule of the global preset table: a selector, and the protection every route it claims receives. The table is read top to bottom and the first match wins."
      >
        {writable && (
          <button className="btn primary" onClick={() => setCreating(true)} disabled={busy}>
            <Icon name="plus" />
            New workspace
          </button>
        )}
      </PageHeader>

      {!table.installed && (
        <Card className="tight" style={{ marginBottom: 14 }}>
          <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
            <div>
              <b>The global preset is not installed yet.</b>
              <div className="muted small">
                Creating a workspace adds it to the global plugins. Until then nothing here applies to any route.
              </div>
            </div>
          </div>
        </Card>
      )}
      {table.installed && table.enabled === false && (
        <div className="alert error" style={{ marginBottom: 14 }}>
          The global preset plugin is present but disabled: no workspace in this table applies to anything.
        </div>
      )}
      {table.dynamic && (
        <div className="alert" style={{ marginBottom: 14 }}>
          A selector in this table reads the request, so the route counts below are what the table resolves with no
          request in flight. The gateway resolves it again per request.
        </div>
      )}

      <div className="grid c4" style={{ marginBottom: 18 }}>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Routes</span>
            <span className="value">{fmtInt(summary.total || 0)}</span>
          </div>
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Governed by this table</span>
            <span className="value">{fmtInt(fleet.governed || 0)}</span>
          </div>
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Enforcing</span>
            <span className="value">{fmtInt(summary.enforcing || 0)}</span>
          </div>
        </Card>
        <Card className="tight">
          <div className="kpi">
            <span className="label">Protected by nothing</span>
            <span className="value" style={unclaimed.length > 0 ? { color: 'var(--negative-text)' } : undefined}>
              {fmtInt(unclaimed.length)}
            </span>
          </div>
        </Card>
      </div>

      {unclaimed.length > 0 && (
        <Card
          className="tight"
          style={{ marginBottom: 18 }}
          actions={
            <Link className="btn sm" to="/fleet">
              See them
            </Link>
          }
          title={`${unclaimed.length} route${unclaimed.length === 1 ? '' : 's'} no rule claims`}
          description="Nobody goes looking for a route they forgot to protect. A catch-all rule at the bottom of the table is the usual answer."
        />
      )}

      <Card className="flush">
        {workspaces.length === 0 ? (
          <Empty title="No workspace yet">
            <p className="muted">
              A workspace pairs a selector — a tag, a group, a metadata, a set of route ids — with the protection its
              routes receive. Start with one that claims every route, then add narrower ones above it.
            </p>
            {writable && (
              <button className="btn primary" style={{ marginTop: 14 }} onClick={() => setCreating(true)}>
                <Icon name="plus" />
                New workspace
              </button>
            )}
          </Empty>
        ) : (
          <div style={{ padding: '4px 22px' }}>
            {workspaces.map((ws) => (
              <WorkspaceRow
                key={ws.id}
                ws={ws}
                table={table}
                busy={busy || !writable}
                onMove={(id, delta) => apply(moveWorkspace(table, id, delta))}
                onDelete={remove}
              />
            ))}
          </div>
        )}
      </Card>

      {!writable && (
        <p className="faint small" style={{ marginTop: 14 }}>
          The table lives on the global configuration, so editing it needs a super admin. Everything else here is
          readable.
        </p>
      )}

      <Modal
        open={creating}
        title="New workspace"
        onClose={() => setCreating(false)}
        footer={
          <>
            <button className="btn" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button className="btn primary" onClick={create}>
              Create
            </button>
          </>
        }
      >
        <Field label="Name" hint="Free text, for whoever reads this table next.">
          <TextInput value={name} onChange={setName} placeholder="Public APIs" autoFocus />
        </Field>
        <p className="muted small">
          It is created claiming nothing — the next screen is where its selectors are written. It is inserted above the
          catch-all, which is the only place a narrow rule can ever win.
        </p>
      </Modal>
    </div>
  );
}
