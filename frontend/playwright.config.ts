import { defineConfig, devices } from '@playwright/test';

const portText = process.env.PLAYWRIGHT_PORT ?? '4173';
const port = Number(portText);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('PLAYWRIGHT_PORT must be an integer between 1 and 65535');
}
const baseURL = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  reporter: [
    ['line'],
    ['json', { outputFile: 'reports/playwright-mock.json' }],
  ],
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${port}`,
    url: baseURL,
    reuseExistingServer: false,
    env: {
      ...process.env,
      VITE_API_BASE_URL: '',
      VITE_AUTH_DIAGNOSTICS: 'true',
    },
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        channel: 'chrome',
        // Cross-tab Web Locks hand-off and late refresh continuations run in
        // hidden renderers. Chrome throttles those tabs by default, which turns
        // scheduling latency into wall-clock flakiness; the security invariants
        // under test do not depend on that throttling behavior.
        launchOptions: {
          args: [
            '--disable-background-timer-throttling',
            '--disable-backgrounding-occluded-windows',
            '--disable-renderer-backgrounding',
          ],
        },
      },
    },
  ],
});
