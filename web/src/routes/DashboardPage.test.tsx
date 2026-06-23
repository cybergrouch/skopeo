import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { DashboardPage } from './DashboardPage'

const { useGetApiV1UsersMe, signOut, navigateMock } = vi.hoisted(() => ({
  useGetApiV1UsersMe: vi.fn(),
  signOut: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersMe }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ signOut }) }))
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})
// Stub the tab bodies so this test focuses on the shell (tab gating + sign-out).
vi.mock('./dashboard/ProfileTab', () => ({
  ProfileTab: () => <div>profile content</div>,
}))
vi.mock('./dashboard/AdminTab', () => ({
  AdminTab: () => <div>admin content</div>,
}))

function renderDashboard() {
  return render(
    <MemoryRouter>
      <DashboardPage />
    </MemoryRouter>,
  )
}

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows a loading state while the profile resolves', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: undefined, isLoading: true })
    renderDashboard()
    expect(screen.getByText('Loading your dashboard…')).toBeInTheDocument()
  })

  it('shows only the Profile tab for a plain player', () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Profile' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Matches' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Admin' })).not.toBeInTheDocument()
  })

  it('shows the Matches tab for a host', () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'HOST'] },
      isLoading: false,
    })
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Matches' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Admin' })).not.toBeInTheDocument()
  })

  it('shows Matches and Admin tabs for an administrator, with placeholders', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'ADMINISTRATOR'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Matches' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Admin' })).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Matches' }))
    expect(screen.getByText('Coming soon.')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Admin' }))
    expect(screen.getByText('admin content')).toBeInTheDocument()
  })

  it('still renders the profile tab when the id is missing', () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { capabilities: ['PLAYER'] },
      isLoading: false,
    })
    renderDashboard()
    expect(screen.getByText('profile content')).toBeInTheDocument()
  })

  it('signs out and returns to /login', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    signOut.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDashboard()

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() => expect(signOut).toHaveBeenCalled())
    expect(navigateMock).toHaveBeenCalledWith('/login', { replace: true })
  })
})
