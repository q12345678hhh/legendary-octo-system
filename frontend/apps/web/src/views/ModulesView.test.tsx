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
  it('lists modules and switches to suggestions', async () => {
    render(
      <ModulesView
        apiClient={fakeClient()}
        permissions={new Set(['appcatalog.module.read', 'appcatalog.suggestion.create'])}
      />,
    );

    expect(await screen.findByText('考勤管理')).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: '模块建议' }));

    expect(await screen.findByText('合同到期提醒')).toBeTruthy();
  });

  it('defaults to suggestions when catalog read is missing', async () => {
    render(
      <ModulesView
        apiClient={fakeClient()}
        permissions={new Set(['appcatalog.suggestion.create'])}
      />,
    );

    expect(await screen.findByText('合同到期提醒')).toBeTruthy();
  });
});
