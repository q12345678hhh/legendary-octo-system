import type { ApiClient, Job } from '@platform/api-client';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { clearSession, saveSession } from './session';

const TENANT_ID = '11111111-1111-4111-8111-111111111111';
const SUBJECT_ID = '22222222-2222-4222-8222-222222222222';

function fakeClient(overrides: Partial<ApiClient> = {}): ApiClient {
  const base = {
    request: vi.fn(),
    getSystemHealth: vi.fn(async () => ({ status: 'UP', service: 'platform-api' })),
    listJobs: vi.fn(async () => [] as Job[]),
    createJob: vi.fn(),
    getJob: vi.fn(),
    cancelJob: vi.fn(),
    createUploadSession: vi.fn(),
    completeUpload: vi.fn(),
    listFiles: vi.fn(async () => []),
    getDownloadUrl: vi.fn(),
    listDeadLetters: vi.fn(async () => []),
    replayDeadLetter: vi.fn(),
    getCapabilities: vi.fn(async () => ({ permissions: [] })),
    listDepartments: vi.fn(async () => []),
    getDepartmentWorkspace: vi.fn(),
    updateDepartmentSettings: vi.fn(),
    getMyWorkPage: vi.fn(),
    getPersonWorkPage: vi.fn(),
    listMyWorkAccessLog: vi.fn(async () => []),
    getWorklogDay: vi.fn(),
    createWorklogEntry: vi.fn(),
    updateWorklogEntry: vi.fn(),
    deleteWorklogEntry: vi.fn(),
    submitWorklogDay: vi.fn(),
    listPendingWorklogReviews: vi.fn(async () => []),
    reviewWorklogSubmission: vi.fn(),
    getWeeklyReport: vi.fn(async () => ({ report: null })),
    generateWeeklyReport: vi.fn(),
    updateWeeklyReportNotes: vi.fn(),
    submitWeeklyReport: vi.fn(),
    listPendingReportReviews: vi.fn(async () => []),
    reviewWeeklyReport: vi.fn(),
    listCollabChannels: vi.fn(async () => []),
    createCollabChannel: vi.fn(),
    getCollabChannel: vi.fn(),
    listChannelMembers: vi.fn(async () => []),
    addChannelMember: vi.fn(),
    removeChannelMember: vi.fn(),
    archiveChannel: vi.fn(),
    listCollabPosts: vi.fn(async () => []),
    createCollabPost: vi.fn(),
    getCollabPost: vi.fn(),
    addCollabComment: vi.fn(),
    acknowledgeCollabPost: vi.fn(),
    pinCollabPost: vi.fn(),
    archiveCollabPost: vi.fn(),
    listCollabProjects: vi.fn(async () => []),
    createCollabProject: vi.fn(),
    listCollabFileLinks: vi.fn(async () => []),
    linkCollabFile: vi.fn(),
    listCollabTasks: vi.fn(async () => []),
    createCollabTask: vi.fn(),
    updateCollabTaskStatus: vi.fn(),
    listMilestones: vi.fn(async () => []),
    createMilestone: vi.fn(),
    listCollabMeetings: vi.fn(async () => []),
    createCollabMeeting: vi.fn(),
    getCollabMeeting: vi.fn(),
    addActionItem: vi.fn(),
    listMyActionItems: vi.fn(async () => []),
    completeActionItem: vi.fn(),
    listCollabDecisions: vi.fn(async () => []),
    createCollabDecision: vi.fn(),
    listCollabPages: vi.fn(async () => []),
    createCollabPage: vi.fn(),
    updateCollabPage: vi.fn(),
    createCollabShareLink: vi.fn(),
    resolveCollabShareLink: vi.fn(),
    listNotifications: vi.fn(async () => ({ rows: [], unread: 0 })),
    markNotificationRead: vi.fn(),
    searchCollaboration: vi.fn(async () => []),
    listInsightDepartments: vi.fn(async () => []),
    getWorkInsight: vi.fn(),
    getWorkInsightPeople: vi.fn(),
    getInsightSettings: vi.fn(),
    updateInsightSettings: vi.fn(),
    listWorkflowDefinitions: vi.fn(async () => []),
    createWorkflowDefinition: vi.fn(),
    updateWorkflowDefinition: vi.fn(),
    publishWorkflowDefinition: vi.fn(),
    retireWorkflowDefinition: vi.fn(),
    startWorkflowInstance: vi.fn(),
    getWorkflowInstance: vi.fn(),
    withdrawWorkflowInstance: vi.fn(),
    cancelWorkflowInstance: vi.fn(),
    listWorkflowTasks: vi.fn(async () => []),
    claimWorkflowTask: vi.fn(),
    completeWorkflowTask: vi.fn(),
    transferWorkflowTask: vi.fn(),
    listWorkflowExceptions: vi.fn(async () => []),
    resolveWorkflowException: vi.fn(),
  };
  return { ...base, ...overrides } as unknown as ApiClient;
}

