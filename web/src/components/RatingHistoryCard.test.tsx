import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RatingHistoryCard } from './RatingHistoryCard'

const { useGetApiV1MatchesIdCalculation } = vi.hoisted(() => ({
  useGetApiV1MatchesIdCalculation: vi.fn(),
}))
vi.mock('@/api/generated/matches/matches', () => ({
  useGetApiV1MatchesIdCalculation,
}))

const entry = (overrides = {}) => ({
  id: 'h1',
  previousRating: '4.000000',
  newRating: '4.100000',
  ratingChange: '0.100000',
  previousLevel: '4.0',
  newLevel: '4.0',
  levelChanged: false,
  smoothingApplied: false,
  calculatedAt: '2026-06-01T12:00:00',
  ...overrides,
})

const breakdown = (overrides = {}) => ({
  dominance: '0.200000',
  scale: '1.000000',
  ratingGap: '0.000000',
  normalizedGap: '0.000000',
  competitiveThresholdPct: '0.083000',
  isUpset: false,
  upsetMultiplier: '2.000000',
  kFactor: '0.160000',
  ...overrides,
})

const detail = (overrides: Record<string, unknown> = {}) => ({
  match: {
    id: 'm1',
    matchDate: '2026-06-01',
    team1: { teamId: 't1', userIds: ['u1'] },
    team2: { teamId: 't2', userIds: ['u2'] },
    winnerTeamId: 't1',
    sets: [
      { setNumber: 1, team1Games: 6, team2Games: 4 },
      { setNumber: 2, team1Games: 6, team2Games: 3 },
    ],
    ...((overrides.match as object) ?? {}),
  },
  changes: overrides.changes ?? [
    {
      userId: 'u1',
      displayName: 'Alice',
      previousRating: '4.000000',
      newRating: '4.100000',
      change: '0.100000',
      levelChanged: false,
      breakdown: breakdown(),
    },
    {
      userId: 'u2',
      displayName: 'Bob',
      previousRating: '4.000000',
      newRating: '3.900000',
      change: '-0.100000',
      levelChanged: false,
      breakdown: null,
    },
  ],
})

