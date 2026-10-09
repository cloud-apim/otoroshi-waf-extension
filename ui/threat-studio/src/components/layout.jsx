import { useEffect, useMemo, useRef, useState } from 'react';
import { bootstrap } from '../lib/bootstrap';
import { initials } from '../lib/format';
import { hasPermission, platform } from '../lib/platform';
import { Link, useRouter } from '../lib/router';
import { Icon } from './icons';
import cloudApimLogo from '../assets/cloud-apim-logo.svg';

/**
 * What a workspace is made of, in the order it is worked.
 *
 * Coverage first — which routes, and what they actually get — then what the traffic did, then the
 * detectors themselves. The order is the reading order of the console, not an alphabet.
 */
export const WORKSPACE_PAGES = [
  { id: 'overview', label: 'Overview', icon: 'grid', permission: 'workspace:read' },
  { id: 'activity', label: 'Activity', icon: 'chart', permission: 'activity:read' },
  { id: 'logs', label: 'Logs', icon: 'list', permission: 'activity:read' },
  { id: 'routes', label: 'Routes', icon: 'route', permission: 'workspace:read' },
  { id: 'scope', label: 'Scope', icon: 'target', permission: 'workspace:read' },
  { id: 'protection', label: 'Protection', icon: 'sliders', permission: 'config:read' },
  { id: 'api', label: 'API', icon: 'file', permission: 'activity:read' },
  { id: 'waf', label: 'WAF', icon: 'shield', permission: 'config:read' },
  { id: 'bots', label: 'Bots', icon: 'ghost', permission: 'config:read' },
  { id: 'reputation', label: 'IP reputation', icon: 'globe', permission: 'config:read' },
  { id: 'policy', label: 'Threat policy', icon: 'gauge', permission: 'config:read' },
  { id: 'incidents', label: 'Bans & incidents', icon: 'ban', permission: 'activity:read' },
  { id: 'settings', label: 'Settings', icon: 'settings', permission: 'workspace:read' },
];

/** The pages of a workspace the signed-in user may open, with the ones the edition adds where it says. */
export function menuOf(workspace) {
  const pages = WORKSPACE_PAGES.slice();
  platform.pages.forEach((p) => {
    const at = pages.findIndex((x) => x.id === p.after);
    pages.splice(at < 0 ? pages.length : at + 1, 0, p);
  });
  return pages.filter((p) => !p.permission || platform.can(p.permission, workspace));
}

// the pages of the install are about the whole gateway, which is an administrator's
export const canSeeInstall = () => hasPermission('admin:read');

/**
 * What belongs to the install rather than to any workspace.
 *
 * Feeds, ASN databases and CrowdSec are consulted for every route that enables reputation, so they
 * cannot be scoped to one workspace without lying. The three pre-routing validators are worse than
 * that: they run before a route is known at all.
 */
export const GLOBAL_PAGES = [
  { id: 'fleet', label: 'Fleet', icon: 'map' },
  { id: 'feeds', label: 'Threat feeds', icon: 'globe' },
  { id: 'asn', label: 'ASN databases', icon: 'network' },
  { id: 'geo', label: 'Geolocation', icon: 'pin' },
  { id: 'crowdsec', label: 'CrowdSec', icon: 'users' },
  { id: 'prerouting', label: 'Pre-routing', icon: 'flag' },
  { id: 'rulesets', label: 'WAF rulesets', icon: 'layers' },
  { id: 'rulefeeds', label: 'Rule feeds', icon: 'download' },
  { id: 'alerts', label: 'Alerts', icon: 'bell' },
  { id: 'scanners', label: 'Malware scanners', icon: 'shield' },
  { id: 'contracts', label: 'API contracts', icon: 'file' },
  { id: 'cluster', label: 'Cluster & state', icon: 'server' },
];

