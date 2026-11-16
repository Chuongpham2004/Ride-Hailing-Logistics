import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type { PricingRule, ServiceType, SurgeRule } from '@/shared/api/types';

/* The versioned price list (Administrator). */

const rulesKey = ['admin', 'pricing'] as const;

export function usePricingRules() {
  return useQuery({
    queryKey: rulesKey,
    queryFn: ({ signal }) => api<PricingRule[]>('/api/v1/admin/pricing/rules', { signal }),
  });
}

/** The price list as a whole VND tariff; the server caps each part at 10 000 000. */
export interface Tariff {
  baseFare: number;
  perKm: number;
  perMinute: number;
  minimumFare: number;
}

export const MAX_TARIFF_PART = 10_000_000;

export interface ScheduleInput extends Tariff {
  serviceType: ServiceType;
  regionCode: string;
  /** Must be in the future and after the current version's start. */
  effectiveFrom: string;
}

export function useScheduleRule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: ScheduleInput) =>
      api<PricingRule>('/api/v1/admin/pricing/rules', { method: 'POST', body: input }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: rulesKey }),
  });
}

const surgeKey = ['admin', 'surge'] as const;

export function useSurgeRules() {
  return useQuery({
    queryKey: surgeKey,
    queryFn: ({ signal }) => api<SurgeRule[]>('/api/v1/admin/pricing/surge-rules', { signal }),
  });
}

export interface SurgeFormula {
  minDemand: number;
  ratioThreshold: number;
  slope: number;
  maxMultiplier: number;
  step: number;
}

export interface SurgeScheduleInput extends SurgeFormula {
  serviceType: ServiceType;
  effectiveFrom: string;
}

export function useScheduleSurgeRule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SurgeScheduleInput) =>
      api<SurgeRule>('/api/v1/admin/pricing/surge-rules', { method: 'POST', body: input }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: surgeKey }),
  });
}

/** Bounds of pricing-service SurgeRule.Formula; decimals have at most two places. */
export const SURGE_BOUNDS: Record<keyof SurgeFormula, { min: number; max: number; integer?: boolean }> = {
  minDemand: { min: 1, max: 10_000, integer: true },
  ratioThreshold: { min: 0, max: 100 },
  slope: { min: 0, max: 10 },
  maxMultiplier: { min: 1, max: 5 },
  step: { min: 0.01, max: 1 },
};

/** A typed number ("1,5" or "1.5"), or NaN when it breaks the field's bounds or precision. */
export function parseSurgeField(key: keyof SurgeFormula, text: string): number {
  const normalized = text.trim().replace(',', '.');
  const bounds = SURGE_BOUNDS[key];
  const pattern = bounds.integer ? /^\d+$/ : /^\d+(\.\d{1,2})?$/;
  if (!pattern.test(normalized)) return NaN;
  const value = Number(normalized);
  return value >= bounds.min && value <= bounds.max ? value : NaN;
}

/** Newest version first per service type, service types in a stable order. */
export function groupSurgeRules(rules: SurgeRule[]): { serviceType: ServiceType; rules: SurgeRule[] }[] {
  const groups = new Map<ServiceType, SurgeRule[]>();
  for (const rule of rules) groups.set(rule.serviceType, [...(groups.get(rule.serviceType) ?? []), rule]);
  return [...groups.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([serviceType, list]) => ({ serviceType, rules: list.sort((a, b) => b.version - a.version) }));
}

/** "yyyy-MM-ddTHH:mm" in local time, the value a datetime-local input takes. */
export function localInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Tomorrow at midnight, local time: the default start of a new version. */
export function tomorrowInput(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  d.setHours(0, 0, 0, 0);
  return localInputValue(d);
}

/** A new version may only start after now (pricing-service PricingRule.supersede). */
export function inFuture(epochMillis: number): boolean {
  return epochMillis > Date.now();
}

export type RuleState = 'past' | 'current' | 'scheduled';

/** Price list and surge versions share the same validity window. */
export function ruleState(
  rule: { effectiveFrom: string; effectiveTo?: string },
  now = Date.now(),
): RuleState {
  if (Date.parse(rule.effectiveFrom) > now) return 'scheduled';
  if (rule.effectiveTo && Date.parse(rule.effectiveTo) <= now) return 'past';
  return 'current';
}

export interface RuleGroup {
  serviceType: ServiceType;
  regionCode: string;
  /** Newest version first. */
  rules: PricingRule[];
}

/** One group per service and region, in a stable order, versions newest first. */
export function groupRules(rules: PricingRule[]): RuleGroup[] {
  const groups = new Map<string, RuleGroup>();
  for (const rule of rules) {
    const key = `${rule.serviceType}/${rule.regionCode}`;
    let group = groups.get(key);
    if (!group) {
      group = { serviceType: rule.serviceType, regionCode: rule.regionCode, rules: [] };
      groups.set(key, group);
    }
    group.rules.push(rule);
  }
  for (const group of groups.values()) group.rules.sort((a, b) => b.version - a.version);
  return [...groups.values()].sort(
    (a, b) => a.regionCode.localeCompare(b.regionCode) || a.serviceType.localeCompare(b.serviceType),
  );
}
