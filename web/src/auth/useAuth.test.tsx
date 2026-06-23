import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { useAuth } from './useAuth'
import { AuthContext, type AuthContextValue } from './auth-context'

describe('useAuth', () => {
  it('returns the context value when inside a provider', () => {
    const value = {
      user: null,
      initializing: false,
    } as unknown as AuthContextValue
    const wrapper = ({ children }: { children: ReactNode }) => (
      <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
    )
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current).toBe(value)
  })

  it('throws when used outside a provider', () => {
    expect(() => renderHook(() => useAuth())).toThrow(/AuthProvider/)
  })
})
