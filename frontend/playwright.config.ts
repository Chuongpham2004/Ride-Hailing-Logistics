import { defineConfig } from '@playwright/test';

/*
 * End-to-end checks of the main flows against the running local stack (frontend-design.md §11,
 * stage 5). Not part of CI: they need every service, Kafka, Postgres and Redis up, plus the
 * accounts named in e2e/README.md. Run with `npm run e2e` while `npm run dev` serves the app.
 */
export default defineConfig({
  testDir: './e2e',
  // One customer and one driver are shared by every spec: run them one at a time.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 4 * 60_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_WEB_URL ?? 'http://localhost:5173',
    // The installed Chrome by default, so no browser download is needed.
    channel: process.env.E2E_CHANNEL ?? 'chrome',
    headless: process.env.E2E_HEADED !== '1',
    locale: 'vi-VN',
    timezoneId: 'Asia/Ho_Chi_Minh',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: {
      // MapLibre needs WebGL; headless Chrome gets it from SwiftShader.
      args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    },
  },
});
