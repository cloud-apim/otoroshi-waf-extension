import '@fontsource-variable/plus-jakarta-sans';
import './styles/app.css';
import { createRoot } from 'react-dom/client';
import { bootstrap, loadBootstrap } from './lib/bootstrap';
import { installBackend, localBackend } from './lib/backend';
import { installPlatform } from './lib/platform';

loadBootstrap().then(async () => {
  // the OSS studio: the admin api through the backoffice session, and what a super admin of the gateway sees
  installBackend(localBackend());
  installPlatform({
    edition: 'oss',
    experimental: true,
    permissions: bootstrap.user.superAdmin ? ['admin:read', 'admin'] : [],
    links: { admin: bootstrap.adminUrl },
  });
  const { App } = await import('./App');
  createRoot(document.getElementById('root')).render(<App />);
});
