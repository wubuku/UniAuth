import { expect, test } from '@playwright/test';

test('resource test page exercises health, JWKS, introspection, and protected resource mocks', async ({ page }) => {
  const requestedUrls: string[] = [];

  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'header.payload.signature');
  });
  page.on('request', (request) => {
    requestedUrls.push(request.url());
  });

  await page.route('http://localhost:5002/health', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'healthy' }),
    });
  });
  await page.route('http://localhost:5002/api/protected', async (route) => {
    expect(route.request().headers().authorization).toBe(
      'Bearer header.payload.signature'
    );
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ message: 'protected resource', userId: 'resource-user' }),
    });
  });
  await page.route(/\/oauth2\/jwks$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        keys: [{ kty: 'RSA', alg: 'RS256', kid: 'key-1' }],
      }),
    });
  });
  await page.route(/\/oauth2\/api\/introspect$/, async (route) => {
    expect(route.request().postData()).toContain('token=header.payload.signature');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ active: true, sub: 'resource-user' }),
    });
  });

  await page.goto('/resource-test');
  await expect(page.getByText('✅ 已登录')).toBeVisible();

  await page.getByRole('button', { name: /资源服务器健康检查/ }).click();
  await expect(page.getByText('"status": "healthy"')).toBeVisible();

  await page.getByRole('button', { name: /测试 JWKS 端点/ }).click();
  await expect(page.getByText('"kid": "key-1"')).toBeVisible();

  await page.getByRole('button', { name: /测试 Token 内省/ }).click();
  await expect(page.getByText('"active": true')).toBeVisible();

  await page.getByRole('button', { name: /获取受保护资源/ }).click();
  await expect(page.getByText('"message": "protected resource"')).toBeVisible();

  expect(requestedUrls).toContain('http://localhost:5002/health');
  expect(requestedUrls).toContain('http://localhost:5002/api/protected');
});
