import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import { Card, PageHeader, useAsync, useToast } from '../components/ui';
import { Resources } from '../lib/entities';
import { Security } from '../lib/security';

/**
 * The antivirus products uploads are handed to (WAF-5).
 *
 * A scanner is the install's: one clamd or one ICAP server serves every workspace whose upload
 * guard points at it.
 */
export function ScannersPage() {
  const toast = useToast();
  const scanners = useAsync(() => Resources.malwareScanners.list(), []);

  const describe = (v) => (v.verdict === 'infected' ? `infected (${v.threat})` : v.verdict === 'failed' ? `failed: ${v.reason}` : v.verdict);

  const test = (scanner) =>
    Security.scannerTest({ id: scanner.id })
      .then((r) => {
        if (r.error) toast.error(r.error);
        else if (r.done) toast.success(`Working: EICAR ${describe(r.eicar)}, a plain file ${describe(r.clean)}`);
        else toast.error(`Not working: EICAR ${describe(r.eicar)}, a plain file ${describe(r.clean)}`);
      })
      .catch(toast.error);

  return (
    <div className="content wide">
      <PageHeader
        title="Malware scanners"
        description="The antivirus every uploaded file can also go to, over clamd or ICAP, before the upload reaches the backend."
      />
      <Card style={{ marginBottom: 18 }}>
        <p className="muted">
          A workspace points its upload guard at one of these in Protection. The upload waits for the scanner&apos;s
          answer; whether a scan that cannot be made refuses it is the workspace&apos;s choice.
        </p>
      </Card>
      <EntitySection
        plural="malware-scanners"
        title="Scanners"
        state={scanners}
        createLabel="New scanner"
        emptyTitle="No malware scanner"
        emptyBody={<p className="muted">A clamd socket, or an ICAP server from an antivirus vendor.</p>}
        columns={[
          { key: 'kind', label: 'Protocol', render: (e) => e.kind },
          { key: 'at', label: 'At', render: (e) => `${e.host}:${e.port}${e.kind === 'icap' ? '/' + e.service : ''}` },
          { key: 'timeout', label: 'Timeout', render: (e) => `${e.timeout_millis} ms` },
          {
            key: 'test',
            label: '',
            render: (e) => (
              <button className="copy-btn" title="Test with EICAR" onClick={() => test(e)}>
                <Icon name="check" />
              </button>
            ),
          },
        ]}
      />
    </div>
  );
}
