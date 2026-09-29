import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

/**
 * What happens when the auth chunk cannot be fetched at all (#1091).
 *
 * Not a hypothetical: chunk filenames are content-hashed, so a deploy landing mid-session makes the
 * URL a long-lived tab holds 404. Every branch here was written for that and is worth pinning —
 * without them the app hangs on a spinner nobody can clear, which is strictly worse than being shown
 * as signed-out.
 *
 * A throwing `vi.mock` factory is what reproduces it: the dynamic `import()` inside `loadAuth`
 * rejects exactly as a missing chunk would.
 */
vi.mock('@/lib/firebaseAuth', () => {
  throw new Error('Failed to fetch dynamically imported module')
})
vi.mock('firebase/auth', () => ({}))

const { AuthProvider } = await import('./AuthProvider')
const { loadAuth, resetAuthModuleForTests } = await import('./authModule')
const { useAuth } = await import('./useAuth')

const SESSION_KEY = 'firebase:authUser:test-api-key:[DEFAULT]'

function Consumer() {
  const { user, initializing } = useAuth()
  return <span>{initializing ? 'initializing' : (user?.email ?? 'signed-out')}</span>
}

describe('when the auth SDK cannot be loaded', () => {
  beforeEach(() => {
    resetAuthModuleForTests()
    window.localStorage.clear()
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  it('rejects rather than resolving a half-built module', async () => {
    await expect(loadAuth()).rejects.toThrow()
  })

  it('forgets the failure so a later attempt can retry', async () => {
    await expect(loadAuth()).rejects.toThrow()

    // A cached *rejected* promise would fail every caller for the rest of the page's life. The
    // second call has to be a real attempt, not the first one's corpse.
    await expect(loadAuth()).rejects.toThrow()
  })

  it('presents the app as signed-out instead of waiting on auth forever', async () => {
    window.localStorage.setItem(SESSION_KEY, '{}')

    render(
      <AuthProvider>
        <Consumer />
      </AuthProvider>,
    )

    // Would sit on 'initializing' indefinitely without the catch: `onAuthStateChanged` never fires,
    // so nothing else would ever resolve it.
    expect(await screen.findByText('signed-out')).toBeInTheDocument()
  })
})
