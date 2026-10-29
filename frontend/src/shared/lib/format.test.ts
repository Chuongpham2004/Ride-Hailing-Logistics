import { describe, expect, it } from 'vitest';
import { formatCountdown, formatDistance, formatDuration, formatVnd } from './format';

// Intl separates the amount and the symbol with a no-break space.
const plain = (s: string) => s.replace(/\s/g, ' ');

describe('format', () => {
  it('writes VND with dot grouping and the dong sign', () => {
    expect(plain(formatVnd(29000))).toBe('29.000 ₫');
    expect(plain(formatVnd(520))).toBe('520 ₫');
    expect(plain(formatVnd(1234567))).toBe('1.234.567 ₫');
  });

  it('writes metres below a kilometre and kilometres with a decimal comma above', () => {
    expect(formatDistance(850)).toBe('850 m');
    expect(formatDistance(3140)).toBe('3,1 km');
    expect(formatDistance(12000)).toBe('12 km');
  });

  it('rounds durations to whole minutes, never below one', () => {
    expect(formatDuration(20)).toBe('1 phút');
    expect(formatDuration(540)).toBe('9 phút');
    expect(formatDuration(3600)).toBe('1 giờ');
    expect(formatDuration(3900)).toBe('1 giờ 5 phút');
  });

  it('writes a countdown as m:ss', () => {
    expect(formatCountdown(292)).toBe('4:52');
    expect(formatCountdown(5)).toBe('0:05');
    expect(formatCountdown(-3)).toBe('0:00');
  });
});
