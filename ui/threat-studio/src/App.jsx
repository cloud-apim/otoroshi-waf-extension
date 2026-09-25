import { createContext, useContext, useEffect, useMemo } from 'react';
import { GlobalSidebar, Topbar, WorkspaceSidebar } from './components/layout';
import { ConfirmProvider, ErrorAlert, Loading, ToastProvider, useAsync } from './components/ui';
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
import { CrowdsecPage } from './pages/Crowdsec';
import { PreRoutingPage } from './pages/PreRouting';
import { RulesetsPage } from './pages/Rulesets';
import { ClusterPage } from './pages/Cluster';

const StudioContext = createContext(null);
export const useStudio = () => useContext(StudioContext);

const WorkspaceContext = createContext(null);
export const useWorkspace = () => useContext(WorkspaceContext);

const WORKSPACE_PAGES = {
  overview: OverviewPage,
  activity: ActivityPage,
  logs: LogsPage,
  routes: RoutesPage,
  scope: ScopePage,
  protection: ProtectionPage,
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
  crowdsec: CrowdsecPage,
  prerouting: PreRoutingPage,
  rulesets: RulesetsPage,
  cluster: ClusterPage,
};

function WorkspaceShell({ wsId, page }) {
  const studio = useStudio();
  const { navigate } = useRouter();
  const workspace = (studio.table.workspaces || []).find((w) => w.id === wsId);

  useEffect(() => {
    if (studio.loaded && !workspace) navigate('/', { replace: true });
  }, [studio.loaded, workspace, navigate]);

  const value = useMemo(
    () => ({ workspace, table: studio.table, reload: studio.reload }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [workspace, studio.table]
  );

  if (!workspace) return <div className="content">{studio.loaded ? null : <Loading />}</div>;
  const Page = WORKSPACE_PAGES[page] || OverviewPage;
  return (
    <WorkspaceContext.Provider value={value}>
      <div className="shell">
        <WorkspaceSidebar workspace={workspace} workspaces={studio.table.workspaces} page={page} />
        <Page key={`${wsId}-${page}`} />
      </div>
    </WorkspaceContext.Provider>
  );
}

function GlobalShell({ page }) {
  const studio = useStudio();
  const Page = GLOBAL_PAGES[page];
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

  let content = null;
  if (table.error) content = <div className="content"><ErrorAlert error={table.error} /></div>;
  else if (wsMatch) content = <WorkspaceShell wsId={wsMatch.id} page={wsMatch.page || 'overview'} />;
  else if (globalMatch && GLOBAL_PAGES[globalMatch.page]) content = <GlobalShell page={globalMatch.page} />;
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
