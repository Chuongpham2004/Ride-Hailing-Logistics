import { expect, test } from '@playwright/test';
import {
  acceptOffer,
  accounts,
  apiLogin,
  call,
  cancelOpenTrips,
  createQuote,
  driveTo,
  driverOffline,
  driverOnline,
  phoneContexts,
  requireAccounts,
  uiLogin,
  type Trip,
} from './support';

/*
 * UC-02 → UC-04: the customer books from a quote in the web app, the driver takes the offer and
 * runs the trip in the web app, both sides follow it live, and the receipt shows the payment.
 */
test('a ride from quote to receipt, followed live on both sides', async ({ browser }) => {
  requireAccounts('customer', 'driver');
  const customerToken = await apiLogin(accounts.customer!);
  await cancelOpenTrips(customerToken);

  const contexts = await phoneContexts(browser);
  const customer = await contexts.customer.newPage();
  const driver = await contexts.driver.newPage();
  try {
    await driverOnline(driver);
    await uiLogin(customer, accounts.customer!, 'app');

    // Address search uses public geocoders, so the quote comes from the API; booking is in the UI.
    const quoteId = await createQuote(customerToken, 'RIDE');
    await customer.goto(`/app/quote/${quoteId}`);
    const book = customer.getByRole('button', { name: 'Đặt chuyến' });
    await expect(book).toBeVisible();
    // Earlier specs leave demand in this area, so the price may be raised: consent first (BR-006).
    const surge = customer.getByRole('checkbox', { name: /giá cao điểm/ });
    if (await surge.isVisible()) {
      await expect(book).toBeDisabled();
      await surge.check();
    }
    await book.click();
    await customer.waitForURL(/\/app\/trips\/[0-9a-f-]{36}$/);
    const tripId = customer.url().split('/').pop()!;
    await expect(customer.getByText('Đang tìm tài xế gần bạn')).toBeVisible();

    await acceptOffer(driver, tripId);
    await expect(customer.getByText('Tài xế đang đến đón')).toBeVisible();

    await driver.getByRole('button', { name: 'Bắt đầu đi đón' }).click();
    await driveTo(driver, 'Giả lập: chạy tới điểm đón');
    await expect(customer.getByRole('img', { name: 'Vị trí tài xế' })).toBeVisible();

    await driver.getByRole('button', { name: 'Đã đến điểm đón' }).click();
    await expect(customer.getByText('Tài xế đã đến điểm đón')).toBeVisible();

    const { pickupCode } = await call<Trip>(`/api/v1/trips/${tripId}`, { token: customerToken });
    await driver.getByLabel(/mã đón/).fill(pickupCode!);
    await driver.getByRole('button', { name: 'Bắt đầu chuyến' }).click();
    await expect(driver.getByText('Đang chở khách')).toBeVisible();

    await driveTo(driver, 'Giả lập: chạy tới điểm đến');
    await driver.getByRole('button', { name: 'Hoàn tất chuyến' }).click();
    await expect(driver.getByText('Chuyến đi đã hoàn tất')).toBeVisible();
    await expect(customer.getByText('Chuyến đi đã hoàn tất')).toBeVisible();

    await customer.goto(`/app/trips/${tripId}/receipt`);
    await expect(customer.getByText('Đã thanh toán').first()).toBeVisible({ timeout: 30_000 });
  } finally {
    await driverOffline(driver);
    await contexts.customer.close();
    await contexts.driver.close();
  }
});
