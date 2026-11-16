import type { ApiClient } from '@platform/api-client';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WorklogView } from './WorklogView';

const QUEUE_KEY = 'platform.worklog.pendingEntries.v1';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function client(overrides: Partial<ApiClient> = {}): ApiClient {
  return {
    getWorklogDay: vi.fn(async () => ({
      workDate: today(), state: 'draft', entries: [],
    })),
    listPendingWorklogReviews: vi.fn(async () => []),
    getWeeklyReport: vi.fn(async () => ({ report: null })),
    listPendingReportReviews: vi.fn(async () => []),
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
