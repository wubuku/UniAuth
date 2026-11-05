import { beforeEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { AuthService } from '../../src/services/authService'

vi.mock('axios', () => {
  const post = vi.fn()
  const get = vi.fn()
  return {
    default: {
      post,
      get,
      defaults: { withCredentials: false },
      interceptors: {
        request: { use: vi.fn() },
        response: { use: vi.fn() },
      },
    },
  }
})

const postMock = axios.post as unknown as ReturnType<typeof vi.fn>
const getMock = axios.get as unknown as ReturnType<typeof vi.fn>

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function installSerialLockManager() {
  let chain: Promise<unknown> = Promise.resolve()
  const request = vi.fn((_name: string, callback: () => Promise<unknown>) => {
    const run = chain.then(callback)
    chain = run.catch(() => undefined)
    return run
  })
  Object.defineProperty(navigator, 'locks', {
    configurable: true,
    value: { request },
  })
  return request
}

function removeLockManager() {
  Reflect.deleteProperty(navigator, 'locks')
}

describe('AuthService token refresh coordination', () => {
  beforeEach(() => {
    postMock.mockReset()
    // `refreshTokenRequest` and `logoutInProgress` are module singletons shared
    // by every test in this file. Clearing local auth also nulls `csrfToken`,
    // so each case starts from the same production bootstrap state instead of
    // inheriting a cached token or a leaked in-flight slot.
    AuthService.clearLocalAuthentication()
    removeLockManager()
  })

  it('shares one in-flight request between concurrent refresh callers', async () => {
    const response = deferred<unknown>()
    postMock.mockReturnValueOnce(response.promise)

    const first = AuthService.refreshToken()
    const second = AuthService.refreshToken()

    expect(postMock).toHaveBeenCalledTimes(1)
    expect(postMock).toHaveBeenCalledWith(
      '/api/auth/refresh',
      {},
      expect.objectContaining({ withCredentials: true })
    )

    response.resolve({
      data: {
        accessToken: 'refreshed.token',
        tokenType: 'Bearer',
        accessTokenExpiresIn: 3600,
        refreshTokenExpiresIn: 604800,
      },
    })

    await expect(first).resolves.toMatchObject({ accessToken: 'refreshed.token' })
    await expect(second).resolves.toMatchObject({ accessToken: 'refreshed.token' })
    expect(postMock).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('auth_session_marker')).toBeTruthy()
  })

  it('does not refresh when the shared marker changed in another browser context', async () => {
    installSerialLockManager()
    localStorage.setItem('auth_session_marker', 'other-tab-marker')
    localStorage.setItem('accessToken', 'other-tab.access.token')

    const result = await AuthService.refreshToken('stale-tab-marker')

    expect(postMock).not.toHaveBeenCalled()
    expect(result.message).toContain('another browser context')
    expect(result.accessToken).toBe('other-tab.access.token')
  })

  it('rejects before requesting when the session marker vanished while queued for the lock', async () => {
    installSerialLockManager()

    await expect(AuthService.refreshToken('marker-that-was-cleared')).rejects.toThrow(
      'Authentication state changed before token refresh'
    )
    expect(postMock).not.toHaveBeenCalled()
  })

  it('waits for the in-flight refresh before revoking the session on logout', async () => {
    const refreshResponse = deferred<unknown>()
    const logoutResponse = deferred<unknown>()
    postMock
      .mockReturnValueOnce(refreshResponse.promise)
      .mockReturnValueOnce(logoutResponse.promise)
    const lockRequest = installSerialLockManager()

    const refresh = AuthService.refreshToken()
    await vi.waitFor(() => expect(lockRequest).toHaveBeenCalledTimes(1))

    const logout = AuthService.logout()
    // Logout cannot even queue for the shared lock until the in-flight refresh
    // settles, so no revoke request may be issued yet.
    expect(postMock).toHaveBeenCalledTimes(1)

    refreshResponse.resolve({
      data: { accessToken: 'refreshed.before.logout', tokenType: 'Bearer' },
    })
    await refresh
    await vi.waitFor(() => expect(lockRequest).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(postMock).toHaveBeenCalledTimes(2))
    expect(postMock.mock.calls[1][0]).toBe('/api/auth/logout')

    logoutResponse.resolve({ data: { message: 'Logged out' } })
    await logout

    expect(localStorage.getItem('auth_user')).toBeNull()
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('auth_session_marker')).toBeNull()
  })

  it('rejects new refreshes while a logout is in progress', async () => {
    const logoutResponse = deferred<unknown>()
    postMock.mockReturnValueOnce(logoutResponse.promise)
    installSerialLockManager()

    const logout = AuthService.logout()
    await vi.waitFor(() => expect(postMock).toHaveBeenCalledTimes(1))

    await expect(AuthService.refreshToken()).rejects.toThrow('Logout is in progress')

    logoutResponse.resolve({ data: { message: 'Logged out' } })
    await logout

    expect(postMock).toHaveBeenCalledTimes(1)
  })

  it('clears the single-flight slot after a failed refresh so a retry is possible', async () => {
    postMock.mockRejectedValueOnce(new Error('refresh unavailable'))
    await expect(AuthService.refreshToken()).rejects.toThrow('刷新Token失败')

    postMock.mockResolvedValueOnce({
      data: { accessToken: 'second.attempt.token', tokenType: 'Bearer' },
    })
    await expect(AuthService.refreshToken()).resolves.toMatchObject({
      accessToken: 'second.attempt.token',
    })
    expect(postMock).toHaveBeenCalledTimes(2)
  })

  it('releases the single-flight slot after a successful refresh', async () => {
    postMock.mockResolvedValueOnce({
      data: { accessToken: 'first.token', tokenType: 'Bearer' },
    })
    await AuthService.refreshToken()

    postMock.mockResolvedValueOnce({
      data: { accessToken: 'second.token', tokenType: 'Bearer' },
    })
    await AuthService.refreshToken()

    // A leaked slot would make the second call resolve the first promise and
    // post only once.
    expect(postMock).toHaveBeenCalledTimes(2)
  })
})

