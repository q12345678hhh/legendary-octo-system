/**
 * 极简 hash 路由：不引路由库，避免为骨架引入额外依赖。
 * 支持 `#/departments/:orgUnitId` 形式的单段参数；未知 hash 返回 null。
 */
export type RouteName =
  | 'login'
  | 'home'
  | 'jobs'
  | 'files'
  | 'departments'
  | 'department'
  | 'work'
  | 'worklog'
  | 'insights'
  | 'collaboration'
  | 'collaborationChannel'
  | 'workflowInbox'
  | 'workflowInstance';

export interface RouteDefinition {
  name: RouteName;
  path: string;
  title: string;
  requiresSession: boolean;
  /** 导航所需权限码；后端仍会对直接访问重新鉴权。 */
  permission?: string;
}

export interface RouteMatch {
  name: RouteName;
  params: Readonly<Record<string, string>>;
}

export const routes: readonly RouteDefinition[] = [
  { name: 'login', path: '#/login', title: '登录', requiresSession: false },
  { name: 'home', path: '#/home', title: '总览', requiresSession: true },
  {
    name: 'departments',
    path: '#/departments',
    title: '部门',
    requiresSession: true,
    permission: 'department.workspace.read',
  },
  {
    name: 'department',
    path: '#/departments/:orgUnitId',
    title: '部门工作区',
    requiresSession: true,
    permission: 'department.workspace.read',
  },
  {
    name: 'work',
    path: '#/work/:personId',
    title: '个人工作页',
    requiresSession: true,
    permission: 'business.work.read',
  },
  {
    name: 'worklog',
    path: '#/worklog',
    title: '工时与周报',
    requiresSession: true,
    permission: 'worklog.time.write',
  },
  {
    name: 'insights',
    path: '#/insights',
    title: '管理洞察',
    requiresSession: true,
    permission: 'insight.work.read',
  },
  {
    name: 'collaboration',
    path: '#/collaboration',
    title: '协作',
    requiresSession: true,
    permission: 'collaboration.channel.read',
  },
  {
    name: 'collaborationChannel',
    path: '#/collaboration/:channelId',
    title: '频道',
    requiresSession: true,
    permission: 'collaboration.channel.read',
  },
  {
    name: 'workflowInbox',
    path: '#/workflow',
    title: '审批箱',
    requiresSession: true,
    permission: 'workflow.task.act',
  },
  {
    name: 'workflowInstance',
    path: '#/workflow/:instanceId',
    title: '流程实例',
    requiresSession: true,
    permission: 'workflow.task.act',
  },
  { name: 'jobs', path: '#/jobs', title: '异步作业', requiresSession: true },
  { name: 'files', path: '#/files', title: '文件', requiresSession: true },
];

type RouteListener = (name: RouteName) => void;

const listeners = new Set<RouteListener>();

function normalize(hash: string): string {
  const withoutQuery = hash.split('?')[0] ?? '';
  return withoutQuery.length > 2 ? withoutQuery.replace(/\/+$/, '') : withoutQuery;
}

/** 匹配路由并抽取单段参数；参数按段解码，便于直接使用 UUID。 */
export function matchRoute(hash: string): RouteMatch | null {
  const normalized = normalize(hash);
  const segments = normalized.split('/');
  for (const route of routes) {
    const patternSegments = route.path.split('/');
    if (patternSegments.length !== segments.length) {
      continue;
    }
    const params: Record<string, string> = {};
    let matched = true;
    for (let index = 0; index < patternSegments.length; index += 1) {
      const pattern = patternSegments[index] ?? '';
      const value = segments[index] ?? '';
      if (pattern.startsWith(':')) {
        if (!value) {
          matched = false;
          break;
        }
        params[pattern.slice(1)] = decodeURIComponent(value);
      } else if (pattern !== value) {
        matched = false;
        break;
      }
    }
    if (matched) {
      return { name: route.name, params };
    }
  }
  return null;
}

export function parseHash(hash: string): RouteName | null {
  return matchRoute(hash)?.name ?? null;
}

export function routeHref(name: RouteName, params: Record<string, string> = {}): string {
  const matched = routes.find((route) => route.name === name);
  if (!matched) {
    return '#/login';
  }
  return matched.path.replace(/:([A-Za-z0-9_]+)/g, (_all, key: string) => {
    const value = params[key];
    return value === undefined ? '' : encodeURIComponent(value);
  });
}

export function routeTitle(name: RouteName): string {
  const matched = routes.find((route) => route.name === name);
  return matched ? matched.title : '';
}

export function requiresSession(name: RouteName): boolean {
  const matched = routes.find((route) => route.name === name);
  return matched ? matched.requiresSession : true;
}

export function routePermission(name: RouteName): string | undefined {
  return routes.find((route) => route.name === name)?.permission;
}

function notify(name: RouteName): void {
  for (const listener of listeners) {
    listener(name);
  }
}

export function navigate(name: RouteName, params: Record<string, string> = {}): void {
  const href = routeHref(name, params);
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
