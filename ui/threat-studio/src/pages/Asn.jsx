import { EntityList, SectionCard } from '../components/entities';
import { Card, PageHeader, useAsync } from '../components/ui';
import { Resources } from '../lib/entities';

export function AsnPage() {
  const dbs = useAsync(() => Resources.asnDatabases.list(), []);
  return (
    <div className="content wide">
      <PageHeader
        title="ASN databases"
        description="Address to network, classified. Hosting, VPN and mobile networks carry very different expectations, and the classification is what lets a policy say so."
      />
      <Card style={{ marginBottom: 18 }}>
        <p className="muted">
          ASN classification is never per route: every enabled database is consulted whenever a workspace enables
          reputation. What it contributes is a weighted signal, never a decision of its own — a hosting network is not a
          reason to refuse a caller, it is a reason to weigh them differently.
        </p>
      </Card>
      <SectionCard title="Databases" plural="asn-databases">
        <EntityList
          state={dbs}
          plural="asn-databases"
          emptyTitle="No ASN database"
          emptyBody={<p className="muted">Without one, reputation still works from feeds and CrowdSec; it simply has no idea what kind of network a caller is on.</p>}
        />
      </SectionCard>
    </div>
  );
}
