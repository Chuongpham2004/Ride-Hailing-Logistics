import { Link } from 'react-router';

/** Also what ACCESS_DENIED shows: the server deliberately does not tell the two apart. */
export function NotFound() {
  return (
    <div className="mx-auto flex h-dvh max-w-[480px] flex-col justify-center gap-3 px-6">
      <h1 className="m-0 text-[28px] leading-tight font-bold">Không tìm thấy trang này</h1>
      <p className="m-0 text-ink-2">Đường dẫn có thể đã cũ hoặc bạn không có quyền xem nội dung này.</p>
      <Link to="/" className="font-semibold">
        Về trang chính
      </Link>
    </div>
  );
}
