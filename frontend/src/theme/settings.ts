/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'auto',
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  themeColor: '#0B7A75',
  otherColor: { info: '#287C96', success: '#3E8065', warning: '#C47732', error: '#C65353' },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: false, animateMode: 'fade' },
  header: { height: 64, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 44, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 216,
    collapsedWidth: 68,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: 'IntelliDoc' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(252, 253, 251)',
        layout: 'rgb(240, 244, 241)',
        inverted: 'rgb(20, 43, 51)',
        'base-text': 'rgb(28, 45, 50)'
      },
      boxShadow: {
        header: '0 1px 0 rgb(25, 62, 61, 0.08)',
        sider: '1px 0 0 rgb(25, 62, 61, 0.10)',
        tab: '0 1px 0 rgb(25, 62, 61, 0.08)'
      }
    },
    dark: {
      colors: {
        container: 'rgb(19, 30, 34)',
        layout: 'rgb(13, 23, 27)',
        inverted: 'rgb(10, 20, 24)',
        'base-text': 'rgb(224, 233, 230)'
      },
      boxShadow: {
        header: '0 1px 0 rgb(255, 255, 255, 0.06)',
        sider: '1px 0 0 rgb(255, 255, 255, 0.06)',
        tab: '0 1px 0 rgb(255, 255, 255, 0.06)'
      }
    }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {};
