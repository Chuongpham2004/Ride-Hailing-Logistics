import { Link, useSearchParams } from 'react-router';
import { messageFor } from '@/shared/api/messages';
import type { ReviewStatus } from '@/shared/api/types';
import { REVIEW_STATUS_NAMES } from '@/shared/drivers/text';
import { formatDateTime } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { ReviewStatusBadge } from '@/shared/ui/StatusBadge';
import { useReviewQueue } from './drivers';

const STATUSES: ReviewStatus[] = ['PENDING_REVIEW', 'APPROVED', 'SUSPENDED', 'REJECTED', 'DRAFT'];

/** /admin/drivers?status=: the review queue, one status at a time (UC-01). */
export default function DriverQueuePage() {
  const [params, setParams] = useSearchParams();
  const asked = params.get('status') as ReviewStatus | null;
  const status = asked && STATUSES.includes(asked) ? asked : 'PENDING_REVIEW';
  const queue = useReviewQueue(status);
  const items = queue.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <div className="flex w-full flex-col gap-5 px-10 py-8 2xl:px-14">
      <h1 className="m-0 text-[28px] leading-tight font-bold">Hồ sơ tài xế</h1>

      <div role="tablist" aria-label="Trạng thái hồ sơ" className="flex flex-wrap gap-2">
        {STATUSES.map((s) => (
          <button
            key={s}
            type="button"
            role="tab"
            aria-selected={s === status}
            onClick={() => setParams(s === 'PENDING_REVIEW' ? {} : { status: s })}
            className={`rounded-full border-[1.5px] px-4 py-1.5 text-[15px] ${
              s === status
                ? 'border-asphalt bg-asphalt font-semibold text-white'
                : 'border-field bg-white text-asphalt hover:bg-concrete'
            }`}
          >
            {REVIEW_STATUS_NAMES[s]}
          </button>
        ))}
      </div>

      {queue.isPending && <div aria-hidden="true" className="h-48 animate-pulse rounded-xl bg-white" />}
      {queue.isError && (
        <div className="flex items-center gap-3">
          <p role="alert" className="m-0 text-[15px]">
            {messageFor(queue.error)}
          </p>
          <Button size="compact" variant="secondary" onClick={() => void queue.refetch()}>
            Thử lại
          </Button>
        </div>
      )}
      {queue.isSuccess && items.length === 0 && (
        <p className="m-0 text-ink-2">Không có hồ sơ nào ở trạng thái “{REVIEW_STATUS_NAMES[status]}”.</p>
      )}

      {items.length > 0 && (
        <div className="overflow-hidden rounded-xl border border-line bg-white">
          <table className="w-full border-collapse text-left text-[15px]">
            <thead className="bg-concrete-2 text-sm text-curb">
              <tr>
                <th className="px-4 py-2.5 font-semibold">Tài xế</th>
                <th className="px-4 py-2.5 font-semibold">Gửi duyệt lúc</th>
                <th className="px-4 py-2.5 font-semibold">Phiên bản</th>
                <th className="px-4 py-2.5 font-semibold">Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.driverId} className="border-t border-line hover:bg-concrete-2">
                  <td className="px-4 py-3">
                    <Link to={`/admin/drivers/${item.driverId}`} className="font-semibold">
                      {item.fullName}
                    </Link>
                  </td>
                  <td className="px-4 py-3 tabular-nums">
                    {item.submittedAt ? formatDateTime(item.submittedAt) : '—'}
                  </td>
                  <td className="px-4 py-3 tabular-nums">{item.profileVersion}</td>
                  <td className="px-4 py-3">
                    <ReviewStatusBadge status={item.reviewStatus} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {queue.hasNextPage && (
        <Button
          size="compact"
          variant="secondary"
          className="self-start"
          busy={queue.isFetchingNextPage}
          onClick={() => void queue.fetchNextPage()}
        >
          Xem thêm
        </Button>
      )}
    </div>
  );
}
