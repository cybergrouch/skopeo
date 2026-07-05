import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { UpcomingMatchesCard } from './UpcomingMatchesCard'

const { useGetApiV1MatchesUpcoming } = vi.hoisted(() => ({
  useGetApiV1MatchesUpcoming: vi.fn(),
}))

vi.mock('@/api/generated/matches/matches', () => ({ useGetApiV1MatchesUpcoming }))

const matches = [
  {
    publicCode: 'M1',
    matchDate: '2026-07-20',
    matchType: 'OPEN_PLAY',
    venue: 'Center Court',
    opponents: [{ displayName: 'Ana', publicCode: 'ANA111' }],
  },
  {
    publicCode: 'M2',
    matchDate: '2026-07-25',
    matchType: 'LEAGUE_MATCH',
    opponents: [{ displayName: null, publicCode: 'BOB222' }],
  },
  {
    publicCode: 'M3',
    matchDate: '2026-07-30',
    matchType: 'OPEN_PLAY',
    opponents: [{ displayName: null, publicCode: null }],
  },
]

function renderCard() {
  return render(
    <MemoryRouter>
      <UpcomingMatchesCard />
    </MemoryRouter>,
  )
}

describe('UpcomingMatchesCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1MatchesUpcoming.mockReturnValue({ data: matches, isLoading: false, isError: false })
  })

  it('lists upcoming matches, each linking to the public match page', () => {
    renderCard()
    const first = screen.getByRole('link', { name: /vs Ana/ })
    expect(first).toHaveAttribute('href', '/matches/M1')
    expect(screen.getByText(/2026-07-20 · open play · Center Court/)).toBeInTheDocument()
  })

  it('falls back to the opponent code, then TBD, when a display name is missing', () => {
    renderCard()
    expect(screen.getByRole('link', { name: /vs BOB222/ })).toHaveAttribute('href', '/matches/M2')
    expect(screen.getByRole('link', { name: /vs TBD/ })).toHaveAttribute('href', '/matches/M3')
  })

  it('shows an empty state when there are no upcoming matches', () => {
    useGetApiV1MatchesUpcoming.mockReturnValue({ data: [], isLoading: false, isError: false })
    renderCard()
    expect(screen.getByText('No upcoming matches.')).toBeInTheDocument()
  })

  it('shows a loading state', () => {
    useGetApiV1MatchesUpcoming.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    renderCard()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an error state', () => {
    useGetApiV1MatchesUpcoming.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    renderCard()
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load your upcoming matches')
  })
})
