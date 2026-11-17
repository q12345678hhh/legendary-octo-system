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

/** 当前登录身份：租户 + 主体，由真实登录返回后写入页面会话。 */
export interface PlatformIdentity {
  tenantId: string;
  subjectId: string;
}

/** 登录成功后返回的会话主体；actorId 即后续请求头里的 subjectId。 */
export interface SessionPrincipal {
  tenantId: string;
  actorId: string;
  userId: string;
  memberId: string;
  sessionId?: string;
  actorType?: string;
  email?: string | null;
  displayName?: string | null;
  owner?: boolean;
  admin?: boolean;
}

/** 登录结果：需要 MFA 时只返回票据，不签发会话 Cookie。 */
export type LoginResult =
  | { status: 'session'; principal: SessionPrincipal; sessionExpiresAt?: string | null }
  | { status: 'mfa_required'; mfaToken: string };

export interface SignInInput {
  tenantId: string;
  email: string;
  password: string;
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
  /** 当前租户已启用的模块 code；旧后端可能缺省，缺失时前端不过滤模块。 */
  enabledModules?: string[];
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
  sourceType?: 'manual' | 'voice';
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
  content: WeeklyReportContent;
  submittedAt?: string | null;
}

export interface WeeklyReportSummaryPoint {
  text: string;
  entryIds: string[];
}

export interface WeeklyReportAnomaly {
  code: string;
  message: string;
  date?: string | null;
  entryId?: string | null;
}

export interface WeeklyReportGeneration {
  mode: 'ai' | 'fallback';
  provider: string;
  model: string;
  promptVersion: string;
  generatedAt: string;
  fallbackReason?: string | null;
  droppedClaims: number;
  truncated: boolean;
}

export interface WeeklyReportDay {
  date: string;
  minutes: number;
  submissionId: string;
  entryIds?: string[];
}

export interface WeeklyReportEntry {
  entryId: string;
  date: string;
  minutes: number;
  projectId?: string | null;
  taskId?: string | null;
  description?: string;
}

export interface WeeklyReportItem {
  key: string;
  minutes: number;
  entryIds?: string[];
}

export interface WeeklyReportContent {
  schemaVersion?: number;
  weekStart?: string;
  weekEnd?: string;
  days?: WeeklyReportDay[];
  entries?: WeeklyReportEntry[];
  items?: WeeklyReportItem[];
  summary?: string;
  summaryPoints?: WeeklyReportSummaryPoint[];
  summarySource?: 'ai' | 'fallback' | 'employee';
  highlights?: WeeklyReportSummaryPoint[];
  anomalies?: WeeklyReportAnomaly[];
  nextWeekPlan?: string;
  nextWeekPlanSource?: 'ai-suggested' | 'employee' | 'empty';
  issues?: string;
  generation?: WeeklyReportGeneration;
}

export type ExpenseDocType = 'invoice' | 'receipt' | 'bill';

export type ExpenseStatus = 'manual_review' | 'approved' | 'posted' | 'rejected' | 'voided';

export type ExpenseOcrStatus =
  | 'OK'
  | 'UNAVAILABLE'
  | 'TIMEOUT'
  | 'INVALID'
  | 'PDF_UNSUPPORTED'
  | 'MANUAL';

export interface ExpenseFinding {
  code: string;
  status: 'pass' | 'fail' | 'blocked' | 'skipped';
  message: string;
  actual?: string | null;
  expected?: string | null;
  confidence?: number | null;
}

export interface ExpenseExtractionSummary {
  id: string;
  status: ExpenseOcrStatus;
  provider?: string | null;
  model?: string | null;
  errorCode?: string | null;
  ocrText?: string | null;
  createdAt: string;
}

