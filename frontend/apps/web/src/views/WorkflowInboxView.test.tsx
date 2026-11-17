import type { ApiClient, WorkflowTask } from '@platform/api-client';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkflowInboxView } from './WorkflowInboxView';

const TASK: WorkflowTask = {
  id: '33333333-3333-4333-8333-333333333333',
  instanceId: null,
  nodeKey: null,
  businessType: 'worklog.time',
  businessId: '44444444-4444-4444-8444-444444444444',
  taskType: 'review',
  title: '工时待审核',
  assigneePersonId: '55555555-5555-4555-8555-555555555555',
  assigneeKind: 'direct',
  status: 'pending',
  action: null,
  comment: null,
  dueAt: null,
  slaState: 'normal',
  createdAt: '2026-11-13T02:00:00Z',
};

function client(tasks: WorkflowTask[]): ApiClient {
  return {
    listWorkflowTasks: vi.fn(async () => tasks),
  } as unknown as ApiClient;
}

afterEach(cleanup);

describe('WorkflowInboxView', () => {
  it('列出本人待办并显示业务类型', async () => {
    render(<WorkflowInboxView apiClient={client([TASK])} />);

    expect(await screen.findByText('工时待审核')).toBeTruthy();
    expect(screen.getByText(/直连审核/)).toBeTruthy();
  });

  it('没有待办时显示空态', async () => {
    render(<WorkflowInboxView apiClient={client([])} />);

    expect(await screen.findByText('当前没有待办任务。')).toBeTruthy();
  });
});
