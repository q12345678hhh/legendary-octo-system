/**
 * 占位会话：租户与主体仅存于 sessionStorage，退出即清除。
 * 这是 WP01-04 的骨架实现，真实认证由 WP02-02 的会话服务替换。
 */
export interface Session {
  tenantId: string;
  subjectId: string;
}

const STORAGE_KEY = 'platform.session.v1';

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

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
