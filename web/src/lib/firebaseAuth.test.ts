import { describe, expect, it, vi } from 'vitest'

// The real SDK is not loaded here: every export is a sentinel, so the test reads exactly what this
// module hands to Firebase.
const { initializeAuth, sentinels } = vi.hoisted(() => ({
  initializeAuth: vi.fn(() => ({ name: 'auth' })),
  sentinels: {
    browserLocalPersistence: { kind: 'LOCAL_STORAGE' },
    indexedDBLocalPersistence: { kind: 'INDEXED_DB' },
    browserPopupRedirectResolver: { kind: 'POPUP_RESOLVER' },
  },
}))
vi.mock('firebase/auth', () => ({
  initializeAuth,
  ...sentinels,
  GoogleAuthProvider: class {},
  FacebookAuthProvider: class {},
}))
vi.mock('./firebase', () => ({ firebaseApp: { name: 'app' } }))

describe('firebaseAuth persistence (#1141)', () => {
  it('stores the session in localStorage first, where hasPersistedSession() looks for it', async () => {
    const { AUTH_PERSISTENCE } = await import('./firebaseAuth')

    // localStorage first: `hasPersistedSession()` reads a `firebase:authUser:` localStorage key, so with
    // anything else first a signed-in visitor looks anonymous on every fresh load of a public page.
    // IndexedDB second: the migration path for sessions saved under getAuth()'s IndexedDB-first default.
    expect(AUTH_PERSISTENCE).toEqual([sentinels.browserLocalPersistence, sentinels.indexedDBLocalPersistence])
    expect(initializeAuth).toHaveBeenCalledWith(
      { name: 'app' },
      {
        persistence: [sentinels.browserLocalPersistence, sentinels.indexedDBLocalPersistence],
        // getAuth supplied this implicitly; initializeAuth does not, and sign-in uses popups.
        popupRedirectResolver: sentinels.browserPopupRedirectResolver,
      },
    )
  })
})
