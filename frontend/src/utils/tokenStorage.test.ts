import { beforeEach, describe, expect, it } from 'vitest'
import { clearStoredToken, getStoredToken, setStoredToken } from './tokenStorage'

describe('tokenStorage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('returns null when no token has been stored', () => {
    expect(getStoredToken()).toBeNull()
  })

  it('persists a token so it can be read back', () => {
    setStoredToken('jwt-token-value')
    expect(getStoredToken()).toBe('jwt-token-value')
  })

  it('removes the token on clear', () => {
    setStoredToken('jwt-token-value')
    clearStoredToken()
    expect(getStoredToken()).toBeNull()
  })

  it('overwrites a previously stored token', () => {
    setStoredToken('first-token')
    setStoredToken('second-token')
    expect(getStoredToken()).toBe('second-token')
  })
})
