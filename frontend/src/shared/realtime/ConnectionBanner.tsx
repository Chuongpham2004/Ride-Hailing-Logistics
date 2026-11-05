import { useOnline, useRealtimeStatus } from './realtime';

/**
 * Shown on every screen while updates cannot arrive (README §11.5): no network at all, or the
 * realtime connection down. `live` false for screens without a realtime connection (staff area),
 * which only need the network notice.
 */
export function ConnectionBanner({ live = true }: { live?: boolean }) {
  const online = useOnline();
  const status = useRealtimeStatus();
  const text = !online
    ? 'Không có mạng. Ứng dụng tự kết nối lại khi có mạng.'
    : !live
      ? null
      : status === 'reconnecting'
        ? 'Mất kết nối, đang thử lại…'
        : status === 'closed'
          ? 'Không nhận được cập nhật trực tiếp. Tải lại trang để thử lại.'
          : null;
  if (!text) return null;
  return (
    <div
      role="status"
      className="absolute inset-x-0 top-0 z-20 bg-asphalt px-4 py-2 text-center text-sm font-semibold text-white"
    >
      {text}
    </div>
  );
}
