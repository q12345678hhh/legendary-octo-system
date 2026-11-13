import { type ApiClient, type WorkflowInstanceDetail } from '@platform/api-client';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState } from '../components/AsyncState';
import { describeError, formatDateTime, shortId } from '../utils/format';

const { Text, Paragraph } = Typography;

const INSTANCE_STATUS_LABEL: Record<string, string> = {
  running: '运行中',
  completed: '已完成',
  returned: '已退回',
  withdrawn: '已撤回',
  cancelled: '已取消',
  failed: '失败',
};

const TASK_STATUS_LABEL: Record<string, string> = {
  pending: '待处理',
  claimed: '已认领',
  completed: '已完成',
  transferred: '已转交',
  returned: '已退回',
  cancelled: '已取消',
};

export interface WorkflowInstanceViewProps {
  apiClient: ApiClient;
  instanceId: string;
}

/** 流程实例详情：令牌、任务与只追加日志共同说明「为什么停在这里」。 */
export function WorkflowInstanceView({ apiClient, instanceId }: WorkflowInstanceViewProps) {
  const [detail, setDetail] = useState<WorkflowInstanceDetail | null>(null);
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => {
    setPhase('loading');
    setError(null);
    return apiClient
      .getWorkflowInstance(instanceId)
      .then((value) => {
        setDetail(value);
        setPhase('ready');
      })
      .catch((cause: unknown) => {
        setError(describeError(cause));
        setPhase('error');
      });
  }, [apiClient, instanceId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  function runAction(kind: 'withdraw' | 'cancel'): void {
    setActionError(null);
    setBusy(true);
    const request =
      kind === 'withdraw'
        ? apiClient.withdrawWorkflowInstance(instanceId)
        : apiClient.cancelWorkflowInstance(instanceId);
    request
      .then(() => refresh())
      .catch((cause: unknown) => setActionError(describeError(cause)))
      .finally(() => setBusy(false));
  }

  if (phase !== 'ready' || detail === null) {
    return (
      <AsyncState
        phase={phase === 'error' ? 'error' : 'loading'}
        loadingText="正在读取流程实例…"
        errorTitle="流程实例加载失败"
        errorMessage={error ?? '请稍后重试，或检查后端服务是否可用。'}
        onRetry={() => void refresh()}
      />
    );
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title="流程概览"
        extra={
          detail.instance.status === 'running' ? (
            <Space>
              <Button size="small" loading={busy} onClick={() => runAction('withdraw')}>
                撤回
              </Button>
              <Button size="small" danger loading={busy} onClick={() => runAction('cancel')}>
                取消
              </Button>
            </Space>
          ) : null
        }
      >
        {actionError && (
          <Alert type="error" showIcon message={actionError} style={{ marginBottom: 12 }} />
        )}
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label="实例">
            <Text code>{shortId(detail.instance.id, 12)}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={detail.instance.status === 'running' ? 'processing' : 'default'}>
              {INSTANCE_STATUS_LABEL[detail.instance.status] ?? detail.instance.status}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="定义">
            {detail.instance.definitionKey} v{detail.instance.definitionVersion}
          </Descriptions.Item>
          <Descriptions.Item label="业务">
            {detail.instance.businessType}
            {detail.instance.businessId ? ` · ${shortId(detail.instance.businessId, 8)}` : ''}
          </Descriptions.Item>
          <Descriptions.Item label="发起人">
            <Text code>{shortId(detail.instance.startedByPersonId, 8)}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="开始时间">
            {formatDateTime(detail.instance.startedAt)}
          </Descriptions.Item>
          <Descriptions.Item label="结束时间">
            {detail.instance.endedAt ? formatDateTime(detail.instance.endedAt) : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="变量">
            <Text code style={{ fontSize: 12 }}>
              {detail.instance.variables}
            </Text>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="任务">
        {detail.tasks.length === 0 ? (
          <Paragraph type="secondary">还没有审批任务。</Paragraph>
        ) : (
          <Table
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={detail.tasks}
            columns={[
              { title: '节点', dataIndex: 'nodeKey' },
              { title: '标题', dataIndex: 'title' },
              {
                title: '处理人',
                dataIndex: 'assigneePersonId',
                render: (value: string | null | undefined) =>
                  value ? <Text code>{shortId(value, 8)}</Text> : '未分配',
              },
              {
                title: '状态',
                dataIndex: 'status',
                render: (value: string) => TASK_STATUS_LABEL[value] ?? value,
              },
              {
                title: '动作',
                dataIndex: 'action',
                render: (value: string | null | undefined) => value ?? '—',
              },
              {
                title: '意见',
                dataIndex: 'comment',
                render: (value: string | null | undefined) => value ?? '—',
              },
            ]}
          />
        )}
      </Card>

      <Card title="状态流转日志">
        {detail.logs.length === 0 ? (
          <Paragraph type="secondary">暂无日志。</Paragraph>
        ) : (
          <Timeline
            items={detail.logs.map((log) => ({
              key: log.id,
              children: (
                <Space direction="vertical" size={0}>
                  <Text>
                    {log.action}
                    {log.nodeKey ? ` · ${log.nodeKey}` : ''}
                    {log.fromStatus || log.toStatus
                      ? `（${log.fromStatus ?? '—'} → ${log.toStatus ?? '—'}）`
                      : ''}
                  </Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {formatDateTime(log.occurredAt)}
                    {log.comment ? ` · ${log.comment}` : ''}
                  </Text>
                </Space>
              ),
            }))}
          />
        )}
      </Card>
    </Space>
  );
}
