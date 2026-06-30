import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
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

describe('PublicPageNav', () => {
  beforeEach(() => {
    state.user = null
  })

  it('shows a sign-up / log-in CTA for anonymous viewers (#193)', () => {
    renderNav()
    expect(screen.getByText(/Sign up to track your own ratings/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign up' })).toHaveAttribute('href', '/signup')
    expect(screen.getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login')
    expect(screen.queryByText('← Back to dashboard')).not.toBeInTheDocument()
  })

  it('shows the back-to-dashboard link for a logged-in viewer', () => {
    state.user = { uid: 'u1' }
    renderNav()
    expect(screen.getByRole('link', { name: '← Back to dashboard' })).toHaveAttribute('href', '/dashboard')
    expect(screen.queryByText(/Sign up to track/i)).not.toBeInTheDocument()
  })
})
