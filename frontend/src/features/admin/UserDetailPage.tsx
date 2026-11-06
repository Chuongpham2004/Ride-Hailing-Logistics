import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { AdminUser, Role, UserStatus } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { REVIEW_STATUS_NAMES } from '@/shared/drivers/text';
import { formatDateTime } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { Dialog } from '@/shared/ui/Dialog';
import { BackIcon } from '@/shared/ui/icons';
import { Card } from './Card';
import { ReasonDialog } from './ReasonDialog';
import { canUse, ROLE_NAMES } from './sections';
import { FIELD, USER_STATUS_NAMES } from './text';
import { UUID } from './trips';
import { ROLES, useAdminUser, useChangeStatus, useReplaceRoles } from './users';
import { UserStatusBadge } from './UserStatusBadge';

/** /admin/users/:userId: one account with its current roles and status, and the changes to them. */
export default function UserDetailPage() {
  const { userId = '' } = useParams();
  const valid = UUID.test(userId);

  return (
    <div className="flex w-full flex-col gap-6 px-10 py-8 2xl:px-14">
      <Link to="/admin/users" className="flex items-center gap-1 self-start text-[15px] font-semibold">
        <BackIcon width={18} height={18} /> Danh sách người dùng
      </Link>
      {valid ? <Account userId={userId} /> : <p role="alert">Mã người dùng không hợp lệ.</p>}
    </div>
  );
}

function Account({ userId }: { userId: string }) {
  const user = useAdminUser(userId);
  if (user.isPending) return <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />;
  if (user.isError) {
    return (
      <p role="alert" className="m-0 text-[15px]">
        {user.error instanceof ApiError && user.error.status === 404
          ? 'Không tìm thấy người dùng này.'
          : messageFor(user.error)}
      </p>
    );
  }
  return <Loaded account={user.data} />;
}

function Loaded({ account }: { account: AdminUser }) {
  const me = useSession((state) => state.user);
  const own = me?.id === account.id;
  const seesDrivers = canUse('drivers', me?.roles ?? []);
  const isDriver = account.roles.includes('DRIVER');

  return (
    <>
      <header className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="m-0 text-[28px] leading-tight font-bold">{account.fullName}</h1>
          <UserStatusBadge status={account.status} />
        </div>
        <p className="m-0 text-[15px] text-curb">
          Mã <span className="text-asphalt tabular-nums">{account.id}</span> · tạo{' '}
          {formatDateTime(account.createdAt)}
        </p>
      </header>

      {own && (
        <p className="m-0 rounded-lg bg-stop-tint px-4 py-3 text-[15px]">
          Đây là tài khoản của bạn: không tự đổi trạng thái hay bỏ vai trò quản trị của chính mình được.
        </p>
      )}

      <div className="flex flex-col gap-6 xl:block xl:columns-2 xl:gap-6 xl:[&>*]:mb-6 xl:[&>*]:break-inside-avoid">
        <Card title="Thông tin">
          <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-8 gap-y-2 text-[15px]">
            <dt className="text-curb">Email</dt>
            <dd className="m-0">
              {account.email ?? '—'}
              {account.email && (
                <span className="text-curb">
                  {' '}
                  · {account.emailVerified ? 'đã xác minh' : 'chưa xác minh'}
                </span>
              )}
            </dd>
            <dt className="text-curb">Số điện thoại</dt>
            <dd className="m-0 tabular-nums">
              {account.phone ?? '—'}
              {account.phone && (
                <span className="text-curb">
                  {' '}
                  · {account.phoneVerified ? 'đã xác minh' : 'chưa xác minh'}
                </span>
              )}
            </dd>
            <dt className="text-curb">Vai trò</dt>
            <dd className="m-0">{account.roles.map((role) => ROLE_NAMES[role]).join(', ')}</dd>
            {isDriver && (
              <>
                <dt className="text-curb">Hồ sơ tài xế</dt>
                <dd className="m-0">
                  {account.driverReviewStatus ? REVIEW_STATUS_NAMES[account.driverReviewStatus] : 'Chưa tạo'}
                  {account.driverReviewStatus && seesDrivers && (
                    <>
                      {' · '}
                      <Link to={`/admin/drivers/${account.id}`} className="font-semibold">
                        Xem hồ sơ
                      </Link>
                    </>
                  )}
                </dd>
              </>
            )}
            <dt className="text-curb">Cập nhật</dt>
            <dd className="m-0">{formatDateTime(account.updatedAt)}</dd>
          </dl>
        </Card>

        {/* Remount on each server answer so the forms start from the saved values. */}
        <RolesForm key={account.roles.join()} userId={account.id} current={account.roles} />
        {!own && <StatusForm key={account.status} userId={account.id} current={account.status} />}
      </div>
    </>
  );
}

