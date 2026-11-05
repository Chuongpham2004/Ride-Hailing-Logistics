import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type { PricingRule, ServiceType } from '@/shared/api/types';

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

/** A new version may only start after now (pricing-service PricingRule.supersede). */
export function inFuture(epochMillis: number): boolean {
  return epochMillis > Date.now();
}

export type RuleState = 'past' | 'current' | 'scheduled';

export function ruleState(rule: PricingRule, now = Date.now()): RuleState {
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