beforeEach(() => {
  clearSession();
  globalThis.location.hash = '';
});

afterEach(() => {
  cleanup();
  globalThis.location.hash = '';
  clearSession();
});

describe('App shell', () => {
  it('requires a placeholder identity before exposing the workspace', () => {
    globalThis.location.hash = '#/home';

    render(<App apiClient={fakeClient()} />);

    expect(screen.getByText('使用占位身份进入')).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('signs in with UUIDs and lands on the home view', async () => {
    globalThis.location.hash = '#/login';
    const client = fakeClient();

    render(<App apiClient={client} />);

    fireEvent.change(screen.getByLabelText(/租户 ID/), { target: { value: TENANT_ID } });
    fireEvent.change(screen.getByLabelText(/主体 ID/), { target: { value: SUBJECT_ID } });
    fireEvent.submit(screen.getByRole('form', { name: '占位登录表单' }));

    expect(await screen.findByText('platform-api')).toBeTruthy();
    expect(screen.getByRole('navigation')).toBeTruthy();
    expect(globalThis.location.hash).toBe('#/home');
    expect(globalThis.sessionStorage.getItem('platform.session.v1')).toContain(TENANT_ID);
  });

  it('rejects malformed identities without leaving the login view', () => {
    globalThis.location.hash = '#/login';

    render(<App apiClient={fakeClient()} />);

    fireEvent.change(screen.getByLabelText(/租户 ID/), { target: { value: 'not-a-uuid' } });
    fireEvent.change(screen.getByLabelText(/主体 ID/), { target: { value: SUBJECT_ID } });
    fireEvent.submit(screen.getByRole('form', { name: '占位登录表单' }));

    expect(screen.getByRole('alert')).toBeTruthy();
    expect(screen.getByText(/租户 ID 必须是标准 UUID/)).toBeTruthy();
    expect(globalThis.location.hash).toBe('#/login');
  });

  it('shows an actionable error when the backend is unreachable', async () => {
    saveSession({ tenantId: TENANT_ID, subjectId: SUBJECT_ID });
    globalThis.location.hash = '#/home';

    render(
      <App
        apiClient={fakeClient({
          getSystemHealth: vi.fn(async () => Promise.reject(new Error('Failed to fetch'))),
        })}
      />,
    );

    expect(await screen.findByText('无法连接后端服务')).toBeTruthy();
    expect(screen.getByText('Failed to fetch')).toBeTruthy();
  });

  it('clears the session on sign out', async () => {
    saveSession({ tenantId: TENANT_ID, subjectId: SUBJECT_ID });
    globalThis.location.hash = '#/home';

    render(<App apiClient={fakeClient()} />);
    await screen.findByText('platform-api');

    fireEvent.click(screen.getByRole('button', { name: /退\s*出/ }));

    expect(await screen.findByText('使用占位身份进入')).toBeTruthy();
    expect(globalThis.sessionStorage.getItem('platform.session.v1')).toBeNull();
  });
});