describe('RatingHistoryCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1MatchesIdCalculation.mockReturnValue({ data: detail(), isLoading: false })
  })

  it('shows a loading state', () => {
    render(<RatingHistoryCard entries={[]} isLoading />)
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an empty state when there are no entries', () => {
    render(<RatingHistoryCard entries={[]} />)
    expect(screen.getByText('No rating changes yet.')).toBeInTheDocument()
  })

  it('shows the full value and the band, and highlights a band change', () => {
    const { container } = render(
      <RatingHistoryCard
        entries={[
          entry({ id: 'h1', newRating: '4.300000', newLevel: '4.5', levelChanged: true }),
        ]}
      />,
    )
    expect(screen.getByText('4.000000 → 4.300000')).toBeInTheDocument()
    expect(screen.getByText('NTRP 4.0 → 4.5')).toBeInTheDocument()
    expect(screen.getByText('Band 4.0 → 4.5')).toBeInTheDocument()
    expect(container.querySelector('.border-primary')).not.toBeNull()
  })

  it('does not highlight or badge a row without a band change', () => {
    const { container } = render(<RatingHistoryCard entries={[entry()]} />)
    expect(screen.getByText('NTRP 4.0 → 4.0')).toBeInTheDocument()
    expect(screen.queryByText(/^Band /)).not.toBeInTheDocument()
    expect(container.querySelector('.border-primary')).toBeNull()
  })

  it('falls back to a dash when a band is missing', () => {
    render(
      <RatingHistoryCard entries={[entry({ previousLevel: null, newLevel: null })]} />,
    )
    expect(screen.getByText('NTRP — → —')).toBeInTheDocument()
  })

  it('uses the provided description', () => {
    render(<RatingHistoryCard entries={[]} description="Full rating history (admin view)." />)
    expect(screen.getByText('Full rating history (admin view).')).toBeInTheDocument()
  })

  it('an initial-assessment entry (no match) is not clickable', () => {
    render(<RatingHistoryCard entries={[entry()]} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('expands a match-driven entry to show the result and the calculation', async () => {
    const user = userEvent.setup()
    render(<RatingHistoryCard entries={[entry({ matchId: 'm1' })]} />)

    await user.click(screen.getByRole('button'))

    expect(useGetApiV1MatchesIdCalculation).toHaveBeenCalledWith('m1', expect.anything())
    expect(screen.getByText('2026-06-01 · 6-4 6-3 · Winner: Alice')).toBeInTheDocument()
    expect(screen.getByText(/Alice: 4\.000000 → 4\.100000 \(0\.100000\)/)).toBeInTheDocument()
    expect(screen.getByText(/dominance 0\.200000 · scale 1\.000000/)).toBeInTheDocument()
    // The opponent has no stored breakdown → only the rating line, no breakdown row.
    expect(screen.getByText(/Bob: 4\.000000 → 3\.900000/)).toBeInTheDocument()
    expect(screen.getByText(/· expected · K 0\.160000/)).toBeInTheDocument()
  })

  it('collapses an expanded entry again', async () => {
    const user = userEvent.setup()
    render(<RatingHistoryCard entries={[entry({ matchId: 'm1' })]} />)
    const button = screen.getByRole('button')

    await user.click(button)
    expect(screen.getByText(/dominance 0\.200000/)).toBeInTheDocument()

    await user.click(button)
    expect(screen.queryByText(/dominance 0\.200000/)).not.toBeInTheDocument()
  })

  it('labels an upset, resolves a team2 winner, and falls back to the id without a name', async () => {
    useGetApiV1MatchesIdCalculation.mockReturnValue({
      data: detail({
        match: { winnerTeamId: 't2' },
        changes: [
          {
            userId: 'abcdef123456',
            displayName: null,
            previousRating: '4.000000',
            newRating: '3.900000',
            change: '-0.100000',
            levelChanged: false,
            breakdown: breakdown({ isUpset: true }),
          },
          {
            userId: 'u2',
            displayName: 'Bob',
            previousRating: '4.000000',
            newRating: '4.100000',
            change: '0.100000',
            levelChanged: false,
            breakdown: breakdown(),
          },
        ],
      }),
      isLoading: false,
    })
    const user = userEvent.setup()
    render(<RatingHistoryCard entries={[entry({ matchId: 'm1' })]} />)

    await user.click(screen.getByRole('button'))
    expect(screen.getByText('2026-06-01 · 6-4 6-3 · Winner: Bob')).toBeInTheDocument()
    expect(screen.getByText(/abcdef12: 4\.000000 → 3\.900000/)).toBeInTheDocument()
    expect(screen.getByText(/· upset · K 0\.160000/)).toBeInTheDocument()
  })

  it('shows a result without scores or a winner when neither is recorded', async () => {
    useGetApiV1MatchesIdCalculation.mockReturnValue({
      data: detail({
        match: { winnerTeamId: null, sets: [], matchDate: '2026-05-20' },
        changes: [
          {
            userId: 'u1',
            displayName: 'Alice',
            previousRating: '4.000000',
            newRating: '4.000000',
            change: '0.000000',
            levelChanged: false,
            breakdown: null,
          },
        ],
      }),
      isLoading: false,
    })
    const user = userEvent.setup()
    render(<RatingHistoryCard entries={[entry({ matchId: 'm1' })]} />)

    await user.click(screen.getByRole('button'))
    // The detail's match date (distinct from the entry's own date) renders without scores/winner.
    expect(screen.getByText('2026-05-20')).toBeInTheDocument()
    expect(screen.queryByText(/Winner:/)).not.toBeInTheDocument()
  })

  it('shows a loading then an unavailable state for the calculation detail', async () => {
    useGetApiV1MatchesIdCalculation.mockReturnValue({ data: undefined, isLoading: true })
    const user = userEvent.setup()
    const { rerender } = render(<RatingHistoryCard entries={[entry({ matchId: 'm1' })]} />)
    await user.click(screen.getByRole('button'))
    expect(screen.getByText('Loading…')).toBeInTheDocument()

    useGetApiV1MatchesIdCalculation.mockReturnValue({ data: undefined, isLoading: false })
    rerender(<RatingHistoryCard entries={[entry({ matchId: 'm1' })]} />)
    expect(screen.getByText(/Calculation details aren’t available/)).toBeInTheDocument()
  })
})
