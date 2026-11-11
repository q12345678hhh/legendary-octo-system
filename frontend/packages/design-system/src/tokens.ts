/**
 * 平台设计 token。组件只引用 token，不写死颜色、间距与字号。
 */
export const tokens = {
  color: {
    brand: '#1b5cff',
    brandHover: '#0f4ae0',
    surface: '#ffffff',
    surfaceMuted: '#f5f7fb',
    border: '#d9e2ec',
    textPrimary: '#1f2933',
    textSecondary: '#52606d',
    danger: '#b42318',
    warning: '#b25e09',
    info: '#0b6bcb',
    success: '#1f7a44',
  },
  /** 键盘焦点环，仅用于 :focus-visible 样式。 */
  focusRing: '0 0 0 3px rgba(27, 92, 255, 0.45)',
  spacing: {
    xs: 4,
    sm: 8,
    md: 16,
    lg: 24,
    xl: 32,
  },
  radius: {
    sm: 4,
    md: 8,
    lg: 12,
  },
  control: {
    heightSm: 32,
    height: 40,
    heightLg: 48,
  },
  font: {
    family: "'Segoe UI', 'Microsoft YaHei', system-ui, sans-serif",
    sizeBody: 14,
    sizeTitle: 20,
  },
} as const;

export type Tokens = typeof tokens;
