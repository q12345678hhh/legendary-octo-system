import {
  ApiProblemError,
  type ApiClient,
  type DepartmentMember,
  type DepartmentWorkspace,
  type Goal,
  type KeyResult,
} from '@platform/api-client';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Progress,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { describeError } from '../utils/format';

const { Text } = Typography;

export interface DepartmentWorkspaceViewProps {
  apiClient: ApiClient;
  orgUnitId: string;
  permissions?: Set<string> | null;
}

const DAY_STATE_LABEL: Record<string, string> = {
  draft: '草稿',
  submitted: '已提交',
  approved: '已通过',
  returned: '已退回',
};

const REPORT_STATE_LABEL: Record<string, string> = {
  draft: '草稿',
  submitted: '已提交',
};

const GOAL_STATUS_LABEL: Record<string, string> = {
  active: '进行中',
  achieved: '已达成',
  archived: '已归档',
};

/** 无权字段由后端整体省略，这里用「—」占位，不补 0，避免把缺失误读成 0 小时。 */
function optionalCell(value: string | number | null | undefined) {
  return value === undefined || value === null ? <Text type="secondary">—</Text> : value;
}

/** 关键结果完成率：目标值由后端保证大于 0，前端仍做一次兜底避免除零。 */
function keyResultPercent(keyResult: KeyResult): number {
  if (!keyResult.targetValue) {
    return 0;
  }
  return Math.min(
    100,
    Math.round((keyResult.currentValue / keyResult.targetValue) * 100),
  );
}

interface TeamGoalFormValues {
  title?: string;
  period?: string;
  dueDate?: string;
  status?: string;
}

interface KeyResultFormValues {
  title?: string;
  targetValue?: number;
  currentValue?: number;
  unit?: string;
}

