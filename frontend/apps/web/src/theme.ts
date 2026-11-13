import type { ThemeConfig } from 'antd';
import { tokens } from '@platform/design-system';

export const platformTheme: ThemeConfig = {
  token: {
    colorPrimary: tokens.color.brand,
    colorLink: tokens.color.brand,
    colorText: tokens.color.textPrimary,
    colorTextSecondary: tokens.color.textSecondary,
    colorBgLayout: tokens.color.pageBg,
    colorBorder: tokens.color.border,
    borderRadius: tokens.radius.md,
    fontFamily: tokens.font.family,
    fontSize: tokens.font.sizeBody,
  },
  components: {
    Layout: {
      headerBg: tokens.color.topbar,
      headerPadding: '0 24px',
      siderBg: tokens.color.sidebar,
      bodyBg: tokens.color.pageBg,
    },
    Menu: {
      itemBg: 'transparent',
      itemColor: tokens.color.sidebarText,
      itemHoverBg: tokens.color.sidebarHover,
      itemSelectedBg: tokens.color.sidebarActive,
      itemSelectedColor: tokens.color.brand,
      itemHeight: 40,
      itemBorderRadius: tokens.radius.md,
      subMenuItemBg: 'transparent',
      groupTitleColor: tokens.color.sidebarMuted,
      groupTitleFontSize: 12,
    },
    Table: {
      headerBg: tokens.color.surfaceMuted,
      headerColor: tokens.color.textSecondary,
      rowHoverBg: '#f7faff',
      cellPaddingBlockSM: 8,
    },
    Card: {
      borderRadiusLG: 10,
    },
    Button: {
      controlHeight: 34,
      fontWeight: 500,
    },
    Input: {
      controlHeight: 34,
    },
    Select: {
      controlHeight: 34,
    },
    Breadcrumb: {
      fontSize: 13,
    },
  },
};
