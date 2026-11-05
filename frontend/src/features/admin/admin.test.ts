import { describe, expect, it } from 'vitest';
import { canUse, sectionsFor } from './sections';
import { changeReason, shortId } from './text';
import { dayStart, searchQuery } from './trips';

describe('sectionsFor', () => {
  it('shows each role only its own sections', () => {
    expect(sectionsFor(['REVIEWER']).map((s) => s.key)).toEqual(['drivers']);
    expect(sectionsFor(['SUPPORT_STAFF']).map((s) => s.key)).toEqual(['trips']);
    expect(sectionsFor(['ADMINISTRATOR']).map((s) => s.key)).toEqual(['drivers', 'trips']);
    expect(sectionsFor(['FINANCE_STAFF'])).toEqual([]);
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
