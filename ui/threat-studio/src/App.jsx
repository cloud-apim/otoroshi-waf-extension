import { createContext, useContext, useEffect, useMemo } from 'react';
import { canSeeInstall, GlobalSidebar, menuOf, Topbar, WorkspaceSidebar } from './components/layout';
import { ConfirmProvider, Empty, ErrorAlert, Loading, ToastProvider, useAsync } from './components/ui';
import { hasPermission, platform, setCurrentWorkspace } from './lib/platform';
import { EntityScopeProvider, workspaceScope } from './lib/scope';
import { matchPath, RouterProvider, useRouter } from './lib/router';
import { useTheme } from './lib/theme';
import { loadTable } from './lib/workspaces';

import { WorkspacesPage } from './pages/Workspaces';
import { OverviewPage } from './pages/Overview';
import { ActivityPage } from './pages/Activity';
import { LogsPage } from './pages/Logs';
import { RoutesPage } from './pages/Routes';
import { ScopePage } from './pages/Scope';
import { ProtectionPage } from './pages/Protection';
import { WafPage } from './pages/Waf';
import { BotsPage } from './pages/Bots';
import { ReputationPage } from './pages/Reputation';
import { PolicyPage } from './pages/Policy';
import { IncidentsPage } from './pages/Incidents';
import { SettingsPage } from './pages/Settings';
import { FleetPage } from './pages/Fleet';
import { FeedsPage } from './pages/Feeds';
import { AsnPage } from './pages/Asn';
import { GeoPage } from './pages/Geo';
import { CrowdsecPage } from './pages/Crowdsec';
import { PreRoutingPage } from './pages/PreRouting';
import { RulesetsPage } from './pages/Rulesets';
import { ClusterPage } from './pages/Cluster';
import { AlertsPage } from './pages/Alerts';
import { ScannersPage } from './pages/Scanners';
import { RuleFeedsPage } from './pages/RuleFeeds';
import { ApiContractsPage } from './pages/ApiContracts';
import { ApiPage } from './pages/Api';

const StudioContext = createContext(null);
export const useStudio = () => useContext(StudioContext);

const WorkspaceContext = createContext(null);
export const useWorkspace = () => useContext(WorkspaceContext);

/** What the signed-in user can do on the workspace shown: what the pages offer, the api still decides. */
export function useCan() {
  const ctx = useContext(WorkspaceContext);
  const workspace = ctx && ctx.workspace;
  return (permission) => platform.can(permission, workspace);
}

function InsufficientAccess() {
  return (
    <div className="content">
      <Empty title="Insufficient access">This page is not part of what you can see here.</Empty>
    </div>
  );
}

const WORKSPACE_PAGES = {
  overview: OverviewPage,
  activity: ActivityPage,
  logs: LogsPage,
  routes: RoutesPage,
  scope: ScopePage,
  protection: ProtectionPage,
  api: ApiPage,
  waf: WafPage,
  bots: BotsPage,
  reputation: ReputationPage,
  policy: PolicyPage,
  incidents: IncidentsPage,
  settings: SettingsPage,
};

const GLOBAL_PAGES = {
  fleet: FleetPage,
  feeds: FeedsPage,
  asn: AsnPage,
  geo: GeoPage,
  crowdsec: CrowdsecPage,
  prerouting: PreRoutingPage,
  rulesets: RulesetsPage,
  alerts: AlertsPage,
  scanners: ScannersPage,
  rulefeeds: RuleFeedsPage,
  contracts: ApiContractsPage,
  cluster: ClusterPage,
};

function WorkspaceShell({ wsId, page }) {
  const studio = useStudio();
  const { navigate } = useRouter();
  const workspace = (studio.table.workspaces || []).find((w) => w.id === wsId);

  useEffect(() => {
    if (studio.loaded && !workspace) navigate('/', { replace: true });
  }, [studio.loaded, workspace, navigate]);

  // what is looked up deep in a page (rules, addresses) is asked of this workspace
  useEffect(() => {
    setCurrentWorkspace(wsId);
    return () => setCurrentWorkspace(null);
  }, [wsId]);

  const value = useMemo(
    () => ({ workspace, table: studio.table, reload: studio.reload }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [workspace, studio.table]
  );
  // the entities its pages work on are the workspace's (see lib/scope.js)
  const entities = useMemo(() => workspaceScope(wsId), [wsId]);

  if (!workspace) return <div className="content">{studio.loaded ? null : <Loading />}</div>;
  const menu = menuOf(workspace);
  const known = WORKSPACE_PAGES[page] || platform.pages.find((p) => p.id === page);
  const allowed = menu.find((p) => p.id === page);
  const Page = !known ? OverviewPage : allowed ? WORKSPACE_PAGES[page] || allowed.component : InsufficientAccess;
  return (
    <WorkspaceContext.Provider value={value}>
      <EntityScopeProvider value={entities}>
        <div className="shell">
          <WorkspaceSidebar workspace={workspace} workspaces={studio.table.workspaces} page={page} />
          <Page key={`${wsId}-${page}`} />
        </div>
      </EntityScopeProvider>
    </WorkspaceContext.Provider>
  );
}

function GlobalShell({ page }) {
  const studio = useStudio();
  const Page = canSeeInstall() ? GLOBAL_PAGES[page] : InsufficientAccess;
  return (
    <div className="shell">
      <GlobalSidebar page={page} workspaces={studio.table.workspaces} />
      <Page key={page} />
    </div>
  );
}

function Root() {
  const theme = useTheme();
  const { path } = useRouter();
  const table = useAsync(() => loadTable(), []);

  const studio = useMemo(
    () => ({
      table: table.data || { workspaces: [], fleet: {} },
      loaded: !!table.data,
      loading: table.loading,
      error: table.error,
      reload: table.reload,
      theme: theme.theme,
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [table.data, table.loading, table.error, theme.theme]
  );

  const wsMatch = matchPath('/workspaces/:id/:page', path) || matchPath('/workspaces/:id', path);
  const globalMatch = matchPath('/:page', path);
  const currentWorkspace = wsMatch && table.data ? (table.data.workspaces || []).find((w) => w.id === wsMatch.id) : null;

  // the pages an edition adds outside of the workspaces
  const editionRoute = platform.routes.find((r) => matchPath(r.path, path));

  let content = null;
  if (table.error) content = <div className="content"><ErrorAlert error={table.error} /></div>;
  else if (wsMatch) content = <WorkspaceShell wsId={wsMatch.id} page={wsMatch.page || 'overview'} />;
  else if (editionRoute) {
    const Route = !editionRoute.permission || hasPermission(editionRoute.permission) ? editionRoute.component : InsufficientAccess;
    content = <Route />;
  } else if (globalMatch && GLOBAL_PAGES[globalMatch.page]) content = <GlobalShell page={globalMatch.page} />;
  else content = <WorkspacesPage />;

  return (
    <StudioContext.Provider value={studio}>
      <Topbar theme={theme} workspaces={table.data && table.data.workspaces} currentWorkspace={currentWorkspace} />
      {content}
    </StudioContext.Provider>
  );
}

export function App() {
  return (
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider>
          <Root />
        </RouterProvider>
      </ConfirmProvider>
    </ToastProvider>
  );
}
