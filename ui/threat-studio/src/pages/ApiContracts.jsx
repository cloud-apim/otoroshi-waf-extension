import { useState } from 'react';
import { useStudio } from '../App';
import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import { Badge, Card, PageHeader, TextInput, useAsync, useToast } from '../components/ui';
import { seedFor } from '../lib/create';
import { useEntities } from '../lib/scope';
import { Resources } from '../lib/entities';
import { Link } from '../lib/router';
import { Security } from '../lib/security';
import { CONTRACT_META } from '../lib/workspaces';

/** The title a contract gives itself, JSON or YAML, without parsing YAML in the browser. */
function titleOf(spec) {
  try {
    return JSON.parse(spec).info.title;
  } catch (e) {
    const m = /^\s{2,}title:\s*["']?(.+?)["']?\s*$/m.exec(spec || '');
    return m ? m[1] : null;
  }
}

/**
 * What a contract compiles to, for the list: its operations, or why it does not compile. Asked of
 * the text the list holds rather than by id, which the gateway's state may not know yet.
 */
const checkOf = (contract) => Security.contractCheck({ spec: contract.spec || '', base_path: contract.base_path || '' });

function Compiled({ contract }) {
  const check = useAsync(() => checkOf(contract), [contract.id, contract.spec, contract.base_path]);
  if (!check.data) return <span className="muted small">…</span>;
  if (!check.data.done) return <Badge kind="negative" title={check.data.error}>does not compile</Badge>;
  const ops = (check.data.operations || []).length;
  const warnings = (check.data.warnings || []).length;
  return (
    <span>
      {ops} operation{ops === 1 ? '' : 's'} · OpenAPI {check.data.version}
      {check.data.base_path ? ` · ${check.data.base_path}` : ''}
      {warnings > 0 && (
        <>
          {' '}
          <Badge kind="warning" title={(check.data.warnings || []).join('\n')}>
            {warnings} warning{warnings === 1 ? '' : 's'}
          </Badge>
        </>
      )}
    </span>
  );
}

/**
 * The OpenAPI contracts requests are checked against (API-1).
 *
 * A contract is the install's, like a scanner: a workspace points its API contract section at one,
 * or leaves it to each route's metadata.
 */
export function ApiContractsPage() {
  const toast = useToast();
  const studio = useStudio();
  const contractsResource = useEntities()('api-contracts');
  const contracts = useAsync(() => Resources.apiContracts.list(), []);
  const routes = useAsync(() => Resources.routes.list(), []);

  // who checks requests against a contract: the workspaces pointing at it, and the routes naming it
  const usedBy = (contract) => {
    const workspaces = (studio.table.workspaces || []).filter(
      (w) => w.preset && w.preset.api_contract && w.preset.api_contract_id === contract.id
    );
    const named = (routes.data || []).filter((r) => (r.metadata || {})[CONTRACT_META] === contract.id);
    if (workspaces.length === 0 && named.length === 0) return <span className="muted small">nothing yet</span>;
    return (
      <span className="small">
        {workspaces.map((w) => (
          <Link key={w.id} to={`/workspaces/${w.id}/protection`} style={{ marginRight: 8 }}>
            {w.name}
          </Link>
        ))}
        {named.length > 0 && (
          <span className="muted" title={named.map((r) => r.name).join('\n')}>
            {named.length} route{named.length === 1 ? '' : 's'} by metadata
          </span>
        )}
      </span>
    );
  };
  const [editing, setEditing] = useState(null);
  const [checked, setChecked] = useState(null);
  const [url, setUrl] = useState('');
  const [fetching, setFetching] = useState(false);

  const inspect = (contract) =>
    checkOf(contract)
      .then((r) => {
        if (r.done) setChecked({ contract, result: r });
        else toast.error(`${contract.name} does not compile: ${r.error}`);
      })
      .catch(toast.error);

  const importFrom = () => {
    setFetching(true);
    Security.contractFetch({ url: url.trim() })
      .then(async (r) => {
        if (!r.done) {
          toast.error(r.error);
          return;
        }
        const seed = await seedFor(contractsResource, {
          patch: { spec: r.spec, name: titleOf(r.spec) || 'Imported contract', description: `Imported from ${url.trim()}` },
        });
        const created = await contractsResource.create(seed);
        setUrl('');
        contracts.reload();
        setEditing(created);
        toast.success('Imported. It changes only when it is edited here, never when the file it came from does.');
      })
      .catch(toast.error)
      .finally(() => setFetching(false));
  };

  return (
    <div className="content wide">
      <PageHeader
        title="API contracts"
        description="The OpenAPI contracts requests are checked against: paths, methods, parameters and bodies."
      />
      <Card style={{ marginBottom: 18 }}>
        <p className="muted">
          A workspace links to a contract in <b>Protection → API contract</b>: one contract for every route it claims, or each
          route its own, chosen there route by route and saved on the route.
        </p>
      </Card>
      <Card style={{ marginBottom: 18 }} title="Import a contract" description="Fetched once, by the gateway, and stored here. Nothing refreshes it behind your back.">
        <div className="row" style={{ gap: 8 }}>
          <TextInput value={url} onChange={setUrl} placeholder="https://api.example.com/openapi.json" style={{ flex: 1 }} />
          <button className="btn sm" disabled={fetching || !/^https?:\/\//.test(url.trim())} onClick={importFrom}>
            <Icon name="download" />
            {fetching ? 'Fetching…' : 'Import'}
          </button>
        </div>
      </Card>
      <EntitySection
        plural="api-contracts"
        title="Contracts"
        state={contracts}
        createLabel="New contract"
        emptyTitle="No API contract"
        emptyBody={<p className="muted">An OpenAPI 3.0 or 3.1 document, JSON or YAML, pasted or imported.</p>}
        editing={editing}
        onEditingChange={setEditing}
        columns={[
          { key: 'compiled', label: 'Contract', render: (e) => <Compiled contract={e} /> },
          { key: 'used', label: 'Used by', render: (e) => usedBy(e) },
          {
            key: 'inspect',
            label: '',
            render: (e) => (
              <button className="copy-btn" title="List its operations" onClick={() => inspect(e)}>
                <Icon name="list" />
              </button>
            ),
          },
        ]}
      />
      {checked && (
        <Card
          style={{ marginTop: 18 }}
          title={`${checked.contract.name} — ${(checked.result.operations || []).length} operations`}
          description={`OpenAPI ${checked.result.version}${checked.result.base_path ? `, under ${checked.result.base_path}` : ', at the root of the route'}`}
          actions={
            <button className="btn sm" onClick={() => setChecked(null)}>
              <Icon name="x" />
              Close
            </button>
          }
        >
          {(checked.result.warnings || []).map((w, i) => (
            <p key={i} className="small">
              <Badge kind="warning">warning</Badge> {w}
            </p>
          ))}
          <table className="table">
            <thead>
              <tr>
                <th>Method</th>
                <th>Path</th>
                <th>Parameters</th>
                <th>Body</th>
                <th>Responses</th>
              </tr>
            </thead>
            <tbody>
              {(checked.result.operations || []).map((o) => (
                <tr key={`${o.method} ${o.path}`}>
                  <td className="mono">{o.method}</td>
                  <td className="mono">
                    {o.path}
                    {o.operation_id && <div className="muted small">{o.operation_id}</div>}
                  </td>
                  <td className="small mono">{(o.parameters || []).join(', ') || '—'}</td>
                  <td className="small mono">{o.body ? `${(o.body.media || []).join(', ')}${o.body.required ? '' : ' (optional)'}` : '—'}</td>
                  <td className="small mono">{(o.responses || []).join(', ') || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}
