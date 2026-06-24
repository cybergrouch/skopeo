import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from './AuthProvider'
import { useAuth } from './useAuth'

const fb = vi.hoisted(() => ({
  authObj: { name: 'auth' },
  googleObj: { name: 'google' },
  facebookObj: { name: 'facebook' },
  createUserWithEmailAndPassword: vi.fn(),
  signInWithEmailAndPassword: vi.fn(),
  signInWithPopup: vi.fn(),
  signOut: vi.fn(),
  onAuthStateChanged: vi.fn(),
}))

vi.mock('@/lib/firebase', () => ({
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
    // Default: subscribe and immediately emit a signed-in user.
    fb.onAuthStateChanged.mockImplementation((_auth, cb) => {
      cb({ email: 'roger@example.com' })
      return () => {}
    })
  })

  it('subscribes to auth state and exposes the current user', () => {
    renderProvider()
    expect(fb.onAuthStateChanged).toHaveBeenCalledWith(
      fb.authObj,
      expect.any(Function),
    )
    expect(screen.getByTestId('state')).toHaveTextContent('roger@example.com')
  })

  it('reports signed-out when no user is emitted', () => {
    fb.onAuthStateChanged.mockImplementation((_auth, cb) => {
      cb(null)
      return () => {}
    })
    renderProvider()
    expect(screen.getByTestId('state')).toHaveTextContent('signed-out')
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

  it('unsubscribes on unmount', () => {
    const unsubscribe = vi.fn()
    fb.onAuthStateChanged.mockReturnValue(unsubscribe)
    const { unmount } = renderProvider()
    unmount()
    expect(unsubscribe).toHaveBeenCalled()
  })
})
