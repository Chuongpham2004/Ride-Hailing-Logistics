import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import { useRealtimeMessages, useRealtimeResync } from '@/shared/realtime/realtime';

/** payment-service PaymentQueries.EntryView; `amount` is signed (commission is negative). */
export interface WalletEntry {
  id: string;
  type: 'EARNING' | 'COMMISSION' | 'ADJUSTMENT';
  amount: number;
  balanceAfter: number;
  referenceType: 'PAYMENT' | 'ADJUSTMENT';
  referenceId: string;
  tripId?: string;
  commissionRuleVersion?: number;
  createdAt: string;
}

/** payment-service PaymentQueries.WalletView; walletId is absent until the first entry. */
export interface Wallet {
  walletId?: string;
  driverId: string;
  balance: number;
  currency: string;
  entries: WalletEntry[];
  nextBefore?: string;
}

export const ENTRY_NAMES: Record<WalletEntry['type'], string> = {
  EARNING: 'Tiền chuyến',
  COMMISSION: 'Phí nền tảng',
  ADJUSTMENT: 'Điều chỉnh',
};

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
