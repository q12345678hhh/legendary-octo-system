/**
 * 极简 hash 路由：不引路由库，避免为骨架引入额外依赖。
 * 路由名 → `#/xxx`；未知 hash 返回 null，由调用方决定回退目标。
 */
export type RouteName = 'login' | 'home' | 'jobs' | 'files';

export interface RouteDefinition {
  name: RouteName;
  path: string;
  title: string;
  requiresSession: boolean;
}

export const routes: readonly RouteDefinition[] = [
  { name: 'login', path: '#/login', title: '登录', requiresSession: false },
  { name: 'home', path: '#/home', title: '总览', requiresSession: true },
  { name: 'jobs', path: '#/jobs', title: '异步作业', requiresSession: true },
  { name: 'files', path: '#/files', title: '文件', requiresSession: true },
];

type RouteListener = (name: RouteName) => void;

const listeners = new Set<RouteListener>();

export function parseHash(hash: string): RouteName | null {
  const withoutQuery = hash.split('?')[0] ?? '';
  const normalized = withoutQuery.length > 2 ? withoutQuery.replace(/\/+$/, '') : withoutQuery;
  const matched = routes.find((route) => route.path === normalized);
  return matched ? matched.name : null;
}

export function routeHref(name: RouteName): string {
  const matched = routes.find((route) => route.name === name);
  return matched ? matched.path : '#/login';
}

export function routeTitle(name: RouteName): string {
  const matched = routes.find((route) => route.name === name);
  return matched ? matched.title : '';
}

export function requiresSession(name: RouteName): boolean {
  const matched = routes.find((route) => route.name === name);
  return matched ? matched.requiresSession : true;
}

function notify(name: RouteName): void {
  for (const listener of listeners) {
    listener(name);
  }
}

export function navigate(name: RouteName): void {
  const href = routeHref(name);
  if (globalThis.location.hash !== href) {
    globalThis.location.hash = href;
  }
  notify(name);
}

/** 订阅路由变化（含浏览器前进/后退触发的 hashchange）。 */
export function subscribeToRoute(listener: RouteListener): () => void {
  listeners.add(listener);

  const onHashChange = (): void => {
    const name = parseHash(globalThis.location.hash);
    if (name) {
      listener(name);
    }
  };
  globalThis.addEventListener('hashchange', onHashChange);

  return () => {
    listeners.delete(listener);
    globalThis.removeEventListener('hashchange', onHashChange);
  };
}
