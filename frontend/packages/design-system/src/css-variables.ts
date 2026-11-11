import { tokens, type Tokens } from './tokens';

function toKebabCase(value: string): string {
  return value.replace(/[A-Z]/g, (char) => `-${char.toLowerCase()}`);
}

function flatten(prefix: string, value: unknown, target: Record<string, string>): void {
  if (typeof value === 'string' || typeof value === 'number') {
    target[prefix] = String(value);
    return;
  }
  if (value !== null && typeof value === 'object') {
    for (const [key, nested] of Object.entries(value)) {
      flatten(`${prefix}-${toKebabCase(key)}`, nested, target);
    }
  }
}

/**
 * 把嵌套 token 展开成 CSS 变量，例如 `--platform-color-brand`。
 * 变量注入到 :root 后，样式表与第三方组件主题共用同一份值。
 */
export function toCssVariables(source: Tokens = tokens): Record<string, string> {
  const variables: Record<string, string> = {};

  for (const [group, entries] of Object.entries(source)) {
    flatten(`--platform-${toKebabCase(group)}`, entries, variables);
  }

  return variables;
}
