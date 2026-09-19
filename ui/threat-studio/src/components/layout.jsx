import { useEffect, useMemo, useRef, useState } from 'react';
import { bootstrap } from '../lib/bootstrap';
import { initials } from '../lib/format';
import { Link, useRouter } from '../lib/router';
import { Icon } from './icons';

/**
 * What a workspace is made of, in the order it is worked.
 *
 * Coverage first — which routes, and what they actually get — then what the traffic did, then the
 * detectors themselves. The order is the reading order of the console, not an alphabet.
 */
export const WORKSPACE_PAGES = [
  { id: 'overview', label: 'Overview', icon: 'grid' },
  { id: 'activity', label: 'Activity', icon: 'chart' },
  { id: 'events', label: 'Events', icon: 'list' },
  { id: 'routes', label: 'Routes', icon: 'route' },
  { id: 'scope', label: 'Scope', icon: 'target' },
  { id: 'protection', label: 'Protection', icon: 'sliders' },
  { id: 'waf', label: 'WAF', icon: 'shield' },
  { id: 'bots', label: 'Bots', icon: 'ghost' },
  { id: 'reputation', label: 'IP reputation', icon: 'globe' },
  { id: 'policy', label: 'Threat policy', icon: 'gauge' },
  { id: 'incidents', label: 'Bans & incidents', icon: 'ban' },
  { id: 'settings', label: 'Settings', icon: 'settings' },
];

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
  { id: 'crowdsec', label: 'CrowdSec', icon: 'users' },
  { id: 'prerouting', label: 'Pre-routing', icon: 'flag' },
  { id: 'rulesets', label: 'WAF rulesets', icon: 'layers' },
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
      WORKSPACE_PAGES.forEach((p) =>
        items.push({ label: p.label, hint: currentWorkspace.name, to: `/workspaces/${currentWorkspace.id}/${p.id}`, icon: p.icon })
      );
    }
    GLOBAL_PAGES.forEach((p) => items.push({ label: p.label, hint: 'Install', to: `/${p.id}`, icon: p.icon }));
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
      <Link to="/" className="brand">
        <span className="brand-mark">TS</span>
        Threat Studio
      </Link>
      <span
        className="badge warning experimental"
        title="Threat Studio is experimental: it does not cover everything the extension can do yet, and may change in future releases"
      >
        Experimental
      </span>
      <SearchBox workspaces={workspaces} currentWorkspace={currentWorkspace} />
      <nav>
        <Link to="/" className={path === '/' ? 'active' : ''}>
          Workspaces
        </Link>
        <Link to="/fleet" className={path === '/fleet' ? 'active' : ''}>
          Fleet
        </Link>
        <a href="/bo/dashboard/extensions/cloud-apim/waf/home" target="_blank" rel="noreferrer">
          Docs
        </a>
      </nav>
      <ThemeMenu theme={theme} />
      <a className="btn sm" href={bootstrap.adminUrl} title="Back to the Otoroshi admin console">
        <Icon name="arrowLeft" />
        Back to Otoroshi
      </a>
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
        {WORKSPACE_PAGES.map((p) => (
          <Link key={p.id} to={`/workspaces/${workspace.id}/${p.id}`} className={`navlink ${page === p.id ? 'active' : ''}`}>
            <Icon name={p.icon} />
            {p.label}
          </Link>
        ))}
      </div>
      <div className="section">
        <div className="label">Install</div>
        {GLOBAL_PAGES.map((p) => (
          <Link key={p.id} to={`/${p.id}`} className="navlink muted">
            <Icon name={p.icon} />
            {p.label}
          </Link>
        ))}
      </div>
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
