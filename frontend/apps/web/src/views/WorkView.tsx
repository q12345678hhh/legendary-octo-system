import {
  ApiProblemError,
  type ApiClient,
  type WorkAccessLogRow,
  type WorkPage,
  type WorkPageSection,
} from '@platform/api-client';
import { Alert, Card, Descriptions, Space, Table, Tag, Typography } from 'antd';
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

function SectionBody({ section }: { section: WorkPageSection }) {
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
  return <Text type="secondary">该分区暂不支持在页面上展示</Text>;
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
                <SectionBody section={section} />
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
