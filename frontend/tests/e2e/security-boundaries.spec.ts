import { expect, test, type Page, type Route } from '@playwright/test';
import { mockCsrfBootstrap } from './csrf';

const walletAddress = '0x1111111111111111111111111111111111111111';
const walletSignature = `0x${'ab'.repeat(65)}`;

type WalletMode = 'success' | 'reject-connect' | 'reject-sign';

test.beforeEach(async ({ page }) => {
  await mockCsrfBootstrap(page);
});

async function installWallet(
  page: Parameters<typeof test>[0]['page'],
  mode: WalletMode
) {
  await page.addInitScript(
    ({ address, signature, walletMode }) => {
      const rejectedRequest = () => {
        const error = new Error('User rejected request') as Error & { code: number };
        error.code = 4001;
        throw error;
      };

      Object.defineProperty(window, 'ethereum', {
        configurable: true,
        value: {
          request: async ({ method }: { method: string; params?: unknown[] }) => {
            switch (method) {
              case 'eth_requestAccounts':
                if (walletMode === 'reject-connect') {
                  return rejectedRequest();
                }
                return [address];
              case 'eth_accounts':
                return [address];
              case 'eth_chainId':
                return '0x1';
              case 'net_version':
                return '1';
              case 'personal_sign':
                if (walletMode === 'reject-sign') {
                  return rejectedRequest();
                }
                return signature;
              default:
                throw new Error(`Unexpected wallet RPC method: ${method}`);
            }
          },
          on: () => undefined,
          removeListener: () => undefined,
        },
      });
    },
    {
      address: walletAddress,
      signature: walletSignature,
      walletMode: mode,
    }
  );
}

async function mockCurrentUser(page: Parameters<typeof test>[0]['page']) {
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'web3',
        userName: walletAddress,
        userId: 'wallet-user-id',
      }),
    });
  });
}

test('mock EIP-1193 wallet completes the Web3 login contract', async ({ page }) => {
  const challengeHandle = '00000000-0000-4000-8000-000000000001';
  const nonce = 'browser-wallet-nonce';
  const message = 'Browser wallet challenge';
  let verifyBody: Record<string, string | number> | undefined;

  await installWallet(page, 'success');
  await mockCurrentUser(page);
  await page.route(/\/api\/auth\/web3\/nonce\/[^/]+$/, async (route) => {
    expect(route.request().method()).toBe('GET');
    expect(new URL(route.request().url()).pathname.toLowerCase())
      .toBe(`/api/auth/web3/nonce/${walletAddress}`);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        challengeHandle,
        nonce,
        message,
        chainId: 1,
        expiresIn: 300
      }),
    });
  });
  await page.route(/\/api\/auth\/web3\/verify$/, async (route) => {
    verifyBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'wallet.access.token',
        tokenType: 'Bearer',
        expiresIn: 3600,
        walletAddress,
        userId: 'wallet-user-id',
        isNewUser: true,
      }),
    });
  });

  await page.goto('/login');
  await page.getByRole('button', { name: '使用 Web3 钱包登录' }).click();

  await expect(page).toHaveURL('/');
  expect(verifyBody).toEqual({
    walletAddress,
    message,
    signature: walletSignature,
    challengeHandle,
    nonce,
    chainId: 1,
  });
  await expect.poll(() => page.evaluate(() => localStorage.getItem('accessToken')))
    .toBe('wallet.access.token');
  await expect.poll(() => page.evaluate(() => localStorage.getItem('refreshToken')))
    .toBeNull();
});

test('Web3 login reports a rejected wallet connection without API calls', async ({ page }) => {
  const apiRequests: string[] = [];
  await installWallet(page, 'reject-connect');
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/')) {
      apiRequests.push(path);
    }
  });

  await page.goto('/login');
  await page.getByRole('button', { name: '使用 Web3 钱包登录' }).click();

  await expect(page.getByText(/user rejected action.*requestAccess/)).toBeVisible();
  expect(apiRequests).toEqual([]);
});

