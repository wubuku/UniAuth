import { expect, test } from '@playwright/test';

const walletAddress = '0x1111111111111111111111111111111111111111';
const walletSignature = `0x${'ab'.repeat(65)}`;

type WalletMode = 'success' | 'reject-connect' | 'reject-sign';

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
  const nonce = 'browser-wallet-nonce';
  const message = 'Browser wallet challenge';
  let verifyBody: Record<string, string> | undefined;

  await installWallet(page, 'success');
  await mockCurrentUser(page);
  await page.route(/\/api\/auth\/web3\/nonce\/[^/]+$/, async (route) => {
    expect(route.request().method()).toBe('GET');
    expect(new URL(route.request().url()).pathname.toLowerCase())
      .toBe(`/api/auth/web3/nonce/${walletAddress}`);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ nonce, message, expiresIn: 300 }),
    });
  });
  await page.route(/\/api\/auth\/web3\/verify$/, async (route) => {
    verifyBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'wallet.access.token',
        refreshToken: 'wallet.refresh.token',
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
    nonce,
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
        nonce: 'rejected-signature-nonce',
        message: 'Reject this challenge',
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
        refreshToken: 'refreshed.refresh.token',
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
    localStorage.setItem('unrelated-preference', 'preserve-in-a-later-hardening-batch');
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
  }))).toEqual({ user: null, access: null, refresh: null });
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
