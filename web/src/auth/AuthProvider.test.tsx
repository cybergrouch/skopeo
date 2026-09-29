import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from './AuthProvider'
import { requestAuth, resetAuthModuleForTests } from './authModule'
import { useAuth } from './useAuth'

/**
 * The key Firebase persists a session under. Seeding it is what makes the provider decide a returning
 * user is likely and load the SDK (#1091) — without it the provider is *correct* to do nothing, which
 * is the behaviour the anonymous-visitor tests below assert.
 */
const SESSION_KEY = 'firebase:authUser:test-api-key:[DEFAULT]'

const fb = vi.hoisted(() => ({
  authObj: { name: 'auth', currentUser: null as unknown },
  googleObj: { name: 'google' },
  facebookObj: { name: 'facebook' },
  createUserWithEmailAndPassword: vi.fn(),
  signInWithEmailAndPassword: vi.fn(),
  signInWithPopup: vi.fn(),
  signOut: vi.fn(),
  onAuthStateChanged: vi.fn(),
  sendSignInLinkToEmail: vi.fn(),
  isSignInWithEmailLink: vi.fn(),
  signInWithEmailLink: vi.fn(),
  updatePassword: vi.fn(),
}))

vi.mock('@/lib/firebaseAuth', () => ({
  auth: fb.authObj,
  googleProvider: fb.googleObj,
  facebookProvider: fb.facebookObj,
}))
vi.mock('firebase/auth', () => ({
  createUserWithEmailAndPassword: fb.createUserWithEmailAndPassword,
  signInWithEmailAndPassword: fb.signInWithEmailAndPassword,
  signInWithPopup: fb.signInWithPopup,
  signOut: fb.signOut,
  onAuthStateChanged: fb.onAuthStateChanged,
  sendSignInLinkToEmail: fb.sendSignInLinkToEmail,
  isSignInWithEmailLink: fb.isSignInWithEmailLink,
  signInWithEmailLink: fb.signInWithEmailLink,
  updatePassword: fb.updatePassword,
}))

function Consumer() {
  const { user, initializing, ...actions } = useAuth()
  return (
    <div>
      <span data-testid="state">
        {initializing ? 'initializing' : (user?.email ?? 'signed-out')}
      </span>
      <button onClick={() => void actions.signUpWithEmail('a@b.co', 'pw')}>
        signup
      </button>
      <button onClick={() => void actions.signInWithEmail('a@b.co', 'pw')}>
        signin
      </button>
      <button onClick={() => void actions.signInWithGoogle()}>google</button>
      <button onClick={() => void actions.signInWithFacebook()}>facebook</button>
      <button onClick={() => void actions.signOut()}>signout</button>
      <button onClick={() => void actions.sendSignInLink('inv@b.co')}>sendlink</button>
      <button onClick={() => void actions.isSignInLink('http://x/link')}>islink</button>
      <button onClick={() => void actions.completeSignInLink('inv@b.co', 'http://x/link')}>
        complete
      </button>
      <button onClick={() => void actions.setPassword('newpass')}>setpw</button>
    </div>
  )
}

function renderProvider() {
  return render(
    <AuthProvider>
      <Consumer />
    </AuthProvider>,
  )
}

