import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiUrl, fail, ok, server } from '@/test/server';
import { ApiError } from './ApiError';
import { api } from './client';
import { tokens } from './tokens';

const pair = (access: string, refresh: string, expiresIn = 900) => ({
  tokenType: 'Bearer' as const,
  accessToken: access,
  expiresIn,
  refreshToken: refresh,
  refreshExpiresIn: 2_592_000,
});

afterEach(() => tokens.clear());

describe('api client', () => {
  it('sends the access token and unwraps the envelope', async () => {
    tokens.store(pair('access-1', 'refresh-1'));
    server.use(
      http.get(apiUrl('/api/v1/users/me'), ({ request }) =>
        request.headers.get('Authorization') === 'Bearer access-1'
          ? ok({ id: 'u1' })
          : fail(401, 'AUTHENTICATION_REQUIRED'),
      ),
    );

    await expect(api('/api/v1/users/me')).resolves.toEqual({ id: 'u1' });
  });

  it('turns an error envelope into an ApiError with the server code and field details', async () => {
    tokens.store(pair('access-1', 'refresh-1'));
    server.use(
      http.post(apiUrl('/api/v1/quotes'), () =>
        fail(400, 'VALIDATION_ERROR', [{ field: 'pickup.latitude', reason: 'must be at most 90' }]),
      ),
    );

    const error = await api('/api/v1/quotes', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 400,
      code: 'VALIDATION_ERROR',
      correlationId: 'test-correlation',
      details: [{ field: 'pickup.latitude', reason: 'must be at most 90' }],
    });
  });

  it('reports an unreachable server as NETWORK_ERROR', async () => {
    tokens.store(pair('access-1', 'refresh-1'));
    server.use(http.get(apiUrl('/api/v1/users/me'), () => HttpResponse.error()));

    await expect(api('/api/v1/users/me')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 });
  });

  it('refreshes once for concurrent 401s, then retries each request with the new token', async () => {
    tokens.store(pair('stale', 'refresh-1'));
    const refreshes = vi.fn();
    server.use(
      http.post(apiUrl('/api/v1/auth/refresh'), async ({ request }) => {
        refreshes(await request.json());
        return ok(pair('fresh', 'refresh-2'));
      }),
      http.get(apiUrl('/api/v1/trips'), ({ request }) =>
        request.headers.get('Authorization') === 'Bearer fresh'
          ? ok(['trip'])
          : fail(401, 'AUTHENTICATION_REQUIRED'),
      ),
    );

    const results = await Promise.all([api('/api/v1/trips'), api('/api/v1/trips'), api('/api/v1/trips')]);

    expect(results).toEqual([['trip'], ['trip'], ['trip']]);
    // Refresh tokens rotate and reuse revokes the session, so exactly one refresh may go out.
    expect(refreshes).toHaveBeenCalledTimes(1);
    expect(refreshes).toHaveBeenCalledWith({ refreshToken: 'refresh-1' });
    expect(tokens.refreshToken()).toBe('refresh-2');
  });

  it('refreshes ahead of time when the access token is about to expire', async () => {
    tokens.store(pair('nearly-expired', 'refresh-1', 60));
    server.use(
      http.post(apiUrl('/api/v1/auth/refresh'), () => ok(pair('fresh', 'refresh-2'))),
      http.get(apiUrl('/api/v1/users/me'), ({ request }) =>
        request.headers.get('Authorization') === 'Bearer fresh'
          ? ok({ id: 'u1' })
          : fail(500, 'INTERNAL_ERROR'),
      ),
    );

    await expect(api('/api/v1/users/me')).resolves.toEqual({ id: 'u1' });
  });

  it('ends the session when the server refuses the refresh token', async () => {
    tokens.store(pair('stale', 'refresh-reused'));
    const ended = vi.fn();
    const unsubscribe = tokens.onClear(ended);
    server.use(
      http.post(apiUrl('/api/v1/auth/refresh'), () => fail(401, 'AUTHENTICATION_REQUIRED')),
      http.get(apiUrl('/api/v1/users/me'), () => fail(401, 'AUTHENTICATION_REQUIRED')),
    );

    await expect(api('/api/v1/users/me')).rejects.toMatchObject({ code: 'AUTHENTICATION_REQUIRED' });
    expect(ended).toHaveBeenCalledTimes(1);
    expect(tokens.refreshToken()).toBeNull();
    unsubscribe();
  });

  it('sends no token to public endpoints', async () => {
    tokens.store(pair('access-1', 'refresh-1'));
    server.use(
      http.post(apiUrl('/api/v1/auth/login'), ({ request }) =>
        request.headers.has('Authorization') ? fail(400, 'VALIDATION_ERROR') : ok(pair('a', 'r')),
      ),
    );

    await expect(api('/api/v1/auth/login', { method: 'POST', body: {}, auth: false })).resolves.toMatchObject(
      {
        accessToken: 'a',
      },
    );
  });
});
