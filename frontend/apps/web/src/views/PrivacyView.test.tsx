import type {
  ApiClient,
  PrivacyMyData,
  PrivacySettings,
  PrivacySettingsInput,
} from '@platform/api-client';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PrivacyView } from './PrivacyView';

const myData: PrivacyMyData = {
  personId: 'p1',
  voiceCaptures: [
    {
      id: 'v1',
      workDate: '2026-11-15',
      status: 'confirmed',
      provider: 'openai-compatible',
      model: 'whisper-1',
      audioDeletedAt: '2026-11-15T09:00:00Z',
      createdAt: '2026-11-15T08:59:00Z',
      transcribed: true,
    },
  ],
  expenseDocuments: [
    {
      id: 'd1',
      docType: 'invoice',
      status: 'posted',
      amount: 128.5,
      currency: 'CNY',
      createdAt: '2026-11-14T02:00:00Z',
    },
  ],
};

const settings: PrivacySettings = {
  dataResidency: 'cn',
  trainingOptOut: true,
  voiceTranscriptRetentionDays: 180,
  expenseOcrRetentionDays: 3650,
  updatedBy: 'admin',
  updatedAt: '2026-11-15T02:00:00Z',
  suppliers: [
    { surface: '语音转写', provider: 'openai-compatible', model: 'whisper-1', configured: true },
    { surface: '单据 OCR', provider: 'not_configured', model: 'gpt-4o-mini', configured: false },
  ],
};

async function flushUntil(check: () => boolean): Promise<void> {
  for (let i = 0; i < 200; i += 1) {
    if (check()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

function client(overrides: Partial<ApiClient> = {}): ApiClient {
  return {
    getMyPrivacyData: vi.fn(async () => myData),
    exportMyPrivacyData: vi.fn(async () => ({
      personId: 'p1',
      generatedAt: '2026-11-16T02:00:00Z',
      voiceCaptures: [],
      expenseDocuments: [],
    })),
    deleteMyPrivacyData: vi.fn(async () => ({
      actions: [{ id: 'v1', dataClass: 'worklog_voice_capture', action: 'recycled' }],
    })),
    getPrivacySettings: vi.fn(async () => settings),
    listPrivacyDataClasses: vi.fn(async () => [
      {
        dataClass: 'worklog_voice_capture',
        purpose: '语音转写文本；音频在确认后立即删除',
        retentionDays: 180,
        retentionNote: '转写保留天数可由租户调整',
        accessRoles: ['worklog.time.read', '本人'],
      },
    ]),
    updatePrivacySettings: vi.fn(async (input: PrivacySettingsInput) => ({ ...settings, ...input })),
    ...overrides,
  } as unknown as ApiClient;
}

const ADMIN = new Set(['tenant.admin']);

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200 })));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe('PrivacyView', () => {
  it('renders my voice captures and expense documents', async () => {
    render(<PrivacyView apiClient={client()} permissions={ADMIN} />);

    expect(await screen.findByText('我的语音转写')).toBeTruthy();
    expect(await screen.findByText('已生成工时')).toBeTruthy();
    expect(await screen.findByText('已入账')).toBeTruthy();
    expect(screen.getByText('只匿名化 OCR，不可删除')).toBeTruthy();
  });

  it('exports my data through the privacy endpoint', async () => {
    const apiClient = client();
    render(<PrivacyView apiClient={apiClient} permissions={ADMIN} />);
    await screen.findByText('已入账');

    fireEvent.click(screen.getByRole('button', { name: '导出我的数据' }));
    await flushUntil(() => (apiClient.exportMyPrivacyData as ReturnType<typeof vi.fn>)
      .mock.calls.length > 0);
    expect(apiClient.exportMyPrivacyData).toHaveBeenCalledTimes(1);
  });

  it('deletes selected voice captures through the privacy endpoint', async () => {
    const apiClient = client();
    render(<PrivacyView apiClient={apiClient} permissions={ADMIN} />);
    await screen.findByText('已入账');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]!);
    const deleteButton = await screen.findByRole('button', { name: '删除选中' });
    await flushUntil(() => !(deleteButton as HTMLButtonElement).disabled);
    fireEvent.click(deleteButton);

    await flushUntil(() => (apiClient.deleteMyPrivacyData as ReturnType<typeof vi.fn>)
      .mock.calls.length > 0);
    expect(apiClient.deleteMyPrivacyData).toHaveBeenCalledWith({
      voiceCaptureIds: ['v1'],
      expenseDocumentIds: [],
    });
  });

  it('shows data classes and suppliers to tenant admin', async () => {
    render(<PrivacyView apiClient={client()} permissions={ADMIN} />);
    await screen.findByText('我的语音转写');

    fireEvent.click(screen.getByText('数据分类与供应商'));
    expect(await screen.findByText('语音转写文本；音频在确认后立即删除')).toBeTruthy();
    expect(await screen.findByText('whisper-1')).toBeTruthy();
    expect(await screen.findByText('未配置')).toBeTruthy();
  });
});
