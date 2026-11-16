import { useId, useState } from 'react';
import { messageFor } from '@/shared/api/messages';
import type { ServiceType, SurgeRule } from '@/shared/api/types';
import { SERVICE_NAMES } from '@/shared/drivers/text';
import { formatDateTime } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { Dialog } from '@/shared/ui/Dialog';
import { Badge } from '@/shared/ui/StatusBadge';
import {
  groupSurgeRules,
  inFuture,
  parseSurgeField,
  ruleState,
  SURGE_BOUNDS,
  type SurgeFormula,
  tomorrowInput,
  useScheduleSurgeRule,
  useSurgeRules,
} from './pricing';
import { FIELD } from './text';

const STATE_BADGES = {
  current: { tone: 'green', label: 'Đang áp dụng' },
  scheduled: { tone: 'amber', label: 'Sắp áp dụng' },
  past: { tone: 'grey', label: 'Đã thay' },
} as const;

const FIELDS: { key: keyof SurgeFormula; label: string; hint: string }[] = [
  { key: 'minDemand', label: 'Cầu tối thiểu', hint: 'Ít yêu cầu hơn thì không tăng giá' },
  { key: 'ratioThreshold', label: 'Ngưỡng cầu/cung', hint: 'Bắt đầu tăng khi vượt tỉ lệ này' },
  { key: 'slope', label: 'Độ dốc', hint: 'Mức tăng cho mỗi đơn vị tỉ lệ vượt ngưỡng' },
  { key: 'maxMultiplier', label: 'Hệ số tối đa', hint: 'Trần, không vượt quá' },
  { key: 'step', label: 'Bước làm tròn', hint: 'Làm tròn xuống theo bước này' },
];

const number = (value: number) => value.toLocaleString('vi-VN', { maximumFractionDigits: 2 });

/** Surge rule versions per service type and the next version (Administrator, FR-PRI-007…012). */
export function SurgeRules({ onScheduled }: { onScheduled: (notice: string) => void }) {
  const rules = useSurgeRules();
  const [scheduling, setScheduling] = useState<{ serviceType: ServiceType; latest: SurgeRule } | null>(null);

  return (
    <>
      <div className="mt-3 flex flex-col gap-1">
        <h2 className="m-0 text-[22px] leading-tight font-bold">Hệ số cao điểm</h2>
        <p className="m-0 max-w-[70ch] text-[15px] text-ink-2">
          Hệ số = 1 + độ dốc × (cầu ÷ cung − ngưỡng), giữ trong khoảng 1 đến hệ số tối đa và làm tròn xuống
          theo bước. Cầu là số yêu cầu, cung là số tài xế rảnh quanh điểm đón.
        </p>
      </div>

      {rules.isPending && <div aria-hidden="true" className="h-40 animate-pulse rounded-xl bg-white" />}
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
        groupSurgeRules(rules.data).map((group) => (
          <section
            key={group.serviceType}
            aria-label={`Hệ số cao điểm ${SERVICE_NAMES[group.serviceType]}`}
            className="flex flex-col gap-3 rounded-xl border border-line bg-white px-5 py-4"
          >
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h3 className="m-0 text-lg font-bold">{SERVICE_NAMES[group.serviceType]}</h3>
              <Button
                size="compact"
                variant="dark"
                onClick={() => setScheduling({ serviceType: group.serviceType, latest: group.rules[0]! })}
              >
                Thêm phiên bản
              </Button>
            </div>
            <table className="w-full border-collapse text-left text-[15px]">
              <thead className="text-sm text-curb">
                <tr>
                  <th className="py-1.5 pr-4 font-semibold">Phiên bản</th>
                  {FIELDS.map((field) => (
                    <th key={field.key} className="py-1.5 pr-4 text-right font-semibold">
                      {field.label}
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
                      {FIELDS.map((field) => (
                        <td key={field.key} className="py-2 pr-4 text-right tabular-nums">
                          {field.key === 'maxMultiplier' ? '×' : ''}
                          {number(rule[field.key])}
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
        <SurgeDialog
          serviceType={scheduling.serviceType}
          latest={scheduling.latest}
          onClose={() => setScheduling(null)}
          onDone={(rule) => {
            setScheduling(null);
            onScheduled(
              `Đã lên lịch hệ số cao điểm phiên bản ${rule.version} cho ${SERVICE_NAMES[rule.serviceType].toLowerCase()}, áp dụng từ ${formatDateTime(rule.effectiveFrom)}.`,
            );
          }}
        />
      )}
    </>
  );
}

function SurgeDialog({
  serviceType,
  latest,
  onClose,
  onDone,
}: {
  serviceType: ServiceType;
  latest: SurgeRule;
  onClose: () => void;
  onDone: (rule: SurgeRule) => void;
}) {
  const schedule = useScheduleSurgeRule();
  const [values, setValues] = useState<Record<keyof SurgeFormula, string>>(() => ({
    minDemand: String(latest.minDemand),
    ratioThreshold: String(latest.ratioThreshold),
    slope: String(latest.slope),
    maxMultiplier: String(latest.maxMultiplier),
    step: String(latest.step),
  }));
  const [from, setFrom] = useState(tomorrowInput);
  const [pastStart, setPastStart] = useState(false);
  const fromId = useId();

  const parsed = Object.fromEntries(
    FIELDS.map(({ key }) => [key, parseSurgeField(key, values[key])]),
  ) as Record<keyof SurgeFormula, number>;
  const fieldError = (key: keyof SurgeFormula) => {
    if (!Number.isNaN(parsed[key])) return undefined;
    const { min, max, integer } = SURGE_BOUNDS[key];
    return `${integer ? 'Số nguyên' : 'Số, tối đa 2 chữ số thập phân'} từ ${number(min)} đến ${number(max)}`;
  };
  const fromTime = from ? new Date(from).getTime() : NaN;
  const fromError = Number.isNaN(fromTime)
    ? 'Chọn thời điểm bắt đầu'
    : fromTime <= Date.parse(latest.effectiveFrom)
      ? `Phải sau khi phiên bản ${latest.version} bắt đầu (${formatDateTime(latest.effectiveFrom)})`
      : undefined;
  const ready = !fromError && FIELDS.every(({ key }) => !fieldError(key));

  return (
    <Dialog
      title={`Hệ số cao điểm phiên bản ${latest.version + 1} · ${SERVICE_NAMES[serviceType]}`}
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
            { serviceType, ...parsed, effectiveFrom: new Date(fromTime).toISOString() },
            { onSuccess: onDone },
          );
        }}
      >
        <p className="m-0 text-[15px] text-ink-2">Điền sẵn theo phiên bản {latest.version}.</p>
        <div className="grid grid-cols-2 gap-3">
          {FIELDS.map(({ key, label, hint }) => (
            <label key={key} className="flex flex-col gap-1 text-sm font-semibold">
              {label}
              <input
                inputMode="decimal"
                className={FIELD}
                value={values[key]}
                aria-invalid={fieldError(key) ? true : undefined}
                onChange={(e) => setValues((v) => ({ ...v, [key]: e.target.value }))}
              />
              <span className={`font-normal ${fieldError(key) ? 'text-stop' : 'text-curb'}`}>
                {fieldError(key) ?? hint}
              </span>
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
