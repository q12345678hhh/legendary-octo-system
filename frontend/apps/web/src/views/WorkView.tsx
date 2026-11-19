import {
  ApiProblemError,
  type ApiClient,
  type WorkAccessLogRow,
  type WorkPage,
  type WorkPageSection,
} from '@platform/api-client';
import { Alert, Button, Card, Descriptions, Divider, Form, Input, InputNumber, Modal, Progress, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { describeError, formatDateTime } from '../utils/format';

const { Text } = Typography;

export interface WorkViewProps {
  apiClient: ApiClient;
  /** 省略时读取 /me/work-page；从部门进入时传入成员 personId。 */
  personId?: string | undefined;
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

const STATUS_LABEL: Record<string, string> = {
  none: '未开始',
  empty: '暂无',
  draft: '草稿',
  submitted: '已提交',
  confirmed: '已确认',
  reviewed: '已审阅',
  returned: '已退回',
};

function statusTag(value: unknown) {
  if (typeof value !== 'string') {
    return '—';
  }
  return <Tag>{STATUS_LABEL[value] ?? value}</Tag>;
}

function SectionBody({ section, apiClient, self, onChanged }: {
  section: WorkPageSection;
  apiClient: ApiClient;
  self: boolean;
  onChanged: () => void;
}) {
  const data = asRecord(section.data);
  if (section.key === 'time') {
    return (
      <Descriptions size="small" column={2}>
        <Descriptions.Item label="今日已确认">
          {typeof data.todayMinutes === 'number' ? `${data.todayMinutes} 分钟` : '—'}
        </Descriptions.Item>
        <Descriptions.Item label="今日状态">{statusTag(data.dayState)}</Descriptions.Item>
      </Descriptions>
    );
  }
  if (section.key === 'report') {
    return (
      <Descriptions size="small" column={2}>
        <Descriptions.Item label="本周合计">
          {typeof data.totalMinutes === 'number' ? `${data.totalMinutes} 分钟` : '—'}
        </Descriptions.Item>
        <Descriptions.Item label="周报状态">{statusTag(data.state)}</Descriptions.Item>
      </Descriptions>
    );
  }
  if (section.key === 'tasks') {
    return (
      <Descriptions size="small" column={2}>
        <Descriptions.Item label="进行中任务">
          {typeof data.open === 'number' ? data.open : '—'}
        </Descriptions.Item>
        <Descriptions.Item label="即将到期">
          {typeof data.dueSoon === 'number' ? data.dueSoon : '—'}
        </Descriptions.Item>
      </Descriptions>
    );
  }
  if (section.key === 'files') {
    return (
      <Text>
        {typeof data.count === 'number' ? `已授权 ${data.count} 个文件` : '—'}
      </Text>
    );
  }
  if (section.key === 'activity') {
    const items = Array.isArray(data.items) ? data.items.map(asRecord) : [];
    if (items.length === 0) {
      return <Text type="secondary">暂无近期动态</Text>;
    }
    return (
      <Space direction="vertical" size={4}>
        {items.map((item, index) => (
          <Space key={`${String(item.title)}-${index}`}>
            <Tag {...(item.read === true ? {} : { color: 'blue' })}>
              {item.read === true ? '已读' : '未读'}
            </Tag>
            <Text>{typeof item.title === 'string' ? item.title : '—'}</Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {typeof item.resourceType === 'string' ? item.resourceType : ''}
              {' · '}
              {formatDateTime(typeof item.occurredAt === 'string' ? item.occurredAt : null)}
            </Text>
          </Space>
        ))}
      </Space>
    );
  }
  if (section.key === 'goals') {
    return (
      <GoalsSection
        apiClient={apiClient}
        self={self}
        data={data}
        onChanged={onChanged}
      />
    );
  }
  return <Text type="secondary">该分区暂不支持在页面上展示</Text>;
}

interface GoalFormValues {
  title?: string;
  period?: string;
  dueDate?: string;
  progress?: number;
  status?: string;
}

/** 目标分区：本人可新建、更新进度与状态；他人工作页只读。 */
function GoalsSection({ apiClient, self, data, onChanged }: {
  apiClient: ApiClient;
  self: boolean;
  data: Record<string, unknown>;
  onChanged: () => void;
}) {
  const items = Array.isArray(data.items) ? data.items.map(asRecord) : [];
  const teamItems = Array.isArray(data.teamGoals) ? data.teamGoals.map(asRecord) : [];
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Record<string, unknown> | null>(null);
  const [saving, setSaving] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [form] = Form.useForm<GoalFormValues>();

  const openCreate = () => {
    setEditing(null);
    setActionError(null);
    form.resetFields();
    setOpen(true);
  };

  const openEdit = (item: Record<string, unknown>) => {
    setEditing(item);
    setActionError(null);
    form.setFieldsValue({
      progress: typeof item.progress === 'number' ? item.progress : 0,
      status: 'active',
    });
    setOpen(true);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    setActionError(null);
    try {
      if (editing) {
        await apiClient.updateGoal(String(editing.id), {
          ...(values.progress === undefined ? {} : { progress: values.progress }),
          ...(values.status === undefined ? {} : { status: values.status }),
        }, Number(editing.version));
      } else {
        await apiClient.createGoal({
          ...(values.title === undefined ? {} : { title: values.title }),
          ...(values.period === undefined ? {} : { period: values.period }),
          dueDate: values.dueDate ? values.dueDate : null,
          ...(values.progress === undefined ? {} : { progress: values.progress }),
        });
      }
      setOpen(false);
      setEditing(null);
      onChanged();
    } catch (cause) {
      setActionError(describeError(cause));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {actionError ? (
        <Alert type="error" showIcon message="操作失败" description={actionError} />
      ) : null}
      <Text type="secondary">
        {typeof data.active === 'number' ? `进行中 ${data.active} 个` : '—'}
        {' · '}
        {typeof data.averageProgress === 'number' ? `平均进度 ${data.averageProgress}%` : '—'}
      </Text>
      {items.length === 0 ? <Text type="secondary">还没有在跟踪的目标</Text> : null}
      {items.map((item) => (
        <Space key={String(item.id)} direction="vertical" size={2} style={{ width: '100%' }}>
          <Space>
            <Text strong>{typeof item.title === 'string' ? item.title : '—'}</Text>
            {typeof item.period === 'string' && item.period ? <Tag>{item.period}</Tag> : null}
            {typeof item.dueDate === 'string' && item.dueDate ? (
              <Text type="secondary">截止 {item.dueDate}</Text>
            ) : null}
            {self ? (
              <Button size="small" type="link" onClick={() => openEdit(item)}>
                更新进度
              </Button>
            ) : null}
          </Space>
          <Progress percent={typeof item.progress === 'number' ? item.progress : 0} size="small" />
        </Space>
      ))}
      {self ? (
        <Button size="small" type="dashed" onClick={openCreate}>
          新建目标
        </Button>
      ) : null}

      {teamItems.length > 0 ? (
        <>
          <Divider style={{ margin: '8px 0' }} />
          <Text type="secondary">
            {'部门团队目标 '}
            {typeof data.teamActive === 'number' ? `· 进行中 ${data.teamActive} 个` : ''}
            {' · '}
            {typeof data.teamAverageProgress === 'number'
              ? `平均进度 ${data.teamAverageProgress}%`
              : '—'}
          </Text>
          {teamItems.map((item) => (
            <Space key={String(item.id)} direction="vertical" size={2} style={{ width: '100%' }}>
              <Space wrap>
                <Text strong>{typeof item.title === 'string' ? item.title : '—'}</Text>
                {typeof item.period === 'string' && item.period ? <Tag>{item.period}</Tag> : null}
                {typeof item.dueDate === 'string' && item.dueDate ? (
                  <Text type="secondary">截止 {item.dueDate}</Text>
                ) : null}
                {typeof item.keyResultCount === 'number' ? (
                  <Text type="secondary">{item.keyResultCount} 个关键结果</Text>
                ) : null}
              </Space>
              <Progress percent={typeof item.progress === 'number' ? item.progress : 0} size="small" />
            </Space>
          ))}
        </>
      ) : null}

      <Modal
        title={editing ? '更新目标进度' : '新建目标'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
      >
        <Form form={form} layout="vertical" initialValues={{ progress: 0, status: 'active' }}>
          {editing ? null : (
            <>
              <Form.Item
                name="title"
                label="目标"
                rules={[{ required: true, message: '请填写目标标题' }]}
              >
                <Input maxLength={120} placeholder="例如：本季度完成客户成功体系落地" />
              </Form.Item>
              <Form.Item name="period" label="周期">
                <Input maxLength={20} placeholder="例如：2026-Q4" />
              </Form.Item>
              <Form.Item
                name="dueDate"
                label="截止日期"
                rules={[{ pattern: /^\d{4}-\d{2}-\d{2}$/, message: '格式为 YYYY-MM-DD' }]}
              >
                <Input placeholder="2026-12-31" />
              </Form.Item>
            </>
          )}
          <Form.Item name="progress" label="进度（%）">
            <InputNumber min={0} max={100} style={{ width: '100%' }} />
          </Form.Item>
          {editing ? (
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 'active', label: '进行中' },
                  { value: 'achieved', label: '已达成' },
                  { value: 'archived', label: '已归档' },
                ]}
              />
            </Form.Item>
          ) : null}
        </Form>
      </Modal>
    </Space>
  );
}

const SECTION_TITLE: Record<string, string> = {
  time: '工时',
  report: '周报',
  tasks: '任务与行动项',
  files: '授权文件',
  activity: '近期动态',
  goals: '目标',
};

/** 个人工作页：分区独立加载，单区无权或失败不影响其他区。 */
export function WorkView({ apiClient, personId }: WorkViewProps) {
  const [page, setPage] = useState<WorkPage | null>(null);
  const [phase, setPhase] = useState<AsyncPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [accessLog, setAccessLog] = useState<WorkAccessLogRow[]>([]);

  const load = useCallback(() => {
    setPhase('loading');
    setForbidden(false);
    const request = personId
      ? apiClient.getPersonWorkPage(personId)
      : apiClient.getMyWorkPage();
    request
      .then((value) => {
        setPage(value);
        setPhase(value.sections.length === 0 ? 'empty' : 'ready');
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiProblemError && cause.status === 403) {
          setForbidden(true);
          setPhase('error');
          return;
        }
        setError(describeError(cause));
        setPhase('error');
      });
  }, [apiClient, personId]);

  useEffect(load, [load]);

  useEffect(() => {
    if (!page?.self) {
      setAccessLog([]);
      return;
    }
    let active = true;
    apiClient
      .listMyWorkAccessLog(50)
      .then((rows) => {
        if (active) {
          setAccessLog(rows);
        }
      })
      .catch(() => {
        if (active) {
          setAccessLog([]);
        }
      });
    return () => {
      active = false;
    };
  }, [apiClient, page?.self]);

  if (forbidden) {
    return <Alert type="warning" showIcon message="无权查看该员工的完整工作页" />;
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title={page ? `${page.displayName} 的工作页` : '个人工作页'}>
        <AsyncState
          phase={phase}
          {...(error ? { errorMessage: error } : {})}
          emptyText="暂无已提交的工作数据"
          onRetry={load}
        >
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            {page?.sections.map((section) => (
              <Card key={section.key} size="small" title={SECTION_TITLE[section.key] ?? section.key}>
                <SectionBody
                  section={section}
                  apiClient={apiClient}
                  self={page?.self === true}
                  onChanged={load}
                />
              </Card>
            ))}
          </Space>
        </AsyncState>
      </Card>

      {page?.self ? (
        <Card title="谁看过我的已提交工作" size="small">
          <Table<WorkAccessLogRow>
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={accessLog}
            locale={{ emptyText: '暂无他人访问记录' }}
            columns={[
              { title: '访问者', dataIndex: 'viewerPersonId' },
              { title: '资源', dataIndex: 'resourceType' },
              { title: '动作', dataIndex: 'action' },
              {
                title: '时间',
                dataIndex: 'occurredAt',
                render: (value?: string | null) => formatDateTime(value),
              },
            ]}
          />
        </Card>
      ) : null}
    </Space>
  );
}
