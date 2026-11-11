import type { ApiClient } from '@platform/api-client';
import { tokens } from '@platform/design-system';
import { Alert, Button, Layout, Space, Typography } from 'antd';
import { Component, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  navigate,
  parseHash,
  routeHref,
  routeTitle,
  routes,
  subscribeToRoute,
  type RouteName,
} from './router';
import { clearSession, readSession, type Session } from './session';
import { shortId } from './utils/format';
import { FilesView } from './views/FilesView';
import { HomeView } from './views/HomeView';
import { JobsView } from './views/JobsView';
import { LoginView } from './views/LoginView';

const { Header, Content } = Layout;
const { Title, Text } = Typography;

const NAV_ROUTES = routes.filter((route) => route.requiresSession);

interface ErrorBoundaryState {
  message: string | null;
}

/** 页面级错误边界：单个视图渲染失败不应让整个壳白屏。 */
class ContentErrorBoundary extends Component<{ children: ReactNode }, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { message: null };

  static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
    return { message: error instanceof Error ? error.message : '未知渲染错误' };
  }

  override componentDidCatch(error: unknown): void {
    console.error('[ui] 页面渲染失败', error);
  }

  override render(): ReactNode {
    if (this.state.message) {
      return (
        <div role="alert">
          <Alert type="error" showIcon message="页面渲染失败" description={this.state.message} />
        </div>
      );
    }
    return this.props.children;
  }
}

export interface AppProps {
  apiClient: ApiClient;
}

/** 无会话只能停在登录页；有会话不应停留在登录页。 */
function resolveRoute(current: RouteName | null, session: Session | null): RouteName {
  if (!session) {
    return 'login';
  }
  if (!current || current === 'login') {
    return 'home';
  }
  return current;
}

export function App({ apiClient }: AppProps) {
  const [session, setSession] = useState<Session | null>(() => readSession());
  const [route, setRoute] = useState<RouteName>(() =>
    resolveRoute(parseHash(globalThis.location.hash), readSession()),
  );
  const mainRef = useRef<HTMLElement>(null);

  useEffect(() => subscribeToRoute(setRoute), []);

  useEffect(() => {
    const target = resolveRoute(route, session);
    // 路由状态与地址栏保持一致：深链越权或无会话访问都要把地址纠正到实际视图。
    if (target !== route || parseHash(globalThis.location.hash) !== target) {
      navigate(target);
    }
  }, [route, session]);

  // 路由切换后把焦点移到主区域，键盘与读屏用户能立即感知内容变化。
  useEffect(() => {
    mainRef.current?.focus();
  }, [route]);

  function handleSignedIn(next: Session): void {
    setSession(next);
    navigate('home');
  }

  function handleSignOut(): void {
    clearSession();
    setSession(null);
    navigate('login');
  }

  return (
    <Layout style={{ minHeight: '100vh', background: tokens.color.surfaceMuted }}>
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

      <Header
        style={{
          background: tokens.color.surface,
          borderBottom: `1px solid ${tokens.color.border}`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: tokens.spacing.md,
          paddingInline: tokens.spacing.lg,
          height: 'auto',
          minHeight: 64,
          flexWrap: 'wrap',
        }}
      >
        <Space size="large" align="center" wrap>
          <Title level={4} style={{ margin: 0 }}>
            企业应用管理平台
          </Title>
          {session && (
            <nav aria-label="主导航">
              <Space size="middle">
                {NAV_ROUTES.map((item) => (
                  <a
                    key={item.name}
                    href={routeHref(item.name)}
                    aria-current={route === item.name ? 'page' : undefined}
                  >
                    {item.title}
                  </a>
                ))}
              </Space>
            </nav>
          )}
        </Space>

        {session && (
          <Space size="middle" wrap>
            <Text type="secondary" style={{ fontSize: 12 }}>
              租户 {shortId(session.tenantId)} · 主体 {shortId(session.subjectId)}
            </Text>
            <Button size="small" onClick={handleSignOut}>
              退出
            </Button>
          </Space>
        )}
      </Header>

      <Content style={{ padding: tokens.spacing.xl }}>
        <main
          id="main-content"
          ref={mainRef}
          tabIndex={-1}
          className="skip-target"
          style={{ maxWidth: 1120, margin: '0 auto' }}
        >
          <Title level={2} style={{ fontSize: 24, marginBottom: tokens.spacing.lg }}>
            {routeTitle(route)}
          </Title>
          <ContentErrorBoundary>
            {route === 'login' && <LoginView onSignedIn={handleSignedIn} />}
            {route === 'home' && session && <HomeView apiClient={apiClient} session={session} />}
            {route === 'jobs' && <JobsView apiClient={apiClient} />}
            {route === 'files' && <FilesView apiClient={apiClient} />}
          </ContentErrorBoundary>
        </main>
      </Content>
    </Layout>
  );
}
