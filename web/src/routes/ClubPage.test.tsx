import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ClubPage } from './ClubPage'

const {
  useGetApiV1ClubsCodeCode,
  useGetApiV1Clubs,
  useGetApiV1ClubsClubIdPointsSummary,
  useGetApiV1UsersMe,
  state,
} = vi.hoisted(() => ({
  useGetApiV1ClubsCodeCode: vi.fn(),
  useGetApiV1Clubs: vi.fn(),
  useGetApiV1ClubsClubIdPointsSummary: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
  state: { user: { uid: 'u1' } as { uid: string } | null },
}))
// PublicPageNav reads auth (#193); default to a logged-in user, overridden per test.
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: state.user }) }))
vi.mock('@/api/generated/clubs/clubs', () => ({
  useGetApiV1ClubsCodeCode,
  useGetApiV1Clubs,
}))
vi.mock('@/api/generated/points-budget/points-budget', () => ({
  useGetApiV1ClubsClubIdPointsSummary,
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersMe }))

const club = {
  publicCode: 'CLB001',
  name: 'Downtown TC',
  isActive: true,
  upcoming: [
    {
      publicCode: 'EVT001',
      name: 'Spring Open',
      startDate: '2026-05-01',
      endDate: '2026-05-03',
      eventType: 'LEAGUE',
      designatedPoints: 30,
      awardedPoints: 0,
    },
  ],
  past: [
    {
      publicCode: 'EVT000',
      name: 'Winter Cup',
      startDate: '2026-01-01',
      endDate: '2026-01-03',
      eventType: 'TOURNAMENT',
      designatedPoints: 40,
      awardedPoints: 40,
    },
  ],
}

function renderAt(code = 'CLB001') {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[`/clubs/${code}`]}>
        <Routes>
          <Route path="/clubs/:code" element={<ClubPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ClubPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.user = { uid: 'u1' }
    // Defaults: anonymous-ish viewer with no profile, no staff club list, no summary.
    useGetApiV1UsersMe.mockReturnValue({ data: undefined })
    useGetApiV1Clubs.mockReturnValue({ data: undefined })
    useGetApiV1ClubsClubIdPointsSummary.mockReturnValue({ data: undefined })
  })

  it('shows a loading state', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: undefined, isLoading: true })
    renderAt()
    expect(screen.getByText('Loading club…')).toBeInTheDocument()
  })

  it('shows an error state', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: undefined, isError: true })
    renderAt()
    expect(screen.getByText(/couldn’t find or load this club/i)).toBeInTheDocument()
  })

  it('renders the name, upcoming + past event links, and a share QR', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false })
    renderAt()

    expect(screen.getByText('Downtown TC')).toBeInTheDocument()
    expect(screen.getByText('CLB001')).toBeInTheDocument()

    // Upcoming event under its heading, linking to the public event page.
    const upcoming = screen.getByText('Upcoming events').parentElement as HTMLElement
    expect(within(upcoming).getByRole('link', { name: /Spring Open/ })).toHaveAttribute(
      'href',
      '/events/EVT001',
    )
    // Past event under its heading.
    const past = screen.getByText('Past events').parentElement as HTMLElement
    expect(within(past).getByRole('link', { name: /Winter Cup/ })).toHaveAttribute(
      'href',
      '/events/EVT000',
    )

    // Share card.
    expect(screen.getByText('Share this club')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
  })

  it('shows the event type and per-event points (designated vs awarded) publicly (#403)', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false })
    renderAt()

    // A non-finalized event shows its designated points; the type badge is present.
    const upcoming = screen.getByText('Upcoming events').parentElement as HTMLElement
    expect(within(upcoming).getByText('LEAGUE')).toBeInTheDocument()
    expect(within(upcoming).getByText(/30 pts designated/)).toBeInTheDocument()
    // A finalized event shows its awarded points.
    const past = screen.getByText('Past events').parentElement as HTMLElement
    expect(within(past).getByText('TOURNAMENT')).toBeInTheDocument()
    expect(within(past).getByText(/40 pts awarded/)).toBeInTheDocument()
  })

  it('does not show club utilization to an anonymous / non-owner viewer (#403)', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false })
    renderAt()
    expect(screen.queryByText(/club utilization/i)).not.toBeInTheDocument()
    // The gated fetch is disabled (no staff list, no summary requested).
    expect(useGetApiV1Clubs).toHaveBeenCalledWith({ query: { enabled: false } })
  })

  it('shows club utilization to a CLUB_OWNER of this club (#403)', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false })
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'owner-1', capabilities: ['PLAYER', 'CLUB_OWNER'] },
    })
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: 'club-uuid', publicCode: 'CLB001', owners: [{ userId: 'owner-1' }] }],
    })
    useGetApiV1ClubsClubIdPointsSummary.mockReturnValue({
      data: {
        clubId: 'club-uuid',
        utilization: [{ eventType: 'LEAGUE', budgeted: 100, allocated: 30, free: 70 }],
        events: [],
      },
    })
    renderAt()

    expect(screen.getByText(/club utilization/i)).toBeInTheDocument()
    const util = screen.getByText(/club utilization/i).parentElement as HTMLElement
    expect(within(util).getByText('100')).toBeInTheDocument()
    expect(within(util).getByText('70')).toBeInTheDocument()
    // The summary fetch was enabled for the matching club id.
    expect(useGetApiV1ClubsClubIdPointsSummary).toHaveBeenCalledWith('club-uuid', {
      query: { enabled: true },
    })
  })

  it('does not show utilization to a CLUB_OWNER of a different club (#403)', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false })
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: 'owner-2', capabilities: ['PLAYER', 'CLUB_OWNER'] },
    })
    // This viewer owns some other club, not the one on screen.
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: 'club-uuid', publicCode: 'CLB001', owners: [{ userId: 'owner-1' }] }],
    })
    renderAt()

    expect(screen.queryByText(/club utilization/i)).not.toBeInTheDocument()
    // The matched club exists but the summary fetch stays disabled for the non-owner.
    expect(useGetApiV1ClubsClubIdPointsSummary).toHaveBeenCalledWith('club-uuid', {
      query: { enabled: false },
    })
  })

  it('shows empty states when a club has no events', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: { ...club, upcoming: [], past: [] },
      isLoading: false,
    })
    renderAt()
    expect(screen.getByText('No upcoming events.')).toBeInTheDocument()
    expect(screen.getByText('No past events.')).toBeInTheDocument()
  })

  it('flags a soft-deleted club but still renders it (#325)', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: { ...club, isActive: false }, isLoading: false })
    renderAt()
    expect(screen.getByText('Downtown TC')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/this club has been deleted/i)
  })

  it('shows no deleted flag for an active club (#325)', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: { ...club, isActive: true }, isLoading: false })
    renderAt()
    expect(screen.queryByText(/this club has been deleted/i)).not.toBeInTheDocument()
  })
})
