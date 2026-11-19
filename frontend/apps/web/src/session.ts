/**
 * 页面会话：登录成功后把租户与 actor 主体存进 sessionStorage，用于请求头与路由刷新恢复。
 * 真正的凭据是后端下发的 HttpOnly 会话 Cookie，这里不保存任何令牌。
 */
export interface Session {
  tenantId: string;
  subjectId: string;
}

const STORAGE_KEY = 'platform.session.v1';

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value.trim());
}

export function readSession(): Session | null {
  try {
    const raw = globalThis.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof (parsed as Session).tenantId === 'string' &&
      typeof (parsed as Session).subjectId === 'string'
    ) {
      const session = parsed as Session;
      return { tenantId: session.tenantId, subjectId: session.subjectId };
    }
    return null;
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  globalThis.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  globalThis.sessionStorage.removeItem(STORAGE_KEY);
}
