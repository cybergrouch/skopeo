import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Capability } from '@/auth/capabilities'
import { ProfileTab } from './ProfileTab'

const {
  useGetApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdRatingHistory,
  useAuthMock,
} = vi.hoisted(() => ({
  useGetApiV1UsersUserIdRatings: vi.fn(),
  useGetApiV1UsersUserIdRatingHistory: vi.fn(),
  useAuthMock: vi.fn(),
}))

vi.mock('@/api/generated/ratings/ratings', () => ({
  useGetApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdRatingHistory,
}))
vi.mock('@/auth/useAuth', () => ({ useAuth: useAuthMock }))

function renderProfile(capabilities: Capability[] = [Capability.PLAYER]) {
  return render(<ProfileTab userId="u1" capabilities={capabilities} />)
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
    expect(screen.getByText('4.000000 · 4.0')).toBeInTheDocument()
    expect(screen.getByText('8.500000')).toBeInTheDocument()
    expect(screen.queryByText('Pending assessment')).not.toBeInTheDocument()
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
    expect(screen.getByText('4.000000 → 4.100000 (4.5)')).toBeInTheDocument()
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
