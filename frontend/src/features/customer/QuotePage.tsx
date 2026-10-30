import { useMemo } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import { formatCountdown, formatDistance, formatDuration } from '@/shared/lib/format';
import { useCountdown } from '@/shared/lib/useCountdown';
import { useHeight } from '@/shared/lib/useHeight';
import { MapView } from '@/shared/map/MapView';
import { Button } from '@/shared/ui/Button';
import { BackIcon, ClockIcon } from '@/shared/ui/icons';
import { Money } from '@/shared/ui/Money';
import { Sheet } from '@/shared/ui/Sheet';
import { BookPanel } from './BookPanel';
import { decodeRoute, fareLines, SERVICE_NAMES, useCreateQuote, useQuote } from './booking';

const multiplier = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });

/** /app/quote/:quoteId: the price, how it is made up, and how long it is held. */
export default function QuotePage() {
  const { quoteId = '' } = useParams();
  const navigate = useNavigate();
  const { data: quote, isPending, error } = useQuote(quoteId);
  const renew = useCreateQuote();
  const secondsLeft = useCountdown(quote?.expiresAt);
  const route = useMemo(() => decodeRoute(quote), [quote]);
  const [sheetRef, sheetHeight] = useHeight<HTMLDivElement>();

  const expired = quote !== undefined && secondsLeft === 0;
  const estimate = quote !== undefined && quote.routeSource !== 'OSRM';

  const askAgain = () => {
    if (!quote) return;
    renew.mutate(
      { serviceType: quote.serviceType, pickup: quote.pickup, dropoff: quote.dropoff },
      { onSuccess: (fresh) => navigate(`/app/quote/${fresh.id}`, { replace: true }) },
    );
  };

  return (
    <div className="absolute inset-0">
      <MapView
        className="absolute inset-0"
        pickup={quote?.pickup}
        dropoff={quote?.dropoff}
        route={route}
        straightLine={estimate}
        bottomInset={sheetHeight}
      />

      <button
        type="button"
        aria-label="Quay lại chọn điểm"
        onClick={() => navigate('/app')}
        className="absolute top-5 left-4 flex size-12 items-center justify-center rounded-full bg-white text-asphalt shadow-[0_2px_6px_rgba(43,46,51,0.18)]"
      >
        <BackIcon />
      </button>

      <div ref={sheetRef} className="absolute inset-x-0 bottom-0 max-h-[70%] overflow-y-auto">
        <Sheet>
          {isPending && <QuoteSkeleton />}

          {error && (
            <div className="flex flex-col gap-3">
              <p role="alert" className="m-0 text-[15px]">
                {error instanceof ApiError && error.status === 404
                  ? 'Không tìm thấy báo giá này. Có thể nó thuộc một phiên đăng nhập khác.'
                  : messageFor(error)}
              </p>
              <Link to="/app" className="font-semibold">
                Chọn lại điểm đón và điểm đến
              </Link>
            </div>
          )}

          {quote && (
            <>
              <div className="flex items-baseline justify-between gap-3">
                <h1 className="m-0 text-xl font-bold">{SERVICE_NAMES[quote.serviceType]}</h1>
                <span className="text-sm text-curb">
                  {estimate ? 'khoảng ' : ''}
                  {formatDistance(quote.distanceMeters)}, {formatDuration(quote.durationSeconds)}
                </span>
              </div>

              <div className="flex items-end justify-between gap-3">
                <p className="m-0 font-plate text-[60px] leading-[0.95] font-bold tracking-[0.01em]">
                  <Money amount={quote.total} />
                </p>
                {!expired && (
                  <p className="m-0 mb-1.5 flex items-center gap-1.5 text-sm" aria-live="off">
                    <ClockIcon />
                    Giữ giá {formatCountdown(secondsLeft)}
                  </p>
                )}
              </div>

              {quote.surgeConfirmationRequired && (
                <p className="m-0 rounded-[10px] bg-[#FDF3D0] px-3.5 py-3 text-[15px]">
                  Giá đang cao hơn bình thường (x{multiplier.format(quote.surgeMultiplier)}) vì nhiều người
                  đặt quanh điểm đón.
                </p>
              )}
              {estimate && (
                <p className="m-0 text-sm text-curb">
                  Chưa có tuyến đường chi tiết, quãng đường đang được ước tính theo đường thẳng.
                </p>
              )}

              <dl className="m-0 flex flex-col text-[15px]">
                {fareLines(quote).map((line) => (
                  <div
                    key={line.label}
                    className="flex justify-between gap-3 border-t border-dashed border-line py-2"
                  >
                    <dt className="text-curb">{line.label}</dt>
                    <dd className="m-0">
                      <Money amount={line.amount} />
                    </dd>
                  </div>
                ))}
              </dl>

              {expired ? (
                <div className="flex flex-col gap-2.5">
                  <p className="m-0 text-[15px]">Báo giá đã hết hạn. Lấy giá mới để đặt chuyến.</p>
                  {renew.isError && (
                    <p role="alert" className="m-0 text-sm text-stop">
                      {messageFor(renew.error)}
                    </p>
                  )}
                  <Button variant="dark" onClick={askAgain} busy={renew.isPending}>
                    Lấy báo giá mới
                  </Button>
                </div>
              ) : (
                <BookPanel quote={quote} />
              )}
            </>
          )}
        </Sheet>
      </div>
    </div>
  );
}

function QuoteSkeleton() {
  return (
    <div aria-hidden="true" className="flex animate-pulse flex-col gap-4">
      <div className="h-6 w-40 rounded bg-concrete-2" />
      <div className="h-14 w-48 rounded bg-concrete-2" />
      <div className="h-24 rounded bg-concrete-2" />
      <div className="h-13 rounded-xl bg-concrete-2" />
    </div>
  );
}