function SearchBox({ workspaces, currentWorkspace }) {
  const { navigate } = useRouter();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const ref = useRef(null);

  const results = useMemo(() => {
    const items = [];
    (workspaces || []).forEach((ws) => {
      items.push({ label: ws.name, hint: 'Workspace', to: `/workspaces/${ws.id}/overview`, icon: 'box' });
    });
    if (currentWorkspace) {
      menuOf(currentWorkspace).forEach((p) =>
        items.push({ label: p.label, hint: currentWorkspace.name, to: `/workspaces/${currentWorkspace.id}/${p.id}`, icon: p.icon })
      );
    }
    if (canSeeInstall()) GLOBAL_PAGES.forEach((p) => items.push({ label: p.label, hint: 'Install', to: `/${p.id}`, icon: p.icon }));
    const needle = q.trim().toLowerCase();
    if (!needle) return [];
    return items.filter((i) => i.label.toLowerCase().includes(needle)).slice(0, 12);
  }, [q, workspaces, currentWorkspace]);

  useEffect(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        ref.current && ref.current.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const go = (item) => {
    setQ('');
    setOpen(false);
    navigate(item.to);
  };

  return (
    <div className="search" style={{ position: 'relative' }}>
      <input
        ref={ref}
        className="input search"
        placeholder="Search"
        value={q}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onChange={(e) => {
          setQ(e.target.value);
          setActive(0);
          setOpen(true);
        }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') setActive((a) => Math.min(a + 1, results.length - 1));
          if (e.key === 'ArrowUp') setActive((a) => Math.max(a - 1, 0));
          if (e.key === 'Enter' && results[active]) go(results[active]);
          if (e.key === 'Escape') setOpen(false);
        }}
      />
      {open && results.length > 0 && (
        <div className="search-results">
          {results.map((r, idx) => (
            <div key={r.to} className={`item ${idx === active ? 'active' : ''}`} onMouseDown={() => go(r)}>
              <Icon name={r.icon} />
              <span className="grow truncate">{r.label}</span>
              <span className="faint small">{r.hint}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const THEME_OPTIONS = [
  { value: 'light', label: 'Light', icon: 'sun' },
  { value: 'dark', label: 'Dark', icon: 'moon' },
  { value: 'system', label: 'System', icon: 'monitor' },
];

function ThemeMenu({ theme }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return;
    const onClick = (e) => ref.current && !ref.current.contains(e.target) && setOpen(false);
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);
  const current = THEME_OPTIONS.find((o) => o.value === theme.preference) || THEME_OPTIONS[2];
  return (
    <div className="menu" ref={ref}>
      <button className="theme-btn" onClick={() => setOpen(!open)} title={`Theme: ${current.label}`}>
        <Icon name={current.icon} />
      </button>
      {open && (
        <div className="menu-items">
          {THEME_OPTIONS.map((o) => (
            <button
              key={o.value}
              className={o.value === theme.preference ? 'active' : ''}
              onClick={() => {
                theme.choose(o.value);
                setOpen(false);
              }}
            >
              <Icon name={o.icon} />
              <span className="grow">{o.label}</span>
              {o.value === theme.preference && <Icon name="check" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function Topbar({ theme, workspaces, currentWorkspace }) {
  const { path } = useRouter();
  const user = bootstrap.user;
  return (
    <header className="topbar">
      <Link to="/" className="brand" title="Threat Studio, by Cloud APIM">
        <img className="brand-mark" src={cloudApimLogo} alt="Cloud APIM" />
        Threat Studio
      </Link>
      {platform.experimental && (
        <span
          className="badge warning experimental"
          title="Threat Studio is experimental: it does not cover everything the extension can do yet, and may change in future releases"
        >
          Experimental
        </span>
      )}
      <SearchBox workspaces={workspaces} currentWorkspace={currentWorkspace} />
      <nav>
        <Link to="/" className={path === '/' ? 'active' : ''}>
          Workspaces
        </Link>
        {canSeeInstall() && (
          <Link to="/fleet" className={path === '/fleet' ? 'active' : ''}>
            Fleet
          </Link>
        )}
        {platform.links.docs && (
          <a href={platform.links.docs} target="_blank" rel="noreferrer">
            Docs
          </a>
        )}
      </nav>
      {platform.topbar.map((Item, i) => (typeof Item === 'function' ? <Item key={i} /> : <span key={i}>{Item}</span>))}
      <ThemeMenu theme={theme} />
      {platform.links.admin && (
        <a className="btn sm" href={platform.links.admin} title="Back to the Otoroshi admin console">
          <Icon name="arrowLeft" />
          Back to Otoroshi
        </a>
      )}
      <div className="user" title={user.email}>
        <span className="avatar">{initials(user.name || user.email)}</span>
        <span className="truncate" style={{ maxWidth: 140 }}>
          {user.name || user.email}
        </span>
      </div>
    </header>
  );
}

export function WorkspaceSidebar({ workspace, workspaces, page }) {
  const { navigate } = useRouter();
  return (
    <aside className="sidebar">
      <Link to="/" className="navlink muted">
        <Icon name="arrowLeft" />
        All workspaces
      </Link>
      <select
        className="ws-switch"
        value={workspace.id}
        onChange={(e) => navigate(`/workspaces/${e.target.value}/${page || 'overview'}`)}
      >
        {(workspaces || [workspace]).map((ws) => (
          <option key={ws.id} value={ws.id}>
            {ws.name}
          </option>
        ))}
      </select>
      <div className="section">
        {menuOf(workspace).map((p) => (
          <Link key={p.id} to={`/workspaces/${workspace.id}/${p.id}`} className={`navlink ${page === p.id ? 'active' : ''}`}>
            <Icon name={p.icon} />
            {p.label}
          </Link>
        ))}
      </div>
      {canSeeInstall() && (
        <div className="section">
          <div className="label">Install</div>
          {GLOBAL_PAGES.map((p) => (
            <Link key={p.id} to={`/${p.id}`} className="navlink muted">
              <Icon name={p.icon} />
              {p.label}
            </Link>
          ))}
        </div>
      )}
    </aside>
  );
}

export function GlobalSidebar({ page, workspaces }) {
  return (
    <aside className="sidebar">
      <div className="section">
        <div className="label">Install</div>
        {GLOBAL_PAGES.map((p) => (
          <Link key={p.id} to={`/${p.id}`} className={`navlink ${page === p.id ? 'active' : ''}`}>
            <Icon name={p.icon} />
            {p.label}
          </Link>
        ))}
      </div>
      <div className="section">
        <div className="label">Workspaces</div>
        <Link to="/" className="navlink muted">
          <Icon name="box" />
          All workspaces
        </Link>
        {(workspaces || []).slice(0, 8).map((ws) => (
          <Link key={ws.id} to={`/workspaces/${ws.id}/overview`} className="navlink muted">
            <Icon name="box" />
            <span className="truncate">{ws.name}</span>
          </Link>
        ))}
      </div>
    </aside>
  );
}
