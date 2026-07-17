import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { PlayerProfilePage } from './PlayerProfilePage'

const {
  useGetApiV1PlayersCode,
  useGetApiV1PlayersCodeMatchHistory,
  useGetApiV1PlayersCodeRatingHistory,
  useGetApiV1UsersMe,
} = vi.hoisted(() => ({
  useGetApiV1PlayersCode: vi.fn(),
  useGetApiV1PlayersCodeMatchHistory: vi.fn(),
  useGetApiV1PlayersCodeRatingHistory: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1PlayersCode,
  useGetApiV1PlayersCodeMatchHistory,
  useGetApiV1PlayersCodeRatingHistory,
  useGetApiV1UsersMe,
}))
// The win–loss card has its own API hook + tests (#276); stub it here.
vi.mock('@/components/WinLossCard', () => ({
  WinLossCard: ({ code }: { code: string }) => <div>win-loss:{code}</div>,
}))
// The standing headline has its own API hook + tests (#448); stub it here.
vi.mock('@/components/PlayerStandingCard', () => ({
  PlayerStandingCard: ({ code }: { code: string }) => <div>standing:{code}</div>,
}))
// The points audit has its own API hook + tests (#448); stub it, echoing the enabled gate so the page
// test can assert who sees it (owner / admin) vs who doesn't (other / anonymous).
vi.mock('@/components/PointsAuditCard', () => ({
  PointsAuditCard: ({ code, enabled }: { code: string; enabled: boolean }) =>
    enabled ? <div>points-audit:{code}</div> : null,
}))
// RatingHistoryCard pulls in the matches API (axios → firebase); mock it so the real Firebase
// client never initializes in tests.
vi.mock('@/api/generated/matches/matches', () => ({
  useGetApiV1MatchesIdCalculation: vi.fn(() => ({ data: undefined, isLoading: false })),
}))
// The page renders PublicPageNav (#193), which reads auth; default to anonymous here.
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: null }) }))

