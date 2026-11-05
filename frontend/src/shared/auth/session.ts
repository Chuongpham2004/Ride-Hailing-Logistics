import { create } from 'zustand';
import { api } from '@/shared/api/client';
import { ApiError } from '@/shared/api/ApiError';
import { tokens } from '@/shared/api/tokens';
import type { CurrentUser, TokenResponse } from '@/shared/api/types';

type Status = 'loading' | 'anonymous' | 'authenticated';

interface SessionState {
  status: Status;
  user: CurrentUser | null;
}

export const useSession = create<SessionState>(() => ({ status: 'loading', user: null }));

tokens.onClear(() => useSession.setState({ status: 'anonymous', user: null }));

async function loadUser(): Promise<void> {
  const user = await api<CurrentUser>('/api/v1/users/me');
  useSession.setState({ status: 'authenticated', user });
}

/** On start-up: resume the session from the stored refresh token, if any. */
export async function resumeSession(): Promise<void> {
  if (!tokens.refreshToken()) {
    useSession.setState({ status: 'anonymous', user: null });
    return;
  }
  try {
    await loadUser();
  } catch {
    // Either the token was refused (already cleared) or the server is unreachable; the refresh
    // token is kept in the second case so the next attempt can still resume.
    useSession.setState({ status: 'anonymous', user: null });
  }
}

export async function login(identifier: string, password: string): Promise<CurrentUser> {
  const pair = await api<TokenResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: { identifier, password },
    auth: false,
  });
  tokens.store(pair);
  await loadUser();
  const user = useSession.getState().user;
  if (!user) throw new ApiError(500, 'INTERNAL_ERROR', 'Signed in without a user');
  return user;
}

export interface SignUp {
  fullName: string;
  email?: string;
  phone?: string;
  password: string;
  /** Only these two roles can be chosen at sign-up (user-service Role.isSelfService). */
  role: 'CUSTOMER' | 'DRIVER';
}

/** POST /auth/register, then signs in with the same identifier so the user lands in their area. */
export async function register(signUp: SignUp): Promise<CurrentUser> {
  await api<CurrentUser>('/api/v1/auth/register', { method: 'POST', body: signUp, auth: false });
  return login(signUp.email ?? signUp.phone ?? '', signUp.password);
}

/**
 * Asks for a reset code. The server answers the same whether or not an account uses this email
 * or phone number, so the screen must not suggest otherwise.
 */
export async function requestPasswordReset(identifier: string): Promise<void> {
  await api('/api/v1/auth/password-reset', { method: 'POST', body: { identifier }, auth: false });
}

/** Sets the new password with the code; the server signs every session of the account out. */
export async function confirmPasswordReset(
  identifier: string,
  code: string,
  newPassword: string,
): Promise<void> {
  await api<void>('/api/v1/auth/password-reset/confirm', {
    method: 'POST',
    body: { identifier, code, newPassword },
    auth: false,
  });
}

/** Reloads the signed-in user, e.g. after a contact was verified. */
export async function refreshUser(): Promise<void> {
  await loadUser();
}

/** Revokes the refresh token on the server, then forgets the session here whatever the outcome. */
export async function logout(): Promise<void> {
  const refreshToken = tokens.refreshToken();
  try {
    await api<void>('/api/v1/auth/logout', { method: 'POST', body: { refreshToken } });
  } catch {
    // The local session ends anyway; the refresh token expires on its own.
  } finally {
    tokens.clear();
  }
}
