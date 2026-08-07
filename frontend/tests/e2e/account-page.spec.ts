import { expect, test } from '@playwright/test';

const removedPaths = [
  '/api/auth/check-user',
  '/api/auth/generate-hash',
  '/api/auth/create-test-user',
  '/api/auth/reset-password',
  '/api/validate-google-token',
  '/api/validate-github-token',
  '/api/validate-x-token',
  '/oauth2/introspect-test',
  '/oauth2/validate',
];

test('account page renders from mocked APIs without removed endpoint calls', async ({ page }) => {
  const requestedPaths: string[] = [];
  const pageErrors: string[] = [];
  const mockUser = {
    authenticated: true,
    provider: 'local',
    userName: 'mock-user',
    userEmail: 'mock-user@example.invalid',
    userId: 'mock-user-id',
  };

  page.on('request', (request) => {
    requestedPaths.push(new URL(request.url()).pathname);
  });
  page.on('pageerror', (error) => {
    pageErrors.push(error.message);
  });

  await page.addInitScript((user) => {
    localStorage.setItem('auth_user', JSON.stringify(user));
    localStorage.setItem('accessToken', 'mock.access.token');
  }, mockUser);

  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        loginMethods: [
          {
            id: 1,
            authProvider: 'LOCAL',
            localUsername: 'mock-user',
            isPrimary: true,
            isVerified: true,
            linkedAt: '2026-08-07T00:00:00Z',
          },
        ],
      }),
    });
  });

  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockUser),
    });
  });

  await page.goto('/test');

  await expect(page.getByRole('heading', { name: '账户与登录方式' })).toBeVisible();
  await expect(page.getByText('已绑定的登录方式 (1)')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Google ID Token 验证' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'GitHub Access Token 验证' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'X Access Token 验证' })).toHaveCount(0);

  expect(pageErrors).toEqual([]);
  expect(requestedPaths.filter((path) => removedPaths.includes(path))).toEqual([]);
});
