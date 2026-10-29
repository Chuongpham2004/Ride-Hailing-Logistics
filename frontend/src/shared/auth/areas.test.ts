import { describe, expect, it } from 'vitest';
import { canEnter, homeFor } from './areas';

describe('areas', () => {
  it('sends each role to its own area', () => {
    expect(homeFor(['CUSTOMER'])).toBe('/app');
    expect(homeFor(['DRIVER'])).toBe('/driver');
    expect(homeFor(['FINANCE_STAFF'])).toBe('/admin');
    expect(homeFor(['ADMINISTRATOR'])).toBe('/admin');
    expect(homeFor([])).toBe('/login');
  });

  it('lets staff roles into the admin area only', () => {
    expect(canEnter('admin', ['REVIEWER'])).toBe(true);
    expect(canEnter('customer', ['REVIEWER'])).toBe(false);
    expect(canEnter('driver', ['CUSTOMER'])).toBe(false);
  });
});
