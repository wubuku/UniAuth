import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const resourceUrl = requiredEnvironment('EMAIL_LOGIN_E2E_RESOURCE_URL');
const captureFile = requiredEnvironment('EMAIL_LOGIN_E2E_CAPTURE_FILE');
const email = requiredEnvironment('EMAIL_LOGIN_E2E_EMAIL');
const password = requiredEnvironment('EMAIL_LOGIN_E2E_PASSWORD');

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

async function waitForVerificationCode(recipient: string): Promise<string> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const lines = (await readFile(captureFile, 'utf8'))
        .split('\n')
        .filter(Boolean)
        .reverse();
      for (const line of lines) {
        const message = JSON.parse(line);
        const code = message.variables?.verificationCode;
        if (
          message.to === recipient
          && message.templateName === 'email/email-verify'
          && typeof code === 'string'
          && /^\d{6}$/.test(code)
        ) {
          return code;
        }
      }
    } catch {
      // The capture file can be empty while the real backend request is in flight.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('verification code was not captured');
}

async function requestProtectedResource(
  page: Parameters<typeof test>[0]['page'],
): Promise<void> {
  await page.getByRole('button', { name: /获取受保护资源/ }).click();
  await expect(page.getByText('"message": "Access granted"')).toBeVisible();
  await expect(page.getByText(`"email": "${email}"`)).toBeVisible();
}

test('email registration and login return to the resource page and use a cross-origin bearer token', async ({
  context,
  page,
  request,
}) => {
  const unauthenticatedResponse = await request.get(
    `${resourceUrl}/api/protected`,
  );
  expect(unauthenticatedResponse.status()).toBe(401);

  let protectedAuthorization = '';
  let protectedCookieHeader = '';
  page.on('request', (browserRequest) => {
    if (browserRequest.url() === `${resourceUrl}/api/protected`) {
      protectedAuthorization = browserRequest.headers().authorization ?? '';
      protectedCookieHeader = browserRequest.headers().cookie ?? '';
    }
  });
  await context.addCookies([
    {
      name: 'resourceSentinel',
      value: 'must-not-be-sent',
      url: resourceUrl,
    },
  ]);

  await page.goto('/resource-test');
  await expect(page).toHaveURL(/\/login\?returnTo=%2Fresource-test$/);

  await page.getByRole('button', { name: '注册', exact: true }).first().click();
  await page.getByPlaceholder('用户名').fill(email);
  await page.getByPlaceholder('显示名称').fill('Email Login E2E');
  await page.getByPlaceholder('密码').fill(password);
  await page.locator('form').getByRole('button', { name: '注册' }).click();

  await expect(page.getByRole('heading', { name: '邮箱验证' })).toBeVisible();
  const verificationCode = await waitForVerificationCode(email);
  await page.getByPlaceholder('请输入6位验证码').fill(verificationCode);
  await page.getByRole('button', { name: '确 定' }).click();

  await expect(page).toHaveURL(/\/resource-test$/);
  await expect(page.getByText('✅ 已登录')).toBeVisible();
  await requestProtectedResource(page);
  expect(protectedAuthorization).toMatch(/^Bearer [^\s]+$/);
  expect(protectedCookieHeader).toBe('');

  const resourceCookies = await context.cookies(resourceUrl);
  expect(
    resourceCookies.some((cookie) => cookie.name === 'resourceSentinel'),
  ).toBe(true);
  expect(resourceCookies.some((cookie) => cookie.name === 'accessToken')).toBe(
    false,
  );

  await context.clearCookies();
  await page.evaluate(() => {
    localStorage.removeItem('auth_user');
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
  });

  await page.goto('/resource-test');
  await expect(page).toHaveURL(/\/login\?returnTo=%2Fresource-test$/);
  await page.getByPlaceholder('用户名').fill(email);
  await page.getByPlaceholder('密码').fill(password);
  await page.locator('form').getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL(/\/resource-test$/);
  await expect(page.getByText('✅ 已登录')).toBeVisible();
  protectedAuthorization = '';
  protectedCookieHeader = '';
  await requestProtectedResource(page);
  expect(protectedAuthorization).toMatch(/^Bearer [^\s]+$/);
  expect(protectedCookieHeader).toBe('');
});