function renderAt(code = 'ABC234') {
  return render(
    <MemoryRouter initialEntries={[`/players/${code}`]}>
      <Routes>
        <Route path="/players/:code" element={<PlayerProfilePage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('PlayerProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [], total: 0 },
      isLoading: false,
    })
    // Default viewer: a plain player (no admin capability).
    useGetApiV1UsersMe.mockReturnValue({ data: { capabilities: ['PLAYER'] } })
    useGetApiV1PlayersCodeRatingHistory.mockReturnValue({
      data: [],
      isLoading: false,
    })
  })

  const loadedPlayer = {
    isLoading: false,
    isError: false,
    data: { publicCode: 'ABC234', displayName: 'Ana', photoUrl: null, rating: undefined },
  }

  it('shows a loading state', () => {
    useGetApiV1PlayersCode.mockReturnValue({ isLoading: true, isError: false })
    renderAt()
    expect(screen.getByText('Loading player…')).toBeInTheDocument()
  })

  it('shows an error message when the player cannot be loaded', () => {
    useGetApiV1PlayersCode.mockReturnValue({ isLoading: false, isError: true })
    renderAt()
    expect(screen.getByText(/couldn’t find or load this player/i)).toBeInTheDocument()
  })

  it('renders the player with avatar and rating', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        publicCode: 'ABC234',
        displayName: 'Ana',
        photoUrl: 'https://example.com/a.jpg',
        rating: { value: '4.000000', level: '4.0' },
      },
    })
    const { container } = renderAt()
    expect(screen.getByText('Ana')).toBeInTheDocument()
    expect(screen.getByText('ABC234')).toBeInTheDocument()
    // Band only — never the 6-decimal value.
    expect(screen.getByText('4.0')).toBeInTheDocument()
    expect(screen.queryByText('4.000000 · 4.0')).not.toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute(
      'src',
      'https://example.com/a.jpg',
    )
    // The shareable QR card (#137) shows for an active profile.
    expect(screen.getByText('Share this profile')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
  })

  it('appends the computed rating confidence as a percentage (#343)', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        publicCode: 'ABC234',
        displayName: 'Ana',
        photoUrl: null,
        rating: { value: '4.000000', level: '4.0', confidence: '0.87' },
      },
    })
    renderAt()
    expect(screen.getByText(/· 87%/)).toBeInTheDocument()
  })

  it('shows a rating without a level', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: { publicCode: 'ABC234', displayName: 'Ana', photoUrl: null, rating: { value: '4.000000', level: null } },
    })
    renderAt()
    expect(screen.getByText('4.000000')).toBeInTheDocument()
  })

  it('falls back to a placeholder name/avatar and a no-rating note', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: { publicCode: 'XYZ789', displayName: null, photoUrl: null, rating: undefined },
    })
    const { container } = renderAt()
    expect(screen.getByText('Player')).toBeInTheDocument()
    expect(screen.getByText('No rating yet.')).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  it('shows rating history to an ADMINISTRATOR viewing the profile', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    useGetApiV1UsersMe.mockReturnValue({
      data: { capabilities: ['PLAYER', 'ADMINISTRATOR'] },
    })
    useGetApiV1PlayersCodeRatingHistory.mockReturnValue({
      isLoading: false,
      data: [
        {
          id: 'h1',
          previousRating: '4.000000',
          newRating: '4.300000',
          ratingChange: '0.300000',
          previousLevel: '4.0',
          newLevel: '4.5',
          levelChanged: true,
          calculatedAt: '2026-06-01T12:00:00',
        },
      ],
    })
    renderAt()
    expect(screen.getByText('Full rating history (admin view).')).toBeInTheDocument()
    expect(screen.getByText('4.000000 → 4.300000')).toBeInTheDocument()
  })

  it('hides rating history from a non-admin viewer', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    // viewer defaults to PLAYER only (set in beforeEach)
    renderAt()
    expect(
      screen.queryByText('Full rating history (admin view).'),
    ).not.toBeInTheDocument()
  })

  it('treats a viewer whose own profile is still loading as non-admin', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    useGetApiV1UsersMe.mockReturnValue({ data: undefined })
    renderAt()
    expect(
      screen.queryByText('Full rating history (admin view).'),
    ).not.toBeInTheDocument()
  })

  it('shows a loading state for an admin while rating history resolves', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    useGetApiV1UsersMe.mockReturnValue({
      data: { capabilities: ['PLAYER', 'ADMINISTRATOR'] },
    })
    useGetApiV1PlayersCodeRatingHistory.mockReturnValue({
      data: undefined,
      isLoading: true,
    })
    renderAt()
    expect(screen.getByText('Full rating history (admin view).')).toBeInTheDocument()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows the public rank + points headline to any viewer (#448)', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    renderAt()
    expect(screen.getByText('standing:ABC234')).toBeInTheDocument()
  })

  it('shows the active-points audit to the profile owner (#448)', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    // The viewer's own public code matches the profile → owner.
    useGetApiV1UsersMe.mockReturnValue({ data: { publicCode: 'ABC234', capabilities: ['PLAYER'] } })
    renderAt()
    expect(screen.getByText('points-audit:ABC234')).toBeInTheDocument()
  })

  it('shows the active-points audit to an ADMINISTRATOR viewing another profile (#448)', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    useGetApiV1UsersMe.mockReturnValue({ data: { publicCode: 'ZZZ999', capabilities: ['PLAYER', 'ADMINISTRATOR'] } })
    renderAt()
    expect(screen.getByText('points-audit:ABC234')).toBeInTheDocument()
  })

  it('hides the active-points audit from a non-owner non-admin viewer (#448)', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    useGetApiV1UsersMe.mockReturnValue({ data: { publicCode: 'ZZZ999', capabilities: ['PLAYER'] } })
    renderAt()
    expect(screen.queryByText('points-audit:ABC234')).not.toBeInTheDocument()
  })

  it('hides the active-points audit from an anonymous viewer (#448)', () => {
    useGetApiV1PlayersCode.mockReturnValue(loadedPlayer)
    useGetApiV1UsersMe.mockReturnValue({ data: undefined })
    renderAt()
    expect(screen.queryByText('points-audit:ABC234')).not.toBeInTheDocument()
  })

  it('renders a merged notice linking to the canonical for a disabled duplicate (#124)', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        publicCode: 'DUP123',
        displayName: 'Dupe',
        photoUrl: null,
        rating: undefined,
        isDisabled: true,
        canonical: { publicCode: 'REAL99', displayName: 'Real', photoUrl: null },
      },
    })
    renderAt('DUP123')
    expect(screen.getByText('This profile has been merged')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /view the active profile \(real\)/i })
    expect(link).toHaveAttribute('href', '/players/REAL99')
    // The normal cards/history (and the share card) are suppressed for a disabled duplicate.
    expect(screen.queryByText('No rating yet.')).not.toBeInTheDocument()
    expect(screen.queryByText('Share this profile')).not.toBeInTheDocument()
  })

  it('links to a canonical without a display name (#124)', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        publicCode: 'DUP123',
        displayName: 'Dupe',
        photoUrl: null,
        rating: undefined,
        isDisabled: true,
        canonical: { publicCode: 'REAL99', displayName: null, photoUrl: null },
      },
    })
    renderAt('DUP123')
    expect(screen.getByRole('link', { name: /view the active profile/i })).toHaveAttribute(
      'href',
      '/players/REAL99',
    )
  })

  it('shows a merged notice without a link when the canonical is unavailable (#124)', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: { publicCode: 'DUP123', displayName: 'Dupe', photoUrl: null, rating: undefined, isDisabled: true, canonical: null },
    })
    renderAt('DUP123')
    expect(screen.getByText('The active profile is unavailable.')).toBeInTheDocument()
  })

  it('handles a missing code param without crashing', () => {
    useGetApiV1PlayersCode.mockReturnValue({ isLoading: true, isError: false })
    render(
      <MemoryRouter initialEntries={['/players']}>
        <Routes>
          <Route path="/players" element={<PlayerProfilePage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByText('Loading player…')).toBeInTheDocument()
  })
})
