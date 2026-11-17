import {
  ApiProblemError,
  type ApiClient,
  type InsightDepartmentOption,
  type InsightDrilldown,
  type InsightOverview,
  type InsightSetting,
  type InsightWeekRow,
} from '@platform/api-client';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  InputNumber,
  message,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { describeError } from '../utils/format';

const { Text } = Typography;

const TASK_STATUS_LABEL: Record<string, string> = {
  todo: '待办',
  doing: '进行中',
  done: '已完成',
};

const REPORT_STATE_LABEL: Record<string, string> = {
  draft: '草稿',
  submitted: '已提交',
  approved: '已通过',
  returned: '已退回',
};

export interface InsightsViewProps {
  apiClient: ApiClient;
}

function isoDate(date: Date): string {
  const offset = date.getTimezoneOffset();
  return new Date(date.getTime() - offset * 60_000).toISOString().slice(0, 10);
}

function daysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return isoDate(date);
}

function minutesLabel(minutes: number | null | undefined): string {
  if (minutes === undefined || minutes === null) {
    return '—';
  }
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours === 0 ? `${rest} 分钟` : `${hours} 小时 ${rest} 分`;
}

/**
 * 管理洞察：固定维度统计 + 阈值抑制 + 二次鉴权下钻。
 *
 * <p>被抑制的口径后端不返回值，页面只显示「低于最小群组」提示，
 * 不补 0、不显示排名，避免把缺失误读成真实业绩。
 */