test('Web3 login reports a rejected signature without submitting verification', async ({ page }) => {
  let nonceCalls = 0;
  let verifyCalls = 0;
  await installWallet(page, 'reject-sign');
  await page.route(/\/api\/auth\/web3\/nonce\/[^/]+$/, async (route) => {
    nonceCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        challengeHandle: '00000000-0000-4000-8000-000000000002',
        nonce: 'rejected-signature-nonce',
        message: 'Reject this challenge',
        chainId: 1,
        expiresIn: 300,
      }),
    });
  });
  await page.route(/\/api\/auth\/web3\/verify$/, async (route) => {
    verifyCalls += 1;
    await route.abort();
  });

  await page.goto('/login');
  await page.getByRole('button', { name: '使用 Web3 钱包登录' }).click();

  await expect(page.getByText(/user rejected action.*signMessage/)).toBeVisible();
  expect(nonceCalls).toBe(1);
  expect(verifyCalls).toBe(0);
});

test('one protected 401 refreshes once and retries with the new access token', async ({ page }) => {
  let loginMethodCalls = 0;
  let refreshCalls = 0;
  const authorizationHeaders: Array<string | undefined> = [];

  await page.addInitScript(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'refresh-user',
      userEmail: 'refresh-user@example.invalid',
      userId: 'refresh-user-id',
    }));
    localStorage.setItem('accessToken', 'expired.access.token');
  });
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'refresh-user',
        userEmail: 'refresh-user@example.invalid',
        userId: 'refresh-user-id',
      }),
    });
  });
  await page.route(/\/api\/auth\/refresh$/, async (route) => {
    refreshCalls += 1;
    expect(route.request().method()).toBe('POST');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'refreshed.access.token',
        tokenType: 'Bearer',
      }),
    });
  });
  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    loginMethodCalls += 1;
    authorizationHeaders.push(route.request().headers().authorization);
    if (loginMethodCalls === 1) {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'expired' }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ loginMethods: [], count: 0 }),
    });
  });

  await page.goto('/test');

  await expect(page.getByText('已绑定的登录方式 (0)')).toBeVisible();
  await expect.poll(() => refreshCalls).toBe(1);
  expect(authorizationHeaders).toContain('Bearer refreshed.access.token');
  await expect.poll(() => page.evaluate(() => localStorage.getItem('accessToken')))
    .toBe('refreshed.access.token');
  await expect.poll(() => page.evaluate(() => localStorage.getItem('refreshToken')))
    .toBeNull();
});

test('concurrent protected 401 responses share one refresh request', async ({ page }) => {
  let refreshCalls = 0;
  let expiredProtectedCalls = 0;
  let refreshedCurrentUserCalls = 0;
  let refreshedLoginMethodCalls = 0;
  let releaseExpiredProtectedCalls!: () => void;
  const concurrentExpiredCallsStarted = new Promise<void>((resolve) => {
    releaseExpiredProtectedCalls = resolve;
  });

  const rejectExpiredToken = async (route: Route) => {
    expiredProtectedCalls += 1;
    if (expiredProtectedCalls === 2) {
      releaseExpiredProtectedCalls();
    }
    await concurrentExpiredCallsStarted;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'expired' }),
    });
  };

  await page.goto('/login');
  await page.evaluate(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'concurrent-refresh-user',
      userEmail: 'concurrent-refresh-user@example.invalid',
      userId: 'concurrent-refresh-user-id',
    }));
    localStorage.setItem('accessToken', 'concurrent.expired.access.token');
  });
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    const authorization = route.request().headers().authorization;
    if (authorization === 'Bearer concurrent.expired.access.token') {
      await rejectExpiredToken(route);
      return;
    }
    expect(authorization).toBe('Bearer concurrent.refreshed.access.token');
    refreshedCurrentUserCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'concurrent-refresh-user',
        userEmail: 'concurrent-refresh-user@example.invalid',
        userId: 'concurrent-refresh-user-id',
      }),
    });
  });
  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    const authorization = route.request().headers().authorization;
    if (authorization === 'Bearer concurrent.expired.access.token') {
      await rejectExpiredToken(route);
      return;
    }
    expect(authorization).toBe('Bearer concurrent.refreshed.access.token');
    refreshedLoginMethodCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ loginMethods: [], count: 0 }),
    });
  });
  await page.route(/\/api\/auth\/refresh$/, async (route) => {
    refreshCalls += 1;
    await new Promise((resolve) => setTimeout(resolve, 100));
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'concurrent.refreshed.access.token',
        tokenType: 'Bearer',
      }),
    });
  });

  await page.goto('/test');

  await expect(page.getByText('已绑定的登录方式 (0)')).toBeVisible();
  await expect.poll(() => refreshedCurrentUserCalls).toBeGreaterThanOrEqual(1);
  await expect.poll(() => refreshedLoginMethodCalls).toBeGreaterThanOrEqual(1);
  expect(refreshCalls).toBe(1);
});

