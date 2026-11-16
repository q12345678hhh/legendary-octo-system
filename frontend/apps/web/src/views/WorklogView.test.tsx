import type { ApiClient, WeeklyReport } from '@platform/api-client';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WorklogView } from './WorklogView';

const QUEUE_KEY = 'platform.worklog.pendingEntries.v1';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function reportFixture(overrides: Partial<WeeklyReport> = {}): WeeklyReport {
  return {
    id: 'r1',
    personId: 'p1',
    weekStart: '2026-11-09',
    weekEnd: '2026-11-15',
    version: 1,
    status: 'draft',
    content: {
      schemaVersion: 2,
      weekStart: '2026-11-09',
      weekEnd: '2026-11-15',
      days: [
        { date: '2026-11-09', minutes: 480, submissionId: 'submission-0001', entryIds: ['e1'] },
      ],
      entries: [
        {
          entryId: 'entry-0001', date: '2026-11-09', minutes: 480,
          projectId: null, taskId: null, description: '完成周报接口',
        },
      ],
      items: [{ key: 'unassigned', minutes: 480, entryIds: ['e1'] }],
      summary: '本周完成周报接口与页面联调。',
      summaryPoints: [{ text: '完成周报接口与页面联调。', entryIds: ['e1'] }],
      summarySource: 'ai',
      highlights: [{ text: '周报接口上线', entryIds: ['e1'] }],
      anomalies: [
        { code: 'MISSING_DAY', message: '工作日未提交工时', date: '2026-11-10', entryId: null },
      ],
      nextWeekPlan: '补齐 AI 周报验收。',
      nextWeekPlanSource: 'ai-suggested',
      issues: '',
      generation: {
        mode: 'ai',
        provider: 'openai-compatible',
        model: 'gpt-4o-mini',
        promptVersion: 'wp04-02.v1',
        generatedAt: '2026-11-16T02:00:00Z',
        fallbackReason: null,
        droppedClaims: 1,
        truncated: false,
      },
    },
    submittedAt: null,
    ...overrides,
  };
}

function client(overrides: Partial<ApiClient> = {}): ApiClient {
  return {
    getWorklogDay: vi.fn(async () => ({
      workDate: today(), state: 'draft', entries: [],
    })),
    listPendingWorklogReviews: vi.fn(async () => []),
    getWeeklyReport: vi.fn(async () => ({ report: null })),
    listPendingReportReviews: vi.fn(async () => []),
    listWeeklyReportVersions: vi.fn(async () => []),
    generateWeeklyReport: vi.fn(async () => reportFixture()),
    updateWeeklyReportNotes: vi.fn(async () => reportFixture()),
    submitWeeklyReport: vi.fn(async () => reportFixture({ status: 'submitted' })),
    createWorklogEntry: vi.fn(async () => ({})),
    createVoiceCapture: vi.fn(async () => ({
      id: 'v1', status: 'uploaded', uploadUrl: 'http://upload.test/voice',
    })),
    transcribeVoiceCapture: vi.fn(async () => ({
      id: 'v1', status: 'transcribed', transcript: '整理客户方案',
      provider: 'openai-compatible', model: 'whisper-1',
    })),
    confirmVoiceCapture: vi.fn(async () => ({
      entry: { id: 'e1', minutes: 1, description: '整理客户方案' }, audioDeleted: true,
    })),
    discardVoiceCapture: vi.fn(async () => undefined),
    ...overrides,
  } as unknown as ApiClient;
}

class FakeMediaRecorder {
  static isTypeSupported = () => true;

