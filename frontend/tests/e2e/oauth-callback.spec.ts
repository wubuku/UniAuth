import { expect, test } from '@playwright/test';

async function openClientRoute(
  page: Parameters<typeof test>[0]['page'],
  path: string
) {
  await page.goto('/login');
  await page.evaluate((clientPath) => {
    window.history.pushState({}, '', clientPath);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

test('OAuth callback processes once under React StrictMode and loads the user', async ({ page }) => {
  let refreshCalls = 0;
  let currentUserCalls = 0;

  await page.route(/\/api\/auth\/refresh$/, async (route) => {
    refreshCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        message: 'Token refreshed successfully',
        accessToken: 'callback.access.token',
        refreshToken: 'callback.refresh.token',
        accessTokenExpiresIn: 3600,
        refreshTokenExpiresIn: 604800,
        tokenType: 'Bearer',
      }),
    });
  });
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    currentUserCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'github',
        userName: 'callback-user',
        userEmail: 'callback-user@example.invalid',
        userId: 'callback-user-id',
      }),
    });
  });

  await openClientRoute(page, '/oauth2/callback');

  await expect(page).toHaveURL('/');
  await expect(page.getByRole('heading', { name: 'React OAuth2 登录演示' })).toBeVisible();
  expect(refreshCalls).toBe(1);
  expect(currentUserCalls).toBeGreaterThanOrEqual(1);
  await expect.poll(() => page.evaluate(() => localStorage.getItem('accessToken')))
    .toBe('callback.access.token');
});

test('OAuth callback provider error is shown on the login page', async ({ page }) => {
  await openClientRoute(
    page,
    '/oauth2/callback?error=access_denied&error_description=Provider%20denied'
  );

  await expect(page).toHaveURL('/login');
  await expect(page.getByText('Provider denied')).toBeVisible();
});

test('OAuth callback refresh failure reaches a stable login error state', async ({ page }) => {
  await page.route(/\/api\/auth\/refresh$/, async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'Refresh token not found' }),
    });
  });

  await openClientRoute(page, '/oauth2/callback');

  await expect(page).toHaveURL('/login');
  await expect(page.getByText('Callback processing failed')).toBeVisible();
});
