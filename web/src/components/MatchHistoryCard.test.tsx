import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MatchHistoryCard } from './MatchHistoryCard'

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

describe('MatchHistoryCard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a loading state', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: undefined,
      isLoading: true,
    })
    renderCard()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an empty state when there are no matches', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: [],
      isLoading: false,
    })
    renderCard()
    expect(screen.getByText('No matches yet.')).toBeInTheDocument()
  })

  it('renders a rated match with opponent photo, result, scores and at-the-time bands', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      isLoading: false,
      data: [
        {
          matchId: 'm1',
          publicCode: 'MATCH1',
          matchDate: '2026-01-01',
          status: 'COMPLETED',
          rated: true,
          result: 'WIN',
          setScores: ['6-4', '6-3'],
          partners: [],
          opponents: [
            {
              publicCode: 'BEN123',
              displayName: 'Ben',
              photoUrl: 'https://example.com/ben.jpg',
              levelAtMatch: '3.5',
            },
          ],
          playerLevelAtMatch: '4.0',
        },
      ],
    })
    const { container } = renderCard()
    expect(screen.getByText('vs Ben')).toBeInTheDocument()
    expect(screen.getByText('Rated')).toBeInTheDocument()
    expect(screen.getByText(/2026-01-01 · WIN · 6-4 6-3/)).toBeInTheDocument()
    expect(screen.getByText(/NTRP 4.0 vs 3.5 \(at the time\)/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Public page (QR)' })).toHaveAttribute(
      'href',
      '/matches/MATCH1',
    )
    expect(container.querySelector('img')).toHaveAttribute(
      'src',
      'https://example.com/ben.jpg',
    )
  })

  it('renders a scheduled match with initials, no result and no bands', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      isLoading: false,
      data: [
        {
          matchId: 'm2',
          publicCode: 'MATCH2',
          matchDate: '2026-03-01',
          status: 'SCHEDULED',
          rated: false,
          result: null,
          setScores: [],
          partners: [],
          opponents: [
            { publicCode: 'BEN123', displayName: 'Ben', photoUrl: null, levelAtMatch: null },
          ],
          playerLevelAtMatch: null,
        },
      ],
    })
    const { container } = renderCard()
    expect(screen.getByText('Scheduled')).toBeInTheDocument()
    expect(screen.getByText('2026-03-01')).toBeInTheDocument()
    expect(screen.queryByText(/\(at the time\)/)).not.toBeInTheDocument()
    // Initials placeholder, not an avatar image.
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('B')).toBeInTheDocument()
  })

  it('labels a completed-but-unrated match as awaiting rating, with a placeholder opponent', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      isLoading: false,
      data: [
        {
          matchId: 'm3',
          publicCode: 'MATCH3',
          matchDate: '2026-02-01',
          status: 'COMPLETED',
          rated: false,
          result: 'LOSS',
          setScores: ['4-6'],
          partners: [],
          opponents: [],
          playerLevelAtMatch: null,
        },
      ],
    })
    renderCard()
    expect(screen.getByText('Awaiting rating')).toBeInTheDocument()
    expect(screen.getByText('vs Player')).toBeInTheDocument()
    expect(screen.getByText(/2026-02-01 · LOSS · 4-6/)).toBeInTheDocument()
  })

  it('renders a dash when a rated match is missing a band', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      isLoading: false,
      data: [
        {
          matchId: 'm4',
          publicCode: 'MATCH4',
          matchDate: '2026-01-15',
          status: 'COMPLETED',
          rated: true,
          result: 'WIN',
          setScores: ['6-0', '6-0'],
          partners: [],
          opponents: [
            { publicCode: 'BEN123', displayName: 'Ben', photoUrl: null, levelAtMatch: null },
          ],
          playerLevelAtMatch: null,
        },
      ],
    })
    renderCard()
    expect(screen.getByText(/NTRP — vs — \(at the time\)/)).toBeInTheDocument()
  })

  it('renders a doubles match with the partner and both opponents and their bands', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      isLoading: false,
      data: [
        {
          matchId: 'm5',
          publicCode: 'MATCH5',
          matchDate: '2026-04-01',
          status: 'COMPLETED',
          rated: true,
          result: 'WIN',
          setScores: ['6-3'],
          partners: [
            { publicCode: 'BEA123', displayName: 'Bea', photoUrl: null, levelAtMatch: '3.5' },
          ],
          opponents: [
            { publicCode: 'CYX123', displayName: 'Cy', photoUrl: null, levelAtMatch: '3.0' },
            { publicCode: 'DEB123', displayName: 'Deb', photoUrl: null, levelAtMatch: '3.5' },
          ],
          playerLevelAtMatch: '4.0',
        },
      ],
    })
    renderCard()
    expect(screen.getByText('with Bea · vs Cy, Deb')).toBeInTheDocument()
    expect(screen.getByText(/NTRP 4.0 vs 3.0, 3.5 \(at the time\)/)).toBeInTheDocument()
  })
})
