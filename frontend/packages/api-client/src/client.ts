import { isProblemDetails, type ProblemDetails } from './problem';

export class ApiProblemError extends Error {
  readonly status: number;
  readonly problem: ProblemDetails | undefined;

  constructor(status: number, problem?: ProblemDetails) {
    super(problem?.title ?? `HTTP ${status}`);
    this.name = 'ApiProblemError';
    this.status = status;
    this.problem = problem;
  }
}

export interface HealthResponse {
  status: string;
  service: string;
}

/** 占位认证身份：租户 + 主体，仅由页面会话提供，后续由真实登录替换。 */
export interface PlatformIdentity {
  tenantId: string;
  subjectId: string;
}

export interface ApiClientOptions {
  baseUrl: string;
  fetchImpl?: typeof fetch;
  correlationIdFactory?: () => string;
  idempotencyKeyFactory?: () => string;
  identityProvider?: () => PlatformIdentity | null;
}

export type JsonRecord = Record<string, unknown>;

export type JobStatus = 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';

export interface Job {
  jobId: string;
  jobType: string;
  status: JobStatus;
  progress: number;
  cancelRequested: boolean;
  payload?: JsonRecord | null;
  result?: JsonRecord | null;
  lastError?: string | null;
  traceId: string;
  correlationId: string;
  requestedAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export interface CreateJobInput {
  jobType: string;
  payload?: JsonRecord;
}

export type FileStatus = 'pending_upload' | 'uploaded' | 'available' | 'rejected';
export type FileScanStatus = 'pending' | 'scanning' | 'clean' | 'infected' | 'failed';

export interface FileMetadata {
  fileId: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  sha256?: string | null;
  status: FileStatus;
  scanStatus: FileScanStatus;
  scanDetail?: string | null;
  createdAt: string;
  uploadedAt?: string | null;
  scannedAt?: string | null;
}

export interface CreateUploadInput {
  fileName: string;
  contentType: string;
  sizeBytes: number;
  sha256?: string;
}

export interface UploadSession {
  fileId: string;
  objectKey: string;
  uploadUrl: string;
  expiresAt: string;
}

export interface DownloadGrant {
  fileId: string;
  url: string;
  expiresAt: string;
}

export interface DeadLetter {
  eventId: string;
  eventName: string;
  attempts: number;
  lastError?: string | null;
  occurredAt: string;
  lastAttemptAt?: string | null;
  correlationId: string;
  traceId?: string | null;
}

export interface ReplayAccepted {
  eventId: string;
  status: 'pending';
}

/** 单次请求的幂等控制：重试同一业务动作时复用同一个 key。 */
export interface RequestOptions {
  idempotencyKey?: string;
  skipIdempotencyKey?: boolean;
}

/** 当前主体持有的权限码，仅用于隐藏导航，不作为前端放行依据。 */
export interface Capabilities {
  permissions: string[];
}

export interface DepartmentSummary {
  orgUnitId: string;
  name: string;
  path: string;
}

export type WorkDayState = 'draft' | 'submitted' | 'approved' | 'returned';

/**
 * 部门首屏成员行。
 *
 * <p>工时、周报与文件字段在无权时由后端整体省略，因此这里全部是可选的；
 * 前端必须把「字段不存在」渲染为「无权查看」，不能补 0 或占位文案。
 */
export interface DepartmentMember {
  personId: string;
  displayName: string;
  positionName?: string | null;
  jobName?: string | null;
  assignmentType?: string | null;
  managerName?: string | null;
  depth: number;
  workPageAccessible: boolean;
  workPagePath?: string | null;
  todayMinutes?: number | null;
  dayState?: WorkDayState | null;
  weekReportState?: 'draft' | 'submitted' | null;
  authorizedFileCount?: number | null;
}

export interface DepartmentWorkspace {
  orgUnitId: string;
  name: string;
  status: 'active' | 'archived';
  dayEndTime: string;
  weekReportDueWeekday: number;
  members: DepartmentMember[];
}

export interface DepartmentSettingInput {
  dayEndTime: string;
  weekReportDueWeekday: number;
}

export interface WorkPageSection {
  key: string;
  available: boolean;
  data?: unknown;
}

export interface WorkPage {
  personId: string;
  displayName: string;
  status: string;
  self: boolean;
  sections: WorkPageSection[];
}

export interface WorkAccessLogRow {
  id: string;
  viewerPersonId: string;
  resourceType: string;
  resourceId?: string | null;
  action: string;
  factors: string[];
  occurredAt?: string | null;
}

export interface WorklogEntryView {
  id: string;
  startsAt?: string | null;
  endsAt?: string | null;
  minutes: number;
  projectId?: string | null;
  taskId?: string | null;
  description: string;
  clientEntryId: string;
  draftVersion: number;
}

export interface WorklogEntryInput {
  workDate: string;
  startsAt?: string | null;
  endsAt?: string | null;
  durationMinutes?: number | null;
  projectId?: string | null;
  taskId?: string | null;
  description: string;
  clientEntryId: string;
}

export interface WorklogSubmission {
  id: string;
  personId: string;
  workDate: string;
  version: number;
  status: 'submitted' | 'approved' | 'returned';
  totalMinutes: number;
  snapshot: string;
  submittedAt?: string | null;
}

export interface WorklogDay {
  workDate: string;
  state: 'draft' | 'submitted' | 'approved' | 'returned';
  entries: WorklogEntryView[];
  submission?: WorklogSubmission | null;
}

export interface WeeklyReport {
  id: string;
  personId: string;
  weekStart: string;
  weekEnd: string;
  version: number;
  status: 'draft' | 'submitted' | 'approved' | 'returned';
  content: Record<string, unknown>;
  submittedAt?: string | null;
}

export interface CollabChannel {
  id: string;
  orgUnitId: string;
  scope: 'all' | 'members' | 'cross_department';
  name: string;
  topic: string;
  createdByPersonId: string;
  createdAt: string;
  archivedAt?: string | null;
}

export interface CollabMember {
  personId: string;
  role: 'owner' | 'member';
  addedAt: string;
  removedAt?: string | null;
}

export interface CollabPost {
  id: string;
  channelId: string;
  authorPersonId: string;
  title: string;
  body: string;
  pinnedAt?: string | null;
  archivedAt?: string | null;
  createdAt: string;
}

export interface CollabComment {
  id: string;
  postId: string;
  authorPersonId: string;
  body: string;
  createdAt: string;
}

export interface CollabPostDetail {
  post: CollabPost;
  comments: CollabComment[];
  receipts: string[];
}

export interface CollabChannelDetail {
  channel: CollabChannel;
  members: CollabMember[];
  posts: CollabPost[];
}

export interface CollabProject {
  id: string;
  orgUnitId: string;
  name: string;
  description: string;
  status: 'active' | 'archived';
  createdAt: string;
}

export interface CollabFileLink {
  id: string;
  channelId?: string | null;
  postId?: string | null;
  projectId?: string | null;
  fileId: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
}

export interface CollabTask {
  id: string;
  projectId?: string | null;
  orgUnitId?: string | null;
  milestoneId?: string | null;
  title: string;
  description: string;
  status: 'todo' | 'doing' | 'done';
  assigneePersonId?: string | null;
  dueDate?: string | null;
  createdByPersonId: string;
  createdAt: string;
}

export interface CollabMilestone {
  id: string;
  projectId: string;
  name: string;
  dueDate?: string | null;
  status: 'planned' | 'achieved' | 'missed';
}

export interface CollabMeeting {
  id: string;
  orgUnitId: string;
  projectId?: string | null;
  title: string;
  meetingDate: string;
  notes: string;
  createdByPersonId: string;
  createdAt: string;
}

export interface CollabActionItem {
  id: string;
  meetingId?: string | null;
  title: string;
  ownerPersonId: string;
  dueDate?: string | null;
  status: 'open' | 'done';
}

export interface CollabMeetingDetail {
  meeting: CollabMeeting;
  actionItems: CollabActionItem[];
}

export interface CollabDecision {
  id: string;
  orgUnitId: string;
  meetingId?: string | null;
  title: string;
  body: string;
  decidedAt: string;
  createdAt: string;
}

export interface CollabPage {
  id: string;
  orgUnitId: string;
  parentId?: string | null;
  title: string;
  body: string;
  createdAt: string;
  updatedAt: string;
}

export interface CollabNotification {
  id: string;
  recipientPersonId: string;
  resourceType: string;
  resourceId: string;
  title: string;
  body: string;
  readAt?: string | null;
  createdAt: string;
}

export interface CollabInbox {
  rows: CollabNotification[];
  unread: number;
}

export interface CollabSearchHit {
  kind: string;
  id: string;
  title: string;
  snippet: string;
}

export interface CollabRevocation {
  wasMember: boolean;
  revokedShareLinks: number;
  dismissedNotifications: number;
  slaMinutes: number;
}

/** 洞察可选范围：人员范围（直属汇报）返回空列表，前端按「我的管理范围」处理。 */
export interface InsightDepartmentOption {
  orgUnitId: string;
  name: string;
}

export interface InsightTaskStatusCount {
  status: string;
  count: number;
}

export interface InsightWeekRow {
  weekStart: string;
  submittedPersonDays?: number | null;
  suppressed: boolean;
}

export interface InsightReportCounts {
  draft: number;
  submitted: number;
  approved: number;
  returned: number;
}

/**
 * 洞察概览。阈值为群组人数下限；`suppressed` 为真时所有数值字段都会是 null，
 * 前端不得显示 0、均值或排名。
 */
export interface InsightOverview {
  orgUnitId?: string | null;
  from: string;
  to: string;
  threshold: number;
  suppressed: boolean;
  memberCount?: number | null;
  submissionRate?: number | null;
  submittedPersonDays?: number | null;
  expectedPersonDays?: number | null;
  totalSubmittedMinutes?: number | null;
  avgMinutesPerPerson?: number | null;
  taskStatus: InsightTaskStatusCount[];
  overdueTasks?: number | null;
  weeklyReports?: InsightReportCounts | null;
  weeks: InsightWeekRow[];
}

export interface InsightDrilldownRow {
  personId: string;
  displayName: string;
  submittedDays: number;
  totalMinutes: number;
}

export interface InsightDrilldown {
  threshold: number;
  rows: InsightDrilldownRow[];
}

export interface InsightSetting {
  minGroupSize: number;
  maxDailyMinutes: number;
}

/** 工作流定义：只有草稿可修改，发布后 schema 与版本不可变。 */
export interface WorkflowDefinition {
  id: string;
  key: string;
  version: number;
  name: string;
  status: string;
  schema: string;
  schemaHash: string;
  createdBy: string;
  publishedAt?: string | null;
}

/** 审批任务：实例任务带 instanceId，工时/周报直连任务为空。 */
export interface WorkflowTask {
  id: string;
  instanceId?: string | null;
  nodeKey?: string | null;
  businessType: string;
  businessId?: string | null;
  taskType: string;
  title: string;
  assigneePersonId?: string | null;
  assigneeKind: string;
  status: string;
  action?: string | null;
  comment?: string | null;
  dueAt?: string | null;
  slaState: string;
  createdAt: string;
}

export interface WorkflowInstance {
  id: string;
  definitionKey: string;
  definitionVersion: number;
  businessType: string;
  businessId?: string | null;
  businessKey?: string | null;
  status: string;
  variables: string;
  startedByPersonId: string;
  startedAt: string;
  endedAt?: string | null;
}

export interface WorkflowToken {
  id: string;
  nodeKey: string;
  status: string;
}

export interface WorkflowLog {
  id: string;
  taskId?: string | null;
  nodeKey?: string | null;
  fromStatus?: string | null;
  toStatus?: string | null;
  action: string;
  actorPersonId?: string | null;
  comment?: string | null;
  occurredAt: string;
}

export interface WorkflowInstanceDetail {
  instance: WorkflowInstance;
  tokens: WorkflowToken[];
  tasks: WorkflowTask[];
  logs: WorkflowLog[];
}

export interface WorkflowException {
  id: string;
  instanceId?: string | null;
  taskId?: string | null;
  code: string;
  message: string;
  status: string;
  resolvedBy?: string | null;
  resolution?: string | null;
  createdAt: string;
}

export interface ApiClient {
  request<T>(path: string, init?: RequestInit, options?: RequestOptions): Promise<T>;
  getSystemHealth(): Promise<HealthResponse>;
  createJob(input: CreateJobInput, options?: RequestOptions): Promise<Job>;
  listJobs(limit?: number): Promise<Job[]>;
  getJob(jobId: string): Promise<Job>;
  cancelJob(jobId: string): Promise<Job>;
  createUploadSession(input: CreateUploadInput, options?: RequestOptions): Promise<UploadSession>;
  completeUpload(fileId: string, options?: RequestOptions): Promise<FileMetadata>;
  listFiles(): Promise<FileMetadata[]>;
  getDownloadUrl(fileId: string): Promise<DownloadGrant>;
  listDeadLetters(): Promise<DeadLetter[]>;
  replayDeadLetter(eventId: string, reason: string, options?: RequestOptions): Promise<ReplayAccepted>;
  getCapabilities(): Promise<Capabilities>;
  listDepartments(): Promise<DepartmentSummary[]>;
  getDepartmentWorkspace(orgUnitId: string): Promise<DepartmentWorkspace>;
  updateDepartmentSettings(
    orgUnitId: string,
    input: DepartmentSettingInput,
    options?: RequestOptions,
  ): Promise<DepartmentSettingInput>;
  getMyWorkPage(): Promise<WorkPage>;
  getPersonWorkPage(personId: string): Promise<WorkPage>;
  listMyWorkAccessLog(limit?: number): Promise<WorkAccessLogRow[]>;
  getWorklogDay(date: string): Promise<WorklogDay>;
  createWorklogEntry(input: WorklogEntryInput, options?: RequestOptions): Promise<WorklogEntryView>;
  updateWorklogEntry(
    entryId: string,
    input: WorklogEntryInput,
    options?: RequestOptions,
  ): Promise<WorklogEntryView>;
  deleteWorklogEntry(entryId: string, options?: RequestOptions): Promise<void>;
  submitWorklogDay(date: string, options?: RequestOptions): Promise<WorklogSubmission>;
  listPendingWorklogReviews(): Promise<WorklogSubmission[]>;
  reviewWorklogSubmission(
    submissionId: string,
    action: 'approve' | 'return',
    comment: string,
    options?: RequestOptions,
  ): Promise<WorklogSubmission>;
  getWeeklyReport(weekStart: string): Promise<{ report: WeeklyReport | null }>;
  generateWeeklyReport(weekStart: string, options?: RequestOptions): Promise<WeeklyReport>;
  updateWeeklyReportNotes(
    weekStart: string,
    input: { issues: string; nextWeekPlan: string },
    options?: RequestOptions,
  ): Promise<WeeklyReport>;
  submitWeeklyReport(weekStart: string, options?: RequestOptions): Promise<WeeklyReport>;
  listPendingReportReviews(): Promise<WeeklyReport[]>;
  reviewWeeklyReport(
    reportId: string,
    action: 'approve' | 'return',
    comment: string,
    options?: RequestOptions,
  ): Promise<WeeklyReport>;
  listCollabChannels(limit?: number): Promise<CollabChannel[]>;
  createCollabChannel(
    input: { orgUnitId: string; scope: string; name: string; topic: string },
    options?: RequestOptions,
  ): Promise<CollabChannel>;
  getCollabChannel(channelId: string): Promise<CollabChannelDetail>;
  listChannelMembers(channelId: string): Promise<CollabMember[]>;
  addChannelMember(
    channelId: string,
    input: { personId: string; role: string },
    options?: RequestOptions,
  ): Promise<CollabMember>;
  removeChannelMember(
    channelId: string,
    personId: string,
    options?: RequestOptions,
  ): Promise<CollabRevocation>;
  archiveChannel(channelId: string, options?: RequestOptions): Promise<CollabRevocation>;
  listCollabPosts(channelId: string, limit?: number): Promise<CollabPost[]>;
  createCollabPost(
    channelId: string,
    input: { title: string; body: string; mentionPersonIds: string[] },
    options?: RequestOptions,
  ): Promise<CollabPost>;
  getCollabPost(postId: string): Promise<CollabPostDetail>;
  addCollabComment(
    postId: string,
    input: { body: string; mentionPersonIds: string[] },
    options?: RequestOptions,
  ): Promise<CollabComment>;
  acknowledgeCollabPost(postId: string, options?: RequestOptions): Promise<void>;
  pinCollabPost(postId: string, options?: RequestOptions): Promise<void>;
  archiveCollabPost(postId: string, options?: RequestOptions): Promise<void>;
  listCollabProjects(limit?: number): Promise<CollabProject[]>;
  createCollabProject(
    input: { orgUnitId: string; name: string; description: string },
    options?: RequestOptions,
  ): Promise<CollabProject>;
  listCollabFileLinks(
    target: { channelId?: string; projectId?: string; limit?: number },
  ): Promise<CollabFileLink[]>;
  linkCollabFile(
    input: { channelId?: string | null; postId?: string | null; projectId?: string | null; fileId: string },
    options?: RequestOptions,
  ): Promise<CollabFileLink>;
  listCollabTasks(
    filter?: { projectId?: string; status?: string; limit?: number },
  ): Promise<CollabTask[]>;
  createCollabTask(
    input: {
      projectId?: string | null;
      orgUnitId?: string | null;
      milestoneId?: string | null;
      title: string;
      description: string;
      assigneePersonId?: string | null;
      dueDate?: string | null;
    },
    options?: RequestOptions,
  ): Promise<CollabTask>;
  updateCollabTaskStatus(
    taskId: string,
    status: 'todo' | 'doing' | 'done',
    options?: RequestOptions,
  ): Promise<CollabTask>;
  listMilestones(projectId: string): Promise<CollabMilestone[]>;
  createMilestone(
    projectId: string,
    input: { name: string; dueDate?: string | null },
    options?: RequestOptions,
  ): Promise<CollabMilestone>;
  listCollabMeetings(orgUnitId?: string, limit?: number): Promise<CollabMeeting[]>;
  createCollabMeeting(
    input: {
      orgUnitId: string;
      projectId?: string | null;
      title: string;
      meetingDate: string;
      notes: string;
    },
    options?: RequestOptions,
  ): Promise<CollabMeeting>;
  getCollabMeeting(meetingId: string): Promise<CollabMeetingDetail>;
  addActionItem(
    meetingId: string,
    input: { title: string; ownerPersonId: string; dueDate?: string | null },
    options?: RequestOptions,
  ): Promise<CollabActionItem>;
  listMyActionItems(limit?: number): Promise<CollabActionItem[]>;
  completeActionItem(actionItemId: string, options?: RequestOptions): Promise<CollabActionItem>;
  listCollabDecisions(orgUnitId?: string, limit?: number): Promise<CollabDecision[]>;
  createCollabDecision(
    input: {
      orgUnitId: string;
      meetingId?: string | null;
      title: string;
      body: string;
      decidedAt?: string | null;
    },
    options?: RequestOptions,
  ): Promise<CollabDecision>;
  listCollabPages(orgUnitId?: string, limit?: number): Promise<CollabPage[]>;
  createCollabPage(
    input: { orgUnitId: string; parentId?: string | null; title: string; body: string },
    options?: RequestOptions,
  ): Promise<CollabPage>;
  updateCollabPage(
    pageId: string,
    input: { orgUnitId: string; title: string; body: string },
    options?: RequestOptions,
  ): Promise<CollabPage>;
  createCollabShareLink(
    input: { resourceType: string; resourceId: string; expiresAt?: string | null },
    options?: RequestOptions,
  ): Promise<{ id: string; token: string; resourceType: string; resourceId: string; expiresAt?: string | null }>;
  resolveCollabShareLink(token: string): Promise<{ resourceType: string; resourceId: string; channelId?: string | null }>;
  listNotifications(limit?: number): Promise<CollabInbox>;
  markNotificationRead(notificationId: string, options?: RequestOptions): Promise<void>;
  searchCollaboration(q: string, limit?: number): Promise<CollabSearchHit[]>;
  listInsightDepartments(): Promise<InsightDepartmentOption[]>;
  getWorkInsight(query: {
    orgUnitId?: string;
    from: string;
    to: string;
  }): Promise<InsightOverview>;
  getWorkInsightPeople(query: {
    orgUnitId?: string;
    from: string;
    to: string;
  }): Promise<InsightDrilldown>;
  getInsightSettings(): Promise<InsightSetting>;
  updateInsightSettings(minGroupSize: number, options?: RequestOptions): Promise<InsightSetting>;
  listWorkflowDefinitions(): Promise<WorkflowDefinition[]>;
  createWorkflowDefinition(
    input: { key: string; name: string; schema: string },
    options?: RequestOptions,
  ): Promise<WorkflowDefinition>;
  updateWorkflowDefinition(
    definitionId: string,
    input: { name: string; schema: string },
    options?: RequestOptions,
  ): Promise<WorkflowDefinition>;
  publishWorkflowDefinition(definitionId: string, options?: RequestOptions): Promise<WorkflowDefinition>;
  retireWorkflowDefinition(definitionId: string, options?: RequestOptions): Promise<WorkflowDefinition>;
  startWorkflowInstance(
    input: {
      definitionKey: string;
      businessType: string;
      businessId?: string;
      businessKey?: string;
      variables?: Record<string, unknown>;
    },
    options?: RequestOptions,
  ): Promise<WorkflowInstance>;
  getWorkflowInstance(instanceId: string): Promise<WorkflowInstanceDetail>;
  withdrawWorkflowInstance(instanceId: string, options?: RequestOptions): Promise<WorkflowInstance>;
  cancelWorkflowInstance(instanceId: string, options?: RequestOptions): Promise<WorkflowInstance>;
  listWorkflowTasks(scope?: 'mine' | 'all'): Promise<WorkflowTask[]>;
  claimWorkflowTask(taskId: string, options?: RequestOptions): Promise<WorkflowTask>;
  completeWorkflowTask(
    taskId: string,
    input: { action: 'approve' | 'return'; comment?: string },
    options?: RequestOptions,
  ): Promise<WorkflowTask>;
  transferWorkflowTask(
    taskId: string,
    input: { targetPersonId: string; comment?: string },
    options?: RequestOptions,
  ): Promise<WorkflowTask>;
  listWorkflowExceptions(): Promise<WorkflowException[]>;
  resolveWorkflowException(
    exceptionId: string,
    input: { action: string; assigneePersonId?: string },
    options?: RequestOptions,
  ): Promise<WorkflowException>;
}

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function queryString(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      search.set(key, value);
    }
  }
  const query = search.toString();
  return query ? `?${query}` : '';
}

