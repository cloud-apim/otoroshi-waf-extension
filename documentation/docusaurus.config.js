// @ts-check
import { themes as prismThemes } from 'prism-react-renderer';

const repo = 'https://github.com/cloud-apim/otoroshi-waf-extension';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Cloud APIM Security Suite',
  tagline: 'A WAF, threat intelligence feeds and CrowdSec for Otoroshi',
  favicon: 'img/favicon.svg',

  url: 'https://cloud-apim.github.io',
  baseUrl: '/otoroshi-waf-extension/',

  organizationName: 'cloud-apim',
  projectName: 'otoroshi-waf-extension',

  onBrokenLinks: 'warn',

  markdown: {
    format: 'detect',
    hooks: {
      onBrokenMarkdownLinks: 'warn',
      onBrokenMarkdownImages: 'warn',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
          editUrl: `${repo}/edit/main/documentation/`,
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themes: [
    [
      // offline index — no Algolia account, no third-party request from the docs
      /** @type {import("@easyops-cn/docusaurus-search-local").PluginOptions} */
      '@easyops-cn/docusaurus-search-local',
      {
        hashed: true,
        language: ['en'],
        highlightSearchTermsOnTargetPage: true,
        explicitSearchResultPath: true,
        indexBlog: false,
      },
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      image: 'img/logo.svg',
      colorMode: {
        defaultMode: 'dark',
        disableSwitch: false,
        respectPrefersColorScheme: true,
      },
      navbar: {
        title: 'Security Suite',
        logo: {
          alt: 'Cloud APIM Security Suite',
          src: 'img/logo.svg',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'docsSidebar',
            position: 'left',
            label: 'Documentation',
          },
          { href: repo, label: 'GitHub', position: 'right' },
          { href: 'https://www.cloud-apim.com', label: 'Cloud APIM', position: 'right' },
          {
            href: 'https://maif.github.io/otoroshi/manual/docs/getting-started',
            label: 'Otoroshi',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Docs',
            items: [
              { label: 'Overview', to: '/docs/overview' },
              { label: 'Install', to: '/docs/install' },
              { label: 'WAF', to: '/docs/waf/configs' },
              { label: 'IP reputation', to: '/docs/reputation/threat-feeds' },
            ],
          },
          {
            title: 'Community',
            items: [
              { label: 'Discord', href: 'https://discord.cloud-apim.com' },
              { label: 'Twitter', href: 'https://twitter.com/cloudapim' },
              { label: 'Youtube', href: 'https://www.youtube.com/@CloudAPIM' },
            ],
          },
          {
            title: 'More',
            items: [
              { label: 'Cloud APIM', href: 'https://www.cloud-apim.com' },
              { label: 'Blog', href: 'https://blog.cloud-apim.com' },
              { label: 'GitHub', href: repo },
              {
                label: 'Otoroshi',
                href: 'https://maif.github.io/otoroshi/manual/docs/getting-started',
              },
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} Cloud APIM. Built with Docusaurus.`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        // only what the docs actually use — prism resolves no dependencies here, so an
        // entry whose component needs another one (scala needs java needs clike) breaks the build
        additionalLanguages: ['bash', 'shell-session', 'json', 'json5', 'yaml', 'http'],
      },
    }),
};

export default config;
