import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import CodeBlock from '@theme/CodeBlock';
import styles from './index.module.css';

const capabilities = [
  {
    kicker: 'Payload',
    title: 'ModSecurity SecLang, on the JVM',
    body: 'A native JVM implementation of the SecLang rule language, passing 100% of the OWASP Core Rule Set v4 regression suite. No native library, no sidecar, no separate process.',
  },
  {
    kicker: 'Payload',
    title: 'The OWASP CRS, embedded',
    body: 'CRS v4 ships inside the extension. Import it with one directive, then layer your own rules on top — or run only your own.',
  },
  {
    kicker: 'Reputation',
    title: 'Threat intelligence feeds',
    body: 'Match callers against public blocklists and cloud provider ranges before a single byte of the request is parsed. Refreshed on a schedule, matched in memory.',
  },
  {
    kicker: 'Reputation',
    title: 'CrowdSec, both directions',
    body: 'Consume decisions from a CrowdSec Local API, and report detections back as alerts — so Otoroshi becomes a CrowdSec detector, not only an enforcement point.',
  },
  {
    kicker: 'Operations',
    title: 'Monitor before you block',
    body: 'Every module runs in monitoring mode first, emitting analytics events without denying anything, so you can measure the false-positive cost before arming it.',
  },
  {
    kicker: 'Operations',
    title: 'Nothing blocking on the hot path',
    body: 'Feed lookups hit a sorted in-memory range index. Refreshes, DNS and CrowdSec syncs run off the request path, and every external dependency fails open.',
  },
];

const layers = [
  {
    num: '01',
    name: 'Connection',
    desc: 'Threat feeds, CrowdSec decisions and cloud-provider ranges, scored before the request is parsed.',
  },
  {
    num: '02',
    name: 'Request payload',
    desc: 'CRS and your own SecLang rules over the URI, headers, cookies, arguments and body.',
  },
  {
    num: '03',
    name: 'Response payload',
    desc: 'The same engine over the backend response, for leakage and error-disclosure rules.',
  },
];

const quickstart = `# grab otoroshi and the extension
curl -L -o otoroshi.jar \\
  'https://github.com/MAIF/otoroshi/releases/download/v18.0.0-preview5/otoroshi.jar'
# the security suite jar, from github.com/cloud-apim/otoroshi-waf-extension/releases/latest
curl -L -o waf.jar \\
  '.../releases/download/<version>/otoroshi-waf-extension_3-<version>.jar'

# run them together, with the extension enabled
java -cp "./waf.jar:./otoroshi.jar" \\
  -Dotoroshi.admin-extensions.configurations.cloud-apim_extensions_waf.enabled=true \\
  play.core.server.ProdServerStart`;

function Hero() {
  return (
    <header className={styles.hero}>
      <div className="container">
        <div className={styles.heroLayout}>
          <div>
            <div className={styles.heroEyebrow}>Cloud APIM · Otoroshi extension</div>
            <Heading as="h1" className={styles.heroTitle}>
              A <span className={styles.heroAccent}>security suite</span> for Otoroshi
            </Heading>
            <p className={styles.heroSubtitle}>
              A web application firewall with the OWASP Core Rule Set, plus ip reputation from
              threat intelligence feeds and CrowdSec — as Otoroshi entities and plugins, with no
              native dependency and no extra process to run.
            </p>
            <div className={styles.heroButtons}>
              <Link className={styles.buttonPrimary} to="/docs/overview">
                Read the docs
              </Link>
              <Link className={styles.buttonGhost} to="/docs/quickstart">
                Quickstart
              </Link>
              <Link
                className={styles.buttonGhost}
                href="https://github.com/cloud-apim/otoroshi-waf-extension">
                GitHub
              </Link>
            </div>
          </div>
          <img
            className={styles.heroLogo}
            src={require('@site/static/img/logo.svg').default}
            alt=""
          />
        </div>
      </div>
    </header>
  );
}

function Layers() {
  return (
    <section className={styles.section}>
      <div className="container">
        <div className={styles.sectionTag}>Where it runs</div>
        <Heading as="h2" className={styles.sectionTitle}>
          Three points on the request path
        </Heading>
        <p className={styles.sectionLede}>
          Each layer is independent — enable only what you need. The cheapest checks run first, so a
          caller already known to be hostile never reaches the rule engine.
        </p>
        <div className={styles.layers}>
          {layers.map((layer) => (
            <div className={styles.layer} key={layer.num}>
              <span className={styles.layerNum}>{layer.num}</span>
              <span className={styles.layerName}>{layer.name}</span>
              <p className={styles.layerDesc}>{layer.desc}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function Capabilities() {
  return (
    <section className={`${styles.section} ${styles.sectionAlt}`}>
      <div className="container">
        <div className={styles.sectionTag}>Capabilities</div>
        <Heading as="h2" className={styles.sectionTitle}>
          What the suite gives you
        </Heading>
        <p className={styles.sectionLede}>
          Two modules today — the WAF and ip reputation — sharing the same entities, the same admin
          section and the same analytics events.
        </p>
        <div className={styles.grid}>
          {capabilities.map((c) => (
            <div className={styles.card} key={c.title}>
              <div className={styles.cardKicker}>{c.kicker}</div>
              <div className={styles.cardTitle}>{c.title}</div>
              <p className={styles.cardBody}>{c.body}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function Start() {
  return (
    <section className={styles.section}>
      <div className="container">
        <div className={styles.sectionTag}>Get going</div>
        <Heading as="h2" className={styles.sectionTitle}>
          Two steps to a running WAF
        </Heading>
        <p className={styles.sectionLede}>
          Download the jar from the releases page — no build step. Then create a WAF config that
          imports the CRS, add the plugin to a route, and leave it in monitoring mode until the
          events look clean.
        </p>
        <div className={styles.snippet}>
          <CodeBlock language="bash">{quickstart}</CodeBlock>
        </div>
      </div>
    </section>
  );
}

export default function Home() {
  return (
    <Layout
      title="A security suite for Otoroshi"
      description="A web application firewall with the OWASP Core Rule Set, plus ip reputation from threat intelligence feeds and CrowdSec, for the Otoroshi API gateway.">
      <Hero />
      <main>
        <Layers />
        <Capabilities />
        <Start />
      </main>
    </Layout>
  );
}