  state = 'inactive';
  mimeType = 'audio/webm';
  ondataavailable: ((event: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;

  start() {
    this.state = 'recording';
  }

  stop() {
    this.state = 'inactive';
    this.onstop?.();
  }
}

const getUserMedia = vi.fn(async () => ({
  getTracks: () => [{ stop: vi.fn() }],
}));

beforeEach(() => {
  window.localStorage.clear();
  vi.stubGlobal('MediaRecorder', FakeMediaRecorder);
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200 })));
  Object.defineProperty(navigator, 'mediaDevices', {
    value: { getUserMedia },
    configurable: true,
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe('WorklogView 语音与离线补录', () => {
  it('挂载时不访问麦克风，点击开始录音后才申请权限', async () => {
    render(<WorklogView apiClient={client()} />);

    expect(await screen.findByText('工时与周报', {}, { timeout: 10000 })).toBeTruthy();
    expect(getUserMedia).not.toHaveBeenCalled();
    expect(screen.getByText(/不录音时不会访问麦克风/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '开始录音' }));

    await waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/录音中/)).toBeTruthy();
  }, 20000);

  it('停止录音后可上传转写并确认生成工时', async () => {
    const api = client();
    render(<WorklogView apiClient={api} />);
    await screen.findByText('工时与周报', {}, { timeout: 10000 });

    fireEvent.click(screen.getByRole('button', { name: '开始录音' }));
    await screen.findByText(/录音中/);
    fireEvent.click(screen.getByRole('button', { name: /停\s*止/ }));

    const transcribe = await screen.findByRole('button', { name: '上传并转写' });
    await waitFor(() => expect(transcribe.hasAttribute('disabled')).toBe(false));
    fireEvent.click(transcribe);

    expect(await screen.findByDisplayValue('整理客户方案')).toBeTruthy();
    expect(api.transcribeVoiceCapture).toHaveBeenCalledWith('v1');

    fireEvent.click(screen.getByRole('button', { name: '确认生成工时' }));

    await waitFor(() => expect(api.confirmVoiceCapture).toHaveBeenCalledTimes(1));
    const [captureId, input] = vi.mocked(api.confirmVoiceCapture).mock.calls[0]!;
    expect(captureId).toBe('v1');
    expect(input.text).toBe('整理客户方案');
  }, 20000);

  it('启动时重放待补录队列并复用原 clientEntryId', async () => {
    const pending = {
      workDate: today(),
      durationMinutes: 45,
      description: '离线补录的工时',
      clientEntryId: 'offline-entry-1',
    };
    window.localStorage.setItem(QUEUE_KEY, JSON.stringify([pending]));

    const api = client();
    render(<WorklogView apiClient={api} />);

    await waitFor(() => expect(api.createWorklogEntry).toHaveBeenCalledWith(pending));
    await waitFor(() => expect(window.localStorage.getItem(QUEUE_KEY)).toBe('[]'));
  }, 20000);
});

describe('WorklogView 周报 AI 摘要', () => {
  async function openReport(api: ApiClient): Promise<void> {
    render(<WorklogView apiClient={api} />);
    await screen.findByText('工时与周报', {}, { timeout: 10000 });
    fireEvent.click(screen.getByRole('tab', { name: '我的周报' }));
  }

  it('展示 AI 摘要、要点与来源，并可保存修改后的摘要', async () => {
    const api = client({ getWeeklyReport: vi.fn(async () => ({ report: reportFixture() })) });
    await openReport(api);

    expect(await screen.findByText(/AI 生成 · openai-compatible/)).toBeTruthy();
    expect(screen.getByText('已丢弃 1 条无法回溯的数字')).toBeTruthy();
    expect(screen.getByText('摘要要点（只读，含来源）')).toBeTruthy();
    expect(screen.getByText('工作产出')).toBeTruthy();
    expect(screen.getByText('工作日未提交工时')).toBeTruthy();
    expect(screen.getByText('AI 建议，待确认')).toBeTruthy();

    fireEvent.change(screen.getByDisplayValue('本周完成周报接口与页面联调。'), {
      target: { value: '本周完成周报接口、页面联调与验收。' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存说明' }));

    await waitFor(() => expect(api.updateWeeklyReportNotes).toHaveBeenCalledTimes(1));
    const payload = vi.mocked(api.updateWeeklyReportNotes).mock.calls[0]![1];
    expect(payload.summary).toBe('本周完成周报接口、页面联调与验收。');
  }, 20000);

  it('AI 未配置时展示降级原因，仍可提交', async () => {
    const degraded = reportFixture();
    degraded.content.generation = {
      mode: 'fallback',
      provider: 'template',
      model: 'duration-only',
      promptVersion: 'wp04-02.v1',
      generatedAt: '2026-11-16T02:00:00Z',
      fallbackReason: 'AI_NOT_CONFIGURED',
      droppedClaims: 0,
      truncated: false,
    };
    degraded.content.summarySource = 'fallback';
    const api = client({ getWeeklyReport: vi.fn(async () => ({ report: degraded })) });
    await openReport(api);

    expect(await screen.findByText('AI 不可用，本次已按已确认时长生成模板')).toBeTruthy();
    expect(screen.getByText(/未配置平台 AI/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '提交周报' }));
    await waitFor(() => expect(api.submitWeeklyReport).toHaveBeenCalledTimes(1));
  }, 20000);

  it('提交后编辑控件只读，并展示历史版本', async () => {
    const submitted = reportFixture({
      status: 'submitted', version: 2, submittedAt: '2026-11-16T03:00:00Z',
    });
    const api = client({
      getWeeklyReport: vi.fn(async () => ({ report: submitted })),
      listWeeklyReportVersions: vi.fn(async () => [submitted, reportFixture()]),
    });
    await openReport(api);

    const save = await screen.findByRole('button', { name: '保存说明' });
    expect(save.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('历史版本（2）')).toBeTruthy();
    expect(screen.getByText('v2')).toBeTruthy();
    expect(screen.getAllByText('已提交').length).toBeGreaterThan(0);
  }, 20000);
});