describe('AuthProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetAuthModuleForTests()
    window.localStorage.clear()
    // Most tests here are about a returning user, so seed the session Firebase would have persisted.
    // The SDK is loaded through a dynamic import since #1091, so these assertions are all async now.
    window.localStorage.setItem(SESSION_KEY, '{}')
    // Default: subscribe and immediately emit a signed-in user.
    fb.onAuthStateChanged.mockImplementation((_auth, cb) => {
      cb({ email: 'roger@example.com' })
      return () => {}
    })
  })

  it('subscribes to auth state and exposes the current user', async () => {
    renderProvider()
    await waitFor(() =>
      expect(fb.onAuthStateChanged).toHaveBeenCalledWith(fb.authObj, expect.any(Function)),
    )
    expect(await screen.findByText('roger@example.com')).toBeInTheDocument()
  })

  it('reports signed-out when no user is emitted', async () => {
    fb.onAuthStateChanged.mockImplementation((_auth, cb) => {
      cb(null)
      return () => {}
    })
    renderProvider()
    await waitFor(() => expect(fb.onAuthStateChanged).toHaveBeenCalled())
    expect(await screen.findByText('signed-out')).toBeInTheDocument()
  })

  /**
   * The point of #1091: an anonymous visitor on a public page must never fetch the SDK.
   *
   * Asserted through `onAuthStateChanged` never being called, which is the first thing the provider
   * does once the module is in hand — so a call here means the import happened.
   */
  it('never loads the SDK for a visitor with no persisted session', async () => {
    window.localStorage.clear()
    renderProvider()

    // Signed-out immediately rather than stuck on 'initializing': with nothing requested there is
    // nothing to wait for, and PublicPageNav renders its real state on first paint (#1027).
    expect(await screen.findByText('signed-out')).toBeInTheDocument()
    expect(fb.onAuthStateChanged).not.toHaveBeenCalled()
  })

  /**
   * ...but a demand loads it anyway, session or not. This is what `RequireAuth` relies on, and it is
   * why a stale `hasPersistedSession` heuristic cannot lock a signed-in user out of the dashboard.
   */
  it('loads the SDK when a route demands auth, even with no persisted session', async () => {
    window.localStorage.clear()
    renderProvider()
    expect(fb.onAuthStateChanged).not.toHaveBeenCalled()

    requestAuth()

    await waitFor(() => expect(fb.onAuthStateChanged).toHaveBeenCalled())
  })

  it('wires each action to its Firebase call with the app auth instance', async () => {
    const user = userEvent.setup()
    renderProvider()

    await user.click(screen.getByText('signup'))
    expect(fb.createUserWithEmailAndPassword).toHaveBeenCalledWith(
      fb.authObj,
      'a@b.co',
      'pw',
    )

    await user.click(screen.getByText('signin'))
    expect(fb.signInWithEmailAndPassword).toHaveBeenCalledWith(
      fb.authObj,
      'a@b.co',
      'pw',
    )

    await user.click(screen.getByText('google'))
    expect(fb.signInWithPopup).toHaveBeenCalledWith(fb.authObj, fb.googleObj)

    await user.click(screen.getByText('facebook'))
    expect(fb.signInWithPopup).toHaveBeenCalledWith(fb.authObj, fb.facebookObj)

    await user.click(screen.getByText('signout'))
    expect(fb.signOut).toHaveBeenCalledWith(fb.authObj)
  })

  it('wires the email-link and set-password actions', async () => {
    const user = userEvent.setup()
    fb.sendSignInLinkToEmail.mockResolvedValue(undefined)
    fb.signInWithEmailLink.mockResolvedValue({})
    fb.isSignInWithEmailLink.mockReturnValue(true)
    fb.updatePassword.mockResolvedValue(undefined)
    fb.authObj.currentUser = { uid: 'u1' }
    renderProvider()

    await user.click(screen.getByText('sendlink'))
    expect(fb.sendSignInLinkToEmail).toHaveBeenCalledWith(
      fb.authObj,
      'inv@b.co',
      expect.objectContaining({
        handleCodeInApp: true,
        url: expect.stringContaining('inv%40b.co'),
      }),
    )

    await user.click(screen.getByText('islink'))
    expect(fb.isSignInWithEmailLink).toHaveBeenCalledWith(fb.authObj, 'http://x/link')

    await user.click(screen.getByText('complete'))
    expect(fb.signInWithEmailLink).toHaveBeenCalledWith(fb.authObj, 'inv@b.co', 'http://x/link')

    await user.click(screen.getByText('setpw'))
    expect(fb.updatePassword).toHaveBeenCalledWith(fb.authObj.currentUser, 'newpass')
  })

  it('unsubscribes on unmount', async () => {
    const unsubscribe = vi.fn()
    fb.onAuthStateChanged.mockReturnValue(unsubscribe)
    const { unmount } = renderProvider()
    // Wait for the dynamic import to land before unmounting: unmounting first would prove only that
    // a subscription that never happened was not torn down.
    await waitFor(() => expect(fb.onAuthStateChanged).toHaveBeenCalled())

    unmount()

    expect(unsubscribe).toHaveBeenCalled()
  })
})
