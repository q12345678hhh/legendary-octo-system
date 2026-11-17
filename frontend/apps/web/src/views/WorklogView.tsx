import type {
  ApiClient,
  WeeklyReport,
  WorklogDay,
  WorklogEntryInput,
  WorklogSubmission,
} from '@platform/api-client';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { describeError, formatDateTime } from '../utils/format';

const { Text, Paragraph } = Typography;

export interface WorklogViewProps {
  apiClient: ApiClient;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function mondayOf(date: string): string {
  const value = new Date(`${date}T00:00:00Z`);
  const day = value.getUTCDay();
  const delta = day === 0 ? -6 : 1 - day;
  value.setUTCDate(value.getUTCDate() + delta);
  return value.toISOString().slice(0, 10);
}

const STATE_LABEL: Record<string, string> = {
  draft: '草稿',
  submitted: '已提交',
  approved: '已通过',
  returned: '已退回',
};

interface EntryFormValues {
  workDate: string;
  minutes: number;
  description: string;
}

/** 手工工时与周报：本人录入/提交，主管在「待我审核」处理。 */
export function WorklogView({ apiClient }: WorklogViewProps) {
  const [date, setDate] = useState(today());
  const [day, setDay] = useState<WorklogDay | null>(null);
  const [phase, setPhase] = useState<AsyncPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<WorklogSubmission[]>([]);
  const [pendingReports, setPendingReports] = useState<WeeklyReport[]>([]);
  const [weekStart, setWeekStart] = useState(mondayOf(today()));
  const [report, setReport] = useState<WeeklyReport | null>(null);
  const [issues, setIssues] = useState('');
  const [nextWeekPlan, setNextWeekPlan] = useState('');
  const [reviewTarget, setReviewTarget] = useState<
    { kind: 'time'; row: WorklogSubmission } | { kind: 'report'; row: WeeklyReport } | null
  >(null);
  const [reviewAction, setReviewAction] = useState<'approve' | 'return'>('approve');
  const [reviewComment, setReviewComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<EntryFormValues>();

  const loadDay = useCallback(() => {
    setPhase('loading');
    apiClient
      .getWorklogDay(date)
      .then((value) => {
        setDay(value);
        setPhase(value.entries.length === 0 ? 'empty' : 'ready');
      })
      .catch((cause: unknown) => {
        setError(describeError(cause));
        setPhase('error');
      });
  }, [apiClient, date]);

  const loadPending = useCallback(() => {
    apiClient.listPendingWorklogReviews().then(setPending).catch(() => setPending([]));
  }, [apiClient]);

  const loadReport = useCallback(() => {
    apiClient
      .getWeeklyReport(weekStart)
      .then((value) => {
        setReport(value.report);
        const content = value.report?.content ?? {};
        setIssues(typeof content.issues === 'string' ? content.issues : '');
        setNextWeekPlan(typeof content.nextWeekPlan === 'string' ? content.nextWeekPlan : '');
      })
      .catch(() => setReport(null));
  }, [apiClient, weekStart]);

  useEffect(loadDay, [loadDay]);
  useEffect(loadPending, [loadPending]);
  useEffect(loadReport, [loadReport]);

  const totalMinutes = useMemo(
    () => day?.entries.reduce((sum, entry) => sum + entry.minutes, 0) ?? 0,
    [day],
  );

  const createEntry = async (values: EntryFormValues) => {
    const input: WorklogEntryInput = {
      workDate: values.workDate,
      durationMinutes: values.minutes,
      description: values.description ?? '',
      clientEntryId: `web-${crypto.randomUUID()}`,
    };
    try {
      await apiClient.createWorklogEntry(input);
      form.resetFields(['minutes', 'description']);
      message.success('已添加');
      loadDay();
    } catch (cause) {
      message.error(describeError(cause));
    }
  };

  const submitDay = async () => {
    setSubmitting(true);
    try {
      await apiClient.submitWorklogDay(date);
      message.success('已提交，等待主管审核');
      loadDay();
    } catch (cause) {
      message.error(describeError(cause));
    } finally {
      setSubmitting(false);
    }
  };

  const generateReport = async () => {
    try {
      const value = await apiClient.generateWeeklyReport(weekStart);
      setReport(value);
      message.success('已按已确认工时生成草稿');
      loadReport();
    } catch (cause) {
      message.error(describeError(cause));
    }
  };

  const saveNotes = async () => {
    try {
      await apiClient.updateWeeklyReportNotes(weekStart, { issues, nextWeekPlan });
      message.success('已保存');
      loadReport();
    } catch (cause) {
      message.error(describeError(cause));
    }
  };

  const submitReport = async () => {
    try {
      await apiClient.submitWeeklyReport(weekStart);
      message.success('周报已提交');
      loadReport();
    } catch (cause) {
      message.error(describeError(cause));
    }
  };

  const doReview = async () => {
    if (!reviewTarget) {
      return;
    }
    try {
      if (reviewTarget.kind === 'time') {
        await apiClient.reviewWorklogSubmission(
          reviewTarget.row.id, reviewAction, reviewComment,
        );
        loadPending();
      } else {
        await apiClient.reviewWeeklyReport(reviewTarget.row.id, reviewAction, reviewComment);
        apiClient.listPendingReportReviews().then(setPendingReports).catch(() => undefined);
      }
      setReviewTarget(null);
      setReviewComment('');
      message.success(reviewAction === 'approve' ? '已通过' : '已退回');
    } catch (cause) {
      message.error(describeError(cause));
    }
  };

  useEffect(() => {
    apiClient.listPendingReportReviews().then(setPendingReports).catch(() => setPendingReports([]));
  }, [apiClient]);

  const reportContent = (report?.content ?? {}) as Record<string, unknown>;
  const reportDays = Array.isArray(reportContent.days) ? reportContent.days : [];

  return (
    <Card title="工时与周报">
      <Tabs
        items={[
          {
            key: 'mine',
            label: '我的工时',
            children: (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Space>
                  <Input
                    type="date"
                    value={date}
                    max={today()}
                    onChange={(event) => setDate(event.target.value)}
                    style={{ width: 180 }}
                  />
                  <Text type="secondary">当日合计 {totalMinutes} 分钟</Text>
                  {day?.state ? <Tag>{STATE_LABEL[day.state] ?? day.state}</Tag> : null}
                  <Button
                    type="primary"
                    onClick={submitDay}
                    loading={submitting}
                    disabled={!day || day.entries.length === 0
                      || day.state === 'submitted' || day.state === 'approved'}
                  >
                    提交当日
                  </Button>
                </Space>
                <Form<EntryFormValues>
                  form={form}
                  layout="inline"
                  initialValues={{ workDate: date, minutes: 60 }}
                  onFinish={createEntry}
                >
                  <Form.Item name="workDate" label="日期" rules={[{ required: true }]}>
                    <Input type="date" style={{ width: 160 }} />
                  </Form.Item>
                  <Form.Item name="minutes" label="分钟" rules={[{ required: true }]}>
                    <InputNumber min={1} max={1440} />
                  </Form.Item>
                  <Form.Item name="description" label="做了什么">
                    <Input placeholder="例如：完成部门工作区首屏接口" style={{ width: 320 }} />
                  </Form.Item>
                  <Button htmlType="submit">添加</Button>
                </Form>
                <AsyncState
                  phase={phase}
                  {...(error ? { errorMessage: error } : {})}
                  emptyText="当天还没有工时"
                  onRetry={loadDay}
                >
                  <Table
                    rowKey="id"
                    size="small"
                    pagination={false}
                    dataSource={day?.entries ?? []}
                    columns={[
                      {
                        title: '开始',
                        dataIndex: 'startsAt',
                        render: (value?: string | null) => formatDateTime(value),
                      },
                      { title: '分钟', dataIndex: 'minutes' },
                      { title: '说明', dataIndex: 'description' },
                      {
                        title: '操作',
                        render: (_value, row) => (
                          <Button
                            size="small"
                            danger
                            disabled={day?.state === 'submitted' || day?.state === 'approved'}
                            onClick={() => {
                              apiClient
                                .deleteWorklogEntry(row.id)
                                .then(loadDay)
                                .catch((cause) => message.error(describeError(cause)));
                            }}
                          >
                            删除
                          </Button>
                        ),
                      },
                    ]}
                  />
                </AsyncState>
                {day?.submission ? (
                  <Alert
                    type="info"
                    showIcon
                    message={`已提交版本 v${day.submission.version}，合计 ${day.submission.totalMinutes} 分钟`}
                  />
                ) : null}
              </Space>
            ),
          },
          {
            key: 'reviews',
            label: `待我审核（${pending.length}）`,
            children: (
              <Table
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={pending}
                locale={{ emptyText: '没有待审核工时' }}
                columns={[
                  { title: '员工', dataIndex: 'personId' },
                  { title: '日期', dataIndex: 'workDate' },
                  { title: '版本', dataIndex: 'version' },
                  { title: '分钟', dataIndex: 'totalMinutes' },
                  {
                    title: '操作',
                    render: (_value, row) => (
                      <Space>
                        <Button
                          size="small"
                          type="primary"
                          onClick={() => {
                            setReviewAction('approve');
                            setReviewComment('');
                            setReviewTarget({ kind: 'time', row });
                          }}
                        >
                          通过
                        </Button>
                        <Button
                          size="small"
                          danger
                          onClick={() => {
                            setReviewAction('return');
                            setReviewComment('');
                            setReviewTarget({ kind: 'time', row });
                          }}
                        >
                          退回
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
            ),
          },
          {
            key: 'report',
            label: '我的周报',
            children: (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Space>
                  <Input
                    type="date"
                    value={weekStart}
                    onChange={(event) => setWeekStart(mondayOf(event.target.value))}
                    style={{ width: 180 }}
                  />
                  {report ? <Tag>{STATE_LABEL[report.status] ?? report.status}</Tag> : null}
                  <Button onClick={generateReport}>按已确认工时生成草稿</Button>
                  <Button
                    onClick={saveNotes}
                    disabled={!report || report.status !== 'draft'}
                  >
                    保存说明
                  </Button>
                  <Button
                    type="primary"
                    onClick={submitReport}
                    disabled={!report || report.status !== 'draft'}
                  >
                    提交周报
                  </Button>
                </Space>
                {report ? (
                  <>
                    <Text type="secondary">
                      {report.weekStart} 至 {report.weekEnd} · 版本 v{report.version}
                    </Text>
                    <Table
                      rowKey="date"
                      size="small"
                      pagination={false}
                      dataSource={reportDays as Array<Record<string, unknown>>}
                      columns={[
                        { title: '日期', dataIndex: 'date' },
                        { title: '分钟', dataIndex: 'minutes' },
                        { title: '来源快照', dataIndex: 'submissionId' },
                      ]}
                    />
                    <Input.TextArea
                      rows={3}
                      placeholder="本周遇到的问题"
                      value={issues}
                      onChange={(event) => setIssues(event.target.value)}
                      disabled={report.status !== 'draft'}
                    />
                    <Input.TextArea
                      rows={3}
                      placeholder="下周计划"
                      value={nextWeekPlan}
                      onChange={(event) => setNextWeekPlan(event.target.value)}
                      disabled={report.status !== 'draft'}
                    />
                  </>
                ) : (
                  <Paragraph type="secondary">
                    本周还没有已确认工时，先提交每日工时再生成周报。
                  </Paragraph>
                )}
                <Card size="small" title={`待审周报（${pendingReports.length}）`}>
                  <Table
                    rowKey="id"
                    size="small"
                    pagination={false}
                    dataSource={pendingReports}
                    locale={{ emptyText: '没有待审核周报' }}
                    columns={[
                      { title: '员工', dataIndex: 'personId' },
                      { title: '周起始', dataIndex: 'weekStart' },
                      { title: '版本', dataIndex: 'version' },
                      {
                        title: '操作',
                        render: (_value, row) => (
                          <Space>
                            <Button
                              size="small"
                              type="primary"
                              onClick={() => {
                                setReviewAction('approve');
                                setReviewComment('');
                                setReviewTarget({ kind: 'report', row });
                              }}
                            >
                              通过
                            </Button>
                            <Button
                              size="small"
                              danger
                              onClick={() => {
                                setReviewAction('return');
                                setReviewComment('');
                                setReviewTarget({ kind: 'report', row });
                              }}
                            >
                              退回
                            </Button>
                          </Space>
                        ),
                      },
                    ]}
                  />
                </Card>
              </Space>
            ),
          },
        ]}
      />
      <Modal
        title={reviewAction === 'approve' ? '通过审核' : '退回并说明原因'}
        open={reviewTarget !== null}
        onCancel={() => setReviewTarget(null)}
        onOk={doReview}
        okText="确认"
      >
        <Input.TextArea
          rows={3}
          value={reviewComment}
          onChange={(event) => setReviewComment(event.target.value)}
          placeholder={reviewAction === 'return' ? '必填：请说明退回原因' : '可选备注'}
        />
      </Modal>
    </Card>
  );
}
