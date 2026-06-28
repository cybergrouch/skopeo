import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Capability } from '@/auth/capabilities'
import { ProfileTab } from './ProfileTab'

const {
  useGetApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdRatingHistory,
  useGetApiV1PlayersCodeMatchHistory,
  useAuthMock,
} = vi.hoisted(() => ({
  useGetApiV1UsersUserIdRatings: vi.fn(),
  useGetApiV1UsersUserIdRatingHistory: vi.fn(),
  useGetApiV1PlayersCodeMatchHistory: vi.fn(),
  useAuthMock: vi.fn(),
}))

vi.mock('@/api/generated/ratings/ratings', () => ({
  useGetApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdRatingHistory,
}))
vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1PlayersCodeMatchHistory,
}))
// RatingHistoryCard pulls in the matches API (axios → firebase); mock it so the real Firebase
// client never initializes in tests.
vi.mock('@/api/generated/matches/matches', () => ({
  useGetApiV1MatchesIdCalculation: vi.fn(() => ({ data: undefined, isLoading: false })),
}))
vi.mock('@/auth/useAuth', () => ({ useAuth: useAuthMock }))
// The band meter animates via requestAnimationFrame/matchMedia; stub it so these tests stay focused
// on the Rating card wiring (the meter itself is covered in RatingBandMeter.test.tsx).
vi.mock('@/components/RatingBandMeter', () => ({
  RatingBandMeter: () => <div>band meter</div>,
}))

function renderProfile(
  capabilities: Capability[] = [Capability.PLAYER],
  publicCode?: string,
  details: { dateOfBirth?: string | null; sex?: string | null } = {},
) {
  return render(
    <ProfileTab
      userId="u1"
      capabilities={capabilities}
      publicCode={publicCode}
      dateOfBirth={details.dateOfBirth}
      sex={details.sex}
    />,
  )
}

