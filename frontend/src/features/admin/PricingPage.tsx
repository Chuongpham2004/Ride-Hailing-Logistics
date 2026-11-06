import { useId, useState } from 'react';
import { messageFor } from '@/shared/api/messages';
import type { PricingRule } from '@/shared/api/types';
import { SERVICE_NAMES } from '@/shared/drivers/text';
import { formatDateTime, formatVnd } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { Dialog } from '@/shared/ui/Dialog';
import { Money } from '@/shared/ui/Money';
import { Badge } from '@/shared/ui/StatusBadge';
import {
  groupRules,
  inFuture,
  MAX_TARIFF_PART,
  ruleState,
  type RuleGroup,
  type Tariff,
  usePricingRules,
  useScheduleRule,
} from './pricing';
import { FIELD } from './text';

const STATE_BADGES = {
  current: { tone: 'green', label: 'Đang áp dụng' },
  scheduled: { tone: 'amber', label: 'Sắp áp dụng' },
  past: { tone: 'grey', label: 'Đã thay' },
} as const;

const PARTS: { key: keyof Tariff; label: string }[] = [
  { key: 'baseFare', label: 'Giá mở cửa' },
  { key: 'perKm', label: 'Mỗi km' },
  { key: 'perMinute', label: 'Mỗi phút' },
  { key: 'minimumFare', label: 'Giá tối thiểu' },
];