describe('AuthService CSRF bootstrap', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    // Module singleton `csrfToken` is reset through the production cleanup
    // path; the isolation assertion below fails if a case leaves it cached.
    AuthService.clearLocalAuthentication()
    removeLockManager()
  })

  it('bootstraps once and shares one token between concurrent callers', async () => {
    const response = deferred<unknown>()
    getMock.mockReturnValueOnce(response.promise)

    const first = AuthService.csrfHeader()
    const second = AuthService.csrfHeader()

    expect(getMock).toHaveBeenCalledTimes(1)
    expect(getMock).toHaveBeenCalledWith('/api/auth/csrf', {
      withCredentials: true,
    })

    response.resolve({ data: { token: 'csrf-token-1', headerName: 'X-CSRF-Token' } })

    await expect(first).resolves.toEqual({ 'X-CSRF-Token': 'csrf-token-1' })
    await expect(second).resolves.toEqual({ 'X-CSRF-Token': 'csrf-token-1' })
    expect(getMock).toHaveBeenCalledTimes(1)
  })

  it('fails closed on a malformed bootstrap and does not cache a token', async () => {
    getMock.mockResolvedValueOnce({ data: { token: '', headerName: 'X-CSRF-Token' } })

    await expect(AuthService.csrfHeader()).rejects.toThrow('CSRF bootstrap failed')

    getMock.mockResolvedValueOnce({ data: { token: 'retry-token', headerName: 'X-CSRF-Token' } })
    await expect(AuthService.csrfHeader()).resolves.toEqual({ 'X-CSRF-Token': 'retry-token' })

    // The failed attempt must not leave a token behind: a retry costs a new GET.
    expect(getMock).toHaveBeenCalledTimes(2)
  })

  it('discards the cached token when local authentication is cleared', async () => {
    getMock.mockResolvedValueOnce({ data: { token: 'first-token', headerName: 'X-CSRF-Token' } })
    await AuthService.csrfHeader()

    AuthService.clearLocalAuthentication()

    getMock.mockResolvedValueOnce({ data: { token: 'second-token', headerName: 'X-CSRF-Token' } })
    await expect(AuthService.csrfHeader()).resolves.toEqual({ 'X-CSRF-Token': 'second-token' })

    // Proves the beforeEach reset used by this file actually isolates cases.
    expect(getMock).toHaveBeenCalledTimes(2)
  })
})

describe('AuthService local authentication state', () => {
  beforeEach(() => {
    postMock.mockReset()
    removeLockManager()
  })

  it('removes every persisted credential when clearing local authentication', () => {
    localStorage.setItem('auth_user', '{"id":"user-1"}')
    localStorage.setItem('accessToken', 'diagnostic.token')
    localStorage.setItem('refreshToken', 'legacy.refresh.token')
    localStorage.setItem('auth_session_marker', 'marker')

    AuthService.clearLocalAuthentication()

    expect(localStorage.getItem('auth_user')).toBeNull()
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('refreshToken')).toBeNull()
    expect(localStorage.getItem('auth_session_marker')).toBeNull()
  })

  it('marks each authenticated session with a fresh marker', () => {
    const first = AuthService.markAuthenticatedSession()
    const firstMarker = localStorage.getItem('auth_session_marker')
    AuthService.markAuthenticatedSession()
    const secondMarker = localStorage.getItem('auth_session_marker')

    expect(first).toBeUndefined()
    expect(firstMarker).toBeTruthy()
    expect(secondMarker).toBeTruthy()
    expect(secondMarker).not.toBe(firstMarker)
  })
})
