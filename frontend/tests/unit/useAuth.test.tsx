import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useAuth } from '../../src/hooks/useAuth'
import { AuthService } from '../../src/services/authService'
import { navigateTo } from '../../src/utils/navigation'
import type { User } from '../../src/types'

// Redirects go through the navigation seam, so the hook test can assert the
// exact target instead of relying on a real jsdom navigation (which jsdom
// cannot perform). Production still uses window.location.assign.
vi.mock('../../src/utils/navigation', () => ({
  navigateTo: vi.fn(),
}))

const currentUser: User = {
  id: 'user-1',
  username: 'alice',
  email: 'alice@example.invalid',
  displayName: 'Alice',
  provider: 'local',
}

describe('useAuth authentication state machine', () => {
  it('loads the current user through checkAuth', async () => {
    vi.spyOn(AuthService, 'getCurrentUser').mockResolvedValue(currentUser)

    const { result } = renderHook(() => useAuth())

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.user).toEqual(currentUser)
    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.error).toBeNull()
  })

  it('records a failed authentication check without redirecting', async () => {
    vi.spyOn(AuthService, 'getCurrentUser').mockRejectedValue(
      new Error('获取用户信息失败')
    )

    const { result } = renderHook(() => useAuth())

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.user).toBeNull()
    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.error).toBe('获取用户信息失败')
  })

  it('persists the authenticated user after a local login', async () => {
    vi.spyOn(AuthService, 'getCurrentUser').mockRejectedValue(new Error('anonymous'))
    vi.spyOn(AuthService, 'login').mockResolvedValue({
      user: currentUser,
      accessToken: 'local.login.token',
    })

    const { result } = renderHook(() => useAuth())
    await waitFor(() => expect(result.current.loading).toBe(false))

    await result.current.localLogin('alice', 'correct-horse-battery')

    await waitFor(() => expect(result.current.user?.id).toBe('user-1'))
    expect(result.current.user).toMatchObject({
      id: 'user-1',
      username: 'alice',
      email: 'alice@example.invalid',
      provider: 'local',
    })
    expect(JSON.parse(localStorage.getItem('auth_user') ?? 'null')).toMatchObject({
      id: 'user-1',
      username: 'alice',
    })
    expect(localStorage.getItem('accessToken')).toBe('local.login.token')
  })

  it('logs out when a token refresh fails', async () => {
    vi.spyOn(AuthService, 'getCurrentUser').mockResolvedValue(currentUser)
    vi.spyOn(AuthService, 'refreshToken').mockRejectedValue(new Error('refresh rejected'))
    const logout = vi.spyOn(AuthService, 'logout').mockResolvedValue(undefined)

    const { result } = renderHook(() => useAuth())
    await waitFor(() => expect(result.current.user).toEqual(currentUser))

    await expect(result.current.refreshToken()).rejects.toThrow('refresh rejected')

    expect(logout).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(result.current.user).toBeNull())
    expect(localStorage.getItem('auth_user')).toBeNull()
    expect(navigateTo).toHaveBeenCalledWith('/login')
  })

  it('navigates to the provider authorization URL on oauthLogin', async () => {
    vi.spyOn(AuthService, 'getCurrentUser').mockRejectedValue(new Error('anonymous'))

    const { result } = renderHook(() => useAuth())
    await waitFor(() => expect(result.current.loading).toBe(false))

    result.current.oauthLogin('github')

    expect(navigateTo).toHaveBeenCalledTimes(1)
    expect(navigateTo).toHaveBeenCalledWith('/oauth2/authorization/github')
  })
})
