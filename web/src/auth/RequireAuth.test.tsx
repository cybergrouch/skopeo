import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { RequireAuth } from './RequireAuth'

const { useAuth } = vi.hoisted(() => ({ useAuth: vi.fn() }))
vi.mock('./useAuth', () => ({ useAuth }))

function renderGuarded() {
  return render(
    <MemoryRouter initialEntries={['/pending']}>
      <Routes>
        <Route
          path="/pending"
          element={
            <RequireAuth>
              <div>secret content</div>
            </RequireAuth>
          }
        />
        <Route path="/login" element={<div>login page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireAuth', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows a loading state while auth initializes', () => {
    useAuth.mockReturnValue({ user: null, initializing: true })
    renderGuarded()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(screen.queryByText('secret content')).not.toBeInTheDocument()
  })

  it('redirects to /login when there is no user', () => {
    useAuth.mockReturnValue({ user: null, initializing: false })
    renderGuarded()
    expect(screen.getByText('login page')).toBeInTheDocument()
    expect(screen.queryByText('secret content')).not.toBeInTheDocument()
  })

  it('renders children when a user is present', () => {
    useAuth.mockReturnValue({ user: { uid: 'u1' }, initializing: false })
    renderGuarded()
    expect(screen.getByText('secret content')).toBeInTheDocument()
  })
})