function fallbackId(prefix: string): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const fetchImpl = options.fetchImpl ?? globalThis.fetch;
  if (typeof fetchImpl !== 'function') {
    throw new Error('fetch implementation is required');
  }
  const correlationIdFactory = options.correlationIdFactory ?? (() => fallbackId('cor'));
  const idempotencyKeyFactory = options.idempotencyKeyFactory ?? (() => fallbackId('idem'));

  async function request<T>(
    path: string,
    init: RequestInit = {},
    requestOptions: RequestOptions = {},
  ): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json, application/problem+json');
    headers.set('X-Correlation-Id', correlationIdFactory());

    const identity = options.identityProvider?.() ?? null;
    if (identity) {
      headers.set('X-Tenant-Id', identity.tenantId);
      headers.set('X-Subject-Id', identity.subjectId);
    }

    const method = (init.method ?? 'GET').toUpperCase();
    if (
      MUTATING_METHODS.has(method) &&
      !requestOptions.skipIdempotencyKey &&
      !headers.has('Idempotency-Key')
    ) {
      headers.set('Idempotency-Key', requestOptions.idempotencyKey ?? idempotencyKeyFactory());
    }

    if (init.body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }

    const response = await fetchImpl(`${options.baseUrl}${path}`, { ...init, headers });

    if (!response.ok) {
      let problem: ProblemDetails | undefined;
      const contentType = response.headers.get('content-type') ?? '';
      if (contentType.includes('problem+json')) {
        const payload: unknown = await response.json();
        if (isProblemDetails(payload)) {
          problem = payload;
        }
      }
      throw new ApiProblemError(response.status, problem);
    }

    if (response.status === 204) {
      return undefined as T;
    }

    return (await response.json()) as T;
  }

  function postJson<T>(path: string, body: unknown, requestOptions?: RequestOptions): Promise<T> {
    return request<T>(
      path,
      { method: 'POST', body: JSON.stringify(body) },
      requestOptions ?? {},
    );
  }

  return {
    request,
    getSystemHealth: () => request<HealthResponse>('/api/v1/system/health'),
    createJob: (input, requestOptions) =>
      postJson<Job>('/api/v1/system/jobs', input, requestOptions),
    listJobs: (limit) => {
      const query = limit === undefined ? '' : `?limit=${encodeURIComponent(String(limit))}`;
      return request<Job[]>(`/api/v1/system/jobs${query}`);
    },
    getJob: (jobId) => request<Job>(`/api/v1/system/jobs/${encodeURIComponent(jobId)}`),
    cancelJob: (jobId) =>
      request<Job>(`/api/v1/system/jobs/${encodeURIComponent(jobId)}/cancel`, { method: 'POST' }),
    createUploadSession: (input, requestOptions) =>
      postJson<UploadSession>('/api/v1/system/files/uploads', input, requestOptions),
    completeUpload: (fileId, requestOptions) =>
      postJson<FileMetadata>(
        `/api/v1/system/files/${encodeURIComponent(fileId)}/complete`,
        {},
        requestOptions,
      ),
    listFiles: () => request<FileMetadata[]>('/api/v1/system/files'),
    getDownloadUrl: (fileId) =>
      request<DownloadGrant>(`/api/v1/system/files/${encodeURIComponent(fileId)}/download`),
    listDeadLetters: () => request<DeadLetter[]>('/api/v1/system/outbox/dead-letters'),
    replayDeadLetter: (eventId, reason, requestOptions) =>
      postJson<ReplayAccepted>(
        `/api/v1/system/outbox/dead-letters/${encodeURIComponent(eventId)}/replay`,
        { reason },
        requestOptions,
      ),
    getCapabilities: () => request<Capabilities>('/api/v1/me/capabilities'),
    listDepartments: () => request<DepartmentSummary[]>('/api/v1/departments'),
    getDepartmentWorkspace: (orgUnitId) =>
      request<DepartmentWorkspace>(
        `/api/v1/departments/${encodeURIComponent(orgUnitId)}/workspace`,
      ),
    updateDepartmentSettings: (orgUnitId, input, requestOptions) =>
      request<DepartmentSettingInput>(
        `/api/v1/departments/${encodeURIComponent(orgUnitId)}/settings`,
        { method: 'PUT', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    getMyWorkPage: () => request<WorkPage>('/api/v1/me/work-page'),
    getPersonWorkPage: (personId) =>
      request<WorkPage>(`/api/v1/people/${encodeURIComponent(personId)}/work-page`),
    listMyWorkAccessLog: (limit) => {
      const query = limit === undefined ? '' : `?limit=${encodeURIComponent(String(limit))}`;
      return request<WorkAccessLogRow[]>(`/api/v1/me/work-access-log${query}`);
    },
    getWorklogDay: (date) =>
      request<WorklogDay>(`/api/v1/worklog/days/${encodeURIComponent(date)}`),
    createWorklogEntry: (input, requestOptions) =>
      postJson<WorklogEntryView>('/api/v1/worklog/entries', input, requestOptions),
    updateWorklogEntry: (entryId, input, requestOptions) =>
      request<WorklogEntryView>(
        `/api/v1/worklog/entries/${encodeURIComponent(entryId)}`,
        { method: 'PATCH', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    deleteWorklogEntry: (entryId, requestOptions) =>
      request<void>(`/api/v1/worklog/entries/${encodeURIComponent(entryId)}`,
        { method: 'DELETE' }, requestOptions ?? {}),
    submitWorklogDay: (date, requestOptions) =>
      postJson<WorklogSubmission>(
        `/api/v1/worklog/days/${encodeURIComponent(date)}/submit`, {}, requestOptions),
    listPendingWorklogReviews: () =>
      request<WorklogSubmission[]>('/api/v1/worklog/reviews'),
    reviewWorklogSubmission: (submissionId, action, comment, requestOptions) =>
      postJson<WorklogSubmission>(
        `/api/v1/worklog/submissions/${encodeURIComponent(submissionId)}/review`,
        { action, comment }, requestOptions),
    getWeeklyReport: (weekStart) =>
      request<{ report: WeeklyReport | null }>(
        `/api/v1/worklog/reports/${encodeURIComponent(weekStart)}`),
    generateWeeklyReport: (weekStart, requestOptions) =>
      postJson<WeeklyReport>(
        `/api/v1/worklog/reports/${encodeURIComponent(weekStart)}/generate`, {}, requestOptions),
    updateWeeklyReportNotes: (weekStart, input, requestOptions) =>
      request<WeeklyReport>(
        `/api/v1/worklog/reports/${encodeURIComponent(weekStart)}`,
        { method: 'PATCH', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    submitWeeklyReport: (weekStart, requestOptions) =>
      postJson<WeeklyReport>(
        `/api/v1/worklog/reports/${encodeURIComponent(weekStart)}/submit`, {}, requestOptions),
    listPendingReportReviews: () =>
      request<WeeklyReport[]>('/api/v1/worklog/reports/reviews'),
    reviewWeeklyReport: (reportId, action, comment, requestOptions) =>
      postJson<WeeklyReport>(
        `/api/v1/worklog/reports/${encodeURIComponent(reportId)}/review`,
        { action, comment }, requestOptions),
    listCollabChannels: (limit) => {
      const query = limit === undefined ? '' : `?limit=${encodeURIComponent(String(limit))}`;
      return request<CollabChannel[]>(`/api/v1/collaboration/channels${query}`);
    },
    createCollabChannel: (input, requestOptions) =>
      postJson<CollabChannel>('/api/v1/collaboration/channels', input, requestOptions),
    getCollabChannel: (channelId) =>
      request<CollabChannelDetail>(
        `/api/v1/collaboration/channels/${encodeURIComponent(channelId)}`),
    listChannelMembers: (channelId) =>
      request<CollabMember[]>(
        `/api/v1/collaboration/channels/${encodeURIComponent(channelId)}/members`),
    addChannelMember: (channelId, input, requestOptions) =>
      postJson<CollabMember>(
        `/api/v1/collaboration/channels/${encodeURIComponent(channelId)}/members`,
        input, requestOptions),
    removeChannelMember: (channelId, personId, requestOptions) =>
      request<CollabRevocation>(
        `/api/v1/collaboration/channels/${encodeURIComponent(channelId)}/members/${encodeURIComponent(personId)}`,
        { method: 'DELETE' }, requestOptions ?? {}),
    archiveChannel: (channelId, requestOptions) =>
      postJson<CollabRevocation>(
        `/api/v1/collaboration/channels/${encodeURIComponent(channelId)}/archive`,
        {}, requestOptions),
    listCollabPosts: (channelId, limit) => {
      const query = limit === undefined ? '' : `?limit=${encodeURIComponent(String(limit))}`;
      return request<CollabPost[]>(
        `/api/v1/collaboration/channels/${encodeURIComponent(channelId)}/posts${query}`);
    },
    createCollabPost: (channelId, input, requestOptions) =>
      postJson<CollabPost>(
        `/api/v1/collaboration/channels/${encodeURIComponent(channelId)}/posts`,
        input, requestOptions),
    getCollabPost: (postId) =>
      request<CollabPostDetail>(`/api/v1/collaboration/posts/${encodeURIComponent(postId)}`),
    addCollabComment: (postId, input, requestOptions) =>
      postJson<CollabComment>(
        `/api/v1/collaboration/posts/${encodeURIComponent(postId)}/comments`,
        input, requestOptions),
    acknowledgeCollabPost: (postId, requestOptions) =>
      postJson<void>(`/api/v1/collaboration/posts/${encodeURIComponent(postId)}/receipts`,
        {}, requestOptions),
    pinCollabPost: (postId, requestOptions) =>
      postJson<void>(`/api/v1/collaboration/posts/${encodeURIComponent(postId)}/pin`,
        {}, requestOptions),
    archiveCollabPost: (postId, requestOptions) =>
      postJson<void>(`/api/v1/collaboration/posts/${encodeURIComponent(postId)}/archive`,
        {}, requestOptions),
    listCollabProjects: (limit) => {
      const query = limit === undefined ? '' : `?limit=${encodeURIComponent(String(limit))}`;
      return request<CollabProject[]>(`/api/v1/collaboration/projects${query}`);
    },
    createCollabProject: (input, requestOptions) =>
      postJson<CollabProject>('/api/v1/collaboration/projects', input, requestOptions),
    listCollabFileLinks: (target) => {
      const params = new URLSearchParams();
      if (target.channelId) {
        params.set('channelId', target.channelId);
      }
      if (target.projectId) {
        params.set('projectId', target.projectId);
      }
      if (target.limit !== undefined) {
        params.set('limit', String(target.limit));
      }
      const query = params.toString();
      return request<CollabFileLink[]>(
        `/api/v1/collaboration/files${query ? `?${query}` : ''}`);
    },
    linkCollabFile: (input, requestOptions) =>
      postJson<CollabFileLink>('/api/v1/collaboration/files', input, requestOptions),
    listCollabTasks: (filter) => {
      const params = new URLSearchParams();
      if (filter?.projectId) {
        params.set('projectId', filter.projectId);
      }
      if (filter?.status) {
        params.set('status', filter.status);
      }
      if (filter?.limit !== undefined) {
        params.set('limit', String(filter.limit));
      }
      const query = params.toString();
      return request<CollabTask[]>(`/api/v1/collaboration/tasks${query ? `?${query}` : ''}`);
    },
    createCollabTask: (input, requestOptions) =>
      postJson<CollabTask>('/api/v1/collaboration/tasks', input, requestOptions),
    updateCollabTaskStatus: (taskId, status, requestOptions) =>
      request<CollabTask>(
        `/api/v1/collaboration/tasks/${encodeURIComponent(taskId)}/status`,
        { method: 'PATCH', body: JSON.stringify({ status }) }, requestOptions ?? {}),
    listMilestones: (projectId) =>
      request<CollabMilestone[]>(
        `/api/v1/collaboration/projects/${encodeURIComponent(projectId)}/milestones`),
    createMilestone: (projectId, input, requestOptions) =>
      postJson<CollabMilestone>(
        `/api/v1/collaboration/projects/${encodeURIComponent(projectId)}/milestones`,
        input, requestOptions),
    listCollabMeetings: (orgUnitId, limit) => {
      const params = new URLSearchParams();
      if (orgUnitId) {
        params.set('orgUnitId', orgUnitId);
      }
      if (limit !== undefined) {
        params.set('limit', String(limit));
      }
      const query = params.toString();
      return request<CollabMeeting[]>(
        `/api/v1/collaboration/meetings${query ? `?${query}` : ''}`);
    },
    createCollabMeeting: (input, requestOptions) =>
      postJson<CollabMeeting>('/api/v1/collaboration/meetings', input, requestOptions),
    getCollabMeeting: (meetingId) =>
      request<CollabMeetingDetail>(
        `/api/v1/collaboration/meetings/${encodeURIComponent(meetingId)}`),
    addActionItem: (meetingId, input, requestOptions) =>
      postJson<CollabActionItem>(
        `/api/v1/collaboration/meetings/${encodeURIComponent(meetingId)}/action-items`,
        input, requestOptions),
    listMyActionItems: (limit) => {
      const query = limit === undefined ? '' : `?limit=${encodeURIComponent(String(limit))}`;
      return request<CollabActionItem[]>(`/api/v1/collaboration/action-items${query}`);
    },
    completeActionItem: (actionItemId, requestOptions) =>
      postJson<CollabActionItem>(
        `/api/v1/collaboration/action-items/${encodeURIComponent(actionItemId)}/complete`,
        {}, requestOptions),
    listCollabDecisions: (orgUnitId, limit) => {
      const params = new URLSearchParams();
      if (orgUnitId) {
        params.set('orgUnitId', orgUnitId);
      }
      if (limit !== undefined) {
        params.set('limit', String(limit));
      }
      const query = params.toString();
      return request<CollabDecision[]>(
        `/api/v1/collaboration/decisions${query ? `?${query}` : ''}`);
    },
    createCollabDecision: (input, requestOptions) =>
      postJson<CollabDecision>('/api/v1/collaboration/decisions', input, requestOptions),
    listCollabPages: (orgUnitId, limit) => {
      const params = new URLSearchParams();
      if (orgUnitId) {
        params.set('orgUnitId', orgUnitId);
      }
      if (limit !== undefined) {
        params.set('limit', String(limit));
      }
      const query = params.toString();
      return request<CollabPage[]>(
        `/api/v1/collaboration/pages${query ? `?${query}` : ''}`);
    },
    createCollabPage: (input, requestOptions) =>
      postJson<CollabPage>('/api/v1/collaboration/pages', input, requestOptions),
    updateCollabPage: (pageId, input, requestOptions) =>
      request<CollabPage>(
        `/api/v1/collaboration/pages/${encodeURIComponent(pageId)}`,
        { method: 'PATCH', body: JSON.stringify(input) }, requestOptions ?? {}),
    createCollabShareLink: (input, requestOptions) =>
      postJson<{
        id: string;
        token: string;
        resourceType: string;
        resourceId: string;
        expiresAt?: string | null;
      }>('/api/v1/collaboration/share-links', input, requestOptions),
    resolveCollabShareLink: (token) =>
      request<{ resourceType: string; resourceId: string; channelId?: string | null }>(
        `/api/v1/collaboration/share-links/${encodeURIComponent(token)}`),
    listNotifications: (limit) => {
      const query = limit === undefined ? '' : `?limit=${encodeURIComponent(String(limit))}`;
      return request<CollabInbox>(`/api/v1/notifications${query}`);
    },
    markNotificationRead: (notificationId, requestOptions) =>
      postJson<void>(
        `/api/v1/notifications/${encodeURIComponent(notificationId)}/read`,
        {}, requestOptions),
    searchCollaboration: (q, limit) => {
      const params = new URLSearchParams({ q });
      if (limit !== undefined) {
        params.set('limit', String(limit));
      }
      return request<CollabSearchHit[]>(`/api/v1/search?${params.toString()}`);
    },
    listInsightDepartments: () =>
      request<InsightDepartmentOption[]>('/api/v1/insights/departments'),
    getWorkInsight: (query) =>
      request<InsightOverview>(
        `/api/v1/insights/work${queryString({
          orgUnitId: query.orgUnitId,
          from: query.from,
          to: query.to,
        })}`,
      ),
    getWorkInsightPeople: (query) =>
      request<InsightDrilldown>(
        `/api/v1/insights/work/people${queryString({
          orgUnitId: query.orgUnitId,
          from: query.from,
          to: query.to,
        })}`,
      ),
    getInsightSettings: () => request<InsightSetting>('/api/v1/insights/settings'),
    updateInsightSettings: (minGroupSize, requestOptions) =>
      request<InsightSetting>(
        '/api/v1/insights/settings',
        { method: 'PUT', body: JSON.stringify({ minGroupSize }) },
        requestOptions ?? {},
      ),
    listWorkflowDefinitions: () =>
      request<WorkflowDefinition[]>('/api/v1/workflow/definitions'),
    createWorkflowDefinition: (input, requestOptions) =>
      request<WorkflowDefinition>(
        '/api/v1/workflow/definitions',
        { method: 'POST', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    updateWorkflowDefinition: (definitionId, input, requestOptions) =>
      request<WorkflowDefinition>(
        `/api/v1/workflow/definitions/${encodeURIComponent(definitionId)}`,
        { method: 'PUT', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    publishWorkflowDefinition: (definitionId, requestOptions) =>
      request<WorkflowDefinition>(
        `/api/v1/workflow/definitions/${encodeURIComponent(definitionId)}/publish`,
        { method: 'POST' },
        requestOptions ?? {},
      ),
    retireWorkflowDefinition: (definitionId, requestOptions) =>
      request<WorkflowDefinition>(
        `/api/v1/workflow/definitions/${encodeURIComponent(definitionId)}/retire`,
        { method: 'POST' },
        requestOptions ?? {},
      ),
    startWorkflowInstance: (input, requestOptions) =>
      request<WorkflowInstance>(
        '/api/v1/workflow/instances',
        { method: 'POST', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    getWorkflowInstance: (instanceId) =>
      request<WorkflowInstanceDetail>(
        `/api/v1/workflow/instances/${encodeURIComponent(instanceId)}`,
      ),
    withdrawWorkflowInstance: (instanceId, requestOptions) =>
      request<WorkflowInstance>(
        `/api/v1/workflow/instances/${encodeURIComponent(instanceId)}/withdraw`,
        { method: 'POST' },
        requestOptions ?? {},
      ),
    cancelWorkflowInstance: (instanceId, requestOptions) =>
      request<WorkflowInstance>(
        `/api/v1/workflow/instances/${encodeURIComponent(instanceId)}/cancel`,
        { method: 'POST' },
        requestOptions ?? {},
      ),
    listWorkflowTasks: (scope = 'mine') =>
      request<WorkflowTask[]>(`/api/v1/workflow/tasks?scope=${scope}`),
    claimWorkflowTask: (taskId, requestOptions) =>
      request<WorkflowTask>(
        `/api/v1/workflow/tasks/${encodeURIComponent(taskId)}/claim`,
        { method: 'POST' },
        requestOptions ?? {},
      ),
    completeWorkflowTask: (taskId, input, requestOptions) =>
      request<WorkflowTask>(
        `/api/v1/workflow/tasks/${encodeURIComponent(taskId)}/complete`,
        { method: 'POST', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    transferWorkflowTask: (taskId, input, requestOptions) =>
      request<WorkflowTask>(
        `/api/v1/workflow/tasks/${encodeURIComponent(taskId)}/transfer`,
        { method: 'POST', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    listWorkflowExceptions: () =>
      request<WorkflowException[]>('/api/v1/workflow/exceptions'),
    resolveWorkflowException: (exceptionId, input, requestOptions) =>
      request<WorkflowException>(
        `/api/v1/workflow/exceptions/${encodeURIComponent(exceptionId)}/resolve`,
        { method: 'POST', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
  };
}
