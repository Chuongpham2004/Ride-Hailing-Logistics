import type { TokenResponse } from './types';

/*
 * The access token lives only in memory. The refresh token is kept in localStorage so a reload
 * stays signed in: a deliberate trade-off (frontend-design.md §5) that relies on a strict CSP and
 * never rendering raw HTML.
 */

const REFRESH_KEY = 'rhl.refreshToken';

let access: { token: string; expiresAt: number } | null = null;
const clearListeners = new Set<() => void>();

function readRefresh(): string | null {
  try {
    return localStorage.getItem(REFRESH_KEY);
  } catch {
    return null;
  }
}

export const tokens = {
  accessToken(): string | null {
    return access?.token ?? null;
  },

  /** True when there is no access token or it expires within `ms`. */
  expiresWithin(ms: number, now = Date.now()): boolean {
    return !access || access.expiresAt - now < ms;
  },

  refreshToken: readRefresh,

  store(pair: TokenResponse, now = Date.now()): void {
    access = { token: pair.accessToken, expiresAt: now + pair.expiresIn * 1000 };
    try {
      localStorage.setItem(REFRESH_KEY, pair.refreshToken);
    } catch {
      // Private mode or blocked storage: the session simply ends with the tab.
    }
  },

  clear(): void {
    const hadSession = access !== null || readRefresh() !== null;
    access = null;
    try {
      localStorage.removeItem(REFRESH_KEY);
    } catch {
      // Nothing stored.
    }
    if (hadSession) clearListeners.forEach((listener) => listener());
  },

  /** Called whenever a session ends: logout, a refused refresh, or a reused refresh token. */
  onClear(listener: () => void): () => void {
    clearListeners.add(listener);
    return () => clearListeners.delete(listener);
  },
};
