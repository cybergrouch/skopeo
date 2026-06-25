import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { RequireProfile } from './RequireProfile'

const { useGetApiV1UsersMe } = vi.hoisted(() => ({
  useGetApiV1UsersMe: vi.fn(),
}))

vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersMe }))

function renderGuard() {
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Routes>
        <Route
          path="/dashboard"
          element={
            <RequireProfile>
              <div>DASHBOARD</div>
            </RequireProfile>
          }
        />
        <Route path="/complete-profile" element={<div>COMPLETE PROFILE</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireProfile', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a loading state while the profile is being fetched', () => {
    useGetApiV1UsersMe.mockReturnValue({ isLoading: true, isError: false })
    renderGuard()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('redirects to /complete-profile when the profile is missing (404)', () => {
    useGetApiV1UsersMe.mockReturnValue({
      isLoading: false,
      isError: true,
      error: { isAxiosError: true, response: { status: 404 } },
    })
    renderGuard()
    expect(screen.getByText('COMPLETE PROFILE')).toBeInTheDocument()
    expect(screen.queryByText('DASHBOARD')).not.toBeInTheDocument()
  })

  it('shows an error message on a non-404 Axios failure', () => {
    useGetApiV1UsersMe.mockReturnValue({
      isLoading: false,
      isError: true,
      error: { isAxiosError: true, response: { status: 500 } },
    })
    renderGuard()
    expect(screen.getByText(/Couldn’t load your profile/)).toBeInTheDocument()
  })

  it('shows an error message when the failure is not an Axios error', () => {
    useGetApiV1UsersMe.mockReturnValue({
      isLoading: false,
      isError: true,
      error: new Error('boom'),
    })
    renderGuard()
    expect(screen.getByText(/Couldn’t load your profile/)).toBeInTheDocument()
  })

  it('renders children once the profile exists', () => {
    useGetApiV1UsersMe.mockReturnValue({ isLoading: false, isError: false })
    renderGuard()
    expect(screen.getByText('DASHBOARD')).toBeInTheDocument()
  })
})
