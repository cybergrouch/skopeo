import {
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react'
import type { User } from 'firebase/auth'
import {
  hasPersistedSession,
  isAuthRequested,
  loadAuth,
  requestAuth,
  subscribeAuthRequested,
  type AuthModule,
} from './authModule'
import { AuthContext, type AuthContextValue } from './auth-context'

/** Continue URL for an invite email-link; the invitee's accept page reads the email from it. */
function inviteActionCodeSettings(email: string) {
  return {
    url: `${window.location.origin}/invite?email=${encodeURIComponent(email)}`,
    handleCodeInApp: true,
  }
}

/**
 * Auth state for the whole app — **without** putting the Firebase SDK in the critical path (#1091).
 *
 * This provider wraps every route, public ones included, so what it imports is what every visitor
 * downloads. It used to import `firebase/auth` statically, which is why an anonymous viewer of
 * `/players/:code` paid 162 kB gzip for a session they will never have.
 *
 * The SDK now arrives on one of two signals, and the distinction is the whole design:
 *
 * - **A persisted session is likely** (`hasPersistedSession`) — a returning user, so load eagerly and
 *   behave exactly as before. A wrong guess costs the signed-out nav on a public page (#1027).
 * - **Something demanded it** (`requestAuth`, called by `RequireAuth` and the auth routes) — a surface
 *   that cannot work without auth. Never a guess, which is what keeps a bad probe from locking a
 *   signed-in user out of the dashboard.
 *
 * An anonymous visitor matches neither and never fetches the SDK.
 *
 * **`initializing` means "we are waiting on auth", not "we have not checked".** With nothing
 * requested there is nothing to wait for, so it is false immediately and the nav renders its
 * signed-out state on first paint instead of sitting on a spinner forever.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const requested = useSyncExternalStore(subscribeAuthRequested, isAuthRequested, isAuthRequested)
  const [sdk, setSdk] = useState<AuthModule | null>(null)
  const [user, setUser] = useState<User | null>(null)
  // Whether an auth state has actually arrived. Distinct from `sdk != null`: the module can be in
  // memory a tick before the first `onAuthStateChanged` fires, and redirecting in that window is the
  // flicker `initializing` exists to prevent.
  const [resolved, setResolved] = useState(false)

  // A returning visitor gets the SDK without anyone asking. Effect rather than render so the store
  // mutation stays out of the render phase.
  useEffect(() => {
    if (hasPersistedSession()) requestAuth()
  }, [])

  useEffect(() => {
    if (!requested) return
    let live = true
    loadAuth()
      .then((module) => {
        if (live) setSdk(module)
      })
      .catch((error: unknown) => {
        // Auth is unreachable — offline, or a stale chunk hash after a deploy. Stop waiting and
        // present the app as signed-out rather than leaving every consumer on `initializing`
        // forever; a spinner nobody can clear is worse than a visible signed-out state.
        console.error('Firebase Auth failed to load', error)
        if (live) setResolved(true)
      })
    return () => {
      live = false
    }
  }, [requested])

  useEffect(() => {
    if (!sdk) return
    return sdk.onAuthStateChanged(sdk.auth, (next) => {
      setUser(next)
      setResolved(true)
    })
  }, [sdk])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      initializing: requested && !resolved,
      signUpWithEmail: async (email, password) => {
        const m = await loadAuth()
        return m.createUserWithEmailAndPassword(m.auth, email, password)
      },
      signInWithEmail: async (email, password) => {
        const m = await loadAuth()
        return m.signInWithEmailAndPassword(m.auth, email, password)
      },
      signInWithGoogle: async () => {
        const m = await loadAuth()
        return m.signInWithPopup(m.auth, m.googleProvider)
      },
      signInWithFacebook: async () => {
        const m = await loadAuth()
        return m.signInWithPopup(m.auth, m.facebookProvider)
      },
      signOut: async () => {
        const m = await loadAuth()
        return m.signOut(m.auth)
      },
      sendSignInLink: async (email) => {
        const m = await loadAuth()
        await m.sendSignInLinkToEmail(m.auth, email, inviteActionCodeSettings(email))
      },
      // The one synchronous member of the contract, because `InviteAcceptPage` derives from it during
      // render. Answering it needs the SDK, so `/invite` is wrapped in `RequireAuthLoaded` — by the
      // time this is reachable the module is in memory. False before then is the honest answer: no
      // link has been verified.
      isSignInLink: (url) => (sdk ? sdk.isSignInWithEmailLink(sdk.auth, url) : false),
      completeSignInLink: async (email, url) => {
        const m = await loadAuth()
        return m.signInWithEmailLink(m.auth, email, url)
      },
      // Only called right after email-link sign-in, so currentUser is set.
      setPassword: async (password) => {
        const m = await loadAuth()
        await m.updatePassword(m.auth.currentUser as User, password)
      },
    }),
    [user, requested, resolved, sdk],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
