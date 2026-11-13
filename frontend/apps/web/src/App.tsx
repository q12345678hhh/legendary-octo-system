import type { ApiClient } from '@platform/api-client';
import { tokens } from '@platform/design-system';
import { Alert, Layout } from 'antd';
import { Component, useEffect, useRef, useState, type ReactNode } from 'react';
import { AppShell } from './layout/AppShell';
import {
  matchRoute,
  navigate,
  parseHash,
  subscribeToRoute,
  type RouteName,
} from './router';
import { clearSession, readSession, type Session } from './session';
import { CollaborationView } from './views/CollaborationView';
import { DepartmentWorkspaceView } from './views/DepartmentWorkspaceView';
import { DepartmentsView } from './views/DepartmentsView';
import { FilesView } from './views/FilesView';
import { HomeView } from './views/HomeView';
import { InsightsView } from './views/InsightsView';
import { JobsView } from './views/JobsView';
import { LoginView } from './views/LoginView';
import { ModulesView } from './views/ModulesView';
import { WorkView } from './views/WorkView';
import { WorkflowInboxView } from './views/WorkflowInboxView';
import { WorkflowInstanceView } from './views/WorkflowInstanceView';
import { WorklogView } from './views/WorklogView';

const { Content } = Layout;

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
  const [permissions, setPermissions] = useState<Set<string> | null>(null);
  const [route, setRoute] = useState<RouteName>(() =>
    resolveRoute(parseHash(globalThis.location.hash), readSession()),
  );
  const mainRef = useRef<HTMLElement>(null);
  const params = matchRoute(globalThis.location.hash)?.params ?? {};

  useEffect(() => subscribeToRoute(setRoute), []);

  useEffect(() => {
    if (!session) {
      setPermissions(null);
      return undefined;
    }
    let active = true;
    apiClient
      .getCapabilities?.()
      .then((capabilities) => {
        if (active) {
          setPermissions(new Set(capabilities.permissions));
        }
      })
      .catch(() => {
        // 能力清单失败时保留全部导航；直接访问仍由后端逐请求鉴权。
        if (active) {
          setPermissions(null);
        }
      });
    return () => {
      active = false;
    };
  }, [apiClient, session]);

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
    // 清 Cookie 失败不阻塞本地退出；服务端会话按 TTL 自然过期。
    void apiClient.signOut().catch(() => undefined);
    clearSession();
    setSession(null);
    navigate('login');
  }

  if (!session) {
    return (
      <Layout style={{ minHeight: '100vh', background: tokens.color.pageBg }}>
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
        <Content style={{ padding: tokens.spacing.xl }}>
          <main
            id="main-content"
            ref={mainRef}
            tabIndex={-1}
            className="skip-target"
            style={{ maxWidth: 960, margin: '0 auto' }}
          >
            <LoginView apiClient={apiClient} onSignedIn={handleSignedIn} />
          </main>
        </Content>
      </Layout>
    );
  }

  return (
    <AppShell
      session={session}
      route={route}
      permissions={permissions}
      onSignOut={handleSignOut}
      mainRef={mainRef}
    >
      <ContentErrorBoundary>
        {route === 'login' && <LoginView apiClient={apiClient} onSignedIn={handleSignedIn} />}
        {route === 'home' && <HomeView apiClient={apiClient} session={session} />}
        {route === 'departments' && <DepartmentsView apiClient={apiClient} />}
        {route === 'department' && params.orgUnitId && (
          <DepartmentWorkspaceView apiClient={apiClient} orgUnitId={params.orgUnitId} />
        )}
        {route === 'work' && params.personId && (
          <WorkView apiClient={apiClient} personId={params.personId} />
        )}
        {route === 'worklog' && <WorklogView apiClient={apiClient} />}
        {route === 'insights' && <InsightsView apiClient={apiClient} />}
        {route === 'modules' && <ModulesView apiClient={apiClient} permissions={permissions} />}
        {route === 'collaboration' && <CollaborationView apiClient={apiClient} />}
        {route === 'collaborationChannel' && params.channelId && (
          <CollaborationView apiClient={apiClient} channelId={params.channelId} />
        )}
        {route === 'workflowInbox' && <WorkflowInboxView apiClient={apiClient} />}
        {route === 'workflowInstance' && params.instanceId && (
          <WorkflowInstanceView apiClient={apiClient} instanceId={params.instanceId} />
        )}
        {route === 'jobs' && <JobsView apiClient={apiClient} />}
        {route === 'files' && <FilesView apiClient={apiClient} />}
      </ContentErrorBoundary>
    </AppShell>
  );
}
