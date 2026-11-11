import type { ApiClient, Job, JsonRecord } from '@platform/api-client';
import { tokens } from '@platform/design-system';
import {
  Alert,
  Button,
  Card,
  Input,
  Progress,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AsyncState } from '../components/AsyncState';
import { JobStatusTag } from '../components/StatusTag';
import { describeError, formatDateTime, shortId } from '../utils/format';

const { Title, Text, Paragraph } = Typography;

export interface JobsViewProps {
  apiClient: ApiClient;
}

function newIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  return `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function parsePayload(text: string): JsonRecord {
  if (text.trim() === '') {
    return {};
  }
  const parsed: unknown = JSON.parse(text);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('载荷必须是 JSON 对象。');
  }
  return parsed as JsonRecord;
}

export function JobsView({ apiClient }: JobsViewProps) {
  const [jobs, setJobs] = useState<Job[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [jobType, setJobType] = useState('demo-work');
  const [payloadText, setPayloadText] = useState('{"note":"骨架演示"}');
  const [creating, setCreating] = useState(false);
  const pendingKey = useRef<string | null>(null);

  const refresh = useCallback(() => {
    return apiClient
      .listJobs(20)
      .then((list) => {
        setJobs(list);
        setLoadError(null);
      })
      .catch((error: unknown) => setLoadError(describeError(error)));
  }, [apiClient]);

  useEffect(() => {
    void refresh();
    const timer = globalThis.setInterval(() => {
      void refresh();
    }, 2000);
    return () => globalThis.clearInterval(timer);
  }, [refresh]);

  function handleCreate(): void {
    setActionError(null);

    let payload: JsonRecord;
    try {
      payload = parsePayload(payloadText);
    } catch (error) {
      setActionError(describeError(error));
      return;
    }

    const key = pendingKey.current ?? newIdempotencyKey();
    pendingKey.current = key;
    setCreating(true);

    apiClient
      .createJob({ jobType, payload }, { idempotencyKey: key })
      .then((job) => {
        pendingKey.current = null;
        setJobs((current) => [job, ...(current ?? [])]);
        return refresh();
      })
      .catch((error: unknown) => {
        // 失败时保留同一个幂等键，便于用户点「重试」而不产生重复作业。
        setActionError(describeError(error));
      })
      .finally(() => setCreating(false));
  }

  function handleCancel(jobId: string): void {
    setActionError(null);
    apiClient
      .cancelJob(jobId)
      .then(() => refresh())
      .catch((error: unknown) => setActionError(describeError(error)));
  }

  const activeJobs = (jobs ?? []).filter(
    (job) => job.status === 'queued' || job.status === 'running',
  );

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="创建异步作业">
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            变更类请求会自动携带 Idempotency-Key；创建失败重试时复用同一键，服务端不会重复建作业。
            <Text code>fail-demo</Text> 用于演示死信与安全回放。
          </Paragraph>

          {actionError && <Alert type="error" showIcon message={actionError} />}

          <Space wrap align="start">
            <label htmlFor="job-type" style={{ display: 'block' }}>
              <Text strong>作业类型</Text>
              <Select
                id="job-type"
                value={jobType}
                onChange={setJobType}
                style={{ width: 220, display: 'block', marginTop: tokens.spacing.xs }}
                options={[
                  { value: 'demo-work', label: 'demo-work（10 步进度）' },
                  { value: 'fail-demo', label: 'fail-demo（制造死信）' },
                ]}
              />
            </label>

            <label htmlFor="job-payload" style={{ display: 'block', flex: 1, minWidth: 280 }}>
              <Text strong>载荷（JSON 对象，可留空）</Text>
              <Input.TextArea
                id="job-payload"
                value={payloadText}
                onChange={(event) => setPayloadText(event.target.value)}
                rows={3}
                style={{ marginTop: tokens.spacing.xs }}
                aria-describedby="job-payload-hint"
              />
              <Text id="job-payload-hint" type="secondary" style={{ fontSize: 12 }}>
                服务端只保存载荷并投递事件，日志不记录载荷正文。
              </Text>
            </label>
          </Space>

          <Space>
            <Button type="primary" loading={creating} onClick={handleCreate}>
              创建作业
            </Button>
            <Button
              onClick={() => {
                pendingKey.current = null;
                void refresh();
              }}
            >
              刷新列表
            </Button>
            <Text type="secondary">
              {activeJobs.length > 0 ? `${activeJobs.length} 个作业进行中，每 2 秒刷新` : '空闲'}
            </Text>
          </Space>
        </Space>
      </Card>

      <Card title="作业列表">
        {loadError ? (
          <AsyncState
            phase="error"
            errorTitle="作业列表加载失败"
            errorMessage={loadError}
            onRetry={() => void refresh()}
          />
        ) : jobs === null ? (
          <AsyncState phase="loading" loadingText="正在读取作业…" />
        ) : jobs.length === 0 ? (
          <AsyncState phase="empty" emptyText="还没有作业，先创建一个。" />
        ) : (
          <Table<Job>
            size="small"
            rowKey="jobId"
            pagination={false}
            dataSource={jobs}
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
                render: (_value, record) => (
                  <Space direction="vertical" size={0}>
                    <JobStatusTag status={record.status} />
                    {record.cancelRequested && (record.status === 'queued' || record.status === 'running') && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        已请求取消
                      </Text>
                    )}
                  </Space>
                ),
              },
              {
                title: '进度',
                dataIndex: 'progress',
                width: 140,
                render: (_value, record) => (
                  <Progress
                    percent={record.progress}
                    size="small"
                    status={record.status === 'failed' ? 'exception' : 'normal'}
                  />
                ),
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
              {
                title: '操作',
                key: 'actions',
                render: (_value, record) =>
                  record.status === 'queued' || record.status === 'running' ? (
                    <Button size="small" danger onClick={() => handleCancel(record.jobId)}>
                      取消
                    </Button>
                  ) : null,
              },
            ]}
          />
        )}
      </Card>

      <Title level={4} style={{ marginBottom: 0 }}>
        作业状态说明
      </Title>
      <Text type="secondary">
        Worker 每步都检查取消标记；失败作业会进入 outbox 死信，可按「安全回放」重投事件但不做业务补偿。
      </Text>
    </Space>
  );
}
