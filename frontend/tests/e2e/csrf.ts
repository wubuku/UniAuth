import type { Page } from '@playwright/test';

export async function mockCsrfBootstrap(page: Page) {
  await page.context().route(/\/api\/auth\/csrf$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        headerName: 'X-CSRF-Token',
        token: 'mock-csrf-token',
      }),
    });
  });
}
