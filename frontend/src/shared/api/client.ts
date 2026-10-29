import { config } from '@/shared/config';
import { ApiError } from './ApiError';
import { tokens } from './tokens';
import type { Envelope, TokenResponse } from './types';

/** Refresh ahead of expiry so a request rarely meets a 401 (frontend-design.md §5). */
const REFRESH_AHEAD_MS = 2 * 60 * 1000;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  /** false for endpoints that take no token (login, register). */
  auth?: boolean;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

let refreshing: Promise<string | null> | null = null;
const refreshedListeners = new Set<(accessToken: string) => void>();

/**
 * Exchanges the refresh token for a new pair. Single-flight: refresh tokens rotate and a reused one
 * revokes the whole session, so concurrent callers must share one request.
 * Resolves to null when there is no session any more.
 */
export function refreshAccessToken(): Promise<string | null> {
  refreshing ??= doRefresh().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

/** For the realtime layer: send AUTH with each new token (frontend-design.md §5). */
export function onTokenRefreshed(listener: (accessToken: string) => void): () => void {
  refreshedListeners.add(listener);
  return () => refreshedListeners.delete(listener);
}

async function doRefresh(): Promise<string | null> {
  const refreshToken = tokens.refreshToken();
  if (!refreshToken) return null;
  try {
    const pair = await send<TokenResponse>('/api/v1/auth/refresh', {
      method: 'POST',
      body: { refreshToken },
    });
    tokens.store(pair);
    refreshedListeners.forEach((listener) => listener(pair.accessToken));
    return pair.accessToken;
  } catch (error) {
    // The server refused the token (expired, revoked or reused): the session is over.
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
      tokens.clear();
      return null;
    }
    throw error;
  }
}

/** Calls the API gateway and unwraps the envelope; throws {@link ApiError} on any failure. */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (options.auth === false) return send<T>(path, options);

  let token = tokens.accessToken();
  if (tokens.expiresWithin(REFRESH_AHEAD_MS) && tokens.refreshToken()) {
    token = await refreshAccessToken();
  }
  try {
    return await send<T>(path, options, token);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
    // Another request may have refreshed while this one was in flight with the old token:
    // reuse that token instead of spending the new refresh token on a second rotation.
    const current = tokens.accessToken();
    const renewed = current && current !== token ? current : await refreshAccessToken();
    if (!renewed) throw error;
    return send<T>(path, options, renewed);
  }
}

async function send<T>(path: string, options: RequestOptions, token?: string | null): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(config.apiUrl + path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw ApiError.network(cause);
  }

  if (response.status === 204) return undefined as T;

  let envelope: Envelope<T> | null = null;
  try {
    envelope = (await response.json()) as Envelope<T>;
  } catch {
    // Not JSON (a proxy error page, say): reported below by status.
  }
  if (response.ok && envelope) return envelope.data as T;
  throw new ApiError(
    response.status,
    envelope?.code ?? `HTTP_${response.status}`,
    envelope?.message ?? response.statusText,
    envelope?.details ?? [],
    envelope?.correlationId,
  );
}
