# Documentation

The [Docusaurus](https://docusaurus.io/) site for the Cloud APIM Security Suite extension.

```bash
npm install
npm start          # dev server on http://localhost:3000/otoroshi-waf-extension/
npm run build      # static build into ./build
npm run serve      # serve the built site
```

The build output is **not committed**. Pushing to `main` with changes under `documentation/`
triggers `.github/workflows/documentation.yaml`, which builds the site and publishes it straight to
GitHub Pages as an artifact.
