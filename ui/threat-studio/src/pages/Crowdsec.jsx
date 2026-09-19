import { EntityList, SectionCard } from '../components/entities';
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
      <SectionCard title="Bouncers" plural="crowdsec-bouncers">
        <EntityList
          state={bouncers}
          plural="crowdsec-bouncers"
          emptyTitle="No CrowdSec bouncer"
          emptyBody={<p className="muted">A bouncer points at a local or hosted CrowdSec API and needs its key.</p>}
          columns={[
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
      </SectionCard>
    </div>
  );
}
