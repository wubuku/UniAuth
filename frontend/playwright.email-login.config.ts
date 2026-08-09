import { defineConfig, devices } from '@playwright/test';

const frontendUrl = process.env.EMAIL_LOGIN_E2E_FRONTEND_URL;
if (!frontendUrl) {
  throw new Error('EMAIL_LOGIN_E2E_FRONTEND_URL is required');
}

export default defineConfig({
  testDir: './tests/email-login-e2e',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  reporter: 'line',
  timeout: 120_000,
  expect: {
    timeout: 20_000,
  },
  use: {
    baseURL: frontendUrl,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        channel: 'chrome',
      },
    },
  ],
});

