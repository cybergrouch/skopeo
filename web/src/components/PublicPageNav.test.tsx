import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { PublicPageNav } from './PublicPageNav'

const { state } = vi.hoisted(() => ({ state: { user: null as { uid: string } | null } }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: state.user }) }))

function renderNav() {
  return render(
    <MemoryRouter>
      <PublicPageNav />
    </MemoryRouter>,
  )
}

/** Surfaces the current pathname so a test can assert where "← Back" lands. */
function LocationProbe() {
  const location = useLocation()
  return <div data-testid="path">{location.pathname}</div>
}

describe('PublicPageNav', () => {
  beforeEach(() => {
    state.user = null
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
    expect(screen.queryByText('← Back to dashboard')).not.toBeInTheDocument()
  })

  it('falls back to a dashboard link when opened cold with no in-app history (#323)', () => {
    // A single-entry history (pasted link / new tab) → the first entry's key is 'default'.
    state.user = { uid: 'u1' }
    renderNav()
    const backToDashboard = screen.getByRole('link', { name: '← Back to dashboard' })
    expect(backToDashboard).toHaveAttribute('href', '/dashboard')
    // The Back-to-dashboard link wears the themed content-link style (#452).
    expect(backToDashboard).toHaveClass('content-link')
    expect(screen.queryByText(/Sign up to track/i)).not.toBeInTheDocument()
  })

  it('goes back to the origin when there is in-app history (#323)', async () => {
    state.user = { uid: 'u1' }
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/dashboard/standings', '/players/AAA111']} initialIndex={1}>
        <PublicPageNav />
        <LocationProbe />
      </MemoryRouter>,
    )

    // Arrived from within the app → a generic Back button, not the dashboard link.
    expect(screen.queryByRole('link', { name: '← Back to dashboard' })).not.toBeInTheDocument()
    expect(screen.getByTestId('path')).toHaveTextContent('/players/AAA111')

    // The Back button wears the themed content-link style (#452).
    expect(screen.getByRole('button', { name: '← Back' })).toHaveClass('content-link')
    await user.click(screen.getByRole('button', { name: '← Back' }))
    expect(screen.getByTestId('path')).toHaveTextContent('/dashboard/standings')
  })
})
