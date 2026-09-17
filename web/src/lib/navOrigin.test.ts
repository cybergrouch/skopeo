import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  clearOrigin,
  isOriginCandidate,
  isPublicPath,
  readOrigin,
  rememberOrigin,
} from './navOrigin'

describe('navOrigin', () => {
  beforeEach(() => clearOrigin())

  it('treats the public-by-code pages and /about as public (#1027)', () => {
    for (const path of [
      '/players/AAA111',
      '/players/AAA111/matches',
      '/matches/BBB222',
      '/events/CCC333',
      '/clubs/DDD444',
      '/about',
    ]) {
      expect(isPublicPath(path)).toBe(true)
      expect(isOriginCandidate(path)).toBe(false)
    }
  })

  it('does not treat live scoring as a public page (#986 owns its own exits)', () => {
    expect(isPublicPath('/matches/BBB222/score')).toBe(false)
  })

  it('refuses auth and onboarding routes as origins (#1027)', () => {
    // Returning someone to /login after they signed in, or /complete-profile after completing it,
    // would be a trap rather than a courtesy.
    for (const path of ['/login', '/signup', '/invite', '/complete-profile', '/']) {
      expect(isOriginCandidate(path)).toBe(false)
    }
  })

  it('accepts the dashboard as an origin', () => {
    expect(isOriginCandidate('/dashboard')).toBe(true)
  })

  it('round-trips an origin including its query string', () => {
    rememberOrigin('/dashboard?tab=standings')
    expect(readOrigin()).toBe('/dashboard?tab=standings')
  })

  it('returns null when nothing has been recorded', () => {
    expect(readOrigin()).toBeNull()
  })

  it('rejects a stored value that is no longer a sensible destination', () => {
    // e.g. an origin written by an older build, or a path that has since become public.
    rememberOrigin('/players/AAA111')
    expect(readOrigin()).toBeNull()
  })
  it('survives an unavailable sessionStorage rather than breaking navigation (#1027)', () => {
    // Private mode or an exhausted quota must not break the page. Back then falls back to the
    // dashboard, which is less precise but not broken.
    //
    // A valid origin is stored FIRST so the assertion can only pass via the catch: if the throw were
    // not caught, getItem would hand back '/dashboard' and readOrigin would return it. Asserting null
    // against an already-empty store would pass either way and prove nothing — which is exactly what
    // an earlier version of this test did.
    //
    // `vi.stubGlobal`, not `vi.spyOn(window.sessionStorage, ...)`: the module reads the bare global,
    // which is not the same object as `window.sessionStorage` under this environment, so an instance
    // spy is never consulted and the test silently passes without entering the catch.
    rememberOrigin('/dashboard')
    expect(readOrigin()).toBe('/dashboard')

    vi.stubGlobal('sessionStorage', {
      getItem: () => {
        throw new Error('sessionStorage unavailable')
      },
      setItem: () => undefined,
      removeItem: () => undefined,
    })
    try {
      expect(readOrigin()).toBeNull()
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
