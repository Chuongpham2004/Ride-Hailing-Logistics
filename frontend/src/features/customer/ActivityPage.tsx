import { Link } from 'react-router';

/** /app/activity: trip history comes with the trip screens (frontend-design.md §11, stage 2). */
export default function ActivityPage() {
  return (
    <div className="flex h-full flex-col gap-3 overflow-y-auto px-5 py-8">
      <h1 className="m-0 text-[28px] leading-tight font-bold">Hoạt động</h1>
      <p className="m-0 max-w-[60ch] text-ink-2">Lịch sử chuyến đi sẽ hiện ở đây từ bản cập nhật tới.</p>
      <Link to="/app" className="font-semibold">
        Xem giá một chuyến
      </Link>
    </div>
  );
}
