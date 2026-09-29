import { describe, it, expect, beforeEach } from 'vitest'
import {
  authLoaded,
  hasPersistedSession,
  isAuthRequested,
  requestAuth,
  resetAuthModuleForTests,
  subscribeAuthRequested,
} from './authModule'

describe('authModule', () => {
  beforeEach(() => {
    resetAuthModuleForTests()
    window.localStorage.clear()
  })

  describe('hasPersistedSession', () => {
    it('sees a persisted Firebase session', () => {
      window.localStorage.setItem('firebase:authUser:some-api-key:[DEFAULT]', '{}')
      expect(hasPersistedSession()).toBe(true)
    })

    it('ignores unrelated keys, so an anonymous visitor never triggers the import', () => {
      window.localStorage.setItem('theme', 'dark')
      window.localStorage.setItem('firebase:host:example', 'x')
      expect(hasPersistedSession()).toBe(false)
    })

    it('reports no session rather than throwing when storage is unavailable', () => {
      // Safari private mode and some embedded webviews throw on access. A scoreboard opened from a
      // QR code on a borrowed phone still has to render.
      const original = Object.getOwnPropertyDescriptor(window, 'localStorage')
      Object.defineProperty(window, 'localStorage', {
        configurable: true,
        get() {
          throw new Error('SecurityError')
        },
      })

      expect(hasPersistedSession()).toBe(false)

      if (original) Object.defineProperty(window, 'localStorage', original)
    })
  })

  describe('requestAuth', () => {
    it('is idempotent and notifies subscribers exactly once', () => {
      let notifications = 0
      const unsubscribe = subscribeAuthRequested(() => {
        notifications += 1
      })
      expect(isAuthRequested()).toBe(false)

      requestAuth()
      requestAuth()

      expect(isAuthRequested()).toBe(true)
      expect(notifications).toBe(1)
      unsubscribe()
    })

    it('marks the module as loading, which is what lets callers take a synchronous path later', () => {
      expect(authLoaded()).toBe(false)
      requestAuth()
      expect(authLoaded()).toBe(true)
    })
  })
})
