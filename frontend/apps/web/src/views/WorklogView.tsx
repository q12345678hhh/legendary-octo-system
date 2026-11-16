import type {
  ApiClient,
  VoiceCapture,
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
import { useVoiceRecorder } from './useVoiceRecorder';

const { Text, Paragraph } = Typography;

const PENDING_QUEUE_KEY = 'platform.worklog.pendingEntries.v1';
const ENTRY_DRAFT_KEY = 'platform.worklog.entryDraft.v1';

function readPendingQueue(): WorklogEntryInput[] {
  try {
    const raw = window.localStorage.getItem(PENDING_QUEUE_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? (parsed as WorklogEntryInput[]) : [];
  } catch {
    return [];
  }
}

function writePendingQueue(items: WorklogEntryInput[]): void {
  try {
    window.localStorage.setItem(PENDING_QUEUE_KEY, JSON.stringify(items));
  } catch {
    // 本地存储不可用时只影响离线补录，不影响在线提交
  }
}

/** fetch 在网络中断时抛 TypeError；业务错误是 ApiProblemError。 */
function isNetworkError(cause: unknown): boolean {
  return cause instanceof TypeError;
}

function toIso(date: string, time: string): string {
  return new Date(`${date}T${time}:00`).toISOString();
}

function minutesBetween(startIso: string, endIso: string): number {
  return Math.round((new Date(endIso).getTime() - new Date(startIso).getTime()) / 60000);
}

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
  minutes?: number;
  description: string;
  startTime?: string;
  endTime?: string;
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
  const [queuedCount, setQueuedCount] = useState(() => readPendingQueue().length);
  const recorder = useVoiceRecorder();
  const [voiceCapture, setVoiceCapture] = useState<VoiceCapture | null>(null);
  const [voiceText, setVoiceText] = useState('');
  const [voiceMinutes, setVoiceMinutes] = useState<number | null>(null);
  const [voiceBusy, setVoiceBusy] = useState(false);
  const [voiceError, setVoiceError] = useState<string | null>(null);

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

  const flushQueue = useCallback(async () => {
    const items = readPendingQueue();
    if (items.length === 0) {
      return;
    }
    const remaining: WorklogEntryInput[] = [];
    for (let index = 0; index < items.length; index += 1) {
      try {
        await apiClient.createWorklogEntry(items[index] as WorklogEntryInput);
      } catch (cause) {
        if (isNetworkError(cause)) {
          remaining.push(...items.slice(index));
          break;
        }
        // 业务错误（日期锁定、重叠等）重试无意义，直接丢弃并继续
      }
    }
    writePendingQueue(remaining);
    setQueuedCount(remaining.length);
    if (remaining.length < items.length) {
      loadDay();
    }
  }, [apiClient, loadDay]);

  useEffect(() => {
    void flushQueue();
  }, [flushQueue]);

  useEffect(() => {
    const onOnline = () => {
      void flushQueue();
    };
    window.addEventListener('online', onOnline);
    return () => window.removeEventListener('online', onOnline);
  }, [flushQueue]);

  useEffect(() => {
    const raw = window.localStorage.getItem(ENTRY_DRAFT_KEY);
    if (!raw) {
      return;
    }
    try {
      form.setFieldsValue(JSON.parse(raw) as Partial<EntryFormValues>);
    } catch {
      window.localStorage.removeItem(ENTRY_DRAFT_KEY);
    }
  }, [form]);

  const totalMinutes = useMemo(
    () => day?.entries.reduce((sum, entry) => sum + entry.minutes, 0) ?? 0,
    [day],
  );

  const createEntry = async (values: EntryFormValues) => {
    const startIso = values.startTime ? toIso(values.workDate, values.startTime) : undefined;
    const endIso = values.endTime ? toIso(values.workDate, values.endTime) : undefined;
    if (Boolean(startIso) !== Boolean(endIso)) {
      message.warning('开始与结束时间需要同时填写');
      return;
    }
    let minutes = values.minutes;
    if (!startIso && !endIso) {
      if (!minutes) {
        message.warning('请填写分钟，或同时填写开始与结束时间');
        return;
      }
    }
    if (startIso && endIso) {
      minutes = minutesBetween(startIso, endIso);
      if (minutes <= 0) {
        message.warning('结束时间必须晚于开始时间');
        return;
      }
      if (minutes > 1440) {
        message.warning('单条工时不能超过 24 小时');
        return;
      }
    }
    const input: WorklogEntryInput = {
      workDate: values.workDate,
      durationMinutes: minutes ?? null,
      description: values.description ?? '',
      clientEntryId: `web-${crypto.randomUUID()}`,
      ...(startIso && endIso ? { startsAt: startIso, endsAt: endIso } : {}),
    };
    try {
      await apiClient.createWorklogEntry(input);
      form.resetFields(['minutes', 'description', 'startTime', 'endTime']);
      window.localStorage.removeItem(ENTRY_DRAFT_KEY);
      message.success('已添加');
      loadDay();
    } catch (cause) {
      if (isNetworkError(cause)) {
        const queue = readPendingQueue();
        queue.push(input);
        writePendingQueue(queue);
        setQueuedCount(queue.length);
        message.warning('网络不可用，已加入待补录队列，恢复后自动重试');
        return;
      }
      message.error(describeError(cause));
    }
  };

  const transcribeRecording = async () => {
    if (!recorder.blob) {
      return;
    }
    const contentType = (recorder.blob.type || 'audio/webm').split(';')[0] as string;
    setVoiceBusy(true);
    setVoiceError(null);
    try {
      const capture = await apiClient.createVoiceCapture({
        workDate: date,
        contentType,
        sizeBytes: recorder.blob.size,
        durationSeconds: Math.max(1, recorder.seconds),
        clientRequestId: `voice-${crypto.randomUUID()}`,
      });
      if (!capture.uploadUrl) {
        throw new Error('服务端没有返回上传地址');
      }
      const upload = await fetch(capture.uploadUrl, {
        method: 'PUT',
        headers: { 'Content-Type': contentType },
        body: recorder.blob,
      });
      if (!upload.ok) {
        throw new Error(`音频上传失败（${upload.status}）`);
      }
      const transcribed = await apiClient.transcribeVoiceCapture(capture.id);
      setVoiceCapture(transcribed);
      setVoiceText(transcribed.transcript ?? '');
      setVoiceMinutes(Math.max(1, Math.ceil(recorder.seconds / 60)));
      recorder.reset();
    } catch (cause) {
      setVoiceError(describeError(cause));
    } finally {
      setVoiceBusy(false);
    }
  };

  const confirmVoice = async () => {
    if (!voiceCapture) {
      return;
    }
    const text = voiceText.trim();
    if (!text) {
      message.warning('请先确认工时说明');
      return;
    }
    setVoiceBusy(true);
    setVoiceError(null);
    try {
      const result = await apiClient.confirmVoiceCapture(voiceCapture.id, {
        text,
        description: text,
        durationMinutes: voiceMinutes,
        clientEntryId: `voice-entry-${crypto.randomUUID()}`,
      });
      message.success(result.audioDeleted
        ? '已生成语音工时，音频已删除'
        : '已生成语音工时，但音频删除失败，请稍后重试');
      setVoiceCapture(null);
      setVoiceText('');
      setVoiceMinutes(null);
      loadDay();
    } catch (cause) {
      setVoiceError(describeError(cause));
    } finally {
      setVoiceBusy(false);
    }
  };

  const discardVoice = async () => {
    setVoiceBusy(true);
    try {
      if (voiceCapture) {
        await apiClient.discardVoiceCapture(voiceCapture.id);
      }
    } catch {
      // 已删除或网络失败都不阻塞界面，下次进入会显示实际状态
    } finally {
      setVoiceBusy(false);
      setVoiceCapture(null);
      setVoiceText('');
      setVoiceMinutes(null);
      recorder.reset();
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
                {queuedCount > 0 ? (
                  <Alert
                    type="warning"
                    showIcon
                    message={`${queuedCount} 条工时待补录，网络恢复后会自动重试`}
                  />
                ) : null}
                <Card size="small" title="语音录入（仅在你按下录音时使用麦克风）">
                  <Space direction="vertical" size="small" style={{ width: '100%' }}>
                    <Space wrap>
                      <Button
                        type="primary"
                        onClick={() => {
                          void recorder.start();
                        }}
                        disabled={recorder.phase === 'recording'
                          || recorder.phase === 'unsupported'
                          || voiceBusy || voiceCapture !== null}
                      >
                        开始录音
                      </Button>
                      <Button onClick={recorder.stop} disabled={recorder.phase !== 'recording'}>
                        停止
                      </Button>
                      {recorder.phase === 'recording' ? (
                        <Tag color="red">录音中 {recorder.seconds}s / 120s</Tag>
                      ) : null}
                      {recorder.phase === 'ready' ? <Tag>已录音 {recorder.seconds}s</Tag> : null}
                      <Button
                        onClick={() => {
                          void transcribeRecording();
                        }}
                        loading={voiceBusy}
                        disabled={!recorder.blob || voiceCapture !== null}
                      >
                        上传并转写
                      </Button>
                      <Button
                        onClick={() => {
                          void discardVoice();
                        }}
                        disabled={voiceBusy || (!voiceCapture && !recorder.blob)}
                      >
                        放弃
                      </Button>
                      <Text type="secondary">
                        不录音时不会访问麦克风；音频在确认生成工时后立即删除。
                      </Text>
                    </Space>
                    {recorder.phase === 'unsupported' ? (
                      <Alert type="info" showIcon message="当前浏览器不支持录音，请使用文字录入" />
                    ) : null}
                    {recorder.error ? <Alert type="error" showIcon message={recorder.error} /> : null}
                    {voiceError ? <Alert type="error" showIcon message={voiceError} /> : null}
                    {voiceCapture?.status === 'failed' ? (
                      <Alert type="error" showIcon message="转写失败，请重新录音或改用文字录入" />
                    ) : null}
                    {voiceCapture?.status === 'transcribed' ? (
                      <Space direction="vertical" size="small" style={{ width: '100%' }}>
                        <Space wrap>
                          <Tag color="blue">
                            {voiceCapture.provider ?? 'ASR'}
                            {voiceCapture.model ? ` · ${voiceCapture.model}` : ''}
                          </Tag>
                          <Text type="secondary">原始转写：{voiceCapture.transcript ?? '—'}</Text>
                        </Space>
                        <Input.TextArea
                          rows={3}
                          value={voiceText}
                          onChange={(event) => setVoiceText(event.target.value)}
                          placeholder="确认或修改工时说明"
                        />
                        <Space>
                          <Text>工时分钟</Text>
                          <InputNumber
                            min={1}
                            max={1440}
                            value={voiceMinutes}
                            onChange={(value) => setVoiceMinutes(value)}
                          />
                          <Button type="primary" onClick={confirmVoice} loading={voiceBusy}>
                            确认生成工时
                          </Button>
                        </Space>
                      </Space>
                    ) : null}
                  </Space>
                </Card>
                <Form<EntryFormValues>
                  form={form}
                  layout="inline"
                  initialValues={{ workDate: date, minutes: 60 }}
                  onFinish={createEntry}
                  onValuesChange={(_changed, all) => {
                    try {
                      window.localStorage.setItem(ENTRY_DRAFT_KEY, JSON.stringify(all));
                    } catch {
                      // 草稿保存失败不影响提交
                    }
                  }}
                >
                  <Form.Item name="workDate" label="日期" rules={[{ required: true }]}>
                    <Input type="date" style={{ width: 160 }} />
                  </Form.Item>
                  <Form.Item name="startTime" label="开始">
                    <Input type="time" style={{ width: 130 }} />
                  </Form.Item>
                  <Form.Item name="endTime" label="结束">
                    <Input type="time" style={{ width: 130 }} />
                  </Form.Item>
                  <Form.Item name="minutes" label="分钟">
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
                        title: '来源',
                        dataIndex: 'sourceType',
                        render: (value?: string) => (
                          <Tag>{value === 'voice' ? '语音' : '手工'}</Tag>
                        ),
                      },
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