export interface ExpenseDocument {
  id: string;
  uploaderPersonId: string;
  fileId: string;
  docType: ExpenseDocType;
  status: ExpenseStatus;
  currency?: string | null;
  amount?: number | null;
  invoiceCode?: string | null;
  invoiceNo?: string | null;
  vendorName?: string | null;
  vendorTaxNo?: string | null;
  occurredOn?: string | null;
  category?: string | null;
  findings: ExpenseFinding[];
  confidence: Record<string, number>;
  ruleVersion: string;
  policyVersion: number;
  extractionId?: string | null;
  postedAt?: string | null;
  postedBy?: string | null;
  postActorKind?: 'ai' | 'employee' | null;
  voidedAt?: string | null;
  voidedBy?: string | null;
  voidReason?: string | null;
  createdAt: string;
}

export interface ExpenseDocumentView {
  document: ExpenseDocument;
  extraction?: ExpenseExtractionSummary | null;
}

export interface ExpenseAuditEvent {
  id: string;
  documentId?: string | null;
  action: string;
  actorPersonId?: string | null;
  actorKind: 'employee' | 'reviewer' | 'poster' | 'ai' | 'system';
  detail: Record<string, unknown>;
  occurredAt: string;
}

export interface ExpensePolicy {
  autoPostEnabled: boolean;
  autoPostLimit: number;
  windowDays: number;
  legalTitle?: string | null;
  taxId?: string | null;
  vendorAllowlist: string[];
  manualCategories: string[];
  allowedCategories: string[];
  minConfidence: number;
  verifyRequired: boolean;
  version: number;
}

export interface ExpensePolicyInput {
  autoPostEnabled: boolean;
  autoPostLimit: number;
  windowDays: number;
  legalTitle?: string;
  taxId?: string;
  vendorAllowlist: string[];
  manualCategories: string[];
  allowedCategories: string[];
  minConfidence: number;
  verifyRequired: boolean;
}

export type ExpenseBatchScope = 'vendor' | 'uploader' | 'amount';

export interface ExpenseBatchHold {
  id: string;
  scopeType: ExpenseBatchScope;
  scopeKey: string;
  reason: string;
  windowHours: number;
  documentCount: number;
  amountTotal?: number | null;
  status: 'active' | 'released';
  createdBy?: string | null;
  createdAt: string;
  releasedBy?: string | null;
  releasedAt?: string | null;
  releaseNote?: string | null;
}

export interface ExpenseLineItem {
  description?: string | null;
  amount?: number | null;
}

export type PrivacyDataClass =
  | 'file'
  | 'worklog_entry'
  | 'worklog_weekly_report'
  | 'worklog_voice_capture'
  | 'expense_document'
  | 'expense_extraction'
  | 'audit';

export interface PrivacyDataClassInfo {
  dataClass: PrivacyDataClass;
  purpose: string;
  retentionDays: number;
  retentionNote: string;
  accessRoles: string[];
}

export type PrivacyResidency = 'cn' | 'eu' | 'us' | 'sg';

export interface PrivacySupplier {
  surface: string;
  provider: string;
  model: string;
  configured: boolean;
}

export interface PrivacySettings {
  dataResidency: PrivacyResidency;
  trainingOptOut: boolean;
  voiceTranscriptRetentionDays: number;
  expenseOcrRetentionDays: number;
  updatedBy?: string | null;
  updatedAt?: string | null;
  suppliers: PrivacySupplier[];
}

export interface PrivacySettingsInput {
  dataResidency: PrivacyResidency;
  trainingOptOut: boolean;
  voiceTranscriptRetentionDays: number;
  expenseOcrRetentionDays: number;
}

export interface PrivacyVoiceData {
  id: string;
  workDate: string;
  status: string;
  provider?: string | null;
  model?: string | null;
  audioDeletedAt?: string | null;
  createdAt?: string | null;
  transcribed: boolean;
}

export interface PrivacyExpenseData {
  id: string;
  docType: string;
  status: string;
  amount?: number | null;
  currency?: string | null;
  createdAt?: string | null;
}

export interface PrivacyMyData {
  personId: string;
  voiceCaptures: PrivacyVoiceData[];
  expenseDocuments: PrivacyExpenseData[];
}

