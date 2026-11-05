import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { messageFor } from '@/shared/api/messages';
import type { Role, UserStatus } from '@/shared/api/types';
import { formatDateTime } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { ROLE_NAMES } from './sections';
import { FIELD, USER_STATUS_NAMES } from './text';
import { UserStatusBadge } from './UserStatusBadge';
import { MIN_QUERY, ROLES, useUserSearch, type UserFilter } from './users';

const STATUSES = Object.keys(USER_STATUS_NAMES) as UserStatus[];

/** The filter lives in the address, so a search can be reloaded or shared. */
function readFilter(params: URLSearchParams): UserFilter {
  const filter: UserFilter = {};
  const q = params.get('q')?.trim();
  if (q && q.length >= MIN_QUERY) filter.q = q;
  const role = params.get('role') as Role | null;
  if (role && ROLES.includes(role)) filter.role = role;
  const status = params.get('status') as UserStatus | null;
  if (status && STATUSES.includes(status)) filter.status = status;
  return filter;
}

/** /admin/users: every account, newest first, by name, email, phone or id, role and status (Administrator). */
export default function UsersPage() {
  const [params, setParams] = useSearchParams();
  const filter = readFilter(params);
  const users = useUserSearch(filter);
  const items = users.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <div className="flex max-w-6xl flex-col gap-5 px-10 py-8">
      <h1 className="m-0 text-[28px] leading-tight font-bold">Người dùng</h1>

      <Filters
        key={params.toString()}
        initial={filter}
        onApply={(next) => {
          const query: Record<string, string> = {};
          if (next.q) query.q = next.q;
          if (next.role) query.role = next.role;
          if (next.status) query.status = next.status;
          setParams(query);
        }}
      />

      {users.isPending && <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />}
      {users.isError && (
        <div className="flex items-center gap-3">
          <p role="alert" className="m-0 text-[15px]">
            {messageFor(users.error)}
          </p>
          <Button size="compact" variant="secondary" onClick={() => void users.refetch()}>
            Thử lại
          </Button>
        </div>
      )}
      {users.isSuccess && items.length === 0 && (
        <p className="m-0 text-ink-2">Không có người dùng nào khớp.</p>
      )}

      {items.length > 0 && (
        <div className="overflow-x-auto rounded-xl border border-line bg-white">
          <table className="w-full border-collapse text-left text-[15px]">
            <thead className="bg-concrete-2 text-sm text-curb">
              <tr>
                <th className="px-4 py-2.5 font-semibold">Họ tên</th>
                <th className="px-4 py-2.5 font-semibold">Liên hệ</th>
                <th className="px-4 py-2.5 font-semibold">Vai trò</th>
                <th className="px-4 py-2.5 font-semibold">Tạo lúc</th>
                <th className="px-4 py-2.5 font-semibold">Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {items.map((user) => (
                <tr key={user.id} className="border-t border-line align-top hover:bg-concrete-2">
                  <td className="px-4 py-3">
                    <Link to={`/admin/users/${user.id}`} className="font-semibold">
                      {user.fullName}
                    </Link>
                  </td>
                  <td className="px-4 py-3">
                    {user.email && <div>{user.email}</div>}
                    {user.phone && <div className="text-curb tabular-nums">{user.phone}</div>}
                  </td>
                  <td className="px-4 py-3">{user.roles.map((role) => ROLE_NAMES[role]).join(', ')}</td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {formatDateTime(user.createdAt)}
                  </td>
                  <td className="px-4 py-3">
                    <UserStatusBadge status={user.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {users.hasNextPage && (
        <Button
          size="compact"
          variant="secondary"
          className="self-start"
          busy={users.isFetchingNextPage}
          onClick={() => void users.fetchNextPage()}
        >
          Xem thêm
        </Button>
      )}
    </div>
  );
}

function Filters({ initial, onApply }: { initial: UserFilter; onApply: (filter: UserFilter) => void }) {
  const [q, setQ] = useState(initial.q ?? '');
  const [role, setRole] = useState<Role | ''>(initial.role ?? '');
  const [status, setStatus] = useState<UserStatus | ''>(initial.status ?? '');
  const [tooShort, setTooShort] = useState(false);

  return (
    <form
      aria-label="Tìm người dùng"
      className="grid grid-cols-[minmax(260px,2fr)_repeat(2,minmax(160px,1fr))_auto] items-end gap-3 rounded-xl border border-line bg-white p-4"
      onSubmit={(event) => {
        event.preventDefault();
        const text = q.trim();
        if (text.length > 0 && text.length < MIN_QUERY) {
          setTooShort(true);
          return;
        }
        onApply({ q: text || undefined, role: role || undefined, status: status || undefined });
      }}
    >
      <label className="flex flex-col gap-1 text-sm font-semibold">
        Tên, email, số điện thoại hoặc mã
        <input
          className={FIELD}
          value={q}
          aria-invalid={tooShort || undefined}
          onChange={(e) => {
            setQ(e.target.value);
            setTooShort(false);
          }}
        />
        {tooShort && <span className="font-normal text-stop">Nhập ít nhất {MIN_QUERY} ký tự</span>}
      </label>
      <label className="flex flex-col gap-1 text-sm font-semibold">
        Vai trò
        <select className={FIELD} value={role} onChange={(e) => setRole(e.target.value as Role | '')}>
          <option value="">Tất cả</option>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {ROLE_NAMES[r]}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-sm font-semibold">
        Trạng thái
        <select
          className={FIELD}
          value={status}
          onChange={(e) => setStatus(e.target.value as UserStatus | '')}
        >
          <option value="">Tất cả</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {USER_STATUS_NAMES[s]}
            </option>
          ))}
        </select>
      </label>
      <Button size="compact" variant="dark" type="submit">
        Tìm
      </Button>
    </form>
  );
}