describe('ProfileTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthMock.mockReturnValue({
      user: { displayName: 'Roger F.', email: 'roger@example.com' },
    })
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: undefined,
      isLoading: false,
    })
    useGetApiV1UsersUserIdRatingHistory.mockReturnValue({
      data: undefined,
      isLoading: false,
    })
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: [],
      isLoading: false,
    })
  })

  it('shows identity and capability badges', () => {
    renderProfile([Capability.PLAYER, Capability.HOST])
    expect(screen.getByText('Roger F.')).toBeInTheDocument()
    expect(screen.getByText('roger@example.com')).toBeInTheDocument()
    expect(screen.getByText('PLAYER')).toBeInTheDocument()
    expect(screen.getByText('HOST')).toBeInTheDocument()
  })

  it('falls back to the email as the title when there is no display name', () => {
    useAuthMock.mockReturnValue({
      user: { displayName: null, email: 'roger@example.com' },
    })
    renderProfile()
    // Email appears as both the title and the description.
    expect(screen.getAllByText('roger@example.com').length).toBeGreaterThan(0)
  })

  it("falls back to 'Player' when there is no user", () => {
    useAuthMock.mockReturnValue({ user: null })
    renderProfile()
    expect(screen.getByText('Player')).toBeInTheDocument()
  })

  it('shows the shareable player code when provided', () => {
    renderProfile([Capability.PLAYER], 'K7Q2MX')
    expect(screen.getByText('K7Q2MX')).toBeInTheDocument()
  })

  it('shows a QR code and a copy-link button when a public code is present', () => {
    const { container } = renderProfile([Capability.PLAYER], 'K7Q2MX')
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('copies the share link to the clipboard and shows feedback', () => {
    const writeText = vi.fn()
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
    renderProfile([Capability.PLAYER], 'K7Q2MX')

    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }))

    expect(writeText).toHaveBeenCalledWith(
      `${window.location.origin}/players/K7Q2MX`,
    )
    expect(
      screen.getByRole('button', { name: 'Copied!' }),
    ).toBeInTheDocument()
  })

  it('shows the provider avatar when a photo URL is present', () => {
    useAuthMock.mockReturnValue({
      user: {
        displayName: 'Roger F.',
        email: 'roger@example.com',
        photoURL: 'https://example.com/avatar.jpg',
      },
    })
    const { container } = renderProfile()
    expect(container.querySelector('img')).toHaveAttribute(
      'src',
      'https://example.com/avatar.jpg',
    )
  })

  it('shows date of birth and sex read-only, formatting the date for the viewer', () => {
    renderProfile([Capability.PLAYER], undefined, { dateOfBirth: '2000-01-15', sex: 'Male' })
    const expectedDate = new Date('2000-01-15T00:00:00').toLocaleDateString()
    expect(screen.getByText('Date of birth')).toBeInTheDocument()
    expect(screen.getByText(expectedDate)).toBeInTheDocument()
    expect(screen.getByText('Sex')).toBeInTheDocument()
    expect(screen.getByText('Male')).toBeInTheDocument()
  })

  it('falls back to a dash when date of birth or sex is missing', () => {
    renderProfile()
    // Both profile-detail rows render an em dash.
    expect(screen.getAllByText('—')).toHaveLength(2)
  })

  it('shows the raw value when the date of birth cannot be parsed', () => {
    renderProfile([Capability.PLAYER], undefined, { dateOfBirth: 'not-a-date', sex: 'Female' })
    expect(screen.getByText('not-a-date')).toBeInTheDocument()
  })

  it('shows the pending notice when there is no rating', () => {
    renderProfile()
    expect(screen.getByText('Pending assessment')).toBeInTheDocument()
  })

  it('lists ratings with and without a level', () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [
        { system: 'NTRP', value: '4.000000', level: '4.0' },
        { system: 'UTR', value: '8.500000', level: null },
      ],
      isLoading: false,
    })
    renderProfile()
    // Band only — never the 6-decimal value when a level is present.
    expect(screen.getByText('4.0')).toBeInTheDocument()
    expect(screen.queryByText('4.000000 · 4.0')).not.toBeInTheDocument()
    // Falls back to the value when there's no published level.
    expect(screen.getByText('8.500000')).toBeInTheDocument()
    expect(screen.queryByText('Pending assessment')).not.toBeInTheDocument()
  })

  it('renders the band meter when a rating exposes a band position', () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ system: 'NTRP', value: null, level: '4.0', bandPosition: 0.7 }],
      isLoading: false,
    })
    renderProfile()
    expect(screen.getByText('4.0')).toBeInTheDocument()
    expect(screen.getByText('band meter')).toBeInTheDocument()
  })

  it('omits the band meter when there is no band position', () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ system: 'NTRP', value: null, level: '4.0' }],
      isLoading: false,
    })
    renderProfile()
    expect(screen.getByText('4.0')).toBeInTheDocument()
    expect(screen.queryByText('band meter')).not.toBeInTheDocument()
  })

  it('renders history entries, with and without a level change', () => {
    useGetApiV1UsersUserIdRatingHistory.mockReturnValue({
      data: [
        {
          id: 'h1',
          system: 'NTRP',
          previousRating: '4.000000',
          newRating: '4.100000',
          newLevel: '4.5',
          levelChanged: true,
          calculatedAt: '2026-06-01T12:00:00',
        },
        {
          id: 'h2',
          system: 'NTRP',
          previousRating: '4.100000',
          newRating: '4.050000',
          newLevel: '4.5',
          levelChanged: false,
          calculatedAt: '2026-06-02T12:00:00',
        },
      ],
      isLoading: false,
    })
    renderProfile()
    // Full value lines (band transitions are rendered separately and covered in RatingHistoryCard).
    expect(screen.getByText('4.000000 → 4.100000')).toBeInTheDocument()
    expect(screen.getByText('4.100000 → 4.050000')).toBeInTheDocument()
  })

  it('shows an empty state when there is no history', () => {
    renderProfile()
    expect(screen.getByText('No rating changes yet.')).toBeInTheDocument()
  })

  it('shows loading states while ratings and history resolve', () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: undefined,
      isLoading: true,
    })
    useGetApiV1UsersUserIdRatingHistory.mockReturnValue({
      data: undefined,
      isLoading: true,
    })
    renderProfile()
    expect(screen.getAllByText('Loading…').length).toBe(2)
  })
})
