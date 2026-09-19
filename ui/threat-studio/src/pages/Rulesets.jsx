import { EntityList, SectionCard } from '../components/entities';
import { Card, PageHeader, useAsync } from '../components/ui';
import { Resources } from '../lib/entities';

export function RulesetsPage() {
  const rulesets = useAsync(() => Resources.wafRulesets.list(), []);
  return (
    <div className="content wide">
      <PageHeader title="WAF rulesets" description="Reusable bodies of SecLang, composed by the WAF configs that list them." />
      <Card style={{ marginBottom: 18 }}>
        <p className="muted">
          A config lists the rulesets it runs, in order, and its own inline rules are appended after them. SecLang is
          position-sensitive, so that order is what makes “a shared baseline plus this route's exceptions” the natural
          arrangement rather than a special case.
        </p>
        <p className="muted" style={{ marginTop: 10 }}>
          An unresolved reference contributes nothing and does not fail the route — the config keeps compiling and
          serving, protecting less than it claims. It is reported on the config's Compile button rather than turning a
          configuration mistake into an outage.
        </p>
      </Card>
      <SectionCard title="Rulesets" plural="waf-rulesets">
        <EntityList
          state={rulesets}
          plural="waf-rulesets"
          emptyTitle="No ruleset"
          emptyBody={<p className="muted">Rulesets are optional: a config with none behaves exactly as it always did.</p>}
        />
      </SectionCard>
    </div>
  );
}
