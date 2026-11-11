import { describe, expect, it } from 'vitest';
import { ApiProblemError, createApiClient } from './client';

function jsonResponse(body: unknown, status = 200, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': contentType },
  });
}

describe('createApiClient', () => {
  it('adds correlation id and resolves json payload', async () => {
    let capturedHeaders: Headers | undefined;
    const client = createApiClient({
      baseUrl: 'https://api.test',
      correlationIdFactory: () => 'cor-fixed',
      fetchImpl: async (_input, init) => {
        capturedHeaders = new Headers(init?.headers);
        return jsonResponse({ status: 'UP', service: 'platform-api' });
      },
    });

    const health = await client.getSystemHealth();

    expect(health).toEqual({ status: 'UP', service: 'platform-api' });
    expect(capturedHeaders?.get('x-correlation-id')).toBe('cor-fixed');
    expect(capturedHeaders?.get('accept')).toContain('application/json');
  });

  it('maps problem+json responses to ApiProblemError', async () => {
    const client = createApiClient({
      baseUrl: 'https://api.test',
      fetchImpl: async () => jsonResponse(
        {
          type: 'https://errors.platform/forbidden',
          title: 'Forbidden',
          status: 403,
          code: 'AUTH_FORBIDDEN',
        },
        403,
        'application/problem+json',
      ),
    });

    await expect(client.request('/api/v1/tenants')).rejects.toMatchObject({
      name: 'ApiProblemError',
      status: 403,
    });

    await client.request('/api/v1/tenants').catch((error: unknown) => {
      expect(error).toBeInstanceOf(ApiProblemError);
      expect((error as ApiProblemError).problem?.code).toBe('AUTH_FORBIDDEN');
    });
  });

  it('returns undefined for 204 responses', async () => {
    const client = createApiClient({
      baseUrl: 'https://api.test',
      fetchImpl: async () => new Response(null, { status: 204 }),
    });

    await expect(client.request('/api/v1/anything')).resolves.toBeUndefined();
  });
});

describe('identity and idempotency', () => {
  it('sends tenant, subject and a generated idempotency key for writes', async () => {
    let capturedHeaders: Headers | undefined;
    const client = createApiClient({
      baseUrl: 'https://api.test',
      correlationIdFactory: () => 'cor-fixed',
      idempotencyKeyFactory: () => 'idem-fixed-0001',
      identityProvider: () => ({ tenantId: 'tenant-a', subjectId: 'subject-a' }),
      fetchImpl: async (_input, init) => {
        capturedHeaders = new Headers(init?.headers);
        return jsonResponse({ jobId: 'job-1', status: 'queued' });
      },
    });

    await client.createJob({ jobType: 'demo-work' });

    expect(capturedHeaders?.get('x-tenant-id')).toBe('tenant-a');
    expect(capturedHeaders?.get('x-subject-id')).toBe('subject-a');
    expect(capturedHeaders?.get('x-correlation-id')).toBe('cor-fixed');
    expect(capturedHeaders?.get('idempotency-key')).toBe('idem-fixed-0001');
    expect(capturedHeaders?.get('content-type')).toContain('application/json');
  });

  it('never adds an idempotency key to reads', async () => {
    let capturedHeaders: Headers | undefined;
    const client = createApiClient({
      baseUrl: 'https://api.test',
      identityProvider: () => ({ tenantId: 'tenant-a', subjectId: 'subject-a' }),
      fetchImpl: async (_input, init) => {
        capturedHeaders = new Headers(init?.headers);
        return jsonResponse({ status: 'UP', service: 'platform-api' });
      },
    });

    await client.getSystemHealth();

    expect(capturedHeaders?.get('idempotency-key')).toBeNull();
    expect(capturedHeaders?.get('x-tenant-id')).toBe('tenant-a');
  });

  it('reuses an explicitly supplied idempotency key across retries', async () => {
    const keys: (string | null)[] = [];
    const client = createApiClient({
      baseUrl: 'https://api.test',
      idempotencyKeyFactory: () => 'should-not-be-used',
      fetchImpl: async (_input, init) => {
        keys.push(new Headers(init?.headers).get('idempotency-key'));
        return jsonResponse({ jobId: 'job-1', status: 'queued' });
      },
    });

    await client.createJob({ jobType: 'demo-work' }, { idempotencyKey: 'manual-key-01' });
    await client.createJob({ jobType: 'demo-work' }, { idempotencyKey: 'manual-key-01' });

    expect(keys).toEqual(['manual-key-01', 'manual-key-01']);
  });
});
