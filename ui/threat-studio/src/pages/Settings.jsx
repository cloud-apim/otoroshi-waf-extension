import { useEffect, useState } from 'react';
import { useStudio, useWorkspace } from '../App';
import { Icon } from '../components/icons';
import { Badge, Card, PageHeader, TextInput, Toggle, useConfirm, useToast } from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { useRouter } from '../lib/router';
import { moveWorkspace, removeWorkspace, replaceWorkspace, saveTable } from '../lib/workspaces';

export function SettingsPage() {
  const { workspace, table } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const confirm = useConfirm();
  const { navigate } = useRouter();
  const writable = canWrite();
  const [name, setName] = useState(workspace.name);
  const [busy, setBusy] = useState(false);

  useEffect(() => setName(workspace.name), [workspace.id, workspace.name]);

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

  const remove = async () => {
    const ok = await confirm({
      title: `Delete ${workspace.name}?`,
      message:
        'The routes it governs fall through to the rules below it, and to nothing at all if there are none. The entities it points at are not deleted.',
      danger: true,
      confirmLabel: 'Delete',
    });
    if (!ok) return;
    await apply(removeWorkspace(table, workspace.id), 'Workspace deleted');
    navigate('/');
  };

  const last = table.workspaces.length - 1;

  return (
    <div className="content narrow">
      <PageHeader title="Settings" description="What this workspace is called, where it sits in the table, and whether it applies at all." />

      <Card>
        <div className="setting-row top" style={{ paddingTop: 4 }}>
          <div>
            <b>Name</b>
            <div className="muted small">Free text, for whoever reads this table next. Never used for matching.</div>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <TextInput value={name} onChange={setName} disabled={!writable} />
            {writable && name !== workspace.name && (
              <button
                className="btn primary"
                disabled={busy}
                onClick={() => apply(replaceWorkspace(table, workspace.id, (w) => ({ ...w, name })), 'Renamed')}
              >
                Save
              </button>
            )}
          </div>
        </div>

        <div className="setting-row">
          <div>
            <b>Enabled</b>
            <div className="muted small">
              A disabled workspace is skipped entirely and the search carries on with the one below it.
            </div>
          </div>
          <Toggle
            value={workspace.enabled !== false}
            disabled={!writable || busy}
            onChange={(v) => apply(replaceWorkspace(table, workspace.id, (w) => ({ ...w, enabled: v })), v ? 'Enabled' : 'Disabled')}
          />
        </div>

        <div className="setting-row">
          <div>
            <b>Position</b>
            <div className="muted small">
              The table is read top to bottom and the first match wins, so this is configuration rather than a display
              preference. Narrow workspaces belong above broad ones.
            </div>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <span className="rank">{workspace.index + 1}</span>
            <span className="faint small">of {table.workspaces.length}</span>
            <button
              className="copy-btn"
              disabled={!writable || busy || workspace.index === 0}
              onClick={() => apply(moveWorkspace(table, workspace.id, -1))}
              title="Move up"
            >
              <Icon name="arrowUp" />
            </button>
            <button
              className="copy-btn"
              disabled={!writable || busy || workspace.index === last}
              onClick={() => apply(moveWorkspace(table, workspace.id, 1))}
              title="Move down"
            >
              <Icon name="arrowDown" />
            </button>
          </div>
        </div>
      </Card>

      <Card style={{ marginTop: 18 }} title="The whole table">
        <div className="setting-row" style={{ paddingTop: 4 }}>
          <div>
            <b>Global preset plugin</b>
            <div className="muted small">
              The one slot in the global plugins that expands this table. Disabling it stops every workspace at once.
            </div>
          </div>
          <div className="row" style={{ gap: 10 }}>
            {table.installed ? <Badge kind={table.enabled ? 'positive' : 'negative'}>{table.enabled ? 'enabled' : 'disabled'}</Badge> : <Badge>not installed</Badge>}
            <Toggle
              value={table.enabled !== false}
              disabled={!writable || busy || !table.installed}
              onChange={(v) => {
                setBusy(true);
                saveTable(table, { slotEnabled: v })
                  .then(() => {
                    studio.reload();
                    toast.success(v ? 'Table enabled' : 'Table disabled');
                  })
                  .catch(toast.error)
                  .finally(() => setBusy(false));
              }}
            />
          </div>
        </div>
        <div className="setting-row">
          <div>
            <b>Leave already-protected routes alone</b>
            <div className="muted small">
              Skip any route that already carries the route-level preset or one of the fabric plugins, so a route
              protected on purpose is never given a second chain.
            </div>
          </div>
          <Toggle
            value={table.skip_protected_routes !== false}
            disabled={!writable || busy}
            onChange={(v) => apply({ ...table, skip_protected_routes: v }, 'Saved')}
          />
        </div>
      </Card>

      <Card style={{ marginTop: 18 }} title="This rule, as configured" description="What the global preset actually reads. The same thing the danger zone shows.">
        <pre className="mono" style={{ maxHeight: 280, overflow: 'auto' }}>
          {JSON.stringify(
            {
              id: workspace.id,
              name: workspace.name,
              enabled: workspace.enabled !== false,
              skip: !!workspace.skip,
              targets: workspace.targets || [],
              preset: workspace.preset || {},
            },
            null,
            2
          )}
        </pre>
      </Card>

      {writable && (
        <Card style={{ marginTop: 18 }} title="Delete" description="The entities this workspace points at are shared, and are not deleted with it.">
          <button className="btn danger" onClick={remove} disabled={busy}>
            <Icon name="trash" />
            Delete this workspace
          </button>
        </Card>
      )}
    </div>
  );
}
