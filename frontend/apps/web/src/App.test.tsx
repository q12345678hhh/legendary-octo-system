import type { ApiClient } from '@platform/api-client';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';

function fakeClient(health: () => Promise<{ status: string; service: string }>): ApiClient {
  return {
    request: vi.fn(),
    getSystemHealth: vi.fn(health),
  } as unknown as ApiClient;
}

describe('App', () => {
  it('shows the platform title and the resolved service state', async () => {
    render(
      <App apiClient={fakeClient(async () => ({ status: 'UP', service: 'platform-api' }))} />,
    );

    expect(screen.getByText('企业应用管理平台')).toBeTruthy();
    expect(await screen.findByText('platform-api')).toBeTruthy();
    expect(screen.getByText('UP')).toBeTruthy();
  });

  it('shows an actionable error when the backend is unreachable', async () => {
    render(
      <App apiClient={fakeClient(async () => Promise.reject(new Error('Failed to fetch')))} />,
    );

    expect(await screen.findByText('无法连接后端服务')).toBeTruthy();
    expect(screen.getByText('Failed to fetch')).toBeTruthy();
  });
});
