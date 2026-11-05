import { expect, test } from '@playwright/test';
import { accounts, requireAccounts, uiLogin } from './support';

/* The staff area opens every section for an administrator; read-only, nothing is changed. */
test('an administrator can open every staff section', async ({ page }) => {
  requireAccounts('admin');
  await page.setViewportSize({ width: 1440, height: 900 });
  await uiLogin(page, accounts.admin!, 'admin');
  await page.waitForURL('**/admin/drivers');

  const nav = page.getByRole('navigation', { name: 'Khu vực nhân viên' });
  const sections: [string, RegExp, string][] = [
    ['Hồ sơ tài xế', /\/admin\/drivers$/, 'Hồ sơ tài xế'],
    ['Chuyến', /\/admin\/trips$/, 'Chuyến'],
    ['Thanh toán', /\/admin\/payments$/, 'Thanh toán'],
    ['Ví tài xế', /\/admin\/wallets$/, 'Ví tài xế'],
    ['Bảng giá', /\/admin\/pricing$/, 'Bảng giá'],
    ['Người dùng', /\/admin\/users$/, 'Người dùng'],
  ];
  for (const [link, url, heading] of sections) {
    await nav.getByRole('link', { name: link }).click();
    await expect(page).toHaveURL(url);
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
  }

  await nav.getByRole('link', { name: 'Chuyến' }).click();
  await page.locator('tbody tr a').first().click();
  await expect(page.getByRole('heading', { name: 'Diễn biến' })).toBeVisible();
});
