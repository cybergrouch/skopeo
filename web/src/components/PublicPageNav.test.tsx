import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { PublicPageNav } from './PublicPageNav'
import { clearOrigin, rememberOrigin } from '@/lib/navOrigin'

const { state } = vi.hoisted(() => ({
  state: { user: null as { uid: string } | null, initializing: false },
}))
vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({ user: state.user, initializing: state.initializing }),
}))

function renderNav() {
  return render(
    <MemoryRouter>
      <PublicPageNav />
    </MemoryRouter>,
  )
}

describe('PublicPageNav', () => {
  beforeEach(() => {
    state.user = null
    state.initializing = false
    clearOrigin()
  })

  it('shows a sign-up / log-in CTA for anonymous viewers (#193)', () => {
    renderNav()
    expect(screen.getByText(/Sign up to track your own ratings/i)).toBeInTheDocument()
    const signUp = screen.getByRole('link', { name: 'Sign up' })
    const logIn = screen.getByRole('link', { name: 'Log in' })
    expect(signUp).toHaveAttribute('href', '/signup')
    expect(logIn).toHaveAttribute('href', '/login')
    // The CTA links wear the themed content-link style (#452).
    expect(signUp).toHaveClass('content-link')
    expect(logIn).toHaveClass('content-link')
  })

  it('gives anonymous viewers a Back that leads to the login page (#1027)', () => {
    // Previously there was no Back at all for a signed-out viewer, so someone who followed the
    // login page's own "About" link had no way onward except the browser's back button.
    renderNav()
    const back = screen.getByRole('link', { name: '← Back' })
    expect(back).toHaveAttribute('href', '/login')
    expect(back).toHaveClass('content-link')
    // The sign-up funnel is kept alongside it rather than replaced.
    expect(screen.getByRole('link', { name: 'Sign up' })).toBeInTheDocument()
  })

  it('returns a signed-in viewer to the recorded origin, tab and all (#1027)', () => {
    // The dashboard syncs its active tab into ?tab=, so storing the query is what makes Back land on
    // the tab the viewer was on rather than resetting to Profile.
    state.user = { uid: 'u1' }
    rememberOrigin('/dashboard?tab=standings')
    renderNav()
    const back = screen.getByRole('link', { name: '← Back' })
    expect(back).toHaveAttribute('href', '/dashboard?tab=standings')
    expect(back).toHaveClass('content-link')
    expect(screen.queryByText(/Sign up to track/i)).not.toBeInTheDocument()
  })

  it('falls back to the dashboard when a signed-in viewer has no recorded origin (#1027)', () => {
    // A pasted link or a fresh tab: the viewer never passed through an origin, so there is nothing
    // to return to and the dashboard is the honest default.
    state.user = { uid: 'u1' }
    renderNav()
    expect(screen.getByRole('link', { name: '← Back' })).toHaveAttribute('href', '/dashboard')
  })

  it('does not treat a still-restoring session as signed out (#1027)', () => {
    // The regression this guards: Firebase restores asynchronously, so on every full page load
    // `user` is briefly null. Rendering the signed-out branch then flashed the CTA and — worse —
    // a Back clicked in that window sent a signed-in viewer to /login.
    state.user = null
    state.initializing = true
    renderNav()
    expect(screen.queryByText(/Sign up to track/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '← Back' })).not.toBeInTheDocument()
  })

  it('keeps the login link returning here, while Back leaves (#1027)', () => {
    // Both point at /login but mean opposite things: "Log in" carries state.from so the viewer comes
    // back after authenticating; "Back" must not, or it would bounce them straight back here.
    renderNav()
    expect(screen.getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login')
    expect(screen.getByRole('link', { name: '← Back' })).toHaveAttribute('href', '/login')
  })
})