/** /admin/pricing: the versioned price list per service and region, and the next version (Administrator). */
export default function PricingPage() {
  const rules = usePricingRules();
  const [scheduling, setScheduling] = useState<RuleGroup | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <div className="flex w-full flex-col gap-5 px-10 py-8 2xl:px-14">
      <div className="flex flex-col gap-1">
        <h1 className="m-0 text-[28px] leading-tight font-bold">Bảng giá</h1>
        <p className="m-0 max-w-[70ch] text-[15px] text-ink-2">
          Mỗi lần đổi giá là một phiên bản mới, bắt đầu từ một thời điểm trong tương lai. Báo giá đã phát
          trước đó giữ nguyên giá cũ. Khu vực mới được thêm bằng migration, không thêm ở đây.
        </p>
      </div>

      {notice && (
        <p
          role="status"
          className="m-0 rounded-lg bg-white px-4 py-3 text-[15px] shadow-[inset_4px_0_0_var(--color-guide)]"
        >
          {notice}
        </p>
      )}

      {rules.isPending && <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />}
      {rules.isError && (
        <div className="flex items-center gap-3">
          <p role="alert" className="m-0 text-[15px]">
            {messageFor(rules.error)}
          </p>
          <Button size="compact" variant="secondary" onClick={() => void rules.refetch()}>
            Thử lại
          </Button>
        </div>
      )}

      {rules.data &&
        groupRules(rules.data).map((group) => (
          <section
            key={`${group.serviceType}/${group.regionCode}`}
            className="flex flex-col gap-3 rounded-xl border border-line bg-white px-5 py-4"
          >
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="m-0 text-lg font-bold">
                {SERVICE_NAMES[group.serviceType]} · khu vực {group.regionCode}
              </h2>
              <Button size="compact" variant="dark" onClick={() => setScheduling(group)}>
                Thêm phiên bản
              </Button>
            </div>
            <table className="w-full border-collapse text-left text-[15px]">
              <thead className="text-sm text-curb">
                <tr>
                  <th className="py-1.5 pr-4 font-semibold">Phiên bản</th>
                  {PARTS.map((part) => (
                    <th key={part.key} className="py-1.5 pr-4 text-right font-semibold">
                      {part.label}
                    </th>
                  ))}
                  <th className="py-1.5 pr-4 font-semibold">Hiệu lực</th>
                  <th className="py-1.5 font-semibold">Trạng thái</th>
                </tr>
              </thead>
              <tbody>
                {group.rules.map((rule) => {
                  const state = STATE_BADGES[ruleState(rule)];
                  return (
                    <tr key={rule.id} className="border-t border-line">
                      <td className="py-2 pr-4 tabular-nums">{rule.version}</td>
                      {PARTS.map((part) => (
                        <td key={part.key} className="py-2 pr-4 text-right whitespace-nowrap">
                          <Money amount={rule[part.key]} />
                        </td>
                      ))}
                      <td className="py-2 pr-4 text-sm tabular-nums">
                        từ {formatDateTime(rule.effectiveFrom)}
                        {rule.effectiveTo && <div>đến {formatDateTime(rule.effectiveTo)}</div>}
                      </td>
                      <td className="py-2">
                        <Badge tone={state.tone}>{state.label}</Badge>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>
        ))}

      {scheduling && (
        <ScheduleDialog
          group={scheduling}
          onClose={() => setScheduling(null)}
          onDone={(rule) => {
            setScheduling(null);
            setNotice(
              `Đã lên lịch phiên bản ${rule.version} cho ${SERVICE_NAMES[rule.serviceType].toLowerCase()} khu vực ${rule.regionCode}, áp dụng từ ${formatDateTime(rule.effectiveFrom)}.`,
            );
          }}
        />
      )}
    </div>
  );
}

/** "yyyy-MM-ddTHH:mm" in local time, the value a datetime-local input takes. */
function localInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function ScheduleDialog({
  group,
  onClose,
  onDone,
}: {
  group: RuleGroup;
  onClose: () => void;
  onDone: (rule: PricingRule) => void;
}) {
  const schedule = useScheduleRule();
  const latest = group.rules[0]!;
  const [tariff, setTariff] = useState<Record<keyof Tariff, string>>({
    baseFare: String(latest.baseFare),
    perKm: String(latest.perKm),
    perMinute: String(latest.perMinute),
    minimumFare: String(latest.minimumFare),
  });
  // Default: tomorrow at midnight, local time.
  const [from, setFrom] = useState(() => {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    d.setHours(0, 0, 0, 0);
    return localInputValue(d);
  });
  const fromId = useId();
  const [pastStart, setPastStart] = useState(false);

  const parsed = Object.fromEntries(
    PARTS.map(({ key }) => {
      const digits = tariff[key].replace(/[\s.,]/g, '');
      return [key, /^\d+$/.test(digits) ? Number(digits) : NaN];
    }),
  ) as Record<keyof Tariff, number>;
  const partError = (key: keyof Tariff) =>
    Number.isNaN(parsed[key])
      ? 'Nhập số nguyên ≥ 0'
      : parsed[key] > MAX_TARIFF_PART
        ? `Tối đa ${formatVnd(MAX_TARIFF_PART)}`
        : undefined;
  const fromTime = from ? new Date(from).getTime() : NaN;
  const fromError = Number.isNaN(fromTime)
    ? 'Chọn thời điểm bắt đầu'
    : fromTime <= Date.parse(latest.effectiveFrom)
      ? `Phải sau khi phiên bản ${latest.version} bắt đầu (${formatDateTime(latest.effectiveFrom)})`
      : undefined;
  const ready = !fromError && PARTS.every(({ key }) => !partError(key));

  return (
    <Dialog
      title={`Phiên bản ${latest.version + 1} · ${SERVICE_NAMES[group.serviceType]} ${group.regionCode}`}
      onClose={onClose}
    >
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          if (!ready) return;
          if (!inFuture(fromTime)) {
            setPastStart(true);
            return;
          }
          schedule.mutate(
            {
              serviceType: group.serviceType,
              regionCode: group.regionCode,
              ...parsed,
              effectiveFrom: new Date(fromTime).toISOString(),
            },
            { onSuccess: onDone },
          );
        }}
      >
        <p className="m-0 text-[15px] text-ink-2">
          Điền sẵn theo phiên bản {latest.version}. Số tiền tính bằng đồng, không có phần lẻ.
        </p>
        <div className="grid grid-cols-2 gap-3">
          {PARTS.map(({ key, label }) => (
            <label key={key} className="flex flex-col gap-1 text-sm font-semibold">
              {label} (₫)
              <input
                inputMode="numeric"
                className={FIELD}
                value={tariff[key]}
                aria-invalid={partError(key) ? true : undefined}
                onChange={(e) => setTariff((t) => ({ ...t, [key]: e.target.value }))}
              />
              {partError(key) && <span className="font-normal text-stop">{partError(key)}</span>}
            </label>
          ))}
        </div>
        <label htmlFor={fromId} className="text-sm font-semibold">
          Áp dụng từ (giờ máy bạn)
        </label>
        <input
          id={fromId}
          type="datetime-local"
          className={FIELD}
          value={from}
          aria-invalid={fromError ? true : undefined}
          onChange={(e) => {
            setFrom(e.target.value);
            setPastStart(false);
          }}
        />
        {fromError && <span className="text-sm text-stop">{fromError}</span>}
        {pastStart && <span className="text-sm text-stop">Phải là một thời điểm trong tương lai</span>}
        {schedule.error && (
          <p role="alert" className="m-0 text-[15px] text-stop">
            {messageFor(schedule.error)}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button size="compact" variant="secondary" onClick={onClose}>
            Đóng
          </Button>
          <Button size="compact" type="submit" variant="dark" disabled={!ready} busy={schedule.isPending}>
            Lên lịch
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
