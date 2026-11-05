import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import LoginPage from '../../src/pages/LoginPage'
import TestPage from '../../src/pages/TestPage'

vi.mock('../../src/services/authService', () => {
  const AuthService = {
    getCurrentUser: vi.fn(),
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    refreshToken: vi.fn(),
    getLoginMethods: vi.fn(),
    sendVerificationCode: vi.fn(),
    verifyEmail: vi.fn(),
    clearLocalAuthentication: vi.fn(),
  }
  return { AuthService, default: AuthService }
})

import { AuthService } from '../../src/services/authService'

const mocked = vi.mocked(AuthService)

function renderLogin(initialEntry = '/login') {
  window.history.replaceState({}, '', initialEntry)
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/test" element={<div>protected page</div>} />
      </Routes>
    </MemoryRouter>
  )
}

function submitForm() {
  const form = document.querySelector('form')
  expect(form).not.toBeNull()
  fireEvent.submit(form as HTMLFormElement)
}

function fillLogin(container: HTMLElement, username: string, password: string) {
  fireEvent.change(
    container.querySelector('input[placeholder="用户名"]') as HTMLInputElement,
    { target: { value: username } }
  )
  fireEvent.change(
    container.querySelector('input[placeholder="密码"]') as HTMLInputElement,
    { target: { value: password } }
  )
}

describe('LoginPage authentication failure paths', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mocked.getLoginMethods.mockResolvedValue({ loginMethods: [] } as never)
  })

  it('shows the backend message when local login is rejected', async () => {
    mocked.login.mockRejectedValue(new Error('用户名或密码错误'))
    const { container } = renderLogin()

    fillLogin(container as HTMLElement, 'alice', 'wrong-password')
    submitForm()

    await waitFor(() => {
      expect(screen.getByText('用户名或密码错误')).toBeTruthy()
    })
    expect(screen.queryByText('protected page')).toBeNull()
  })

  it('navigates back to the return path after a successful login', async () => {
    mocked.login.mockResolvedValue({
      user: {
        id: 'user-1',
        username: 'alice',
        email: 'alice@example.com',
        displayName: 'Alice',
      },
      accessToken: 'diagnostic-access-token',
    } as never)
    const { container } = renderLogin('/login?returnTo=%2Ftest')

    fillLogin(container as HTMLElement, 'alice', 'correct-password')
    submitForm()

    await waitFor(() => {
      expect(screen.getByText('protected page')).toBeTruthy()
    })
    expect(window.location.pathname).toBe('/login')
  })

  it('maps EMAIL_ALREADY_REGISTERED to the stable user-facing message', async () => {
    mocked.register.mockRejectedValue({
      response: { data: { errorCode: 'EMAIL_ALREADY_REGISTERED' } },
    })
    const { container } = renderLogin()

    fireEvent.click(screen.getByRole('button', { name: '注册' }))
    fireEvent.change(
      container.querySelector('input[placeholder="用户名"]') as HTMLInputElement,
      { target: { value: 'alice@example.com' } }
    )
    submitForm()

    await waitFor(() => {
      expect(
        screen.getByText('该邮箱已注册，请使用忘记密码功能重置密码')
      ).toBeTruthy()
    })
  })

  it('opens the email verification modal when registration requires it', async () => {
    mocked.register.mockResolvedValue({
      requireEmailVerification: true,
      email: 'alice@example.com',
    } as never)
    mocked.sendVerificationCode.mockResolvedValue({
      challengeHandle: 'handle-1',
      resendAfter: 60,
    } as never)
    const { container } = renderLogin()

    fireEvent.click(screen.getByRole('button', { name: '注册' }))
    fireEvent.change(
      container.querySelector('input[placeholder="用户名"]') as HTMLInputElement,
      { target: { value: 'alice@example.com' } }
    )
    fireEvent.change(
      container.querySelector('input[placeholder="密码"]') as HTMLInputElement,
      { target: { value: 'correct-password' } }
    )
    submitForm()

    await waitFor(() => {
      expect(screen.getByText('邮箱验证')).toBeTruthy()
    })
    expect(screen.getByPlaceholderText('请输入6位验证码')).toBeTruthy()
    await waitFor(() => {
      expect(mocked.sendVerificationCode).toHaveBeenCalledWith({
        email: 'alice@example.com',
        purpose: 'REGISTRATION',
      })
    })
  })

  it('keeps only six digits in the verification code input', async () => {
    mocked.register.mockResolvedValue({
      requireEmailVerification: true,
      email: 'alice@example.com',
    } as never)
    mocked.sendVerificationCode.mockResolvedValue({
      challengeHandle: 'handle-1',
      resendAfter: 60,
    } as never)
    const { container } = renderLogin()

    fireEvent.click(screen.getByRole('button', { name: '注册' }))
    fireEvent.change(
      container.querySelector('input[placeholder="用户名"]') as HTMLInputElement,
      { target: { value: 'alice@example.com' } }
    )
    submitForm()

    const codeInput = await screen.findByPlaceholderText('请输入6位验证码')
    fireEvent.change(codeInput, { target: { value: '12ab34cd56' } })

    expect((codeInput as HTMLInputElement).value).toBe('123456')
  })
})

describe('Protected page guard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('does not render protected content when the current user request is rejected', async () => {
    mocked.getCurrentUser.mockRejectedValue({
      response: { status: 401, data: { error: 'User not authenticated' } },
    })
    window.history.replaceState({}, '', '/test')

    render(<TestPage />)

    await waitFor(() => {
      expect(screen.getByText('加载中...')).toBeTruthy()
    })
    expect(screen.queryByText(/多登录方式管理/)).toBeNull()
  })

  it('renders protected content after the current user request succeeds', async () => {
    mocked.getCurrentUser.mockResolvedValue({
      authenticated: true,
      userId: 'user-1',
      userName: 'alice',
      userEmail: 'alice@example.com',
    } as never)
    window.history.replaceState({}, '', '/test')

    render(<TestPage />)

    await waitFor(() => {
      expect(screen.getByText(/多登录方式管理/)).toBeTruthy()
    })
  })
})
