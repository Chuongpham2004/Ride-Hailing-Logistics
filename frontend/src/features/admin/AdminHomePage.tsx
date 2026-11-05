import { Navigate } from 'react-router';
import { useSession } from '@/shared/auth/session';
import { sectionsFor } from './sections';

/** /admin: opens the first section the user's roles allow. */
export default function AdminHomePage() {
  const roles = useSession((state) => state.user?.roles ?? []);
  const first = sectionsFor(roles)[0];
  if (first) return <Navigate to={first.to} replace />;
  return (
    <div className="px-10 py-10">
      <h1 className="m-0 text-2xl font-bold">Khu vực nhân viên</h1>
      <p className="max-w-[60ch] text-ink-2">
        Thanh toán, hoàn tiền, ví tài xế và bảng giá sẽ có ở bản cập nhật tới. Trong lúc chờ, dùng collection
        Postman trong thư mục postman/.
      </p>
    </div>
  );
}