test('same-origin tabs coordinate refresh through one cookie rotation', async ({
  context,
  page,
}) => {
  let refreshCalls = 0;
  const expiredPages = new Set<Page>();
  const refreshedPages = new Set<Page>();
  let releaseExpiredCalls!: () => void;
  const bothTabsRequestedWithExpiredToken = new Promise<void>((resolve) => {
    releaseExpiredCalls = resolve;
  });

  await context.addInitScript(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'cross-tab-refresh-user',
      userEmail: 'cross-tab-refresh-user@example.invalid',
      userId: 'cross-tab-refresh-user-id',
    }));
    localStorage.setItem('accessToken', 'cross-tab.expired.access.token');
  });
  await context.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    const authorization = route.request().headers().authorization;
    if (authorization === 'Bearer cross-tab.expired.access.token') {
      expiredPages.add(route.request().frame().page());
      if (expiredPages.size === 2) {
        releaseExpiredCalls();
      }
      await bothTabsRequestedWithExpiredToken;
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'expired' }),
      });
      return;
    }
    expect(authorization).toBe('Bearer cross-tab.refreshed.access.token');
    refreshedPages.add(route.request().frame().page());
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'cross-tab-refresh-user',
        userEmail: 'cross-tab-refresh-user@example.invalid',
        userId: 'cross-tab-refresh-user-id',
      }),
    });
  });
  await context.route(/\/api\/user\/login-methods$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ loginMethods: [], count: 0 }),
    });
  });
  await context.route(/\/api\/auth\/refresh$/, async (route) => {
    refreshCalls += 1;
    await new Promise((resolve) => setTimeout(resolve, 100));
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'cross-tab.refreshed.access.token',
        tokenType: 'Bearer',
      }),
    });
  });

  const secondPage = await context.newPage();
  await Promise.all([
    page.goto('/test'),
    secondPage.goto('/test'),
  ]);

  await expect(page.getByText('已绑定的登录方式 (0)')).toBeVisible();
  await expect(secondPage.getByText('已绑定的登录方式 (0)')).toBeVisible();
  await expect.poll(() => refreshedPages.size).toBe(2);
  expect(refreshCalls).toBe(1);
  await expect.poll(async () => Promise.all([
    page.evaluate(() => localStorage.getItem('accessToken')),
    secondPage.evaluate(() => localStorage.getItem('accessToken')),
  ])).toEqual([
    'cross-tab.refreshed.access.token',
    'cross-tab.refreshed.access.token',
  ]);
});

