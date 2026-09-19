import { EntityList, SectionCard } from '../components/entities';
import { Card, PageHeader, useAsync } from '../components/ui';
import { Resources } from '../lib/entities';

/**
 * What runs before a route is known.
 *
 * Out of reach for any workspace, and that is structural rather than an omission: the three incoming
 * request validators run before routing, so there is no route to attribute them to and no table that
 * could select them.
 */
export function PreRoutingPage() {
  const honeypots = useAsync(() => Resources.honeypotPolicies.list(), []);

  return (
    <div className="content wide">
      <PageHeader
        title="Pre-routing"
        description="What the suite runs before a route is known — and therefore what no workspace can govern."
      />

      <Card style={{ marginBottom: 18 }}>
        <p className="muted">
          Otoroshi reads incoming request validators from the global configuration and never from a route. A workspace
          selects routes, so by construction none of this can be scoped to one: it applies to everything that reaches
          the gateway, decided before the router has run.
        </p>
        <p className="muted" style={{ marginTop: 10 }}>
          There is no admin form for that list, so it is edited as JSON on the global configuration. The three
          validators the suite offers are the honeypot, the WAF and IP reputation.
        </p>
        <pre className="mono" style={{ marginTop: 12, maxHeight: 260, overflow: 'auto' }}>
{`{
  "plugins": {
    "config": {
      "incoming_request_validators": [
        {
          "plugin": "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.IncomingRequestValidatorCloudApimIpReputation",
          "enabled": true,
          "config": { "mode": "monitor" }
        },
        {
          "plugin": "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.IncomingRequestValidatorCloudApimWaf",
          "enabled": true,
          "config": { "ref": "waf-config_…" }
        }
      ]
    }
  }
}`}
        </pre>
        <p className="faint small" style={{ marginTop: 10 }}>
          Order is significant — validators run in the order listed, so put the cheap reputation check before the rule
          engine.
        </p>
        <a className="btn" style={{ marginTop: 14 }} href="/bo/dashboard/dangerzone" target="_blank" rel="noreferrer">
          Open the danger zone
        </a>
      </Card>

      <SectionCard
        title="Honeypot policies"
        description="Decoy paths and canary tokens, evaluated before routing. A caller that touches one has proved intent rather than tripped a heuristic."
        plural="honeypot-policies"
      >
        <EntityList
          state={honeypots}
          plural="honeypot-policies"
          emptyTitle="No honeypot policy"
          emptyBody={<p className="muted">A honeypot needs no traffic model and produces no false positive: nothing legitimate requests a path that does not exist.</p>}
        />
      </SectionCard>
    </div>
  );
}
