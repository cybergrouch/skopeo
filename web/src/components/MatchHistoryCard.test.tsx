import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MatchHistoryCard } from './MatchHistoryCard'
import type { PlayerMatchHistoryEntry } from '@/api/generated/model'

const { useGetApiV1PlayersCodeMatchHistory } = vi.hoisted(() => ({
  useGetApiV1PlayersCodeMatchHistory: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1PlayersCodeMatchHistory,
}))

function renderCard() {
  return render(
    <MemoryRouter>
      <MatchHistoryCard code="K7Q2MX" />
    </MemoryRouter>,
  )
}

/** A minimal completed-match row; only the fields the card surfaces need to be realistic. */
function match(id: string, opponent: string): PlayerMatchHistoryEntry {
  return {
    matchId: id,
    publicCode: id.toUpperCase(),
    matchDate: '2026-01-01',
    status: 'COMPLETED',
    rated: false,
    result: 'WIN',
    setScores: ['6-4'],
    partners: [],
    opponents: [{ publicCode: `${opponent}1`, displayName: opponent, photoUrl: null, levelAtMatch: null }],
    playerLevelAtMatch: null,
  }
}

describe('MatchHistoryCard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('requests only a bounded preview', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({ data: undefined, isLoading: true })
    renderCard()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    // The preview asks for a small page, not the whole history.
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenCalledWith('K7Q2MX', { limit: 5 }, { query: { enabled: true } })
  })

  it('shows an empty state when there are no matches', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({ data: { items: [], total: 0 }, isLoading: false })
    renderCard()
    expect(screen.getByText('No matches yet.')).toBeInTheDocument()
    expect(screen.queryByText(/View all/)).not.toBeInTheDocument()
  })

  it('renders the preview rows and links to the full page when there are more (#284)', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [match('a', 'Ben'), match('b', 'Cara')], total: 12 },
      isLoading: false,
    })
    renderCard()
    expect(screen.getByText('Ben')).toBeInTheDocument()
    expect(screen.getByText('Cara')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View all 12 matches' })).toHaveAttribute('href', '/players/K7Q2MX/matches')
  })

  it('omits the "View all" link when the preview already shows everything', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [match('a', 'Ben')], total: 1 },
      isLoading: false,
    })
    renderCard()
    expect(screen.getByText('Ben')).toBeInTheDocument()
    expect(screen.queryByText(/View all/)).not.toBeInTheDocument()
  })
})
