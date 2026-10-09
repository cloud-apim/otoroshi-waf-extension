import { useState } from 'react';
import { useCan, useStudio, useWorkspace } from '../App';
import { EntitySection } from '../components/entities';
import { Badge, Card, Loading, Modal, PageHeader, useAsync, useToast } from '../components/ui';
import { useEntities } from '../lib/scope';
import { workspaceSecurity } from '../lib/security';
import { PRESET_DEFAULTS, savePreset } from '../lib/workspaces';

/** Crawlers: which ones are named, which ones are verified, and what robots.txt says about them. */
export function BotsPage() {
  const { workspace } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const writable = useCan()('config:write');
  const entities = useEntities();
  const preset = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };

  const policies = useAsync(() => entities('bot-policies').list(), []);
  const challenges = useAsync(() => entities('challenge-providers').list(), []);
  const [robots, setRobots] = useState(null);

  const current = (policies.data || []).find((p) => p.id === preset.bot_policy) || (policies.data || []).find((p) => p.enabled !== false);

  const use = (id) =>
    savePreset(workspace.id, { bot_policy: id, bots: true })
      .then(() => {
        studio.reload();
        toast.success('Bot policy selected');
      })
      .catch(toast.error);

  const showRobots = () =>
    workspaceSecurity(workspace.id)
      .robotsTxt({ policy: current && current.id })
      .then((r) => (r.done ? setRobots(r) : toast.error(r.error || 'no bot policy')))
      .catch(toast.error);

  return (
    <div className="content wide">
      <PageHeader title="Bots" description="Identify crawlers, verify the ones that publish a method, and say what they may do." />

      <Card style={{ marginBottom: 18 }}>
        {!preset.bots ? (
          <p className="muted">The bot guard is switched off for this workspace.</p>
        ) : !current ? (
          <p className="muted">No bot policy is enabled, so the guard identifies crawlers and applies nothing.</p>
        ) : (
          <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
            <div>
              <div className="row" style={{ gap: 8 }}>
                <b style={{ fontSize: 16 }}>{current.name}</b>
                {preset.bot_policy ? <Badge kind="info">selected</Badge> : <Badge>first enabled one</Badge>}
              </div>
              {current.description && <div className="muted small" style={{ marginTop: 4 }}>{current.description}</div>}
            </div>
            <button className="btn sm" onClick={showRobots}>
              Generated robots.txt
            </button>
          </div>
        )}
      </Card>

      <EntitySection
        plural="bot-policies"
        title="Bot policies"
        description="AI crawler rules, enforced, with a generated robots.txt."
        state={policies}
        selectedId={preset.bot_policy}
        onSelect={use}
        writable={writable}
        createLabel="New policy"
        emptyTitle="No bot policy"
        emptyBody={<p className="muted">Without one the guard still identifies crawlers and contributes signals, but applies no rule of its own.</p>}
        columns={[{ key: 'rules', label: 'Rules', render: (e) => (e.rules || []).length }]}
      />

      <EntitySection
        plural="challenge-providers"
        title="Challenge providers"
        description="What a challenge tier serves. Proof of work needs nothing external; the vendor backends do."
        state={challenges}
        writable={writable}
        createLabel="New provider"
        emptyTitle="No challenge provider"
        emptyBody={<p className="muted">A threat policy tier set to <code>challenge</code> needs one of these to have anything to serve.</p>}
        columns={[{ key: 'kind', label: 'Kind', render: (e) => e.kind || 'pow' }]}
      />

      <Modal open={!!robots} title="Generated robots.txt" onClose={() => setRobots(null)} size="lg">
        <pre className="mono" style={{ maxHeight: 400, overflow: 'auto' }}>{robots && robots.robots_txt}</pre>
        {robots && robots.llms_txt && (
          <>
            <h3 style={{ margin: '18px 0 8px', fontSize: 15 }}>llms.txt</h3>
            <pre className="mono" style={{ maxHeight: 300, overflow: 'auto' }}>{robots.llms_txt}</pre>
          </>
        )}
      </Modal>
    </div>
  );
}
