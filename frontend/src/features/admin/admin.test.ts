import { describe, expect, it } from 'vitest';
import { parseVnd } from './finance';
import { groupRules, ruleState } from './pricing';
import { canUse, sectionsFor } from './sections';
import { changeReason, shortId } from './text';
import { dayStart, searchQuery } from './trips';

describe('sectionsFor', () => {
  it('shows each role only its own sections', () => {
    expect(sectionsFor(['REVIEWER']).map((s) => s.key)).toEqual(['drivers']);
    expect(sectionsFor(['SUPPORT_STAFF']).map((s) => s.key)).toEqual(['trips']);
    expect(sectionsFor(['FINANCE_STAFF']).map((s) => s.key)).toEqual(['payments', 'wallets']);
    expect(sectionsFor(['ADMINISTRATOR']).map((s) => s.key)).toEqual([
      'drivers',
      'trips',
      'payments',
      'wallets',
      'pricing',
      'users',
    ]);
  });

  it('combines the sections of several roles', () => {
    expect(canUse('trips', ['REVIEWER', 'SUPPORT_STAFF'])).toBe(true);
    expect(canUse('drivers', ['SUPPORT_STAFF'])).toBe(false);
  });
});

describe('searchQuery', () => {
  it('sends local calendar days as an instant range with an exclusive end', () => {
    const query = new URLSearchParams(searchQuery({ from: '2026-11-05', to: '2026-11-05' }));
    expect(query.get('createdFrom')).toBe(new Date(2026, 10, 5).toISOString());
    expect(query.get('createdTo')).toBe(new Date(2026, 10, 6).toISOString());
  });

  it('leaves out empty filters', () => {
    expect(searchQuery({ status: 'COMPLETED' })).toBe('status=COMPLETED&limit=20');
  });

  it('rolls the exclusive end over a month boundary', () => {
    expect(dayStart('2026-11-30', true)).toBe(new Date(2026, 11, 1).toISOString());
  });
});

describe('shortId and changeReason', () => {
  it('keeps the end of a UUIDv7, where rows differ', () => {
    expect(shortId('01a1aae3-0000-7000-8000-00000000abcd')).toBe('…0000abcd');
  });

  it('names cancel reasons and keeps other reasons as sent', () => {
    expect(changeReason('SAFETY_CONCERN')).toBe('Lo ngại an toàn');
    expect(changeReason('offer expired')).toBe('offer expired');
  });
});

describe('parseVnd', () => {
  it('reads whole VND written with dots or spaces', () => {
    expect(parseVnd('38.000')).toBe(38000);
    expect(parseVnd('1 200 000')).toBe(1200000);
    expect(parseVnd('500')).toBe(500);
  });

  it('refuses zero, negatives, decimals and words', () => {
    for (const text of ['0', '-5', '12,5x', 'abc', '']) expect(parseVnd(text)).toBeNull();
  });
});

describe('pricing rules', () => {
  const rule = (version: number, from: string, to?: string, serviceType: 'RIDE' | 'DELIVERY' = 'RIDE') => ({
    id: `r${serviceType}${version}`,
    serviceType,
    regionCode: 'HCM',
    version,
    baseFare: 10000,
    perKm: 5000,
    perMinute: 300,
    minimumFare: 15000,
    currency: 'VND',
    effectiveFrom: from,
    effectiveTo: to,
    createdAt: from,
  });
  const now = Date.parse('2026-11-05T12:00:00Z');

  it('tells past, current and scheduled versions apart', () => {
    expect(ruleState(rule(1, '2026-01-01T00:00:00Z', '2026-11-01T00:00:00Z'), now)).toBe('past');
    expect(ruleState(rule(2, '2026-11-01T00:00:00Z', '2026-12-01T00:00:00Z'), now)).toBe('current');
    expect(ruleState(rule(3, '2026-12-01T00:00:00Z'), now)).toBe('scheduled');
  });

  it('groups by service and region with the newest version first', () => {
    const groups = groupRules([
      rule(1, '2026-01-01T00:00:00Z', '2026-11-01T00:00:00Z'),
      rule(1, '2026-01-01T00:00:00Z', undefined, 'DELIVERY'),
      rule(2, '2026-11-01T00:00:00Z'),
    ]);
    expect(groups.map((g) => [g.serviceType, g.rules.map((r) => r.version)])).toEqual([
      ['DELIVERY', [1]],
      ['RIDE', [2, 1]],
    ]);
  });
});
