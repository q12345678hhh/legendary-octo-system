import { Breadcrumb, Button, Layout, Menu, Space } from 'antd';
import type { MenuProps } from 'antd';
import type { ReactNode, RefObject } from 'react';
import { navigate, routePermission, routeTitle, routes, type RouteName } from '../router';
import type { Session } from '../session';
import { shortId } from '../utils/format';

const { Header, Sider, Content } = Layout;

interface NavGroup {
  label: string;
  items: RouteName[];
}

const NAV_GROUPS: readonly NavGroup[] = [
  { label: '工作台', items: ['myWork', 'home', 'departments', 'worklog', 'collaboration', 'workflowInbox'] },
  { label: '管理', items: ['insights', 'modules', 'jobs', 'files'] },
];

/** 子页面高亮所属的一级菜单。 */
const PARENT_ROUTE: Partial<Record<RouteName, RouteName>> = {
  department: 'departments',
  collaborationChannel: 'collaboration',
  workflowInstance: 'workflowInbox',
};

export interface AppShellProps {
  session: Session;
  route: RouteName;
  permissions: Set<string> | null;
  onSignOut: () => void;
  mainRef: RefObject<HTMLElement>;
  children: ReactNode;
}

/** 全站骨架：蓝色顶栏 + 浅色侧栏 + 卡片化内容区。 */
export function AppShell({
  session,
  route,
  permissions,
  onSignOut,
  mainRef,
  children,
}: AppShellProps) {
  const visibleItems = (names: RouteName[]): { key: string; label: string }[] =>
    names.flatMap((name) => {
      const item = routes.find((candidate) => candidate.name === name);
      if (!item) {
        return [];
      }
      const permission = routePermission(item.name);
      const allowed = !permission || permissions === null || permissions.has(permission);
      return allowed ? [{ key: item.name, label: item.title }] : [];
    });

  const items: NonNullable<MenuProps['items']> = [];
  for (const group of NAV_GROUPS) {
    const children = visibleItems(group.items);
    if (children.length > 0) {
      items.push({ type: 'group', label: group.label, children });
    }
  }

  const selectedKey = PARENT_ROUTE[route] ?? route;
  const breadcrumbItems =
    route === 'home'
      ? [{ title: '首页' }]
      : [{ title: '首页' }, { title: routeTitle(PARENT_ROUTE[route] ?? route) }];

  return (
    <Layout className="app-shell">
      <a
        className="skip-link"
        href="#main-content"
        onClick={(event) => {
          event.preventDefault();
          mainRef.current?.focus();
        }}
      >
        跳到主内容
      </a>

      <Header className="app-shell-header">
        <span className="app-shell-brand">企业应用管理平台</span>
        <Space size="middle" align="center">
          <span className="app-shell-user">
            租户 {shortId(session.tenantId)} · 主体 {shortId(session.subjectId)}
          </span>
          <Button size="small" ghost onClick={onSignOut}>
            退出
          </Button>
        </Space>
      </Header>

      <Layout>
        <Sider
          width={208}
          theme="light"
          className="app-shell-sider"
          breakpoint="lg"
          collapsedWidth={0}
        >
          <nav aria-label="主导航">
            <Menu
              mode="inline"
              items={items}
              selectedKeys={[selectedKey]}
              onClick={({ key }) => {
                if (routes.some((item) => item.name === key)) {
                  navigate(key as RouteName);
                }
              }}
            />
          </nav>
        </Sider>

        <Content className="app-shell-main">
          <main id="main-content" ref={mainRef} tabIndex={-1} className="skip-target">
            <Breadcrumb className="app-shell-breadcrumb" items={breadcrumbItems} />
            <h1 className="app-shell-title">{routeTitle(route)}</h1>
            {children}
          </main>
        </Content>
      </Layout>
    </Layout>
  );
}
