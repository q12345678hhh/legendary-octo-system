import {
  ApiProblemError,
  type ApiClient,
  type DepartmentMember,
  type DepartmentWorkspace,
} from '@platform/api-client';
import { Alert, Card, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { describeError } from '../utils/format';

const { Text } = Typography;

export interface DepartmentWorkspaceViewProps {
  apiClient: ApiClient;
  orgUnitId: string;
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

/** 无权字段由后端整体省略，这里用「—」占位，不补 0，避免把缺失误读成 0 小时。 */
function optionalCell(value: string | number | null | undefined) {
  return value === undefined || value === null ? <Text type="secondary">—</Text> : value;
}

/** 部门第一屏：固定表格展示成员、职位、汇报层级与逐格授权状态。 */
export function DepartmentWorkspaceView({ apiClient, orgUnitId }: DepartmentWorkspaceViewProps) {
  const [workspace, setWorkspace] = useState<DepartmentWorkspace | null>(null);
  const [phase, setPhase] = useState<AsyncPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

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

  useEffect(load, [load]);

  if (forbidden) {
    return <Alert type="warning" showIcon message="无权访问该部门工作区" />;
  }

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
    </Space>
  );
}
