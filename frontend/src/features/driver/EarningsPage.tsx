import { Link } from 'react-router';
import { messageFor } from '@/shared/api/messages';
import { formatDateTime } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { Money } from '@/shared/ui/Money';
import { ENTRY_NAMES, useWallet, type WalletEntry } from './wallet';

/** /driver/earnings: balance and every ledger line, newest first (frontend-design.md §4.3). */
export default function EarningsPage() {
  const wallet = useWallet();
  const first = wallet.data?.pages[0];
  const entries = wallet.data?.pages.flatMap((page) => page.entries) ?? [];

  return (
    <div className="flex h-full flex-col gap-5 overflow-y-auto px-5 py-8">
      <h1 className="m-0 text-[28px] leading-tight font-bold">Thu nhập</h1>

      {wallet.isPending && <div aria-hidden="true" className="h-28 animate-pulse rounded-xl bg-concrete-2" />}
      {wallet.isError && (
        <div className="flex flex-col gap-3">
          <p role="alert" className="m-0 text-[15px]">
            {messageFor(wallet.error)}
          </p>
          <Button variant="secondary" onClick={() => void wallet.refetch()}>
            Thử lại
          </Button>
        </div>
      )}

      {first && (
        <section className="flex flex-col gap-1 rounded-xl bg-asphalt px-5 py-4 text-white">
          <h2 className="m-0 text-sm font-normal text-concrete">Số dư ví</h2>
          <p className="m-0 font-plate text-[44px] leading-none font-bold">
            <Money amount={first.balance} />
          </p>
          <p className="m-0 text-sm text-concrete">
            Đã trừ phí nền tảng; cập nhật khi khách thanh toán xong.
          </p>
        </section>
      )}

      {wallet.isSuccess && entries.length === 0 && (
        <p className="m-0 text-ink-2">
          Chưa có khoản thu nào. Hoàn tất chuyến đầu tiên để thấy thu nhập ở đây.
        </p>
      )}

      {entries.length > 0 && (
        <ul className="m-0 flex list-none flex-col p-0">
          {entries.map((entry) => (
            <li key={entry.id}>
              <EntryRow entry={entry} />
            </li>
          ))}
        </ul>
      )}

      {wallet.hasNextPage && (
        <Button
          variant="secondary"
          busy={wallet.isFetchingNextPage}
          onClick={() => void wallet.fetchNextPage()}
        >
          Xem thêm
        </Button>
      )}

      <Link to="/driver/activity" className="self-center font-semibold">
        Lịch sử chuyến
      </Link>
    </div>
  );
}

function EntryRow({ entry }: { entry: WalletEntry }) {
  const credit = entry.amount >= 0;
  return (
    <div className="flex items-center gap-3 border-t border-dashed border-line py-3">
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="font-semibold">{ENTRY_NAMES[entry.type]}</span>
        <span className="text-sm text-curb">
          {formatDateTime(entry.createdAt)}
          {entry.tripId && (
            <>
              {' · '}
              <Link to={`/driver/trips/${entry.tripId}`}>Xem chuyến</Link>
            </>
          )}
        </span>
      </div>
      <div className="flex flex-col items-end">
        <span className={`font-semibold tabular-nums ${credit ? 'text-go' : 'text-stop'}`}>
          {credit ? '+' : '−'}
          <Money amount={Math.abs(entry.amount)} />
        </span>
        <span className="text-sm text-curb">
          Số dư <Money amount={entry.balanceAfter} />
        </span>
      </div>
    </div>
  );
}
