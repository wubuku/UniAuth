import { readdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { expect, test } from '@playwright/test';

const staticAssets = resolve(
  process.cwd(),
  '../src/main/resources/static/assets'
);

test('production excludes diagnostic routes and bundle code', async ({ page }) => {
  await page.route('**/api/user?*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ authenticated: false }),
    });
  });

  for (const path of ['/test', '/resource-test']) {
    await page.goto(path);
    await expect(page).toHaveURL('/');
  }

  const assetFiles = await readdir(staticAssets);
  expect(assetFiles.some((name) => name.includes('TestPage'))).toBe(false);
  expect(assetFiles.some((name) => name.includes('ResourceTestPage'))).toBe(false);

  const javascript = (
    await Promise.all(
      assetFiles
        .filter((name) => name.endsWith('.js'))
        .map((name) => readFile(resolve(staticAssets, name), 'utf8'))
    )
  ).join('\n');
  expect(javascript).not.toContain('"/test"');
  expect(javascript).not.toContain('/resource-test');
  expect(javascript).not.toContain('modifiedToken');
  expect(javascript).not.toContain('查看用户信息和Token验证');
  expect(javascript).not.toContain('测试异构资源服务器');
  expect(javascript).not.toContain('测试 Token 内省');
});

test('production login does not persist bearer credentials', async ({ page }) => {
  await page.route(/\/api\/auth\/csrf$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        headerName: 'X-CSRF-Token',
        token: 'production-csrf-token',
      }),
    });
  });
  await page.route(/\/api\/auth\/login$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        user: {
          id: 'production-user-id',
          username: 'production-user',
          email: 'production-user@example.invalid',
          displayName: 'Production User',
          provider: 'local',
        },
        accessToken: 'must.not.persist',
        refreshToken: 'legacy.must.not.persist',
        tokenType: 'Bearer',
      }),
    });
  });
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'production-user',
        userEmail: 'production-user@example.invalid',
        userId: 'production-user-id',
      }),
    });
  });

  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('production-user');
  await page.getByPlaceholder('密码').fill('production-password');
  await page.locator('form').getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL('/');
  await expect(page.getByText('已登录')).toBeVisible();
  await expect.poll(() => page.evaluate(() => ({
    accessToken: localStorage.getItem('accessToken'),
    refreshToken: localStorage.getItem('refreshToken'),
  }))).toEqual({
    accessToken: null,
    refreshToken: null,
  });
});
