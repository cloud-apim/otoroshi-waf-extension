import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import { Badge, Card, PageHeader, useAsync, useToast } from '../components/ui';
import { Resources } from '../lib/entities';
import { Security } from '../lib/security';

/**
 * Who hears about an attack, and when (OPS-6).
 *
 * Alerting is the install's, not a workspace's: an attacker crosses workspaces, and the incident and
 * the ban an alert is about are cluster-wide already.
 */
export function AlertsPage() {
  const toast = useToast();
  const rules = useAsync(() => Resources.alertRules.list(), []);
  const status = useAsync(() => Security.status(), []);

  const test = (rule) =>
    Security.alertTest({ id: rule.id })
      .then((r) => {
        const d = r.delivery || {};
        if (r.done) toast.success(d.status ? `The channel answered ${d.status}` : d.body || 'Sent');
        else toast.error(r.error || (d.status ? `The channel answered ${d.status}: ${d.body || ''}` : d.body));
      })
      .catch(toast.error);

  const alerts = (status.data && status.data.alerts) || {};

  return (
    <div className="content wide">
      <PageHeader
        title="Alerts"
        description="One message per attacker, ban or burst, to Slack, Teams, PagerDuty or a webhook. Never one per request."
      />
      <Card style={{ marginBottom: 18 }}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          {alerts.enabled === false ? <Badge kind="warning">alerting off on this node</Badge> : <Badge kind="positive">alerting on</Badge>}
          {alerts.ocsf ? <Badge kind="info">OCSF findings emitted</Badge> : null}
        </div>
        <p className="muted" style={{ marginTop: 8 }}>
          Every node evaluates what it sees, and the shared state decides which one sends: two nodes seeing the same attacker
          send one message. Every alert is also emitted as a <code>CloudApimSecurityAlert</code> event, so a data exporter can
          take it anywhere else.
        </p>
      </Card>
      <EntitySection
        plural="alert-rules"
        title="Rules"
        state={rules}
        createLabel="New alert rule"
        emptyTitle="No alert rule"
        emptyBody={<p className="muted">Nobody hears about an attack until somebody opens the console.</p>}
        columns={[
          { key: 'trigger', label: 'Trigger', render: (e) => e.trigger },
          {
            key: 'when',
            label: 'When',
            render: (e) =>
              e.trigger === 'incident'
                ? `score ≥ ${e.min_score}`
                : e.trigger === 'burst'
                ? `${e.burst_threshold} in ${e.burst_window_seconds}s`
                : 'any ban',
          },
          { key: 'channel', label: 'Channel', render: (e) => (e.channel || {}).kind },
          { key: 'cooldown', label: 'Cooldown', render: (e) => `${e.cooldown_seconds}s` },
          {
            key: 'test',
            label: '',
            render: (e) => (
              <button className="copy-btn" title="Send a test alert" onClick={() => test(e)}>
                <Icon name="send" />
              </button>
            ),
          },
        ]}
      />
    </div>
  );
}
