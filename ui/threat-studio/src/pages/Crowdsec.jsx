import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import { Card, PageHeader, useAsync, useToast } from '../components/ui';
import { Resources } from '../lib/entities';
import { Reputation } from '../lib/security';

export function CrowdsecPage() {
  const toast = useToast();
  const bouncers = useAsync(() => Resources.crowdsecBouncers.list(), []);

  const sync = (id) =>
    Reputation.crowdsecSync({ ref: id })
      .then(() => toast.success('Sync requested'))
      .catch(toast.error);

  return (
    <div className="content wide">
      <PageHeader
        title="CrowdSec"
        description="The community decision list, in both directions: decisions are consulted on the way in, and what the fabric decides can be reported back."
      />
      <Card style={{ marginBottom: 18 }}>
        <p className="muted">
          A bouncer is consulted for every route whose workspace enables reputation. Reporting back is what makes the
          list better for everyone else, and it is configured per bouncer.
        </p>
      </Card>
      <EntitySection
        plural="crowdsec-bouncers"
        title="Bouncers"
        state={bouncers}
        createLabel="New bouncer"
        emptyTitle="No CrowdSec bouncer"
        emptyBody={<p className="muted">A bouncer points at a local or hosted CrowdSec API and needs its key.</p>}
        columns={[
          { key: 'action', label: 'Action', render: (e) => e.action || 'block' },
          { key: 'push_enabled', label: 'Reports back', render: (e) => (e.push_enabled ? 'yes' : 'no') },
          {
            key: 'sync',
            label: '',
            render: (e) => (
              <button className="copy-btn" title="Sync decisions now" onClick={() => sync(e.id)}>
                <Icon name="refresh" />
              </button>
            ),
          },
        ]}
      />
    </div>
  );
}
