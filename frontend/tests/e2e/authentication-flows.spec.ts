import { expect, test } from '@playwright/test';
import { mockCsrfBootstrap } from './csrf';

const currentUser = {
  authenticated: true,
  provider: 'local',
  userName: 'browser-user',
  userEmail: 'browser-user@example.invalid',
  userId: 'browser-user-id',
};

test.beforeEach(async ({ page }) => {
  await mockCsrfBootstrap(page);
});

async function mockCurrentUser(
  page: Parameters<typeof test>[0]['page'],
  user = currentUser,
) {
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(user),
    });
  });
}

test('local login stores authentication state and opens the authenticated home page', async ({ page }) => {
  let loginBody: Record<string, string> | undefined;
  await mockCurrentUser(page);
  await page.route(/\/api\/auth\/login$/, async (route) => {
    loginBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        user: {
          id: 'browser-user-id',
          username: 'browser-user',
          email: 'browser-user@example.invalid',
          displayName: 'Browser User',
          provider: 'local',
        },
        accessToken: 'mock.access.token',
        tokenType: 'Bearer',
      }),
    });
  });

  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('browser-user');
  await page.getByPlaceholder('密码').fill('browser-password');
  await page.locator('form').getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL('/');
  await expect(page.getByRole('heading', { name: 'React OAuth2 登录演示' })).toBeVisible();
  expect(loginBody).toEqual({
    username: 'browser-user',
    password: 'browser-password',
  });
  await expect.poll(() => page.evaluate(() => localStorage.getItem('accessToken')))
    .toBe('mock.access.token');
  await expect.poll(() => page.evaluate(() => localStorage.getItem('refreshToken')))
    .toBeNull();
});

test('local login surfaces a rejected credential response', async ({ page }) => {
  await page.route(/\/api\/auth\/login$/, async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'Invalid credentials' }),
    });
  });

  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('browser-user');
  await page.getByPlaceholder('密码').fill('wrong-password');
  await page.locator('form').getByRole('button', { name: '登录' }).click();

  await expect(page.getByText('Invalid credentials')).toBeVisible();
  await expect(page).toHaveURL('/login');
});

test('OAuth provider navigation does not attach client-controlled redirect state', async ({ page }) => {
  const authorizationRequest = page.waitForRequest(
    (request) => new URL(request.url()).pathname === '/oauth2/authorization/github'
  );
  await page.route(/\/oauth2\/authorization\/github$/, async (route) => {
    await route.fulfill({
      status: 204,
      body: '',
    });
  });

  await page.goto('/login');
  await page.getByRole('button', { name: 'GitHub 登录' }).click();

  const requestUrl = new URL((await authorizationRequest).url());
  expect(requestUrl.search).toBe('');
  expect(requestUrl.hash).toBe('');
});

