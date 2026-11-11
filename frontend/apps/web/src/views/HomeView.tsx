import type { ApiClient, HealthResponse, Job } from '@platform/api-client';
import { Card, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { JobStatusTag } from '../components/StatusTag';
import type { Session } from '../session';
import { describeError, formatDateTime, shortId } from '../utils/format';

const { Text } = Typography;

export interface HomeViewProps {
  apiClient: ApiClient;
  session: Session;
}

type HomeState =
  | { phase: 'loading' }
  | { phase: 'ready'; health: HealthResponse; jobs: Job[] }
  | { phase: 'error'; message: string };

export function HomeView({ apiClient, session }: HomeViewProps) {
  const [state, setState] = useState<HomeState>({ phase: 'loading' });

  const load = useCallback(() => {
    setState({ phase: 'loading' });
    Promise.all([apiClient.getSystemHealth(), apiClient.listJobs(5)])
      .then(([health, jobs]) => setState({ phase: 'ready', health, jobs }))
      .catch((error: unknown) => setState({ phase: 'error', message: describeError(error) }));
  }, [apiClient]);

  useEffect(() => {
    load();
  }, [load]);

  const phase: AsyncPhase = state.phase === 'loading' ? 'loading' : 'ready';
  const errorMessage = state.phase === 'error' ? state.message : '';

  if (state.phase === 'error') {
    return (
      <AsyncState
        phase="error"
        errorTitle="无法连接后端服务"
        errorMessage={errorMessage}
        onRetry={load}
      />
    );
  }

  return (
    <AsyncState phase={phase}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Card title="系统状态" style={{ maxWidth: 640 }}>
          {state.phase === 'ready' && (
            <Space direction="vertical" size="small">
              <Space>
                <Text>服务</Text>
                <Text strong>{state.health.service}</Text>
                <Tag color={state.health.status === 'UP' ? 'green' : 'red'}>
                  {state.health.status}
                </Tag>
              </Space>
              <Text type="secondary">数据源：GET /api/v1/system/health</Text>
            </Space>
          )}
        </Card>

        <Card
          title="最近作业与追踪号"
          extra={<Text type="secondary">当前租户 {shortId(session.tenantId)}</Text>}
        >
          {state.phase === 'ready' && (
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Text type="secondary">
                页面请求会带上 X-Correlation-Id 与追踪号；用作业的 traceId 可在 API 与 Worker
                日志中串起同一次执行，数据库行同样保存该值。
              </Text>
              {state.jobs.length === 0 ? (
                <AsyncState phase="empty" emptyText="还没有作业，去「异步作业」页创建一个。" />
              ) : (
                <Table<Job>
                  size="small"
                  rowKey="jobId"
                  pagination={false}
                  dataSource={state.jobs}
                  columns={[
                    {
                      title: '作业',
                      dataIndex: 'jobType',
                      render: (_value, record) => (
                        <Space direction="vertical" size={0}>
                          <Text>{record.jobType}</Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            {shortId(record.jobId, 12)}
                          </Text>
                        </Space>
                      ),
                    },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      render: (_value, record) => <JobStatusTag status={record.status} />,
                    },
                    {
                      title: '进度',
                      dataIndex: 'progress',
                      render: (_value, record) => `${record.progress}%`,
                    },
                    {
                      title: 'traceId',
                      dataIndex: 'traceId',
                      render: (_value, record) => (
                        <Text code copyable style={{ fontSize: 12 }}>
                          {record.traceId}
                        </Text>
                      ),
                    },
                    {
                      title: '请求时间',
                      dataIndex: 'requestedAt',
                      render: (_value, record) => formatDateTime(record.requestedAt),
                    },
                  ]}
                />
              )}
            </Space>
          )}
        </Card>

        <Card title="可观测性入口" style={{ maxWidth: 640 }}>
          <Space direction="vertical" size="small">
            <Text>
              <Text code>/actuator/metrics/platform.jobs.submitted</Text> 查看作业计数
            </Text>
            <Text>
              <Text code>/actuator/prometheus</Text> 抓取全部平台指标
            </Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              日志为 ECS 结构化 JSON，默认不记录请求体与业务字段。
            </Text>
          </Space>
        </Card>
      </Space>
    </AsyncState>
  );
}
