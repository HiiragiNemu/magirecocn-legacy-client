import { defineConfig } from 'vitepress'

// 介绍站配置。绑定自定义域名（public/CNAME），部署在域名根下，base 保持 '/'。
export default defineConfig({
  lang: 'zh-CN',
  title: '魔法纪录中文化 · 客户端',
  description: '魔法纪录中文化客户端（legacy / Totentanz 系）：下载、安装、FAQ、分层反馈',
  base: '/',
  cleanUrls: true,
  lastUpdated: true,

  head: [
    ['meta', { name: 'theme-color', content: '#b1457b' }],
  ],

  themeConfig: {
    nav: [
      { text: '首页', link: '/' },
      { text: '下载安装', link: '/download' },
      {
        text: '参与反馈',
        items: [
          { text: '反馈总览', link: '/feedback/' },
          { text: '普通玩家', link: '/feedback/player' },
          { text: '进阶排查', link: '/feedback/advanced' },
          { text: '开发者', link: '/feedback/developer' },
        ],
      },
      { text: '常见问题', link: '/faq' },
      { text: '更新日志', link: '/changelog' },
    ],

    sidebar: {
      '/feedback/': [
        {
          text: '参与反馈',
          items: [
            { text: '反馈总览', link: '/feedback/' },
            { text: '普通玩家', link: '/feedback/player' },
            { text: '进阶排查', link: '/feedback/advanced' },
            { text: '开发者', link: '/feedback/developer' },
          ],
        },
      ],
    },

    socialLinks: [
      {
        icon: 'github',
        link: 'https://github.com/MagirecoCN-Revival-Project/legacy-client',
      },
    ],

    search: { provider: 'local' },
    outline: { level: [2, 3], label: '本页目录' },

    docFooter: { prev: '上一页', next: '下一页' },
    lastUpdatedText: '最后更新',
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '菜单',
    darkModeSwitchLabel: '外观',
    lightModeSwitchTitle: '切换到浅色模式',
    darkModeSwitchTitle: '切换到深色模式',

    footer: {
      message:
        '补丁源码与热更链路见 GitHub 仓库 · 补丁层以 GPLv3 授权',
      copyright: 'MagirecoCN-Revival-Project · 粉丝项目，与 Aniplex / f4samurai 无关',
    },
  },
})