test('email registration returns to the requested same-origin resource page', async ({ page }) => {
  const email = 'browser-registration@example.invalid';
  let sendCount = 0;
  let verificationRequest: Record<string, string> | undefined;

  await mockCurrentUser(page, {
    ...currentUser,
    userName: email,
    userEmail: email,
    userId: 'browser-registration-id',
  });
  await page.route(/\/api\/auth\/register$/, async (route) => {
    const body = route.request().postDataJSON();
    expect(body.username).toBe(email);
    expect(body.email).toBe(email);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        requireEmailVerification: true,
        username: email,
        message: 'Please verify your email',
      }),
    });
  });
  await page.route(/\/api\/auth\/send-verification-code$/, async (route) => {
    sendCount += 1;
    const body = route.request().postDataJSON();
    expect(body).toEqual({
      email,
      purpose: 'REGISTRATION',
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        message: 'sent',
        challengeHandle: 'registration-challenge',
        expiresIn: 120,
        resendAfter: 7,
      }),
    });
  });
  await page.route(/\/api\/auth\/verify-email$/, async (route) => {
    verificationRequest = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        message: 'verified',
        user: {
          id: 'browser-registration-id',
          username: email,
          email,
          displayName: 'Browser Registration',
        },
        accessToken: 'registration.access.token',
      }),
    });
  });

  await page.goto('/login?returnTo=%2Fresource-test');
  await page.getByRole('button', { name: '注册', exact: true }).first().click();
  await page.getByPlaceholder('用户名').fill(email);
  await page.getByPlaceholder('显示名称').fill('Browser Registration');
  await page.getByPlaceholder('密码').fill('browser-password');
  await page.locator('form').getByRole('button', { name: '注册' }).click();

  await expect(page.getByRole('heading', { name: '邮箱验证' })).toBeVisible();
  await expect.poll(() => sendCount).toBe(1);
  await page.getByPlaceholder('请输入6位验证码').fill('123456');
  await page.getByRole('button', { name: '确 定' }).click();

  await expect(page).toHaveURL('/resource-test');
  expect(verificationRequest).toEqual({
    challengeHandle: 'registration-challenge',
    username: email,
    email,
    password: 'browser-password',
    displayName: 'Browser Registration',
    verificationCode: '123456',
  });
  await expect.poll(() => page.evaluate(() => localStorage.getItem('accessToken')))
    .toBe('registration.access.token');
  await expect.poll(() => page.evaluate(() => localStorage.getItem('refreshToken')))
    .toBeNull();
  await expect.poll(() => page.evaluate(() => {
    const value = localStorage.getItem('auth_user');
    return value ? JSON.parse(value).id : null;
  })).toBe('browser-registration-id');
});

test('resource page sends unauthenticated users through an internal login return path', async ({ page }) => {
  await page.goto('/resource-test');

  await expect(page).toHaveURL('/login?returnTo=%2Fresource-test');
});

test('login rejects an external return target', async ({ page }) => {
  await mockCurrentUser(page);
  await page.route(/\/api\/auth\/login$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        user: {
          id: 'browser-user-id',
          username: 'browser-user',
          email: 'browser-user@example.invalid',
          displayName: 'Browser User',
          provider: 'local',
        },
        accessToken: 'mock.access.token',
        tokenType: 'Bearer',
      }),
    });
  });

  await page.goto('/login?returnTo=https%3A%2F%2Fevil.example%2F');
  await page.getByPlaceholder('用户名').fill('browser-user');
  await page.getByPlaceholder('密码').fill('browser-password');
  await page.locator('form').getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL('/');
});

test('email registration resend uses the latest challenge without persisting the password', async ({ page }) => {
  const email = 'browser-resend@example.invalid';
  let sendCount = 0;
  let verificationRequest: Record<string, string> | undefined;

  await mockCurrentUser(page, {
    ...currentUser,
    userName: email,
    userEmail: email,
    userId: 'browser-resend-id',
  });
  await page.route(/\/api\/auth\/register$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        requireEmailVerification: true,
        username: email,
        message: 'Please verify your email',
      }),
    });
  });
  await page.route(/\/api\/auth\/send-verification-code$/, async (route) => {
    sendCount += 1;
    expect(route.request().postDataJSON()).toEqual({
      email,
      purpose: 'REGISTRATION',
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        message: 'accepted',
        challengeHandle: `resend-challenge-${sendCount}`,
        expiresIn: 600,
        resendAfter: 0,
      }),
    });
  });
  await page.route(/\/api\/auth\/verify-email$/, async (route) => {
    verificationRequest = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        message: 'verified',
        user: {
          id: 'browser-resend-id',
          username: email,
          email,
          displayName: 'Delivery Retry',
        },
        accessToken: 'resend.access.token',
      }),
    });
  });

  await page.goto('/login');
  await page.getByRole('button', { name: '注册', exact: true }).first().click();
  await page.getByPlaceholder('用户名').fill(email);
  await page.getByPlaceholder('显示名称').fill('Delivery Retry');
  await page.getByPlaceholder('密码').fill('browser-password');
  await page.locator('form').getByRole('button', { name: '注册' }).click();

  await expect(page.getByRole('heading', { name: '邮箱验证' })).toBeVisible();
  await expect.poll(() => sendCount).toBe(1);
  await expect.poll(() => page.evaluate((password) => {
    for (const storage of [localStorage, sessionStorage]) {
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index);
        if (key && storage.getItem(key)?.includes(password)) {
          return false;
        }
      }
    }
    return true;
  }, 'browser-password')).toBe(true);
  await expect(page.getByRole('button', { name: '重新发送验证码' })).toBeVisible();
  await page.getByRole('button', { name: '重新发送验证码' }).click();
  await expect.poll(() => sendCount).toBe(2);
  await page.getByPlaceholder('请输入6位验证码').fill('123456');
  await page.getByRole('button', { name: '确 定' }).click();
  await expect(page).toHaveURL('/');
  expect(verificationRequest).toEqual({
    challengeHandle: 'resend-challenge-2',
    username: email,
    email,
    password: 'browser-password',
    displayName: 'Delivery Retry',
    verificationCode: '123456',
  });
});

