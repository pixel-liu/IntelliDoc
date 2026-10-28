/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'auto',
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  themeColor: '#315F78',
  otherColor: { info: '#3E7088', success: '#42765C', warning: '#9A6A39', error: '#BD4D57' },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: false, animateMode: 'fade' },
  header: { height: 56, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 44, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 208,
    collapsedWidth: 60,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: 'IntelliDoc' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(245, 246, 248)',
        inverted: 'rgb(24, 37, 48)',
        'base-text': 'rgb(23, 33, 43)'
      },
      boxShadow: {
        header: '0 1px 0 rgb(23, 33, 43, 0.08)',
        sider: '1px 0 0 rgb(23, 33, 43, 0.08)',
        tab: '0 1px 0 rgb(23, 33, 43, 0.08)'
      }
    },
    dark: {
      colors: {
        container: 'rgb(21, 26, 31)',
        layout: 'rgb(16, 20, 24)',
        inverted: 'rgb(12, 16, 20)',
        'base-text': 'rgb(231, 235, 239)'
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
