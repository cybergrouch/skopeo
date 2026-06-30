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
vi.mock('./dashboard/EventOrganizerTab', () => ({
  EventOrganizerTab: () => <div>event organizer content</div>,
}))
vi.mock('./dashboard/SeedingTab', () => ({
  SeedingTab: () => <div>seeding content</div>,
}))
vi.mock('./dashboard/RatingsTab', () => ({
  RatingsTab: () => <div>ratings content</div>,
}))
vi.mock('./dashboard/ResearchTab', () => ({
  ResearchTab: () => <div>research content</div>,
}))
vi.mock('./dashboard/StandingsTab', () => ({
  StandingsTab: () => <div>standings content</div>,
}))
vi.mock('./dashboard/InvitesTab', () => ({
  InvitesTab: () => <div>invites content</div>,
}))
vi.mock('./dashboard/ActivityTab', () => ({
  ActivityTab: () => <div>activity content</div>,
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

  it('shows Profile and Research (but not Matches/Ratings/Admin) for a default player', () => {
    // Every sign-up is PLAYER + RESEARCHER (#107), so Research is visible by default.
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RESEARCHER'] },
      isLoading: false,
    })
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Profile' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Research' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Event Organizer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Seeding' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Ratings' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Invites' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Activity Log' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Admin' })).not.toBeInTheDocument()
  })

  it('hides the Research tab from a player without RESEARCHER (#107)', () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Profile' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Research' })).not.toBeInTheDocument()
  })

  it('shows the Ratings tab for a rater (no Matches/Admin) (#106)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RATER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Ratings' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Event Organizer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Invites' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Activity Log' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Admin' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Ratings' }))
    expect(screen.getByText('ratings content')).toBeInTheDocument()
  })

  it('shows the Matches tab for a host (plus Profile/Research, no Admin)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RESEARCHER', 'HOST'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Event Organizer' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Seeding' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Research' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Admin' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Seeding' }))
    expect(screen.getByText('seeding content')).toBeInTheDocument()
  })

  it('shows the Matches tab for a club owner (same as a host, no Admin)', () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RESEARCHER', 'CLUB_OWNER'] },
      isLoading: false,
    })
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Event Organizer' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Research' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Admin' })).not.toBeInTheDocument()
  })

  it('shows Matches and Admin tabs for an administrator, with placeholders', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'ADMINISTRATOR'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    expect(screen.getByRole('tab', { name: 'Event Organizer' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Ratings' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Invites' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Activity Log' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Admin' })).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Event Organizer' }))
    expect(screen.getByText('event organizer content')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Standings' }))
    expect(screen.getByText('standings content')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Ratings' }))
    expect(screen.getByText('ratings content')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Research' }))
    expect(screen.getByText('research content')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Invites' }))
    expect(screen.getByText('invites content')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Activity Log' }))
    expect(screen.getByText('activity content')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Admin' }))
    expect(screen.getByText('admin content')).toBeInTheDocument()
  })

  it('opens the mobile drawer and switches sections from it (#187)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RATER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()

    // Drawer is closed initially — its items (role=button) aren't rendered yet.
    expect(screen.queryByRole('button', { name: 'Ratings' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Open navigation menu' }))
    // Gated items appear in the drawer: Ratings for a rater, but not Admin.
    expect(screen.getByRole('button', { name: 'Ratings' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Admin' })).not.toBeInTheDocument()

    // Selecting a section from the drawer switches the content.
    await user.click(screen.getByRole('button', { name: 'Ratings' }))
    expect(screen.getByText('ratings content')).toBeInTheDocument()
  })

  it('the mobile drawer respects capability gating (#187)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] }, // plain player: no RESEARCHER/ADMIN
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()

    await user.click(screen.getByRole('button', { name: 'Open navigation menu' }))
    expect(screen.getByRole('button', { name: 'Profile' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Research' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Admin' })).not.toBeInTheDocument()
  })

  it('shows the current section as a mobile page header (#187)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()

    // The header doubles as the page title in place of the tab strip on mobile.
    expect(screen.getByRole('heading', { name: 'Profile' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Open navigation menu' }))
    await user.click(screen.getByRole('button', { name: 'Standings' }))
    expect(screen.getByRole('heading', { name: 'Standings' })).toBeInTheDocument()
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