test('forgot-password flow reaches the success state with matching passwords', async ({ page }) => {
  const email = 'browser-reset@example.invalid';
  let resetRequest: Record<string, string> | undefined;

  await page.route(/\/api\/auth\/forgot-password$/, async (route) => {
    expect(route.request().postDataJSON()).toEqual({ email });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        message: 'sent',
        challengeHandle: 'reset-challenge',
        expiresIn: 600,
        resendAfter: 7,
      }),
    });
  });
  await page.route(/\/api\/auth\/verify-reset-code$/, async (route) => {
    resetRequest = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        message: 'password reset',
      }),
    });
  });

  await page.goto('/login');
  await page.getByRole('button', { name: '忘记密码？' }).click();
  await page.getByPlaceholder('请输入邮箱地址').fill(email);
  await page.getByRole('button', { name: '发送验证码' }).click();

  await expect(page.getByRole('heading', { name: '输入验证码' })).toBeVisible();
  await expect(page.getByText('7 秒后可重新发送')).toBeVisible();
  await page.getByPlaceholder('请输入6位验证码').fill('654321');
  await page.getByPlaceholder('请输入新密码').fill('updated-password');
  await page.getByPlaceholder('请确认新密码').fill('updated-password');
  await page.getByRole('button', { name: '确认重置' }).click();

  await expect(page.getByRole('heading', { name: /密码重置成功/ })).toBeVisible();
  expect(resetRequest).toEqual({
    challengeHandle: 'reset-challenge',
    email,
    verificationCode: '654321',
    newPassword: 'updated-password',
  });
});

test('unknown reset email follows the same external verification flow', async ({ page }) => {
  await page.route(/\/api\/auth\/forgot-password$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        challengeHandle: 'decoy-reset-challenge',
        expiresIn: 600,
        resendAfter: 7,
        message: 'If the account can be recovered, a verification code was sent',
      }),
    });
  });

  await page.goto('/login');
  await page.getByRole('button', { name: '忘记密码？' }).click();
  await page.getByPlaceholder('请输入邮箱地址').fill('unknown@example.invalid');
  await page.getByRole('button', { name: '发送验证码' }).click();

  await expect(page.getByRole('heading', { name: '输入验证码' })).toBeVisible();
  await expect(page.getByText('7 秒后可重新发送')).toBeVisible();
  await expect(page.getByRole('button', { name: '去注册' })).toHaveCount(0);
});

test('Web3 button reports a missing browser wallet without API calls', async ({ page }) => {
  const apiRequests: string[] = [];
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/')) {
      apiRequests.push(path);
    }
  });

  await page.goto('/login');
  await page.getByRole('button', { name: '使用 Web3 钱包登录' }).click();

  await expect(page.getByText('请先安装MetaMask或其他Web3钱包插件')).toBeVisible();
  expect(apiRequests).toEqual([]);
});