test('cross-tab logout cannot be undone by a late refresh continuation', async ({
  context,
  page,
}) => {
  let refreshCalls = 0;
  let logoutCalls = 0;
  let markRefreshStarted!: () => void;
  let releaseRefresh!: () => void;
  const refreshStarted = new Promise<void>((resolve) => {
    markRefreshStarted = resolve;
  });
  const refreshCanFinish = new Promise<void>((resolve) => {
    releaseRefresh = resolve;
  });

  await page.addInitScript(() => {
    const originalSetItem = Storage.prototype.setItem;
    let refreshedAccessTokenWrites = 0;
    Storage.prototype.setItem = function setItem(key: string, value: string) {
      if (key === 'accessToken' && value === 'cross-tab-logout.refreshed.access.token') {
        refreshedAccessTokenWrites += 1;
        if (refreshedAccessTokenWrites > 1) {
          window.setTimeout(() => originalSetItem.call(this, key, value), 250);
          return;
        }
      }
      originalSetItem.call(this, key, value);
    };
  });

  const secondPage = await context.newPage();
  await Promise.all([
    page.goto('/login'),
    secondPage.goto('/login'),
  ]);
  await page.evaluate(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'cross-tab-logout-user',
      userEmail: 'cross-tab-logout-user@example.invalid',
      userId: 'cross-tab-logout-user-id',
    }));
    localStorage.setItem('accessToken', 'cross-tab-logout.old.access.token');
  });

  await context.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'cross-tab-logout-user',
        userEmail: 'cross-tab-logout-user@example.invalid',
        userId: 'cross-tab-logout-user-id',
      }),
    });
  });
  await context.route(/\/api\/user\/login-methods$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ loginMethods: [], count: 0 }),
    });
  });
  await context.route(/\/api\/auth\/refresh$/, async (route) => {
    refreshCalls += 1;
    markRefreshStarted();
    await refreshCanFinish;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        message: 'Token refresh successful',
        accessToken: 'cross-tab-logout.refreshed.access.token',
        accessTokenExpiresIn: 3600,
        refreshTokenExpiresIn: 604800,
        tokenType: 'Bearer',
      }),
    });
  });
  await context.route(/\/api\/auth\/logout$/, async (route) => {
    logoutCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ message: 'Logged out successfully' }),
    });
  });

  await Promise.all([
    page.goto('/test'),
    secondPage.goto('/test'),
  ]);
  await expect(page.getByText('已绑定的登录方式 (0)')).toBeVisible();
  await expect(secondPage.getByText('已绑定的登录方式 (0)')).toBeVisible();

  await page.getByRole('button', { name: '刷新Token' }).click();
  await refreshStarted;
  await secondPage.getByRole('button', { name: '登出' }).click();
  expect(logoutCalls).toBe(0);

  releaseRefresh();

  await expect(secondPage).toHaveURL('/login');
  await page.waitForTimeout(400);
  expect(refreshCalls).toBe(1);
  expect(logoutCalls).toBe(1);
  await expect.poll(async () => Promise.all([
    page.evaluate(() => ({
      user: localStorage.getItem('auth_user'),
      access: localStorage.getItem('accessToken'),
      refresh: localStorage.getItem('refreshToken'),
    })),
    secondPage.evaluate(() => ({
      user: localStorage.getItem('auth_user'),
      access: localStorage.getItem('accessToken'),
      refresh: localStorage.getItem('refreshToken'),
    })),
  ])).toEqual([
    { user: null, access: null, refresh: null },
    { user: null, access: null, refresh: null },
  ]);
});

test('application startup removes only the legacy refresh-token key', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('refreshToken', 'legacy.refresh.token');
    localStorage.setItem('unrelated-preference', 'keep-me');
  });

  await page.goto('/login');

  await expect.poll(() => page.evaluate(() => ({
    refresh: localStorage.getItem('refreshToken'),
    unrelated: localStorage.getItem('unrelated-preference'),
  }))).toEqual({ refresh: null, unrelated: 'keep-me' });
});