function RolesForm({ userId, current }: { userId: string; current: Role[] }) {
  const replace = useReplaceRoles(userId);
  const [roles, setRoles] = useState<Role[]>(current);
  const [confirming, setConfirming] = useState(false);
  const toggle = (role: Role) =>
    setRoles((list) => (list.includes(role) ? list.filter((r) => r !== role) : [...list, role]));
  const changed = roles.length !== current.length || roles.some((role) => !current.includes(role));
  const names = (list: Role[]) =>
    ROLES.filter((r) => list.includes(r))
      .map((r) => ROLE_NAMES[r])
      .join(', ');

  return (
    <Card title="Vai trò">
      <fieldset className="m-0 grid grid-cols-2 gap-2 border-0 p-0 text-[15px] sm:grid-cols-3">
        <legend className="sr-only">Vai trò</legend>
        {ROLES.map((role) => (
          <label key={role} className="flex items-center gap-2">
            <input type="checkbox" checked={roles.includes(role)} onChange={() => toggle(role)} />
            {ROLE_NAMES[role]}
          </label>
        ))}
      </fieldset>
      <div className="flex items-center gap-3">
        <Button
          size="compact"
          variant="dark"
          disabled={roles.length === 0 || !changed}
          onClick={() => {
            replace.reset();
            setConfirming(true);
          }}
        >
          Lưu vai trò
        </Button>
        {roles.length === 0 && <span className="text-sm text-curb">Chọn ít nhất một vai trò.</span>}
      </div>
      {confirming && (
        <Dialog title="Thay vai trò" onClose={() => setConfirming(false)}>
          <p className="m-0 text-[15px]">
            Từ <strong>{names(current)}</strong> thành <strong>{names(roles)}</strong>. Phiên đăng nhập hiện
            tại của họ bị đăng xuất để nhận vai trò mới.
          </p>
          {replace.error && (
            <p role="alert" className="m-0 text-[15px] text-stop">
              {messageFor(replace.error)}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button size="compact" variant="secondary" onClick={() => setConfirming(false)}>
              Đóng
            </Button>
            <Button
              size="compact"
              variant="dark"
              autoFocus
              busy={replace.isPending}
              onClick={() => replace.mutate(roles, { onSuccess: () => setConfirming(false) })}
            >
              Thay vai trò
            </Button>
          </div>
        </Dialog>
      )}
    </Card>
  );
}

function StatusForm({ userId, current }: { userId: string; current: UserStatus }) {
  const change = useChangeStatus(userId);
  const options = (Object.keys(USER_STATUS_NAMES) as UserStatus[]).filter((s) => s !== current);
  const [status, setStatus] = useState<UserStatus>(options[0]!);
  const [confirming, setConfirming] = useState(false);

  return (
    <Card title="Trạng thái tài khoản">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm font-semibold">
          Chuyển sang
          <select className={FIELD} value={status} onChange={(e) => setStatus(e.target.value as UserStatus)}>
            {options.map((s) => (
              <option key={s} value={s}>
                {USER_STATUS_NAMES[s]}
              </option>
            ))}
          </select>
        </label>
        <Button
          size="compact"
          variant={status === 'ACTIVE' ? 'dark' : 'danger'}
          onClick={() => {
            change.reset();
            setConfirming(true);
          }}
        >
          Đổi trạng thái
        </Button>
      </div>
      <p className="m-0 text-sm text-curb">
        Tạm khoá: chặn đăng nhập, mở lại được. Vô hiệu hoá: dùng khi đóng hẳn tài khoản. Tài xế đang online bị
        chuyển offline ngay.
      </p>
      {confirming && (
        <ReasonDialog
          title={`Chuyển tài khoản sang “${USER_STATUS_NAMES[status]}”`}
          confirmLabel="Đổi trạng thái"
          danger={status !== 'ACTIVE'}
          minLength={status === 'ACTIVE' ? 0 : 1}
          busy={change.isPending}
          error={change.error ? messageFor(change.error) : undefined}
          onClose={() => setConfirming(false)}
          onConfirm={(reason) => change.mutate({ status, reason }, { onSuccess: () => setConfirming(false) })}
        />
      )}
    </Card>
  );
}
