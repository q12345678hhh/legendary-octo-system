import type { ThemeConfig } from 'antd';
import { tokens } from '@platform/design-system';

export const platformTheme: ThemeConfig = {
  token: {
    colorPrimary: tokens.color.brand,
    colorText: tokens.color.textPrimary,
    colorTextSecondary: tokens.color.textSecondary,
    colorBgLayout: tokens.color.surfaceMuted,
    colorBorder: tokens.color.border,
    borderRadius: tokens.radius.md,
    fontFamily: tokens.font.family,
    fontSize: tokens.font.sizeBody,
  },
};
