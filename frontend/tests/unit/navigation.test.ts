import { describe, expect, it, vi } from 'vitest'
import { navigateTo } from '../../src/utils/navigation'

describe('navigateTo', () => {
  it('delegates the exact target to the injected location', () => {
    const assign = vi.fn()

    navigateTo('/login?passwordChanged=true', { assign })

    expect(assign).toHaveBeenCalledTimes(1)
    expect(assign).toHaveBeenCalledWith('/login?passwordChanged=true')
  })

  it('uses window.location.assign by default', () => {
    const assign = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { assign },
    })

    navigateTo('/oauth2/authorization/google')

    expect(assign).toHaveBeenCalledTimes(1)
    expect(assign).toHaveBeenCalledWith('/oauth2/authorization/google')
  })
})
