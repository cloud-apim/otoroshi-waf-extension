import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import CodeBlock from '@theme/CodeBlock';
import styles from './index.module.css';

const capabilities = [
  {
    kicker: 'Fabric',
    title: 'One score, one decision',
    body: 'Detectors contribute weighted signals instead of each blocking alone. One component reads the total and applies one graded action — log, challenge, throttle, tarpit, deny or ban — with the full attribution kept, so any decision can be explained afterwards.',
  },
  {
    kicker: 'Fabric',
    title: 'Bans that survive the node',
    body: 'A shared ban registry and a cross-request ledger: a caller banned anywhere is refused everywhere, and one that trips a rule a minute for an hour is eventually promoted to a ban even though every single request looked innocent.',
  },
  {
    kicker: 'Fabric',
    title: 'One incident, not nine thousand alerts',
    body: 'Every outcome emits one normalised ECS-shaped event, correlated per identity into incidents — so a scan is one line in your SIEM with a count on it, not a pager storm.',
  },
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
    kicker: 'Payload',
    title: 'Uploads judged by their bytes',
    body: 'Disguised scripts and executables, polyglots, archive bombs and zip slips refused from what a file is rather than what it is called — and every file handed to a clamd or ICAP antivirus before the upload completes, when you have one.',
  },
  {
    kicker: 'Reputation',
    title: 'Threat intelligence feeds',
    body: 'Match callers against public blocklists, cloud provider ranges and Tor exit nodes before a byte is parsed, from a catalog of fourteen curated sources. Refreshed on a schedule, matched in memory, rollback-able.',
  },
  {
    kicker: 'Reputation',
    title: 'CrowdSec, both directions',
    body: 'Consume decisions from a CrowdSec Local API, and report detections back as alerts — so Otoroshi becomes a CrowdSec detector, not only an enforcement point.',
  },
  {
    kicker: 'Reputation',
    title: 'The network behind the address',
    body: 'Address-to-ASN from the public routing table, classified into cdn, hosting and vpn. It ships as a low weight and never as a block: a datacenter is a hint, not a verdict.',
  },
  {
    kicker: 'Bots',
    title: 'Crawlers proved, not trusted',
    body: 'Forward-confirmed reverse DNS over twenty-seven known signatures. Googlebot is the most forged user-agent on the web, and a failed check turns a suspicious string into a demonstrated lie.',
  },
  {
    kicker: 'Bots',
    title: 'A challenge you host yourself',
    body: 'An embedded proof of work — no third party, no external call, difficulty scaled by the score. Or plug in a widget: Friendly Captcha and captcha.eu for a European deployment, Turnstile and hCaptcha otherwise.',
  },
  {
    kicker: 'Bots',
    title: 'AI crawlers and decoys',
    body: 'Per-category rules over AI, search, SEO and monitoring crawlers, actually enforced, with a matching robots.txt and llms.txt generated from them — plus honeypot paths that catch a scanner with almost no false-positive risk.',
  },
  {
    kicker: 'Behaviour',
    title: 'What no single request shows',
    body: 'Credential stuffing, password spraying and likely account takeovers on the login endpoints; a surge away from the traffic each route usually gets; a consumer walking object identifiers one after another — each scored on the shared judgement, never an account locked out.',
  },
  {
    kicker: 'Behaviour',
    title: 'Objects, not requests',
    body: 'A scraper that keeps under your rate limit still reads the whole catalogue. A budget of distinct objects per consumer, counted across the cluster, refuses the next new one and never the ones already read.',
  },
  {
    kicker: 'Response',
    title: 'What leaves the backend',
    body: 'Stack traces and debug pages replaced by a neutral error; card numbers, IBANs, national identifiers and secrets masked in place, each checked the way its issuer would, the response read as it streams and kept valid.',
  },
  {
    kicker: 'API',
    title: 'The contract, enforced',
    body: 'Every request checked against the route\'s OpenAPI 3.0 or 3.1 contract — paths, methods, parameters and bodies. Whole classes of attack never reach the backend, because the payload does not match the contract to begin with.',
  },
  {
    kicker: 'API',
    title: 'What the API really is',
    body: 'Endpoints the backend answers that nobody documented, operations nobody calls, fields the backend returns that the contract never mentions, and the credential each endpoint actually checks — in the console, and served to your CI.',
  },
  {
    kicker: 'Operations',
    title: 'Monitor before you block',
    body: 'Every module runs in monitoring mode first, and a fresh threat policy starts in dry run — emitting events without denying anything, so you can measure the false-positive cost before arming it.',
  },
  {
    kicker: 'Operations',
    title: 'Nothing blocking on the hot path',
    body: 'Feed lookups hit a sorted in-memory range index. Refreshes, DNS and CrowdSec syncs run off the request path, and every external dependency fails open — visibly, as an event.',
  },
];

const layers = [
  {
    num: '01',
    name: 'Before routing',
    desc: 'Honeypot paths and global validators, evaluated off the global configuration — so traffic matching no route at all is still covered.',
  },
  {
    num: '02',
    name: 'Who is calling',
    desc: 'Standing bans, threat feeds, CrowdSec decisions, network classification, bot verification and traffic surges, all before the request is parsed.',
  },
  {
    num: '03',
    name: 'Request payload',
    desc: 'The API contract, then CRS and your own SecLang rules over the URI, headers, cookies, arguments and body, uploaded files, logins and objects. Then the fabric reads the accumulated score and acts.',
  },
  {
    num: '04',
    name: 'Response payload',
    desc: 'The same engine over the backend response, error leakage replaced and sensitive data masked — and failed statuses and logins counted towards the cluster-wide memory.',
  },
];

const quickstart = `# grab otoroshi and the extension
curl -L -o otoroshi.jar \\
  'https://github.com/MAIF/otoroshi/releases/download/v18.0.0-preview6/otoroshi.jar'
# the threat protection suite jar, from github.com/cloud-apim/otoroshi-waf-extension/releases/latest
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
              A <span className={styles.heroAccent}>threat protection suite</span> for Otoroshi
            </Heading>
            <p className={styles.heroSubtitle}>
              A web application firewall with the OWASP Core Rule Set, ip reputation, bot and
              AI-crawler control, behavioural detection, response protection and API security built
              on your OpenAPI contracts — feeding one shared judgement instead of each blocking
              alone. As Otoroshi entities and plugins, with no native dependency and no extra
              process to run.
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
          Four points on the request path
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
          Four detection modules — payload, reputation, bots and behaviour — feeding one decision
          fabric, sharing the same entities, the same admin section and the same analytics events.
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
          Two steps to a running suite
        </Heading>
        <p className={styles.sectionLede}>
          Download the jar from the releases page — no build step. Then add the preset plugin to a
          route: one slot that lays down the whole fabric in the right order, and starts in dry run
          until the events look clean.
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
      title="A threat protection suite for Otoroshi"
      description="A web application firewall with the OWASP Core Rule Set, ip reputation from threat intelligence feeds and CrowdSec, bot and AI-crawler control, and a decision fabric that makes them act as one — for the Otoroshi API gateway.">
      <Hero />
      <main>
        <Layers />
        <Capabilities />
        <Start />
      </main>
    </Layout>
  );
}
