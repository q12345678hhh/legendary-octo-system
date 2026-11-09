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

export interface ApiClientOptions {
  baseUrl: string;
  fetchImpl?: typeof fetch;
  correlationIdFactory?: () => string;
}

export interface ApiClient {
  request<T>(path: string, init?: RequestInit): Promise<T>;
  getSystemHealth(): Promise<HealthResponse>;
}

function defaultCorrelationId(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  return `cor-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const fetchImpl = options.fetchImpl ?? globalThis.fetch;
  if (typeof fetchImpl !== 'function') {
    throw new Error('fetch implementation is required');
  }
  const correlationIdFactory = options.correlationIdFactory ?? defaultCorrelationId;

  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json, application/problem+json');
    headers.set('X-Correlation-Id', correlationIdFactory());
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

  return {
    request,
    getSystemHealth: () => request<HealthResponse>('/api/v1/system/health'),
  };
}
