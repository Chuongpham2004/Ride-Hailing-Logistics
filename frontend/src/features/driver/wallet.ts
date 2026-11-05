import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type { Wallet } from '@/shared/api/types';
import { useRealtimeMessages, useRealtimeResync } from '@/shared/realtime/realtime';

export type { Wallet, WalletEntry } from '@/shared/api/types';
export { ENTRY_NAMES } from '@/shared/payments/text';

const walletKey = ['driver', 'wallet'] as const;
const PAGE_SIZE = 20;

/** The pushes that change the wallet (payment-service events forwarded by the gateway). */
const WALLET_EVENTS = new Set(['DRIVER_EARNING_POSTED', 'WALLET_ADJUSTED']);

/** GET /wallets/me, newest entries first; the balance is taken from the first page. */
export function useWallet() {
  const queryClient = useQueryClient();
  const reload = () => void queryClient.invalidateQueries({ queryKey: walletKey });
  useRealtimeMessages((message) => {
    if (WALLET_EVENTS.has(message.type)) reload();
  });
  useRealtimeResync(reload);

  return useInfiniteQuery({
    queryKey: walletKey,
    queryFn: ({ pageParam, signal }) =>
      api<Wallet>(
        `/api/v1/wallets/me?limit=${PAGE_SIZE}${pageParam ? `&before=${encodeURIComponent(pageParam)}` : ''}`,
        { signal },
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextBefore ?? null,
  });
}
