// Values injected by the html page served by the extension (see studio/studio.scala). When running
// on the vite dev server they are fetched from the extension instead (see `loadBootstrap`).

const DEFAULT_CONFIG = { enabled: true };

export const bootstrap = {
  basePath: '/extensions/cloud-apim/threat-studio',
  adminUrl: '/bo/dashboard',
  apiPath: '/extensions/cloud-apim/extensions/waf/studio',
  extensionId: 'cloud-apim.extensions.Waf',
  user: { email: 'unknown', name: 'unknown', superAdmin: false, rights: [] },
  config: DEFAULT_CONFIG,
  otoroshi: { version: 'dev' },
};

function apply(values) {
  Object.assign(bootstrap, values, { config: { ...DEFAULT_CONFIG, ...((values && values.config) || {}) } });
}

export async function loadBootstrap() {
  if (window.__THREAT_STUDIO__) {
    apply(window.__THREAT_STUDIO__);
    return;
  }
  const res = await fetch('/extensions/cloud-apim/extensions/waf/studio/bootstrap', { credentials: 'include' });
  if (res.status === 401) {
    window.location.href = `${import.meta.env.DEV ? import.meta.env.VITE_OTOROSHI_URL || 'http://otoroshi.oto.tools:9999' : ''}/bo/dashboard`;
    throw new Error('not logged in');
  }
  apply(await res.json());
  if (import.meta.env.DEV) bootstrap.adminUrl = `${import.meta.env.VITE_OTOROSHI_URL || 'http://otoroshi.oto.tools:9999'}/bo/dashboard`;
}

export function currentTenant() {
  try {
    return window.localStorage.getItem('Otoroshi-Tenant') || 'default';
  } catch (e) {
    return 'default';
  }
}

export const canWrite = () => !!bootstrap.user.superAdmin;
