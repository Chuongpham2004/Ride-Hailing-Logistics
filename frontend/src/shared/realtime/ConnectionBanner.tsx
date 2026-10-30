import { useRealtimeStatus } from './realtime';

/** Shown on every screen while the realtime connection is down (README §11.5). */
export function ConnectionBanner() {
  const status = useRealtimeStatus();
  if (status !== 'reconnecting' && status !== 'closed') return null;
  return (
    <div
      role="status"
      className="absolute inset-x-0 top-0 z-20 bg-asphalt px-4 py-2 text-center text-sm font-semibold text-white"
    >
      {status === 'reconnecting'
        ? 'Mất kết nối, đang thử lại…'
        : 'Không nhận được cập nhật trực tiếp. Tải lại trang để thử lại.'}
    </div>
  );
}
