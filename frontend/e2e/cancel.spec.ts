import { expect, test } from '@playwright/test';
import {
  acceptOffer,
  accounts,
  apiLogin,
  bookByApi,
  call,
  cancelOpenTrips,
  driverOffline,
  driverOnline,
  phoneContexts,
  requireAccounts,
  uiLogin,
  type Trip,
} from './support';

/*
 * UC-08: the customer cancels after a driver took the trip. The app shows the fee the server
 * computed (or that it is free) before confirming; the driver is told at once; repeating the
 * cancellation does not charge twice.
 */
test('the customer cancels an accepted trip after seeing the fee', async ({ browser }) => {
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

    await customer.getByRole('button', { name: 'Huỷ chuyến' }).click();
    await customer.getByRole('radio', { name: 'Chờ lâu quá' }).check();
    const fee = customer.getByText(/Phí huỷ:|Huỷ lúc này không mất phí\./);
    await expect(fee).toBeVisible();
    const charged = (await fee.textContent())!.startsWith('Phí huỷ');
    await customer.getByRole('button', { name: charged ? 'Huỷ và trả phí' : 'Huỷ chuyến' }).click();

    await expect(customer.getByText('Bạn đã huỷ chuyến')).toBeVisible();
    await expect(driver.getByText(/Khách đã huỷ chuyến/)).toBeVisible();

    // A repeated cancel is answered with the same cancelled trip, and no second fee.
    const again = await call<Trip>(`/api/v1/trips/${trip.id}/cancel`, {
      token: customerToken,
      method: 'POST',
      body: { reason: 'WAIT_TOO_LONG' },
    });
    expect(again.status).toBe('CANCELLED');
    const payments = await call<{ purpose: string }[]>(`/api/v1/payments?tripId=${trip.id}`, {
      token: customerToken,
    });
    expect(payments.filter((p) => p.purpose === 'CANCELLATION_FEE').length).toBeLessThanOrEqual(1);
  } finally {
    await driverOffline(driver);
    await contexts.customer.close();
    await contexts.driver.close();
  }
});
