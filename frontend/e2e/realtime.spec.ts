import { expect, test } from '@playwright/test';
import {
  acceptOffer,
  accounts,
  apiLogin,
  bookByApi,
  cancelOpenTrips,
  driveTo,
  driverOffline,
  driverOnline,
  phoneContexts,
  requireAccounts,
  uiLogin,
} from './support';

/*
 * UC-07: the customer's phone loses the network in the middle of a trip. The app says so, and
 * once the network is back it reconnects at once and shows what happened meanwhile without a
 * page reload (fresh snapshot after the reconnect; the server kept the state).
 */
test('the customer catches up after losing the network mid-trip', async ({ browser }) => {
  requireAccounts('customer', 'driver');
  const customerToken = await apiLogin(accounts.customer!);
  await cancelOpenTrips(customerToken);

  const contexts = await phoneContexts(browser);
  const customer = await contexts.customer.newPage();
  const driver = await contexts.driver.newPage();
  try {
    await driverOnline(driver);
    await uiLogin(customer, accounts.customer!, 'app');
    const trip = await bookByApi(customerToken, 'RIDE');
    await customer.goto(`/app/trips/${trip.id}`);

    await acceptOffer(driver, trip.id);
    await expect(customer.getByText('Tài xế đang đến đón')).toBeVisible();

    await contexts.customer.setOffline(true);
    await expect(customer.getByRole('status').filter({ hasText: 'Không có mạng' })).toBeVisible();

    // Meanwhile the driver drives to the pickup and arrives.
    await driver.getByRole('button', { name: 'Bắt đầu đi đón' }).click();
    await driveTo(driver, 'Giả lập: chạy tới điểm đón');
    await driver.getByRole('button', { name: 'Đã đến điểm đón' }).click();
    await expect(driver.getByLabel(/mã đón/)).toBeVisible();
    await expect(customer.getByText('Tài xế đã đến điểm đón')).toBeHidden();

    const backOnline = Date.now();
    await contexts.customer.setOffline(false);
    await expect(customer.getByText('Tài xế đã đến điểm đón')).toBeVisible({ timeout: 15_000 });
    await expect(customer.getByRole('status').filter({ hasText: /Không có mạng|Mất kết nối/ })).toBeHidden();
    // Reconnecting on the "online" event, not after the 10 s backoff.
    expect(Date.now() - backOnline).toBeLessThan(8000);

    await driver.getByRole('button', { name: 'Huỷ chuyến' }).click();
    await driver.getByRole('radio', { name: 'Khách không ra điểm đón' }).check();
    await driver.getByRole('button', { name: 'Huỷ chuyến' }).click();
    await expect(customer.getByText('Tài xế đã huỷ chuyến')).toBeVisible();
  } finally {
    await contexts.customer.setOffline(false).catch(() => undefined);
    await driverOffline(driver);
    await contexts.customer.close();
    await contexts.driver.close();
  }
});