export interface PrivacyVoiceExport extends PrivacyVoiceData {
  transcriptText?: string | null;
  editedText?: string | null;
}

export interface PrivacyExpenseExport extends PrivacyExpenseData {
  vendorName?: string | null;
  invoiceCode?: string | null;
  invoiceNo?: string | null;
  occurredOn?: string | null;
  extraction?: {
    id: string;
    status: string;
    provider?: string | null;
    model?: string | null;
    errorCode?: string | null;
    ocrText?: string | null;
    createdAt?: string | null;
  } | null;
}

export interface PrivacyDataExport {
  personId: string;
  generatedAt: string;
  voiceCaptures: PrivacyVoiceExport[];
  expenseDocuments: PrivacyExpenseExport[];
}

export interface PrivacyDeleteInput {
  voiceCaptureIds?: string[];
  expenseDocumentIds?: string[];
}

export interface PrivacyDeleteAction {
  id: string;
  dataClass: 'worklog_voice_capture' | 'expense_document';
  action: 'recycled' | 'anonymized';
}

export interface PrivacyDeleteResult {
  actions: PrivacyDeleteAction[];
}

export interface ExpenseManualFields {
  amount?: number | null;
  currency?: string | null;
  invoiceCode?: string | null;
  invoiceNo?: string | null;
  vendorName?: string | null;
  vendorTaxNo?: string | null;
  occurredOn?: string | null;
  category?: string | null;
  title?: string | null;
  lines?: ExpenseLineItem[];
}

export interface CreateExpenseDocumentInput {
  fileId: string;
  docType: ExpenseDocType;
  fields?: ExpenseManualFields | null;
}

export interface ExpenseReviewOverride {
  ruleCode: string;
  reason: string;
}

export interface ExpenseReviewInput {
  decision: 'approve' | 'reject';
  comment?: string;
  overrides?: ExpenseReviewOverride[];
}

export interface VoiceCapture {
  id: string;
  status: 'uploaded' | 'transcribed' | 'confirmed' | 'discarded' | 'failed';
  uploadUrl?: string | null;
  expiresAt?: string | null;
  transcript?: string | null;
  editedText?: string | null;
  provider?: string | null;
  model?: string | null;
  confidence?: number | null;
  transcribedAt?: string | null;
  worklogEntryId?: string | null;
  errorCode?: string | null;
}

export interface VoiceCaptureInput {
  workDate: string;
  contentType: string;
  sizeBytes: number;
  durationSeconds?: number | null;
  clientRequestId: string;
}

