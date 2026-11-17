import { routeModule, type RouteName } from '../router';

/** 新租户默认启用的基础模块；与后端 ModuleRegistry 保持一致。 */
export const BASE_MODULES: readonly string[] = [
  'platform.workbench',
  'organization',
  'appcatalog',
  'privacy',
  'worklog',
  'expense',
  'collaboration',
  'workflow',
  'insights',
];

/**
 * 路由是否可用：能力清单未拿到（null）时不过滤模块，交给后端逐请求鉴权。
 */
export function isModuleEnabled(
  route: RouteName,
  enabledModules: ReadonlySet<string> | null,
): boolean {
  if (enabledModules === null) {
    return true;
  }
  const moduleCode = routeModule(route);
  return !moduleCode || enabledModules.has(moduleCode);
}