test('refresh failure clears auth state without retrying the refresh endpoint', async ({ page }) => {
  let loginMethodCalls = 0;
  let refreshCalls = 0;

  await page.addInitScript(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'expired-user',
      userEmail: 'expired-user@example.invalid',
      userId: 'expired-user-id',
    }));
    localStorage.setItem('accessToken', 'expired.access.token');
    localStorage.setItem('refreshToken', 'legacy.refresh.token');
  });
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'expired-user',
        userEmail: 'expired-user@example.invalid',
        userId: 'expired-user-id',
      }),
    });
  });
  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    loginMethodCalls += 1;
    if (loginMethodCalls === 1) {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'expired' }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ loginMethods: [], count: 0 }),
    });
  });
  await page.route(/\/api\/auth\/refresh$/, async (route) => {
    refreshCalls += 1;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'refresh expired' }),
    });
  });

  await page.goto('/test');

  await expect(page.getByText('已绑定的登录方式 (0)')).toBeVisible();
  await expect.poll(() => refreshCalls).toBe(1);
  await expect.poll(() => page.evaluate(() => ({
    access: localStorage.getItem('accessToken'),
    refresh: localStorage.getItem('refreshToken'),
  }))).toEqual({ access: null, refresh: null });
});

test('logout calls the backend once and clears application authentication state', async ({ page }) => {
  let logoutCalls = 0;

  await page.goto('/login');
  await page.evaluate(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'logout-user',
      userEmail: 'logout-user@example.invalid',
      userId: 'logout-user-id',
    }));
    localStorage.setItem('accessToken', 'logout.access.token');
    localStorage.setItem('refreshToken', 'logout.refresh.token');
    localStorage.setItem('unrelated-preference', 'keep-me');
    document.cookie = 'unrelated-cookie=keep-me; path=/';
  });
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'logout-user',
        userEmail: 'logout-user@example.invalid',
        userId: 'logout-user-id',
      }),
    });
  });
  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        loginMethods: [
          {
            id: '00000000-0000-0000-0000-000000000001',
            authProvider: 'local',
            localUsername: 'logout-user',
            isPrimary: true,
            isVerified: true,
            linkedAt: '2026-08-07T00:00:00Z',
          },
        ],
        count: 1,
      }),
    });
  });
  await page.route(/\/api\/auth\/logout$/, async (route) => {
    logoutCalls += 1;
    expect(route.request().method()).toBe('POST');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ message: 'Logged out successfully' }),
    });
  });

  await page.goto('/test');
  await expect(page.getByRole('button', { name: '登出' })).toBeVisible();
  await page.getByRole('button', { name: '登出' }).click();

  await expect(page).toHaveURL('/login');
  expect(logoutCalls).toBe(1);
  await expect.poll(() => page.evaluate(() => ({
    user: localStorage.getItem('auth_user'),
    access: localStorage.getItem('accessToken'),
    refresh: localStorage.getItem('refreshToken'),
    unrelated: localStorage.getItem('unrelated-preference'),
    cookie: document.cookie,
  }))).toEqual({
    user: null,
    access: null,
    refresh: null,
    unrelated: 'keep-me',
    cookie: 'unrelated-cookie=keep-me',
  });
});

