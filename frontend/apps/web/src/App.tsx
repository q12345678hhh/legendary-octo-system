import type { ApiClient } from '@platform/api-client';
import { tokens } from '@platform/design-system';
import { Alert, Card, Layout, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';

const { Header, Content } = Layout;
const { Title, Text } = Typography;

type HealthState =
  | { phase: 'loading' }
  | { phase: 'ready'; status: string; service: string }
  | { phase: 'error'; message: string };

interface AppProps {
  apiClient: ApiClient;
}

export function App({ apiClient }: AppProps) {
  const [health, setHealth] = useState<HealthState>({ phase: 'loading' });

  useEffect(() => {
    let cancelled = false;

    apiClient
      .getSystemHealth()
      .then((response) => {
        if (!cancelled) {
          setHealth({ phase: 'ready', status: response.status, service: response.service });
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setHealth({ phase: 'error', message: error instanceof Error ? error.message : '未知错误' });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [apiClient]);

  return (
    <Layout style={{ minHeight: '100vh', background: tokens.color.surfaceMuted }}>
      <Header style={{ background: tokens.color.surface, borderBottom: `1px solid ${tokens.color.border}` }}>
        <Title level={4} style={{ margin: 0, lineHeight: '64px' }}>
          企业应用管理平台
        </Title>
      </Header>
      <Content style={{ padding: tokens.spacing.xl }}>
        <Card title="系统状态" style={{ maxWidth: 560 }}>
          {health.phase === 'loading' && (
            <Space>
              <Spin size="small" />
              <Text type="secondary">正在读取服务状态…</Text>
            </Space>
          )}
          {health.phase === 'error' && (
            <Alert type="error" showIcon message="无法连接后端服务" description={health.message} />
          )}
          {health.phase === 'ready' && (
            <Space direction="vertical" size="small">
              <Space>
                <Text>服务</Text>
                <Text strong>{health.service}</Text>
                <Tag color={health.status === 'UP' ? 'green' : 'red'}>{health.status}</Tag>
              </Space>
              <Text type="secondary">数据源：GET /api/v1/system/health</Text>
            </Space>
          )}
        </Card>
      </Content>
    </Layout>
  );
}
