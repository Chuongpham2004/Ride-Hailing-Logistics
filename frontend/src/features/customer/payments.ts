import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type { Payment, PaymentStatus, Refund } from '@/shared/api/types';
import { useRealtimeMessages } from '@/shared/realtime/realtime';

const paymentsKey = (tripId: string) => ['payments', tripId] as const;

/** The outcome is not known yet: the provider has not answered the charge or the refund. */
export const IN_FLIGHT: ReadonlySet<PaymentStatus> = new Set(['PENDING', 'REFUND_PENDING']);

/** Domain events about money that the gateway forwards to the customer. */
const PAYMENT_EVENTS = new Set(['PAYMENT_SUCCEEDED', 'PAYMENT_FAILED', 'REFUND_COMPLETED']);

/** Polls before payment-service has created the payment (it follows the trip's end by a moment). */
const WAIT_POLL_MS = 3000;
const WAIT_POLLS = 20;

/**
 * GET /payments?tripId=: the fare or the cancellation fee of a trip. Empty right after the trip
 * ends, and for a free cancellation, so it polls for about a minute and then stops. Payment
 * events from the realtime connection reload it at once.
 */
export function usePayments(tripId: string) {
  const queryClient = useQueryClient();
  useRealtimeMessages((message) => {
    if (!PAYMENT_EVENTS.has(message.type)) return;
    if ((message.data as { tripId?: string }).tripId !== tripId) return;
    void queryClient.invalidateQueries({ queryKey: paymentsKey(tripId) });
  });

  const query = useQuery({
    queryKey: paymentsKey(tripId),
    queryFn: ({ signal }) =>
      api<Payment[]>(`/api/v1/payments?tripId=${encodeURIComponent(tripId)}`, { signal }),
    refetchInterval: (query) => {
      const payments = query.state.data;
      if (payments?.some((p) => IN_FLIGHT.has(p.status))) return 10_000;
      if (payments?.length === 0 && query.state.dataUpdateCount < WAIT_POLLS) return WAIT_POLL_MS;
      return false;
    },
  });
  const updates = queryClient.getQueryState(paymentsKey(tripId))?.dataUpdateCount ?? 0;
  /** Nothing yet, and still looking for it. */
  const waiting = query.data?.length === 0 && updates < WAIT_POLLS;
  return { ...query, waiting };
}

/** Refunds of one payment; only asked for when something was refunded or is being refunded. */
export function useRefunds(payment: Payment) {
  const enabled = payment.refundedAmount > 0 || payment.status === 'REFUND_PENDING';
  return useQuery({
    queryKey: ['payments', payment.tripId, payment.id, 'refunds', payment.refundedAmount, payment.status],
    queryFn: ({ signal }) =>
      api<Refund[]>(`/api/v1/payments/${encodeURIComponent(payment.id)}/refunds`, { signal }),
    enabled,
  });
}

/** Pays a failed payment again; the server ignores it while an attempt is in flight. */
export function useRetryPayment(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (paymentId: string) =>
      api<Payment>(`/api/v1/payments/${encodeURIComponent(paymentId)}/retry`, { method: 'POST' }),
    onSuccess: (payment) => {
      queryClient.setQueryData<Payment[]>(paymentsKey(tripId), (list) =>
        list?.map((p) => (p.id === payment.id ? payment : p)),
      );
    },
  });
}