export function InsightsView({ apiClient }: InsightsViewProps) {
  const [departments, setDepartments] = useState<InsightDepartmentOption[]>([]);
  const [orgUnitId, setOrgUnitId] = useState<string | null>(null);
  const [from, setFrom] = useState(() => daysAgo(29));
  const [to, setTo] = useState(() => daysAgo(0));
  const [overview, setOverview] = useState<InsightOverview | null>(null);
  const [phase, setPhase] = useState<AsyncPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [drilldown, setDrilldown] = useState<InsightDrilldown | null>(null);
  const [drilldownLoading, setDrilldownLoading] = useState(false);
  const [drilldownError, setDrilldownError] = useState<string | null>(null);

  const [setting, setSetting] = useState<InsightSetting | null>(null);
  const [thresholdDraft, setThresholdDraft] = useState<number | null>(null);
  const [canManage, setCanManage] = useState(false);
  const [savingThreshold, setSavingThreshold] = useState(false);

  useEffect(() => {
    let active = true;
    apiClient
      .listInsightDepartments()
      .then((rows) => {
        if (!active) {
          return;
        }
        setDepartments(rows);
        setOrgUnitId((current) => current ?? rows[0]?.orgUnitId ?? null);
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiProblemError && cause.status === 403) {
          setForbidden(true);
        }
      });
    apiClient
      .getInsightSettings()
      .then((value) => {
        if (active) {
          setSetting(value);
          setThresholdDraft(value.minGroupSize);
        }
      })
      .catch(() => undefined);
    apiClient
      .getCapabilities()
      .then((capabilities) => {
        if (active) {
          setCanManage(capabilities.permissions.includes('tenant.admin'));
        }
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [apiClient]);

  const loadOverview = useCallback(() => {
    if (!from || !to) {
      return;
    }
    setPhase('loading');
    setDrilldown(null);
    setDrilldownError(null);
    const query = orgUnitId ? { orgUnitId, from, to } : { from, to };
    apiClient
      .getWorkInsight(query)
      .then((value) => {
        setOverview(value);
        setPhase('ready');
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiProblemError && cause.status === 403) {
          setForbidden(true);
          return;
        }
        setError(describeError(cause));
        setPhase('error');
      });
  }, [apiClient, from, orgUnitId, to]);

  useEffect(loadOverview, [loadOverview]);

  const loadDrilldown = useCallback(() => {
    if (!from || !to) {
      return;
    }
    setDrilldownLoading(true);
    setDrilldownError(null);
    const query = orgUnitId ? { orgUnitId, from, to } : { from, to };
    apiClient
      .getWorkInsightPeople(query)
      .then(setDrilldown)
      .catch((cause: unknown) => setDrilldownError(describeError(cause)))
      .finally(() => setDrilldownLoading(false));
  }, [apiClient, from, orgUnitId, to]);

  const saveThreshold = useCallback(() => {
    if (thresholdDraft === null) {
      return;
    }
    setSavingThreshold(true);
    apiClient
      .updateInsightSettings(thresholdDraft)
      .then((value) => {
        setSetting(value);
        setThresholdDraft(value.minGroupSize);
        message.success(`最小群组阈值已调整为 ${value.minGroupSize}`);
        loadOverview();
      })
      .catch((cause: unknown) => message.error(describeError(cause)))
      .finally(() => setSavingThreshold(false));
  }, [apiClient, loadOverview, thresholdDraft]);

  const statusRows = useMemo(
    () => overview?.taskStatus ?? [],
    [overview],
  );

  if (forbidden) {
    return <Alert type="warning" showIcon message="无权查看工作洞察" />;
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="统计范围">
        <Space wrap size="middle">
          {departments.length > 0 ? (
            <Select
              aria-label="部门"
              style={{ minWidth: 220 }}
              value={orgUnitId ?? null}
              onChange={(value: string) => setOrgUnitId(value)}
              options={departments.map((item) => ({
                value: item.orgUnitId,
                label: item.name,
              }))}
              placeholder="选择部门"
            />
          ) : (
            <Tag color="blue">我的管理范围</Tag>
          )}
          <Input
            type="date"
            aria-label="开始日期"
            value={from}
            max={to}
            onChange={(event) => setFrom(event.target.value)}
            style={{ width: 170 }}
          />
          <Input
            type="date"
            aria-label="结束日期"
            value={to}
            min={from}
            onChange={(event) => setTo(event.target.value)}
            style={{ width: 170 }}
          />
          <Button type="primary" onClick={loadOverview}>
            刷新统计
          </Button>
          <Button loading={drilldownLoading} onClick={loadDrilldown}>
            查看人员明细
          </Button>
        </Space>
        {overview ? (
          <Text type="secondary" style={{ display: 'block', marginTop: 12 }}>
            阈值：群组少于 {overview.threshold} 人时整格抑制；草稿与退回中的工时不计入。
          </Text>
        ) : null}
      </Card>

      <Card title="概览">
        <AsyncState
          phase={phase}
          {...(error ? { errorMessage: error } : {})}
          onRetry={loadOverview}
        >
          {overview?.suppressed ? (
            <Alert
              type="warning"
              showIcon
              message="统计已抑制"
              description={`当前口径的贡献人数低于最小群组 ${overview.threshold} 人，按隐私规则不展示任何数值与排名。`}
            />
          ) : overview ? (
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Space size="large" wrap>
                <Statistic
                  title="提交人日"
                  value={overview.submittedPersonDays ?? 0}
                  suffix={`/ ${overview.expectedPersonDays ?? 0}`}
                />
                <Statistic
                  title="提交率"
                  value={Math.round((overview.submissionRate ?? 0) * 1000) / 10}
                  suffix="%"
                />
                <Statistic
                  title="已提交工时"
                  value={minutesLabel(overview.totalSubmittedMinutes)}
                />
                <Statistic
                  title="人均工时"
                  value={minutesLabel(overview.avgMinutesPerPerson)}
                />
                <Statistic title="成员数" value={overview.memberCount ?? 0} />
                <Statistic title="逾期任务" value={overview.overdueTasks ?? 0} />
              </Space>

              <Descriptions size="small" column={{ xs: 1, sm: 2, md: 4 }} bordered>
                {statusRows.map((row) => (
                  <Descriptions.Item
                    key={row.status}
                    label={`任务 · ${TASK_STATUS_LABEL[row.status] ?? row.status}`}
                  >
                    {row.count}
                  </Descriptions.Item>
                ))}
                {Object.entries(overview.weeklyReports ?? {}).map(([state, count]) => (
                  <Descriptions.Item key={state} label={`周报 · ${REPORT_STATE_LABEL[state] ?? state}`}>
                    {count}
                  </Descriptions.Item>
                ))}
              </Descriptions>
            </Space>
          ) : null}
        </AsyncState>
      </Card>

      <Card title="按周趋势">
        <Table<InsightWeekRow>
          rowKey="weekStart"
          size="small"
          pagination={false}
          dataSource={overview?.weeks ?? []}
          locale={{ emptyText: overview?.suppressed ? '统计已抑制' : '暂无按周数据' }}
          columns={[
            { title: '周起始（周一）', dataIndex: 'weekStart' },
            {
              title: '提交人日',
              dataIndex: 'submittedPersonDays',
              render: (value: number | null | undefined, row) =>
                row.suppressed ? <Tag>低于阈值，已抑制</Tag> : value,
            },
          ]}
        />
      </Card>

      <Card
        title="人员明细"
        extra={
          <Text type="secondary">
            每行都按 worklog.time.read 二次鉴权；可见人数低于阈值时整表抑制。
          </Text>
        }
      >
        {drilldownError ? <Alert type="error" showIcon message={drilldownError} /> : null}
        {!drilldownError && drilldown && drilldown.rows.length === 0 ? (
          <Alert
            type="warning"
            showIcon
            message="明细已抑制"
            description={`二次鉴权后可见人数低于最小群组 ${drilldown.threshold} 人，按隐私规则不展示个人明细。`}
          />
        ) : null}
        {!drilldownError && drilldown && drilldown.rows.length > 0 ? (
          <Table
            rowKey="personId"
            size="small"
            pagination={false}
            dataSource={drilldown.rows}
            columns={[
              { title: '姓名', dataIndex: 'displayName' },
              { title: '提交天数', dataIndex: 'submittedDays' },
              {
                title: '已提交工时',
                dataIndex: 'totalMinutes',
                render: (value: number) => minutesLabel(value),
              },
            ]}
          />
        ) : null}
        {!drilldownError && !drilldown ? (
          <Text type="secondary">点击「查看人员明细」加载。</Text>
        ) : null}
      </Card>

      <Card title="隐私阈值">
        <Space wrap size="middle">
          <Text>
            当前最小群组：<Text strong>{setting?.minGroupSize ?? overview?.threshold ?? '—'}</Text> 人
          </Text>
          {canManage ? (
            <>
              <InputNumber
                aria-label="最小群组阈值"
                min={setting?.minGroupSize ?? 5}
                max={100}
                value={thresholdDraft ?? null}
                onChange={(value) => setThresholdDraft(typeof value === 'number' ? value : null)}
              />
              <Button type="primary" loading={savingThreshold} onClick={saveThreshold}>
                保存
              </Button>
              <Text type="secondary">只能提高，最低 5 人。</Text>
            </>
          ) : (
            <Text type="secondary">仅租户管理员可以调整。</Text>
          )}
        </Space>
      </Card>
    </Space>
  );
}
