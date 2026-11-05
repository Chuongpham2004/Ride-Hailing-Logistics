import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';

/*
 * Shared pieces of the end-to-end specs: accounts from the environment, a small REST client for
 * the steps the specs do not test through the UI, and the driver's moves in the web app.
 */

export const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

interface Account {
  identifier: string;
  password: string;
}

function account(name: string, passwordName = 'E2E_PASSWORD'): Account | null {
  const identifier = process.env[name];
  const password = process.env[passwordName] ?? process.env.E2E_PASSWORD;
  return identifier && password ? { identifier, password } : null;
}

export const accounts = {
  customer: account('E2E_CUSTOMER'),
  /** An approved driver with an active vehicle that serves RIDE and DELIVERY. */
  driver: account('E2E_DRIVER'),
  admin: account('E2E_ADMIN', 'E2E_ADMIN_PASSWORD'),
};

/** Skips the calling spec when its accounts are not configured (see e2e/README.md). */
export function requireAccounts(...names: (keyof typeof accounts)[]): void {
  const missing = names.filter((name) => !accounts[name]);
  test.skip(
    missing.length > 0,
    `Set the e2e accounts first (missing: ${missing.join(', ')}); see e2e/README.md`,
  );
}

export async function call<T>(
  path: string,
  {
    token,
    method = 'GET',
    body,
    headers = {},
  }: { token?: string; method?: string; body?: unknown; headers?: Record<string, string> } = {},
): Promise<T> {
  const response = await fetch(API + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = (await response.json().catch(() => ({}))) as { code?: string; message?: string; data?: T };
  if (!response.ok) throw new Error(`${method} ${path} -> ${response.status} ${json.code} ${json.message}`);
  return json.data as T;
}

export async function apiLogin(who: Account): Promise<string> {
  const pair = await call<{ accessToken: string }>('/api/v1/auth/login', {
    method: 'POST',
    body: { identifier: who.identifier, password: who.password },
  });
  return pair.accessToken;
}

export interface Trip {
  id: string;
  status: string;
  pickupCode?: string;
  deliveryCode?: string;
}

/** Near the default simulated position, so the shared driver is the closest one. */
export const PICKUP = { latitude: 10.779, longitude: 106.702, address: 'Điểm đón e2e, Quận 1' };
export const DROPOFF = { latitude: 10.783, longitude: 106.7055, address: 'Điểm đến e2e, Quận 1' };
const DRIVER_START = { latitude: 10.7769, longitude: 106.7009 };

const OPEN = ['CREATED', 'MATCHING', 'ACCEPTED', 'PICKING_UP', 'ARRIVED', 'IN_TRIP'];

/** Leftovers of an earlier, interrupted run would block a new booking: cancel them. */
export async function cancelOpenTrips(customerToken: string): Promise<void> {
  const page = await call<{ items: Trip[] }>('/api/v1/trips?limit=5', { token: customerToken });
  for (const trip of page.items.filter((t) => OPEN.includes(t.status) && t.status !== 'IN_TRIP')) {
    await call(`/api/v1/trips/${trip.id}/cancel`, {
      token: customerToken,
      method: 'POST',
      body: { reason: 'OTHER' },
    }).catch(() => undefined);
  }
}

export async function createQuote(customerToken: string, serviceType: 'RIDE' | 'DELIVERY'): Promise<string> {
  const quote = await call<{ id: string }>('/api/v1/quotes', {
    token: customerToken,
    method: 'POST',
    body: { serviceType, pickup: PICKUP, dropoff: DROPOFF },
  });
  return quote.id;
}

export async function bookByApi(customerToken: string, serviceType: 'RIDE' | 'DELIVERY'): Promise<Trip> {
  const quote = await call<{ id: string; surgeConfirmationRequired: boolean; surgeMultiplier: number }>(
    '/api/v1/quotes',
    { token: customerToken, method: 'POST', body: { serviceType, pickup: PICKUP, dropoff: DROPOFF } },
  );
  const body: Record<string, unknown> = { quoteId: quote.id };
  if (quote.surgeConfirmationRequired) body.acceptedSurgeMultiplier = quote.surgeMultiplier;
  return call<Trip>('/api/v1/trips', {
    token: customerToken,
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  });
}

export async function uiLogin(page: Page, who: Account, area: 'app' | 'driver' | 'admin'): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email hoặc số điện thoại').fill(who.identifier);
  await page.getByLabel('Mật khẩu').fill(who.password);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(`**/${area}**`);
}

/** Phone-sized contexts; the driver's has a GPS fix next to the pickup. */
export async function phoneContexts(
  browser: Browser,
): Promise<{ customer: BrowserContext; driver: BrowserContext }> {
  const size = { viewport: { width: 420, height: 860 } };
  return {
    customer: await browser.newContext(size),
    driver: await browser.newContext({
      ...size,
      permissions: ['geolocation'],
      geolocation: { ...DRIVER_START, accuracy: 10 },
    }),
  };
}

/** Signs the driver in, goes online and switches to the simulated position so the car can drive. */
export async function driverOnline(page: Page): Promise<void> {
  await uiLogin(page, accounts.driver!, 'driver');
  await expect(page.getByText('GPS tốt')).toBeVisible();
  const goOnline = page.getByRole('button', { name: 'Bật online' });
  if (await goOnline.isVisible().catch(() => false)) await goOnline.click();
  await expect(page.getByText('Đang online, chờ chuyến')).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'Giả lập' }).click();
  await expect(page.getByText(/Vị trí giả lập/)).toBeVisible();
}

/** Accepts the next offer and waits for the driver's trip screen. */
export async function acceptOffer(page: Page, tripId: string): Promise<void> {
  await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 45_000 });
  await page.getByRole('button', { name: 'Nhận chuyến' }).click();
  await page.waitForURL(`**/driver/trips/${tripId}`);
}

/** Simulated drive to the next stop: the button is disabled while the car moves. */
export async function driveTo(page: Page, label: string): Promise<void> {
  await page.getByRole('button', { name: label }).click();
  await expect(page.getByRole('button', { name: 'Xe giả lập đang chạy…' })).toBeVisible();
  await expect(page.getByRole('button', { name: label })).toBeEnabled({ timeout: 120_000 });
  // One more location report from the stop, so the server sees the car there.
  await page.waitForTimeout(3500);
}

/**
 * Leaves the shared driver offline for the next run: through the app when the page still works,
 * otherwise (a timed-out test closes the page first) straight through the API.
 */
export async function driverOffline(page: Page): Promise<void> {
  try {
    await page.goto('/driver');
    const off = page.getByRole('button', { name: 'Tắt online' });
    await off.click({ timeout: 10_000 });
    await expect(page.getByText('Bạn đang offline')).toBeVisible({ timeout: 10_000 });
  } catch {
    const token = await apiLogin(accounts.driver!);
    await call('/api/v1/drivers/me/availability/offline', { token, method: 'POST' }).catch(() => undefined);
  }
}
