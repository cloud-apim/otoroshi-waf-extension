import { useStudio, useWorkspace } from '../App';
import { EntitySection, OpenInOtoroshi } from '../components/entities';
import { Badge, Card, PageHeader, useAsync, useToast } from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { Resources } from '../lib/entities';
import { PRESET_DEFAULTS, replaceWorkspace, saveTable } from '../lib/workspaces';

/**
 * The policy turns the accumulated score into one graded action.
 *
 * It is the single thing standing between a fully configured workspace and a workspace that stops
 * nothing, which is why its dry run is called out rather than left in a form.
 */
export function PolicyPage() {
  const { workspace, table } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const writable = canWrite();
  const preset = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };

  const policies = useAsync(() => Resources.threatPolicies.list(), []);
  const current = (policies.data || []).find((p) => p.id === preset.threat_policy);

  const use = (id) =>
    saveTable(replaceWorkspace(table, workspace.id, (w) => ({ ...w, preset: { ...preset, threat_policy: id } })))
      .then(() => {
        studio.reload();
        toast.success('Threat policy selected');
      })
      .catch(toast.error);

  const tiers = (current && (current.tiers || current.levels)) || [];

  return (
    <div className="content wide">
      <PageHeader title="Threat policy" description="Where the accumulated score becomes an action: log, tarpit, challenge, deny, ban." />

      <Card style={{ marginBottom: 18 }}>
        {!current ? (
          <div>
            <div className="row" style={{ gap: 8 }}>
              <b style={{ fontSize: 16 }}>Built-in policy</b>
              <Badge kind="warning">dry run</Badge>
            </div>
            <p className="muted small" style={{ marginTop: 6 }}>
              No policy is selected, so the built-in one applies: every decision is recorded and nothing is ever
              enforced. That is the right default and the wrong end state.
            </p>
          </div>
        ) : (
          <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
            <div>
              <div className="row" style={{ gap: 8 }}>
                <b style={{ fontSize: 16 }}>{current.name}</b>
                {current.dry_run ? <Badge kind="warning">dry run</Badge> : <Badge kind="positive">enforcing</Badge>}
              </div>
              <div className="muted small" style={{ marginTop: 4 }}>
                {current.dry_run
                  ? 'Every tier is computed and reported, and nothing is applied.'
                  : 'Tiers apply for real on every route this workspace governs.'}
              </div>
              {tiers.length > 0 && (
                <div className="posture" style={{ marginTop: 10 }}>
                  {tiers.map((t, i) => (
                    <span key={i} className={`mark ${current.dry_run ? 'observing' : 'armed'}`}>
                      ≥ {t.score ?? t.from ?? '?'} → {t.action}
                    </span>
                  ))}
                </div>
              )}
            </div>
            <OpenInOtoroshi plural="threat-policies" id={current.id} label="Edit tiers" />
          </div>
        )}
      </Card>

      <EntitySection
        plural="threat-policies"
        title="Threat policies"
        description="Shared by the threat gate and the threat response of every workspace that points at them."
        state={policies}
        selectedId={preset.threat_policy}
        onSelect={use}
        writable={writable}
        workspaceId={workspace.id}
        kind="policy"
        createLabel="New policy"
        emptyTitle="No threat policy"
        emptyBody={<p className="muted">Without one the built-in dry-run policy applies: everything is recorded, nothing is enforced.</p>}
        columns={[
          {
            key: 'dry_run',
            label: 'Mode',
            render: (e) => (e.dry_run ? <Badge kind="warning">dry run</Badge> : <Badge kind="positive">enforcing</Badge>),
          },
          { key: 'tiers', label: 'Tiers', render: (e) => (e.tiers || []).length },
        ]}
      />
    </div>
  );
}
