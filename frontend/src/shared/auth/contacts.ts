import { useMutation } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import { refreshUser } from './session';

/* Verifying the signed-in user's email or phone with a one-time code (FR-IAM). */

export type Channel = 'email' | 'phone';

/** user-service UserController.CodeSentResponse; the destination is masked. */
export interface CodeSent {
  channel: string;
  destination: string;
  expiresInSeconds: number;
  resendAfterSeconds: number;
}

export function useSendCode() {
  return useMutation({
    mutationFn: (channel: Channel) =>
      api<CodeSent>(`/api/v1/users/me/contacts/${channel}/verification`, { method: 'POST' }),
  });
}

/** Confirms the code, then reloads the signed-in user so every screen sees the verified contact. */
export function useConfirmCode(onVerified?: () => unknown) {
  return useMutation({
    mutationFn: ({ channel, code }: { channel: Channel; code: string }) =>
      api(`/api/v1/users/me/contacts/${channel}/verification/confirm`, { method: 'POST', body: { code } }),
    onSuccess: async () => {
      await refreshUser();
      await onVerified?.();
    },
  });
}
