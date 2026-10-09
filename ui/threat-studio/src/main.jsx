import '@fontsource-variable/plus-jakarta-sans';
import './styles/app.css';
import { createRoot } from 'react-dom/client';
import { bootstrap, loadBootstrap } from './lib/bootstrap';
import { installBackend, localBackend } from './lib/backend';
import { installPlatform } from './lib/platform';

loadBootstrap().then(async () => {
  // the OSS studio: the admin api through the backoffice session, and every page of the gateway, as the rest of
  // the backoffice shows them: what the signed-in user may do there, the admin api decides with their rights
  installBackend(localBackend());
  installPlatform({
    edition: 'oss',
    experimental: true,
    permissions: ['admin:read', 'admin'],
    links: { admin: bootstrap.adminUrl },
  });
  const { App } = await import('./App');
  createRoot(document.getElementById('root')).render(<App />);
});
