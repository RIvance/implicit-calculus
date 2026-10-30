import { defineConfig } from '@playwright/test';

const production = process.env.PLAYWRIGHT_PREVIEW === '1';
const external = process.env.PLAYWRIGHT_BASE_URL;
const baseURL = external ?? `http://127.0.0.1:${production ? 4173 : 5173}`;

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  workers: 1,
  use: {
    baseURL,
    viewport: { width: 1500, height: 1000 },
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH },
    trace: 'retain-on-failure',
  },
  webServer: external
    ? undefined
    : {
        command: production ? 'npm run preview' : 'npm run dev',
        url: baseURL,
        reuseExistingServer: !process.env.CI,
      },
});
