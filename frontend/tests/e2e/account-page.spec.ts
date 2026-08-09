import { expect, test } from '@playwright/test';
import { mockCsrfBootstrap } from './csrf';

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

test.beforeEach(async ({ page }) => {
  await mockCsrfBootstrap(page);
});

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
            id: '00000000-0000-0000-0000-000000000001',
            authProvider: 'local',
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
  await expect(page.getByRole('button', { name: /添加本地登录方式/ })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Google ID Token 验证' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'GitHub Access Token 验证' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'X Access Token 验证' })).toHaveCount(0);

  expect(pageErrors).toEqual([]);
  expect(requestedPaths.filter((path) => removedPaths.includes(path))).toEqual([]);
});

test('account page sends UUID login-method operations and refreshes the list', async ({ page }) => {
  const localId = '00000000-0000-0000-0000-000000000001';
  const githubId = '00000000-0000-0000-0000-000000000002';
  const operationPaths: string[] = [];
  let methods = [
    {
      id: localId,
      authProvider: 'local',
      localUsername: 'mock-user',
      isPrimary: true,
      isVerified: true,
      linkedAt: '2026-08-07T00:00:00Z',
    },
    {
      id: githubId,
      authProvider: 'github',
      providerEmail: 'mock-user@example.invalid',
      providerUsername: 'mock-user',
      isPrimary: false,
      isVerified: true,
      linkedAt: '2026-08-07T00:00:00Z',
    },
  ];

  await page.addInitScript(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'mock-user',
      userEmail: 'mock-user@example.invalid',
      userId: 'mock-user-id',
    }));
    localStorage.setItem('accessToken', 'mock.access.token');
  });

  await page.route(/\/api\/user\/login-methods\/[^/]+\/primary$/, async (route) => {
    const path = new URL(route.request().url()).pathname;
    operationPaths.push(path);
    expect(route.request().headers().authorization).toBe('Bearer mock.access.token');
    methods = methods.map((method) => ({
      ...method,
      isPrimary: method.id === githubId,
    }));
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        message: '主登录方式已设置',
        methodId: githubId,
      }),
    });
  });

  await page.route(/\/api\/user\/login-methods\/[^/]+$/, async (route) => {
    const path = new URL(route.request().url()).pathname;
    operationPaths.push(path);
    expect(route.request().headers().authorization).toBe('Bearer mock.access.token');
    methods = methods.filter((method) => method.id !== localId);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        message: '登录方式已移除',
        methodId: localId,
      }),
    });
  });

  await page.route(/\/api\/user\/login-methods\/add-local-login$/, async (route) => {
    const path = new URL(route.request().url()).pathname;
    operationPaths.push(path);
    expect(route.request().headers().authorization).toBe('Bearer mock.access.token');
    expect(route.request().postDataJSON()).toEqual({
      username: 'replacement-local',
      password: 'replacement-password',
      passwordConfirm: 'replacement-password',
    });
    methods = [
      ...methods,
      {
        id: localId,
        authProvider: 'local',
        localUsername: 'replacement-local',
        isPrimary: false,
        isVerified: false,
        linkedAt: '2026-08-07T00:00:00Z',
      },
    ];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        message: '本地登录方式添加成功',
        loginMethod: methods.at(-1),
      }),
    });
  });

  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        loginMethods: methods,
        count: methods.length,
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
        userName: 'mock-user',
        userEmail: 'mock-user@example.invalid',
        userId: 'mock-user-id',
      }),
    });
  });
  await page.route(/\/oauth2\/bind\/github$/, async (route) => {
    await route.abort();
  });

  await page.goto('/test');
  await expect(page.getByText('已绑定的登录方式 (2)')).toBeVisible();

  await page.getByRole('button', { name: '设为主登录' }).click();
  await expect(page.getByText(/主登录方式已更新/)).toBeVisible();

  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '删除' }).first().click();
  await expect(page.getByText('已绑定的登录方式 (1)')).toBeVisible();
  await expect(page.getByText(/登录方式已删除/)).toBeVisible();

  await page.getByRole('button', { name: /添加本地登录方式/ }).click();
  await page.getByPlaceholder('用户名').fill('replacement-local');
  await page.getByPlaceholder('密码（至少6个字符）').fill('replacement-password');
  await page.getByPlaceholder('确认密码').fill('replacement-password');
  await page.getByRole('button', { name: /确认添加/ }).click();
  await expect(page.getByText('已绑定的登录方式 (2)')).toBeVisible();
  await expect(page.getByText(/本地登录方式添加成功/)).toBeVisible();

  expect(operationPaths).toEqual([
    `/api/user/login-methods/${githubId}/primary`,
    `/api/user/login-methods/${localId}`,
    '/api/user/login-methods/add-local-login',
  ]);
});

test('account page uses the explicit OAuth binding endpoint', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'mock-user',
      userEmail: 'mock-user@example.invalid',
      userId: 'mock-user-id',
    }));
    localStorage.setItem('accessToken', 'mock.access.token');
  });
  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        loginMethods: [{
          id: '00000000-0000-0000-0000-000000000001',
          authProvider: 'local',
          localUsername: 'mock-user',
          isPrimary: true,
          isVerified: true,
          linkedAt: '2026-08-09T00:00:00Z',
        }],
        count: 1,
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
        userName: 'mock-user',
        userEmail: 'mock-user@example.invalid',
        userId: 'mock-user-id',
      }),
    });
  });

  await page.goto('/test');
  const bindingRequest = page.waitForRequest(
    (request) => new URL(request.url()).pathname === '/oauth2/bind/github'
  );
  await page.getByRole('button', { name: /GitHub/ }).click();

  const request = await bindingRequest;
  expect(new URL(request.url()).pathname).toBe('/oauth2/bind/github');
});
