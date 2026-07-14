import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
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
// Stub the section bodies so this test focuses on the shell (nav gating + sign-out).
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
vi.mock('./dashboard/ReportTab', () => ({
  ReportTab: () => <div>report content</div>,
}))

/** Surfaces the current query string so a test can assert the tab is synced into the URL (#323). */
function SearchProbe() {
  const location = useLocation()
  return <div data-testid="search">{location.search}</div>
}

function renderDashboard(initialEntries: string[] = ['/']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <DashboardPage />
      <SearchProbe />
    </MemoryRouter>,
  )
}

/** Open the hamburger menu so its section items (role=button) are queryable. */
async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Open navigation menu' }))
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

  it('shows Profile and Research (but not Matches/Ratings/Admin) for a default player', async () => {
    // Every sign-up is PLAYER + RESEARCHER (#107), so Research is visible by default.
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RESEARCHER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    await openMenu(user)
    expect(screen.getByRole('button', { name: 'Profile' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Research' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Event Organizer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Seeding' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ratings' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Invites' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Activity Log' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reports' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Admin' })).not.toBeInTheDocument()
  })

  it('hides the Research item from a player without RESEARCHER (#107)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    await openMenu(user)
    expect(screen.getByRole('button', { name: 'Profile' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Research' })).not.toBeInTheDocument()
  })

  it('shows the Ratings item for a rater (no Matches/Admin) (#106)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RATER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    await openMenu(user)
    expect(screen.getByRole('button', { name: 'Ratings' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Event Organizer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Invites' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Activity Log' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Admin' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Ratings' }))
    expect(screen.getByText('ratings content')).toBeInTheDocument()
  })

  it('shows the Matches items for a host (plus Profile/Research, no Admin)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RESEARCHER', 'HOST'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    await openMenu(user)
    expect(screen.getByRole('button', { name: 'Event Organizer' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Seeding' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Research' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Admin' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Seeding' }))
    expect(screen.getByText('seeding content')).toBeInTheDocument()
  })

  it('shows the Matches items for a club owner (same as a host, no Admin)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'RESEARCHER', 'CLUB_OWNER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    await openMenu(user)
    expect(screen.getByRole('button', { name: 'Event Organizer' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Research' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Admin' })).not.toBeInTheDocument()
  })

  it('shows every section for an administrator and switches between them', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER', 'ADMINISTRATOR'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    await openMenu(user)
    expect(screen.getByRole('button', { name: 'Event Organizer' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ratings' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Invites' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Activity Log' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reports' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Admin' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Event Organizer' }))
    expect(screen.getByText('event organizer content')).toBeInTheDocument()

    // The menu closes on select, so re-open it to navigate again.
    await openMenu(user)
    await user.click(screen.getByRole('button', { name: 'Admin' }))
    expect(screen.getByText('admin content')).toBeInTheDocument()
  })

  it('reflects the selected section as the page header, closing the menu on select (#187)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    // The header doubles as the page title in place of a tab strip.
    expect(screen.getByRole('heading', { name: 'Profile' })).toBeInTheDocument()

    await openMenu(user)
    await user.click(screen.getByRole('button', { name: 'Standings' }))
    expect(screen.getByRole('heading', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.getByText('standings content')).toBeInTheDocument()
    // Selecting closed the drawer — its items are no longer rendered.
    expect(screen.queryByRole('button', { name: 'Profile' })).not.toBeInTheDocument()
  })

  it('restores the active tab from the URL so returning to the dashboard keeps it (#323)', () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    renderDashboard(['/?tab=standings'])
    // No menu interaction: the tab is read straight from the URL on mount.
    expect(screen.getByRole('heading', { name: 'Standings' })).toBeInTheDocument()
    expect(screen.getByText('standings content')).toBeInTheDocument()
  })

  it('syncs the selected tab into the URL (#323)', async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDashboard()
    expect(screen.getByTestId('search')).toHaveTextContent('')

    await openMenu(user)
    await user.click(screen.getByRole('button', { name: 'Standings' }))
    expect(screen.getByTestId('search')).toHaveTextContent('tab=standings')

    // Returning to Profile (the default) drops the param again for a clean URL.
    await openMenu(user)
    await user.click(screen.getByRole('button', { name: 'Profile' }))
    expect(screen.getByTestId('search')).toHaveTextContent('')
  })

  it('falls back to Profile when the URL names a tab the viewer cannot access (#323)', () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'u1', capabilities: ['PLAYER'] },
      isLoading: false,
    })
    renderDashboard(['/?tab=admin'])
    expect(screen.getByRole('heading', { name: 'Profile' })).toBeInTheDocument()
    expect(screen.getByText('profile content')).toBeInTheDocument()
  })

  it('still renders the profile section when the id is missing', () => {
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
