import { describe, expect, it } from 'vitest';
import { normalisePlate, PLATE, requirementText, todayIso } from './onboarding';

describe('onboarding helpers', () => {
  it('turns the server requirement sentences into Vietnamese', () => {
    expect(requirementText('DRIVER_LICENSE is missing')).toBe('Giấy phép lái xe còn thiếu');
    expect(requirementText('NATIONAL_ID has expired')).toBe('CCCD / CMND đã hết hạn');
    expect(requirementText('VEHICLE_INSURANCE of 59X123456 has expired')).toBe(
      'Bảo hiểm xe của xe 59X123456 đã hết hạn',
    );
    expect(requirementText('At least one active vehicle is required')).toBe('Thêm ít nhất một xe');
    expect(requirementText('Verify your email or phone number first')).toBe(
      'Xác minh email hoặc số điện thoại',
    );
    expect(requirementText('Something new')).toBe('Something new');
  });

  it('normalises plates like the server does', () => {
    expect(normalisePlate('59x1-234.56')).toBe('59X123456');
    expect(PLATE.test(normalisePlate('51F 123.45'))).toBe(true);
    expect(PLATE.test(normalisePlate('ABC123'))).toBe(false);
  });

  it('gives today as a local calendar date', () => {
    expect(todayIso(new Date(2026, 0, 5, 23, 30))).toBe('2026-01-05');
  });
});
