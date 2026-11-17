import type { ApiClient, AppModule, ModuleSuggestion } from '@platform/api-client';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ModulesView } from './ModulesView';

const MODULE: AppModule = {
  id: '11111111-1111-4111-8111-111111111111',
  code: 'hr.attendance',
  name: '考勤管理',
  category: '人事',
  description: '排班与请假',
  templateKey: null,
  ownerPersonId: null,
  version: '1.0.0',
  dependencies: '[]',
  applicableScope: 'tenant',
  healthStatus: 'healthy',
  installStatus: 'not_installed',
  createdAt: '2026-11-13T09:00:00Z',
  updatedAt: '2026-11-13T09:00:00Z',
};

const SUGGESTION: ModuleSuggestion = {
  id: '22222222-2222-4222-8222-222222222222',
  title: '合同到期提醒',
  requesterPersonId: '33333333-3333-4333-8333-333333333333',
  businessProblem: '合同到期靠人工翻台账，容易漏提醒。',
  goal: '到期前 30 天自动提醒',
  scope: '人事部',
  dataScope: '合同、员工',
  permissionsNeeded: '合同读取',
  costEstimate: '低',
  benefitEstimate: '减少漏提醒',
  suggestedOwnerPersonId: null,
  configOwnerPersonId: null,
  status: 'communicating',
  createdAt: '2026-11-13T09:00:00Z',
  updatedAt: '2026-11-13T10:00:00Z',
};

function fakeClient(): ApiClient {
  return {
    listAppModules: vi.fn(async () => [MODULE]),
    listModuleSuggestions: vi.fn(async () => [SUGGESTION]),
    getModuleSuggestion: vi.fn(),
    createAppModule: vi.fn(),
    updateAppModule: vi.fn(),
    createModuleSuggestion: vi.fn(),
    actOnModuleSuggestion: vi.fn(),
  } as unknown as ApiClient;
}

afterEach(cleanup);

describe('ModulesView', () => {
  it(
    'lists modules and switches to suggestions',
    async () => {
      render(
        <ModulesView
          apiClient={fakeClient()}
          permissions={new Set(['appcatalog.module.read', 'appcatalog.suggestion.create'])}
        />,
      );

      expect(await screen.findByText('考勤管理', {}, { timeout: 10000 })).toBeTruthy();

      fireEvent.click(screen.getByRole('tab', { name: '模块建议' }));

      expect(
        await screen.findByText('合同到期提醒', {}, { timeout: 10000 }),
      ).toBeTruthy();
    },
    20000,
  );

  it('defaults to suggestions when catalog read is missing', async () => {
    render(
      <ModulesView
        apiClient={fakeClient()}
        permissions={new Set(['appcatalog.suggestion.create'])}
      />,
    );

    expect(await screen.findByText('合同到期提醒')).toBeTruthy();
  });

  it('shows the server error and keeps the modal open when module creation fails', async () => {
    const client = fakeClient();
    vi.mocked(client.createAppModule).mockRejectedValue(new Error('模块编码已存在'));
    render(
      <ModulesView
        apiClient={client}
        permissions={new Set(['appcatalog.module.read', 'appcatalog.module.manage'])}
      />,
    );

    expect(await screen.findByText('考勤管理', {}, { timeout: 10000 })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '新增模块' }));
    fireEvent.change(screen.getByLabelText('模块编码'), { target: { value: 'demo.duplicate' } });
    fireEvent.change(screen.getByLabelText('模块名称'), { target: { value: '重复模块' } });
    fireEvent.click(screen.getByRole('button', { name: '保 存' }));

    expect(await screen.findByText('模块保存失败', {}, { timeout: 10000 })).toBeTruthy();
    expect(await screen.findByText(/模块编码已存在/, {}, { timeout: 10000 })).toBeTruthy();
    expect(screen.getByText('登记模块')).toBeTruthy();
  }, 30000);

  it('rejects an invalid module code before calling the API', async () => {
    const client = fakeClient();
    render(
      <ModulesView
        apiClient={client}
        permissions={new Set(['appcatalog.module.read', 'appcatalog.module.manage'])}
      />,
    );

    expect(await screen.findByText('考勤管理', {}, { timeout: 10000 })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '新增模块' }));
    fireEvent.change(screen.getByLabelText('模块编码'), { target: { value: '中文编码' } });
    fireEvent.change(screen.getByLabelText('模块名称'), { target: { value: '中文模块' } });
    fireEvent.click(screen.getByRole('button', { name: '保 存' }));

    expect(
      await screen.findByText(
        '需以小写字母开头，只含小写字母、数字、点、下划线或连字符',
        {},
        { timeout: 10000 },
      ),
    ).toBeTruthy();
    expect(client.createAppModule).not.toHaveBeenCalled();
  }, 30000);

  it('edits an existing module and keeps its code read-only', async () => {
    const client = fakeClient();
    vi.mocked(client.updateAppModule).mockResolvedValue({ ...MODULE, name: '考勤管理 Pro' });
    render(
      <ModulesView
        apiClient={client}
        permissions={new Set(['appcatalog.module.read', 'appcatalog.module.manage'])}
      />,
    );

    expect(await screen.findByText('考勤管理', {}, { timeout: 10000 })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '编 辑' }));

    expect(await screen.findByText('编辑模块', {}, { timeout: 10000 })).toBeTruthy();
    expect((screen.getByLabelText('模块编码') as HTMLInputElement).disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('模块名称'), { target: { value: '考勤管理 Pro' } });
    fireEvent.click(screen.getByRole('button', { name: '保 存' }));

    await vi.waitFor(
      () =>
        expect(client.updateAppModule).toHaveBeenCalledWith(
          MODULE.id,
          expect.objectContaining({
            code: 'hr.attendance',
            name: '考勤管理 Pro',
            installStatus: 'not_installed',
          }),
        ),
      { timeout: 10000 },
    );
    expect(client.createAppModule).not.toHaveBeenCalled();
  }, 30000);

  it('marks a module as installed and reloads the catalog', async () => {
    const client = fakeClient();
    const installed = { ...MODULE, installStatus: 'installed' as const };
    let updated = false;
    vi.mocked(client.listAppModules).mockImplementation(async () => (updated ? [installed] : [MODULE]));
    vi.mocked(client.updateAppModule).mockImplementation(async () => {
      updated = true;
      return installed;
    });
    render(
      <ModulesView
        apiClient={client}
        permissions={new Set(['appcatalog.module.read', 'appcatalog.module.manage'])}
      />,
    );

    expect(await screen.findByText('考勤管理', {}, { timeout: 10000 })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '标记已安装' }));

    expect(await screen.findByText('已安装', {}, { timeout: 10000 })).toBeTruthy();
    expect(client.updateAppModule).toHaveBeenCalledWith(
      MODULE.id,
      expect.objectContaining({ code: 'hr.attendance', installStatus: 'installed' }),
    );
  }, 30000);
});
