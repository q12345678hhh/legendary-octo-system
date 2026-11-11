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
}

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

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
  };
}