export interface VoiceConfirmInput {
  text: string;
  durationMinutes?: number | null;
  startsAt?: string | null;
  endsAt?: string | null;
  projectId?: string | null;
  taskId?: string | null;
  description?: string | null;
  clientEntryId: string;
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

export type AppModuleHealth = 'healthy' | 'degraded' | 'offline' | 'unknown';
export type AppModuleInstallStatus = 'not_installed' | 'installed' | 'disabled';
export type AppModuleScope = 'tenant' | 'department' | 'project' | 'person';
export type ModuleActivationStatus = 'enabled' | 'disabled';

export interface AppModule {
  id: string;
  code: string;
  name: string;
  category: string;
  description: string;
  templateKey?: string | null;
  ownerPersonId?: string | null;
  version: string;
  dependencies: string;
  applicableScope: AppModuleScope;
  healthStatus: AppModuleHealth;
  installStatus: AppModuleInstallStatus;
  /** 无激活记录时为 enabled。 */
  activationStatus: ModuleActivationStatus;
  activationVersion: number;
  createdAt: string;
  updatedAt: string;
}

export interface ModuleActivation {
  moduleId: string;
  code: string;
  status: ModuleActivationStatus;
  version: number;
  config: string;
  enabledAt?: string | null;
  enabledBy?: string | null;
  disabledAt?: string | null;
  disabledBy?: string | null;
  updatedAt?: string | null;
  /** false 表示没有显式记录，按默认启用处理。 */
  explicit: boolean;
}

export interface ModuleActivationInput {
  status: ModuleActivationStatus;
  expectedVersion?: number | undefined;
  comment?: string | undefined;
}

export interface AppModuleInput {
  code: string;
  name: string;
  category?: string | undefined;
  description?: string | undefined;
  templateKey?: string | null | undefined;
  ownerPersonId?: string | null | undefined;
  version?: string | undefined;
  dependencies?: string | undefined;
  applicableScope?: AppModuleScope | undefined;
  healthStatus?: AppModuleHealth | undefined;
  installStatus?: AppModuleInstallStatus | undefined;
}

export type ModuleSuggestionStatus =
  | 'draft'
  | 'communicating'
  | 'submitted'
  | 'configuring'
  | 'testing'
  | 'rectifying'
  | 'test_failed'
  | 'pending_final_approval'
  | 'rejected'
  | 'withdrawn'
  | 'enabled'
  | 'special_pending_review'
  | 'paused'
  | 'closed'
  | 'deprecated';

export interface ModuleSuggestion {
  id: string;
  title: string;
  requesterPersonId: string;
  businessProblem: string;
  goal: string;
  scope: string;
  dataScope: string;
  permissionsNeeded: string;
  costEstimate: string;
  benefitEstimate: string;
  suggestedOwnerPersonId?: string | null;
  configOwnerPersonId?: string | null;
  status: ModuleSuggestionStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ModuleSuggestionEvent {
  id: string;
  eventKind: 'transition' | 'comment' | 'notification';
  fromStatus?: string | null;
  toStatus?: string | null;
  action: string;
  actorPersonId?: string | null;
  recipientPersonId?: string | null;
  comment: string;
  occurredAt: string;
}

export interface ModuleSuggestionDetail {
  suggestion: ModuleSuggestion;
  events: ModuleSuggestionEvent[];
}

export interface ModuleSuggestionInput {
  title: string;
  businessProblem: string;
  goal?: string | undefined;
  scope?: string | undefined;
  dataScope?: string | undefined;
  permissionsNeeded?: string | undefined;
  costEstimate?: string | undefined;
  benefitEstimate?: string | undefined;
  suggestedOwnerPersonId?: string | null | undefined;
}

export interface ModuleSuggestionAction {
  action: string;
  comment?: string | undefined;
  configOwnerPersonId?: string | null | undefined;
}

export interface ApiClient {
  request<T>(path: string, init?: RequestInit, options?: RequestOptions): Promise<T>;
  signIn(input: SignInInput): Promise<LoginResult>;
  signOut(): Promise<void>;
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
  createVoiceCapture(
    input: VoiceCaptureInput,
    options?: RequestOptions,
  ): Promise<VoiceCapture>;
  transcribeVoiceCapture(captureId: string, options?: RequestOptions): Promise<VoiceCapture>;
  confirmVoiceCapture(
    captureId: string,
    input: VoiceConfirmInput,
    options?: RequestOptions,
  ): Promise<{ entry: WorklogEntryView; audioDeleted: boolean }>;
  discardVoiceCapture(captureId: string, options?: RequestOptions): Promise<void>;
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
    input: { summary?: string; issues?: string; nextWeekPlan?: string },
    options?: RequestOptions,
  ): Promise<WeeklyReport>;
  submitWeeklyReport(weekStart: string, options?: RequestOptions): Promise<WeeklyReport>;
  listWeeklyReportVersions(weekStart: string): Promise<WeeklyReport[]>;
  listPendingReportReviews(): Promise<WeeklyReport[]>;
  reviewWeeklyReport(
    reportId: string,
    action: 'approve' | 'return',
    comment: string,
    options?: RequestOptions,
  ): Promise<WeeklyReport>;
  createExpenseDocument(
    input: CreateExpenseDocumentInput,
    options?: RequestOptions,
  ): Promise<ExpenseDocumentView>;
  listExpenseDocuments(
    params?: { status?: string; limit?: number },
    options?: RequestOptions,
  ): Promise<ExpenseDocumentView[]>;
  getExpenseDocument(documentId: string): Promise<ExpenseDocumentView>;
  listExpenseDocumentAudit(documentId: string): Promise<ExpenseAuditEvent[]>;
  reviewExpenseDocument(
    documentId: string,
    input: ExpenseReviewInput,
    options?: RequestOptions,
  ): Promise<ExpenseDocumentView>;
  postExpenseDocument(
    documentId: string,
    options?: RequestOptions,
  ): Promise<ExpenseDocumentView>;
  voidExpenseDocument(
    documentId: string,
    input: { reason: string },
    options?: RequestOptions,
  ): Promise<ExpenseDocumentView>;
  getExpensePolicy(): Promise<ExpensePolicy>;
  updateExpensePolicy(
    input: ExpensePolicyInput,
    options?: RequestOptions,
  ): Promise<ExpensePolicy>;
  listExpenseHolds(
    status?: 'active' | 'released',
    options?: RequestOptions,
  ): Promise<ExpenseBatchHold[]>;
  releaseExpenseHold(
    holdId: string,
    input: { note: string },
    options?: RequestOptions,
  ): Promise<ExpenseBatchHold>;
  listPrivacyDataClasses(options?: RequestOptions): Promise<PrivacyDataClassInfo[]>;
  getPrivacySettings(options?: RequestOptions): Promise<PrivacySettings>;
  updatePrivacySettings(
    input: PrivacySettingsInput,
    options?: RequestOptions,
  ): Promise<PrivacySettings>;
  getMyPrivacyData(): Promise<PrivacyMyData>;
  exportMyPrivacyData(): Promise<PrivacyDataExport>;
  deleteMyPrivacyData(
    input: PrivacyDeleteInput,
    options?: RequestOptions,
  ): Promise<PrivacyDeleteResult>;
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
  listAppModules(query?: {
    q?: string | undefined;
    category?: string | undefined;
    health?: string | undefined;
  }): Promise<AppModule[]>;
  getAppModule(moduleId: string): Promise<AppModule>;
  createAppModule(input: AppModuleInput, options?: RequestOptions): Promise<AppModule>;
  updateAppModule(
    moduleId: string,
    input: AppModuleInput,
    options?: RequestOptions,
  ): Promise<AppModule>;
  getModuleActivation(moduleId: string): Promise<ModuleActivation>;
  setModuleActivation(
    moduleId: string,
    input: ModuleActivationInput,
    options?: RequestOptions,
  ): Promise<ModuleActivation>;
  listModuleSuggestions(scope?: 'mine' | 'all'): Promise<ModuleSuggestion[]>;
  getModuleSuggestion(suggestionId: string): Promise<ModuleSuggestionDetail>;
  createModuleSuggestion(
    input: ModuleSuggestionInput,
    options?: RequestOptions,
  ): Promise<ModuleSuggestion>;
  actOnModuleSuggestion(
    suggestionId: string,
    input: ModuleSuggestionAction,
    options?: RequestOptions,
  ): Promise<ModuleSuggestionDetail>;
}

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** CSRF Cookie 与后端 `platform.identity.csrf-cookie-name` 默认值一致，非 HttpOnly。 */
const CSRF_COOKIE_NAME = 'plt_csrf';
const CSRF_HEADER = 'X-CSRF-Token';
const TENANT_HEADER = 'X-Tenant-Id';

function readCsrfToken(): string | null {
  if (typeof document === 'undefined' || !document.cookie) {
    return null;
  }
  for (const part of document.cookie.split(';')) {
    const separator = part.indexOf('=');
    if (separator < 0) {
      continue;
    }
    if (part.slice(0, separator).trim() !== CSRF_COOKIE_NAME) {
      continue;
    }
    const value = part.slice(separator + 1).trim();
    return value ? decodeURIComponent(value) : null;
  }
  return null;
}

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
      headers.set(TENANT_HEADER, identity.tenantId);
      headers.set('X-Subject-Id', identity.subjectId);
    }

    const method = (init.method ?? 'GET').toUpperCase();
    if (MUTATING_METHODS.has(method) && !headers.has(CSRF_HEADER)) {
      // Cookie 会话的写操作必须双提交 CSRF；登录/登出没有会话时读不到 Cookie，自动跳过。
      const csrf = readCsrfToken();
      if (csrf) {
        headers.set(CSRF_HEADER, csrf);
      }
    }
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

    const response = await fetchImpl(`${options.baseUrl}${path}`, {
      ...init,
      headers,
      credentials: 'include',
    });

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
    signIn: (input) =>
      request<LoginResult>(
        '/api/v1/auth/login',
        {
          method: 'POST',
          headers: { [TENANT_HEADER]: input.tenantId },
          body: JSON.stringify({ email: input.email, password: input.password }),
        },
        { skipIdempotencyKey: true },
      ),
    signOut: () =>
      request<void>('/api/v1/auth/logout', { method: 'POST' }, { skipIdempotencyKey: true }),
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
    createVoiceCapture: (input, requestOptions) =>
      postJson<VoiceCapture>('/api/v1/worklog/voice/captures', input, requestOptions),
    transcribeVoiceCapture: (captureId, requestOptions) =>
      postJson<VoiceCapture>(
        `/api/v1/worklog/voice/captures/${encodeURIComponent(captureId)}/transcription`,
        {},
        requestOptions,
      ),
    confirmVoiceCapture: (captureId, input, requestOptions) =>
      postJson<{ entry: WorklogEntryView; audioDeleted: boolean }>(
        `/api/v1/worklog/voice/captures/${encodeURIComponent(captureId)}/confirm`,
        input,
        requestOptions,
      ),
    discardVoiceCapture: (captureId, requestOptions) =>
      request<void>(
        `/api/v1/worklog/voice/captures/${encodeURIComponent(captureId)}`,
        { method: 'DELETE' },
        requestOptions ?? {},
      ),
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
    listWeeklyReportVersions: (weekStart) =>
      request<WeeklyReport[]>(
        `/api/v1/worklog/reports/${encodeURIComponent(weekStart)}/versions`),
    listPendingReportReviews: () =>
      request<WeeklyReport[]>('/api/v1/worklog/reports/reviews'),
    reviewWeeklyReport: (reportId, action, comment, requestOptions) =>
      postJson<WeeklyReport>(
        `/api/v1/worklog/reports/${encodeURIComponent(reportId)}/review`,
        { action, comment }, requestOptions),
    createExpenseDocument: (input, requestOptions) =>
      postJson<ExpenseDocumentView>('/api/v1/expense/documents', input, requestOptions),
    listExpenseDocuments: (params, requestOptions) => {
      const query = new URLSearchParams();
      if (params?.status !== undefined) {
        query.set('status', params.status);
      }
      if (params?.limit !== undefined) {
        query.set('limit', String(params.limit));
      }
      const suffix = query.size > 0 ? `?${query.toString()}` : '';
      return request<ExpenseDocumentView[]>(
        `/api/v1/expense/documents${suffix}`, {}, requestOptions);
    },
    getExpenseDocument: (documentId) =>
      request<ExpenseDocumentView>(
        `/api/v1/expense/documents/${encodeURIComponent(documentId)}`),
    listExpenseDocumentAudit: (documentId) =>
      request<ExpenseAuditEvent[]>(
        `/api/v1/expense/documents/${encodeURIComponent(documentId)}/audit`),
    reviewExpenseDocument: (documentId, input, requestOptions) =>
      postJson<ExpenseDocumentView>(
        `/api/v1/expense/documents/${encodeURIComponent(documentId)}/review`,
        input, requestOptions),
    postExpenseDocument: (documentId, requestOptions) =>
      postJson<ExpenseDocumentView>(
        `/api/v1/expense/documents/${encodeURIComponent(documentId)}/post`,
        {}, requestOptions),
    voidExpenseDocument: (documentId, input, requestOptions) =>
      postJson<ExpenseDocumentView>(
        `/api/v1/expense/documents/${encodeURIComponent(documentId)}/void`,
        input, requestOptions),
    getExpensePolicy: () => request<ExpensePolicy>('/api/v1/expense/policy'),
    updateExpensePolicy: (input, requestOptions) =>
      request<ExpensePolicy>(
        '/api/v1/expense/policy',
        { method: 'PUT', body: JSON.stringify(input) },
        requestOptions ?? {}),
    listExpenseHolds: (status, requestOptions) =>
      request<ExpenseBatchHold[]>(
        `/api/v1/expense/holds${status ? `?status=${encodeURIComponent(status)}` : ''}`,
        {}, requestOptions),
    releaseExpenseHold: (holdId, input, requestOptions) =>
      postJson<ExpenseBatchHold>(
        `/api/v1/expense/holds/${encodeURIComponent(holdId)}/release`,
        input, requestOptions),
    listPrivacyDataClasses: (requestOptions) =>
      request<PrivacyDataClassInfo[]>('/api/v1/privacy/data-classes', {}, requestOptions),
    getPrivacySettings: (requestOptions) =>
      request<PrivacySettings>('/api/v1/privacy/settings', {}, requestOptions),
    updatePrivacySettings: (input, requestOptions) =>
      request<PrivacySettings>(
        '/api/v1/privacy/settings',
        { method: 'PUT', body: JSON.stringify(input) },
        requestOptions ?? {}),
    getMyPrivacyData: () => request<PrivacyMyData>('/api/v1/privacy/my-data'),
    exportMyPrivacyData: () => request<PrivacyDataExport>('/api/v1/privacy/my-data/export'),
    deleteMyPrivacyData: (input, requestOptions) =>
      postJson<PrivacyDeleteResult>('/api/v1/privacy/my-data/delete', input, requestOptions),
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
    listAppModules: (query = {}) =>
      request<AppModule[]>(
        `/api/v1/app-catalog/modules${queryString({
          q: query.q,
          category: query.category,
          health: query.health,
        })}`,
      ),
    getAppModule: (moduleId) =>
      request<AppModule>(`/api/v1/app-catalog/modules/${encodeURIComponent(moduleId)}`),
    createAppModule: (input, requestOptions) =>
      postJson<AppModule>('/api/v1/app-catalog/modules', input, requestOptions),
    updateAppModule: (moduleId, input, requestOptions) =>
      request<AppModule>(
        `/api/v1/app-catalog/modules/${encodeURIComponent(moduleId)}`,
        { method: 'PUT', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    getModuleActivation: (moduleId) =>
      request<ModuleActivation>(
        `/api/v1/app-catalog/modules/${encodeURIComponent(moduleId)}/activation`,
      ),
    setModuleActivation: (moduleId, input, requestOptions) =>
      request<ModuleActivation>(
        `/api/v1/app-catalog/modules/${encodeURIComponent(moduleId)}/activation`,
        { method: 'PUT', body: JSON.stringify(input) },
        requestOptions ?? {},
      ),
    listModuleSuggestions: (scope = 'mine') =>
      request<ModuleSuggestion[]>(
        `/api/v1/app-catalog/suggestions${queryString({ scope })}`,
      ),
    getModuleSuggestion: (suggestionId) =>
      request<ModuleSuggestionDetail>(
        `/api/v1/app-catalog/suggestions/${encodeURIComponent(suggestionId)}`,
      ),
    createModuleSuggestion: (input, requestOptions) =>
      postJson<ModuleSuggestion>('/api/v1/app-catalog/suggestions', input, requestOptions),
    actOnModuleSuggestion: (suggestionId, input, requestOptions) =>
      postJson<ModuleSuggestionDetail>(
        `/api/v1/app-catalog/suggestions/${encodeURIComponent(suggestionId)}/actions`,
        input,
        requestOptions,
      ),
  };
}
