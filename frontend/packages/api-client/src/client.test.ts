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
