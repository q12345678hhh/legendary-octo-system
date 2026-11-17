import { type ApiClient, type WorkflowTask } from '@platform/api-client';
import {
  Alert,
  Button,
  Card,
  Input,
  Modal,
  Segmented,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState } from '../components/AsyncState';
import { routeHref } from '../router';
import { describeError, formatDateTime, shortId } from '../utils/format';

const { Text, Paragraph } = Typography;

const SLA_LABEL: Record<string, string> = {
  normal: '正常',
  warning: '即将超时',
  breached: '已超时',
};

const SLA_COLOR: Record<string, string> = {
  normal: 'default',
  warning: 'orange',
  breached: 'red',
};

const BUSINESS_LABEL: Record<string, string> = {
  'worklog.time': '工时',
  'worklog.report': '周报',
  'workflow.escalation': 'SLA 升级',
};

export interface WorkflowInboxViewProps {
  apiClient: ApiClient;
}

type ActionKind = 'approve' | 'return' | 'transfer';

/**
 * 审批箱：默认只看本人待办；管理员可切到「全部」。
 *
 * <p>退回必须填写原因；所有动作都会重新拉取列表，避免本地状态与服务端不一致。
 */
export function WorkflowInboxView({ apiClient }: WorkflowInboxViewProps) {
  const [scope, setScope] = useState<'mine' | 'all'>('mine');
  const [tasks, setTasks] = useState<WorkflowTask[] | null>(null);
  const [phase, setPhase] = useState<'loading' | 'ready' | 'empty' | 'error'>('loading');
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [acting, setActing] = useState<{ task: WorkflowTask; kind: ActionKind } | null>(null);
  const [comment, setComment] = useState('');
  const [targetPersonId, setTargetPersonId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const refresh = useCallback(
    (nextScope: 'mine' | 'all' = scope) => {
      setPhase('loading');
      setError(null);
      return apiClient
        .listWorkflowTasks(nextScope)
        .then((list) => {
          setTasks(list);
          setPhase(list.length === 0 ? 'empty' : 'ready');
        })
        .catch((cause: unknown) => {
          setError(describeError(cause));
          setPhase('error');
        });
    },
    [apiClient, scope],
  );

  useEffect(() => {
    void refresh(scope);
  }, [refresh, scope]);

  function openAction(task: WorkflowTask, kind: ActionKind): void {
    setActionError(null);
    setActing({ task, kind });
    setComment('');
    setTargetPersonId('');
  }

  function closeAction(): void {
    setActing(null);
    setSubmitting(false);
  }

  function submitAction(): void {
    if (!acting) {
      return;
    }
    if (acting.kind === 'return' && comment.trim() === '') {
      setActionError('退回必须填写原因。');
      return;
    }
    if (acting.kind === 'transfer' && targetPersonId.trim() === '') {
      setActionError('转交必须填写目标人员 ID。');
      return;
    }
    setSubmitting(true);
    const request =
      acting.kind === 'transfer'
        ? apiClient.transferWorkflowTask(acting.task.id, {
            targetPersonId: targetPersonId.trim(),
            ...(comment.trim() ? { comment: comment.trim() } : {}),
          })
        : apiClient.completeWorkflowTask(acting.task.id, {
            action: acting.kind,
            ...(comment.trim() ? { comment: comment.trim() } : {}),
          });
    request
      .then(() => {
        closeAction();
        return refresh();
      })
      .catch((cause: unknown) => {
        setActionError(describeError(cause));
        setSubmitting(false);
      });
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title="待办审批"
        extra={
          <Segmented
            value={scope}
            onChange={(value) => {
              setScope(value as 'mine' | 'all');
            }}
            options={[
              { label: '我的待办', value: 'mine' },
              { label: '全部待办', value: 'all' },
            ]}
          />
        }
      >
        <Paragraph type="secondary">
          审批箱只展示待办与已认领任务。退回是终态，业务需要重新提交并生成新的流程实例；
          SLA 超时只会升级提醒，不会自动通过。
        </Paragraph>
        {actionError && (
          <Alert type="error" showIcon message={actionError} style={{ marginBottom: 12 }} />
        )}
        <AsyncState
          phase={phase}
          loadingText="正在读取审批任务…"
          emptyText="当前没有待办任务。"
          errorTitle="审批任务加载失败"
          errorMessage={error ?? '请稍后重试，或检查后端服务是否可用。'}
          onRetry={() => void refresh()}
        >
          <Table<WorkflowTask>
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={tasks ?? []}
            columns={[
              {
                title: '任务',
                dataIndex: 'title',
                render: (_value, record) => (
                  <Space direction="vertical" size={0}>
                    <Text>{record.title}</Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {BUSINESS_LABEL[record.businessType] ?? record.businessType}
                      {' · '}
                      {record.nodeKey ?? '直连审核'}
                      {' · '}
                      {shortId(record.id, 8)}
                    </Text>
                  </Space>
                ),
              },
              {
                title: 'SLA',
                dataIndex: 'slaState',
                width: 110,
                render: (_value, record) => (
                  <Tag color={SLA_COLOR[record.slaState] ?? 'default'}>
                    {SLA_LABEL[record.slaState] ?? record.slaState}
                  </Tag>
                ),
              },
              {
                title: '截止',
                dataIndex: 'dueAt',
                width: 170,
                render: (_value, record) =>
                  record.dueAt ? formatDateTime(record.dueAt) : '—',
              },
              {
                title: '操作',
                key: 'actions',
                width: 320,
                render: (_value, record) => (
                  <Space wrap>
                    {record.status === 'pending' && (
                      <Button
                        size="small"
                        onClick={() => {
                          setActionError(null);
                          apiClient
                            .claimWorkflowTask(record.id)
                            .then(() => refresh())
                            .catch((cause: unknown) => setActionError(describeError(cause)));
                        }}
                      >
                        认领
                      </Button>
                    )}
                    <Button size="small" type="primary" onClick={() => openAction(record, 'approve')}>
                      通过
                    </Button>
                    <Button size="small" danger onClick={() => openAction(record, 'return')}>
                      退回
                    </Button>
                    <Button size="small" onClick={() => openAction(record, 'transfer')}>
                      转交
                    </Button>
                    {record.instanceId && (
                      <a href={routeHref('workflowInstance', { instanceId: record.instanceId })}>
                        查看流程
                      </a>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </AsyncState>
      </Card>

      <Modal
        open={acting !== null}
        title={
          acting?.kind === 'approve'
            ? '通过审批'
            : acting?.kind === 'return'
              ? '退回审批'
              : '转交审批'
        }
        okText="提交"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={submitAction}
        onCancel={closeAction}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Text type="secondary">{acting?.task.title}</Text>
          {acting?.kind === 'transfer' && (
            <Input
              aria-label="目标人员 ID"
              placeholder="目标人员 UUID"
              value={targetPersonId}
              onChange={(event) => setTargetPersonId(event.target.value)}
            />
          )}
          <Input.TextArea
            aria-label="审批意见"
            placeholder={acting?.kind === 'return' ? '退回原因（必填）' : '审批意见（可选）'}
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            rows={3}
          />
          {actionError && <Alert type="error" showIcon message={actionError} />}
        </Space>
      </Modal>
    </Space>
  );
}