test('logout waits for an in-flight refresh and leaves authentication state cleared', async ({
  page,
}) => {
  const requestOrder: string[] = [];
  let logoutCalls = 0;
  let releaseRefresh!: () => void;
  let markRefreshStarted!: () => void;
  const refreshStarted = new Promise<void>((resolve) => {
    markRefreshStarted = resolve;
  });
  const refreshCanFinish = new Promise<void>((resolve) => {
    releaseRefresh = resolve;
  });

  await page.goto('/login');
  await page.evaluate(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'refresh-logout-user',
      userEmail: 'refresh-logout-user@example.invalid',
      userId: 'refresh-logout-user-id',
    }));
    localStorage.setItem('accessToken', 'refresh-logout.old.access.token');
    localStorage.setItem('refreshToken', 'legacy.refresh.token');
    localStorage.setItem('unrelated-preference', 'keep-me');
  });
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'refresh-logout-user',
        userEmail: 'refresh-logout-user@example.invalid',
        userId: 'refresh-logout-user-id',
      }),
    });
  });
  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ loginMethods: [], count: 0 }),
    });
  });
  await page.route(/\/api\/auth\/refresh$/, async (route) => {
    requestOrder.push('refresh-start');
    markRefreshStarted();
    await refreshCanFinish;
    requestOrder.push('refresh-finish');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        message: 'Token refresh successful',
        accessToken: 'refresh-logout.new.access.token',
        accessTokenExpiresIn: 3600,
        refreshTokenExpiresIn: 604800,
        tokenType: 'Bearer',
      }),
    });
  });
  await page.route(/\/api\/auth\/logout$/, async (route) => {
    logoutCalls += 1;
    requestOrder.push('logout');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ message: 'Logged out successfully' }),
    });
  });

  await page.goto('/test');
  await expect(page.getByText('已绑定的登录方式 (0)')).toBeVisible();

  await page.getByRole('button', { name: '刷新Token' }).click();
  await refreshStarted;
  await page.getByRole('button', { name: '登出' }).click();

  expect(logoutCalls).toBe(0);
  releaseRefresh();

  await expect(page).toHaveURL('/login');
  expect(logoutCalls).toBe(1);
  expect(requestOrder).toEqual(['refresh-start', 'refresh-finish', 'logout']);
  await expect.poll(() => page.evaluate(() => ({
    user: localStorage.getItem('auth_user'),
    access: localStorage.getItem('accessToken'),
    refresh: localStorage.getItem('refreshToken'),
    unrelated: localStorage.getItem('unrelated-preference'),
  }))).toEqual({
    user: null,
    access: null,
    refresh: null,
    unrelated: 'keep-me',
  });
});

test('login-method conflicts remain visible and do not mutate the list', async ({ page }) => {
  const githubId = '00000000-0000-0000-0000-000000000002';

  await page.addInitScript(() => {
    localStorage.setItem('auth_user', JSON.stringify({
      authenticated: true,
      provider: 'local',
      userName: 'method-error-user',
      userEmail: 'method-error-user@example.invalid',
      userId: 'method-error-user-id',
    }));
    localStorage.setItem('accessToken', 'method.error.access.token');
  });
  await page.route(/\/api\/user(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        provider: 'local',
        userName: 'method-error-user',
        userEmail: 'method-error-user@example.invalid',
        userId: 'method-error-user-id',
      }),
    });
  });
  await page.route(/\/api\/user\/login-methods\/[^/]+\/primary$/, async (route) => {
    expect(route.request().headers().authorization)
      .toBe('Bearer method.error.access.token');
    await route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({ error: '主登录方式已被并发修改，请重试' }),
    });
  });
  await page.route(/\/api\/user\/login-methods$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        loginMethods: [
          {
            id: '00000000-0000-0000-0000-000000000001',
            authProvider: 'local',
            localUsername: 'method-error-user',
            isPrimary: true,
            isVerified: true,
            linkedAt: '2026-08-07T00:00:00Z',
          },
          {
            id: githubId,
            authProvider: 'github',
            providerUsername: 'method-error-user',
            isPrimary: false,
            isVerified: true,
            linkedAt: '2026-08-07T00:00:00Z',
          },
        ],
        count: 2,
      }),
    });
  });

  await page.goto('/test');
  await expect(page.getByText('已绑定的登录方式 (2)')).toBeVisible();
  await page.getByRole('button', { name: '设为主登录' }).click();

  await expect(page.getByText('设置失败: 主登录方式已被并发修改，请重试')).toBeVisible();
  await expect(page.getByText('已绑定的登录方式 (2)')).toBeVisible();
});
