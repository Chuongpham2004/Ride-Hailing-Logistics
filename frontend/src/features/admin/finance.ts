import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type {
  AdjustmentReason,
  Payment,
  PaymentAttempt,
  PaymentPage,
  PaymentStatus,
  RefundReason,
  StaffRefund,
  Wallet,
  WalletAdjustment,
} from '@/shared/api/types';

/*
 * Finance staff (README §3, frontend-design.md §4.4): payments, refunds and driver wallets.
 * Writes carry an Idempotency-Key made once per dialog, so a retry after a network error cannot
 * refund or adjust twice (COM-008).
 */

const PAGE_SIZE = 20;
const paymentPath = (id: string) => `/api/v1/payments/${encodeURIComponent(id)}`;

/** The most a single wallet adjustment may move (payment-service rhl.payment.wallet.max-adjustment). */
export const MAX_ADJUSTMENT = 5_000_000;

export function usePayments(status?: PaymentStatus) {
  return useInfiniteQuery({
    queryKey: ['admin', 'payments', status ?? 'ALL'],
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE) });
      if (status) params.set('status', status);
      if (pageParam) params.set('before', pageParam);
      return api<PaymentPage>(`/api/v1/admin/payments?${params}`, { signal });
    },
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextBefore ?? null,
  });
}

export const paymentKey = (id: string) => ['admin', 'payment', id] as const;

export function usePayment(id: string) {
  return useQuery({
    queryKey: paymentKey(id),
    queryFn: ({ signal }) => api<Payment>(paymentPath(id), { signal }),
  });
}

export function useAttempts(id: string) {
  return useQuery({
    queryKey: [...paymentKey(id), 'attempts'],
    queryFn: ({ signal }) =>
      api<PaymentAttempt[]>(`/api/v1/admin/payments/${encodeURIComponent(id)}/attempts`, { signal }),
  });
}

export function useRefunds(id: string) {
  return useQuery({
    queryKey: [...paymentKey(id), 'refunds'],
    queryFn: ({ signal }) => api<StaffRefund[]>(`${paymentPath(id)}/refunds`, { signal }),
  });
}

/** Payments of one trip; finance staff see all of them. */
export function useTripPayments(tripId: string) {
  return useQuery({
    queryKey: ['admin', 'trip', tripId, 'payments'],
    queryFn: ({ signal }) =>
      api<Payment[]>(`/api/v1/payments?tripId=${encodeURIComponent(tripId)}`, { signal }),
  });
}

export interface RefundInput {
  /** Omitted: everything not refunded yet. */
  amount?: number;
  reason: RefundReason;
  note?: string;
  idempotencyKey: string;
}

export function useRefund(paymentId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: RefundInput) =>
      api<StaffRefund>(`/api/v1/admin/payments/${encodeURIComponent(paymentId)}/refunds`, {
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: paymentKey(paymentId) });
      await queryClient.invalidateQueries({ queryKey: ['admin', 'payments'] });
    },
  });
}

export const walletKey = (driverId: string) => ['admin', 'wallet', driverId] as const;

/** A driver's wallet, newest lines first; the balance comes with the first page. */
export function useDriverWallet(driverId: string) {
  return useInfiniteQuery({
    queryKey: walletKey(driverId),
    queryFn: ({ pageParam, signal }) =>
      api<Wallet>(
        `/api/v1/admin/wallets/${encodeURIComponent(driverId)}?limit=${PAGE_SIZE}${
          pageParam ? `&before=${encodeURIComponent(pageParam)}` : ''
        }`,
        { signal },
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextBefore ?? null,
  });
}

export interface AdjustmentInput {
  /** Signed: positive credits the driver, negative debits. */
  amount: number;
  reason: AdjustmentReason;
  note?: string;
  tripId?: string;
  refundId?: string;
  idempotencyKey: string;
}

export function useAdjustWallet(driverId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: AdjustmentInput) =>
      api<WalletAdjustment>(`/api/v1/admin/wallets/${encodeURIComponent(driverId)}/adjustments`, {
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: walletKey(driverId) }),
  });
}

/** Parses "38.000", "38000" or "38 000" as whole VND; null when it is not a positive whole number. */
export function parseVnd(text: string): number | null {
  const digits = text.replace(/[\s.,]/g, '');
  if (!/^\d+$/.test(digits)) return null;
  const value = Number(digits);
  return value > 0 && Number.isSafeInteger(value) ? value : null;
}
