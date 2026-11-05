import { useState } from 'react';
import { useParams } from 'react-router';
import { messageFor } from '@/shared/api/messages';
import type { CurrentUser, Role, UserStatus } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { Button } from '@/shared/ui/Button';
import { Dialog } from '@/shared/ui/Dialog';
import { Card } from './Card';
import { IdLookup } from './IdLookup';
import { ReasonDialog } from './ReasonDialog';
import { ROLE_NAMES } from './sections';
import { FIELD, USER_STATUS_NAMES } from './text';
import { UUID } from './trips';
import { ROLES, useChangeStatus, useReplaceRoles } from './users';

/**
 * /admin/users and /admin/users/:userId (Administrator). The API has no user lookup yet
 * (frontend-design.md §10), so the page works from a known id and shows the account as the
 * server returns it after each change.
 */
export default function UsersPage() {
  const { userId } = useParams();
  return (
    <div className="flex max-w-4xl flex-col gap-5 px-10 py-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <h1 className="m-0 text-[28px] leading-tight font-bold">Người dùng</h1>
        <IdLookup key={userId} label="Mã người dùng" initial={userId} to={(id) => `/admin/users/${id}`} />
      </div>
      <p className="m-0 max-w-[70ch] text-[15px] text-ink-2">
        Chưa có danh sách người dùng: nhập mã người dùng (mã tài xế cũng là mã người dùng của tài xế). Vai trò
        được thay cả bộ, nên chọn đủ mọi vai trò người này cần giữ.
      </p>
      {userId && !UUID.test(userId) && <p role="alert">Mã người dùng không hợp lệ.</p>}
      {userId && UUID.test(userId) && <Account key={userId} userId={userId} />}
    </div>
  );
}

function Account({ userId }: { userId: string }) {
  const me = useSession((state) => state.user);
  const [account, setAccount] = useState<CurrentUser | null>(null);
  const own = me?.id === userId;

  return (
    <>
      {own && (
        <p className="m-0 rounded-lg bg-stop-tint px-4 py-3 text-[15px]">
          Đây là tài khoản của bạn: không tự đổi trạng thái hay bỏ vai trò quản trị của chính mình được.
        </p>
      )}
      {account && <AccountSummary account={account} />}
      <RolesForm userId={userId} current={account?.roles} onSaved={setAccount} />
      {!own && (
        <StatusForm
          userId={userId}
          current={account?.status as UserStatus | undefined}
          onSaved={setAccount}
        />
      )}
    </>
  );
}

function AccountSummary({ account }: { account: CurrentUser }) {
  return (
    <Card title={account.fullName}>
      <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-8 gap-y-2 text-[15px]">
        <dt className="text-curb">Email</dt>
        <dd className="m-0">{account.email ?? '—'}</dd>
        <dt className="text-curb">Số điện thoại</dt>
        <dd className="m-0">{account.phone ?? '—'}</dd>
        <dt className="text-curb">Vai trò</dt>
        <dd className="m-0">{account.roles.map((role) => ROLE_NAMES[role]).join(', ')}</dd>
        <dt className="text-curb">Trạng thái</dt>
        <dd className="m-0">{USER_STATUS_NAMES[account.status as UserStatus] ?? account.status}</dd>
      </dl>
    </Card>
  );
}

function RolesForm({
  userId,
  current,
  onSaved,
}: {
  userId: string;
  current?: Role[];
  onSaved: (account: CurrentUser) => void;
}) {
  const replace = useReplaceRoles(userId);
  const [roles, setRoles] = useState<Role[]>(current ?? []);
  const [confirming, setConfirming] = useState(false);
  const toggle = (role: Role) =>
    setRoles((list) => (list.includes(role) ? list.filter((r) => r !== role) : [...list, role]));

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
          disabled={roles.length === 0}
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
            Người dùng sẽ có đúng các vai trò:{' '}
            <strong>
              {ROLES.filter((r) => roles.includes(r))
                .map((r) => ROLE_NAMES[r])
                .join(', ')}
            </strong>
            . Vai trò khác (nếu có) bị bỏ. Phiên đăng nhập hiện tại của họ nhận vai trò mới khi làm mới token.
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
              onClick={() =>
                replace.mutate(roles, {
                  onSuccess: (account) => {
                    setConfirming(false);
                    onSaved(account);
                  },
                })
              }
            >
              Thay vai trò
            </Button>
          </div>
        </Dialog>
      )}
    </Card>
  );
}

function StatusForm({
  userId,
  current,
  onSaved,
}: {
  userId: string;
  current?: UserStatus;
  onSaved: (account: CurrentUser) => void;
}) {
  const change = useChangeStatus(userId);
  const [status, setStatus] = useState<UserStatus>(current === 'ACTIVE' ? 'LOCKED' : 'ACTIVE');
  const [confirming, setConfirming] = useState(false);

  return (
    <Card title="Trạng thái tài khoản">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm font-semibold">
          Chuyển sang
          <select className={FIELD} value={status} onChange={(e) => setStatus(e.target.value as UserStatus)}>
            {(Object.keys(USER_STATUS_NAMES) as UserStatus[]).map((s) => (
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
        Tạm khoá: chặn đăng nhập, mở lại được. Vô hiệu hoá: dùng khi đóng hẳn tài khoản.
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
          onConfirm={(reason) =>
            change.mutate(
              { status, reason },
              {
                onSuccess: (account) => {
                  setConfirming(false);
                  onSaved(account);
                },
              },
            )
          }
        />
      )}
    </Card>
  );
}