/** 部门第一屏：成员、职位、汇报层级、逐格授权状态，以及团队目标与关键结果。 */
export function DepartmentWorkspaceView({
  apiClient,
  orgUnitId,
  permissions = null,
}: DepartmentWorkspaceViewProps) {
  const canReadTeamGoals = permissions?.has('work.goal.team.read') ?? false;
  const canManageTeamGoals = permissions?.has('work.goal.team.manage') ?? false;

  const [workspace, setWorkspace] = useState<DepartmentWorkspace | null>(null);
  const [phase, setPhase] = useState<AsyncPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [teamGoals, setTeamGoals] = useState<Goal[]>([]);
  const [goalPhase, setGoalPhase] = useState<AsyncPhase>('loading');
  const [goalError, setGoalError] = useState<string | null>(null);
  const [goalActionError, setGoalActionError] = useState<string | null>(null);
  const [goalSaving, setGoalSaving] = useState(false);
  const [goalModalOpen, setGoalModalOpen] = useState(false);
  const [goalEditing, setGoalEditing] = useState<Goal | null>(null);
  const [keyResultModalOpen, setKeyResultModalOpen] = useState(false);
  const [keyResultGoal, setKeyResultGoal] = useState<Goal | null>(null);
  const [keyResultEditing, setKeyResultEditing] = useState<
    { goal: Goal; keyResult: KeyResult } | null
  >(null);
  const [goalForm] = Form.useForm<TeamGoalFormValues>();
  const [keyResultForm] = Form.useForm<KeyResultFormValues>();

  const load = useCallback(() => {
    setPhase('loading');
    setForbidden(false);
    apiClient
      .getDepartmentWorkspace(orgUnitId)
      .then((value) => {
        setWorkspace(value);
        setPhase(value.members.length === 0 ? 'empty' : 'ready');
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
  }, [apiClient, orgUnitId]);

  const loadTeamGoals = useCallback(() => {
    if (!canReadTeamGoals) {
      return;
    }
    setGoalPhase('loading');
    setGoalActionError(null);
    apiClient
      .listTeamGoals(orgUnitId)
      .then((value) => {
        setTeamGoals(value);
        setGoalPhase(value.length === 0 ? 'empty' : 'ready');
      })
      .catch((cause: unknown) => {
        setGoalError(describeError(cause));
        setGoalPhase('error');
      });
  }, [apiClient, orgUnitId, canReadTeamGoals]);

  useEffect(load, [load]);
  useEffect(loadTeamGoals, [loadTeamGoals]);

  if (forbidden) {
    return <Alert type="warning" showIcon message="无权访问该部门工作区" />;
  }

  const openCreateGoal = () => {
    setGoalEditing(null);
    goalForm.resetFields();
    setGoalActionError(null);
    setGoalModalOpen(true);
  };

  const openEditGoal = (goal: Goal) => {
    setGoalEditing(goal);
    goalForm.setFieldsValue({
      title: goal.title,
      period: goal.period,
      ...(goal.dueDate ? { dueDate: goal.dueDate } : {}),
      status: goal.status,
    });
    setGoalActionError(null);
    setGoalModalOpen(true);
  };

  const openAddKeyResult = (goal: Goal) => {
    setKeyResultGoal(goal);
    setKeyResultEditing(null);
    keyResultForm.resetFields();
    setGoalActionError(null);
    setKeyResultModalOpen(true);
  };

  const openEditKeyResult = (goal: Goal, keyResult: KeyResult) => {
    setKeyResultGoal(goal);
    setKeyResultEditing({ goal, keyResult });
    keyResultForm.setFieldsValue({ currentValue: keyResult.currentValue });
    setGoalActionError(null);
    setKeyResultModalOpen(true);
  };

  const submitTeamGoal = async () => {
    const values = await goalForm.validateFields();
    setGoalSaving(true);
    setGoalActionError(null);
    try {
      if (goalEditing) {
        await apiClient.updateTeamGoal(
          goalEditing.id,
          orgUnitId,
          {
            ...(values.title === undefined ? {} : { title: values.title }),
            ...(values.period ? { period: values.period } : {}),
            dueDate: values.dueDate ? values.dueDate : null,
            ...(values.status ? { status: values.status } : {}),
          },
          goalEditing.version,
        );
      } else {
        await apiClient.createTeamGoal(orgUnitId, {
          ...(values.title === undefined ? {} : { title: values.title }),
          ...(values.period ? { period: values.period } : {}),
          dueDate: values.dueDate ? values.dueDate : null,
        });
      }
      setGoalModalOpen(false);
      setGoalEditing(null);
      goalForm.resetFields();
      loadTeamGoals();
    } catch (cause) {
      setGoalActionError(describeError(cause));
    } finally {
      setGoalSaving(false);
    }
  };

  const moveKeyResult = async (goal: Goal, keyResult: KeyResult, direction: 'up' | 'down') => {
    setGoalActionError(null);
    try {
      await apiClient.moveKeyResult(goal.id, keyResult.id, orgUnitId, direction);
      loadTeamGoals();
    } catch (cause) {
      setGoalActionError(describeError(cause));
    }
  };

  const removeKeyResult = async (goal: Goal, keyResult: KeyResult) => {
    setGoalActionError(null);
    try {
      await apiClient.deleteKeyResult(goal.id, keyResult.id, orgUnitId);
      loadTeamGoals();
    } catch (cause) {
      setGoalActionError(describeError(cause));
    }
  };

  const submitKeyResult = async () => {
    const values = await keyResultForm.validateFields();
    setGoalSaving(true);
    setGoalActionError(null);
    try {
      if (keyResultEditing) {
        await apiClient.updateKeyResult(
          keyResultEditing.goal.id,
          keyResultEditing.keyResult.id,
          orgUnitId,
          Number(values.currentValue),
          keyResultEditing.keyResult.version,
        );
      } else if (keyResultGoal) {
        await apiClient.addKeyResult(keyResultGoal.id, orgUnitId, {
          title: values.title ?? '',
          targetValue: Number(values.targetValue),
          ...(values.currentValue === undefined ? {} : { currentValue: values.currentValue }),
          ...(values.unit ? { unit: values.unit } : {}),
        });
      }
      setKeyResultModalOpen(false);
      setKeyResultGoal(null);
      setKeyResultEditing(null);
      keyResultForm.resetFields();
      loadTeamGoals();
    } catch (cause) {
      setGoalActionError(describeError(cause));
    } finally {
      setGoalSaving(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title={workspace ? workspace.name : '部门工作区'}
        extra={
          workspace ? (
            <Text type="secondary">
              下班汇总 {workspace.dayEndTime} · 周报截止星期 {workspace.weekReportDueWeekday}
            </Text>
          ) : null
        }
      >
        <AsyncState
          phase={phase}
          {...(error ? { errorMessage: error } : {})}
          emptyText="该部门暂无在职成员"
          onRetry={load}
        >
          <Table<DepartmentMember>
            rowKey="personId"
            pagination={false}
            dataSource={workspace?.members ?? []}
            columns={[
              {
                title: '姓名',
                dataIndex: 'displayName',
                render: (name: string, row) =>
                  row.workPageAccessible && row.workPagePath ? (
                    <a href={row.workPagePath}>{name}</a>
                  ) : (
                    <span>{name}</span>
                  ),
              },
              { title: '职位', dataIndex: 'positionName', render: optionalCell },
              {
                title: '主/兼岗',
                dataIndex: 'assignmentType',
                render: (value?: string) =>
                  value === 'secondary' ? <Tag color="blue">兼岗</Tag> : <Tag>主岗</Tag>,
              },
              { title: '汇报对象', dataIndex: 'managerName', render: optionalCell },
              { title: '汇报层级', dataIndex: 'depth', render: (value: number) => value },
              {
                title: '今日工时',
                dataIndex: 'todayMinutes',
                render: (minutes: number | null | undefined, row) =>
                  minutes === undefined || minutes === null ? (
                    <Text type="secondary">—</Text>
                  ) : (
                    <Space size={4}>
                      <span>{minutes} 分钟</span>
                      {row.dayState ? (
                        <Tag>{DAY_STATE_LABEL[row.dayState] ?? row.dayState}</Tag>
                      ) : null}
                    </Space>
                  ),
              },
              {
                title: '本周周报',
                dataIndex: 'weekReportState',
                render: (state: string | null | undefined) =>
                  state === undefined || state === null ? (
                    <Text type="secondary">—</Text>
                  ) : (
                    <Tag>{REPORT_STATE_LABEL[state] ?? state}</Tag>
                  ),
              },
              {
                title: '授权文件',
                dataIndex: 'authorizedFileCount',
                render: optionalCell,
              },
            ]}
          />
        </AsyncState>
      </Card>
      {workspace?.status === 'archived' ? (
        <Alert type="info" showIcon message="该部门已归档，空间只读" />
      ) : null}

      {canReadTeamGoals ? (
        <Card
          title="团队目标"
          extra={
            canManageTeamGoals ? (
              <Button size="small" onClick={openCreateGoal}>
                新建团队目标
              </Button>
            ) : null
          }
        >
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            {goalActionError ? (
              <Alert type="error" showIcon message="操作失败" description={goalActionError} />
            ) : null}
            <AsyncState
              phase={goalPhase}
              {...(goalError ? { errorMessage: goalError } : {})}
              emptyText="该部门还没有团队目标"
              onRetry={loadTeamGoals}
            >
              {teamGoals.map((goal) => (
                <Space
                  key={goal.id}
                  direction="vertical"
                  size={4}
                  style={{ width: '100%' }}
                >
                  <Space wrap>
                    <Text strong>{goal.title}</Text>
                    {goal.period ? <Tag>{goal.period}</Tag> : null}
                    {goal.dueDate ? (
                      <Text type="secondary">截止 {goal.dueDate}</Text>
                    ) : null}
                    <Tag color={goal.status === 'achieved' ? 'green' : 'blue'}>
                      {GOAL_STATUS_LABEL[goal.status] ?? goal.status}
                    </Tag>
                    {canManageTeamGoals ? (
                      <>
                        <Button size="small" type="link" onClick={() => openEditGoal(goal)}>
                          编辑
                        </Button>
                        <Button size="small" type="link" onClick={() => openAddKeyResult(goal)}>
                          添加关键结果
                        </Button>
                      </>
                    ) : null}
                  </Space>
                  <Progress percent={goal.progress} size="small" />
                  {goal.keyResults && goal.keyResults.length > 0 ? (
                    goal.keyResults.map((keyResult, index) => (
                      <Space key={keyResult.id} wrap size={8}>
                        <Text>{keyResult.title}</Text>
                        <Text type="secondary">
                          {keyResult.currentValue}/{keyResult.targetValue}
                          {keyResult.unit ? ` ${keyResult.unit}` : ''}
                        </Text>
                        <Progress
                          percent={keyResultPercent(keyResult)}
                          size="small"
                          style={{ width: 140 }}
                        />
                        {canManageTeamGoals ? (
                          <>
                            <Button
                              size="small"
                              type="link"
                              onClick={() => openEditKeyResult(goal, keyResult)}
                            >
                              更新当前值
                            </Button>
                            <Button
                              size="small"
                              type="link"
                              disabled={index === 0}
                              onClick={() => void moveKeyResult(goal, keyResult, 'up')}
                            >
                              上移
                            </Button>
                            <Button
                              size="small"
                              type="link"
                              disabled={index === (goal.keyResults?.length ?? 0) - 1}
                              onClick={() => void moveKeyResult(goal, keyResult, 'down')}
                            >
                              下移
                            </Button>
                            <Popconfirm
                              title="删除这个关键结果？"
                              okText="删除"
                              cancelText="取消"
                              onConfirm={() => void removeKeyResult(goal, keyResult)}
                            >
                              <Button size="small" type="link" danger>
                                删除
                              </Button>
                            </Popconfirm>
                          </>
                        ) : null}
                      </Space>
                    ))
                  ) : (
                    <Text type="secondary">还没有关键结果</Text>
                  )}
                </Space>
              ))}
            </AsyncState>
          </Space>
        </Card>
      ) : null}

      <Modal
        title={goalEditing ? '编辑团队目标' : '新建团队目标'}
        open={goalModalOpen}
        onCancel={() => {
          setGoalModalOpen(false);
          setGoalEditing(null);
        }}
        onOk={() => void submitTeamGoal()}
        confirmLoading={goalSaving}
        okText="保存"
        cancelText="取消"
      >
        <Form form={goalForm} layout="vertical">
          <Form.Item
            name="title"
            label="目标"
            rules={[{ required: true, message: '请填写目标标题' }]}
          >
            <Input maxLength={120} placeholder="例如：本季度把华东区续约率提升到 90%" />
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
          {goalEditing ? (
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

      <Modal
        title={keyResultEditing ? '更新关键结果当前值' : '添加关键结果'}
        open={keyResultModalOpen}
        onCancel={() => setKeyResultModalOpen(false)}
        onOk={() => void submitKeyResult()}
        confirmLoading={goalSaving}
        okText="保存"
        cancelText="取消"
      >
        <Form form={keyResultForm} layout="vertical">
          {keyResultEditing ? null : (
            <>
              <Form.Item
                name="title"
                label="关键结果"
                rules={[{ required: true, message: '请填写关键结果标题' }]}
              >
                <Input maxLength={120} placeholder="例如：签约客户数" />
              </Form.Item>
              <Form.Item
                name="targetValue"
                label="目标值"
                rules={[{ required: true, message: '请填写目标值' }]}
              >
                <InputNumber min={0.01} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="unit" label="单位">
                <Input maxLength={20} placeholder="例如：家、万元、%" />
              </Form.Item>
            </>
          )}
          <Form.Item
            name="currentValue"
            label="当前值"
            rules={
              keyResultEditing
                ? [{ required: true, message: '请填写当前值' }]
                : []
            }
          >
            <InputNumber min={0} step={1} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
