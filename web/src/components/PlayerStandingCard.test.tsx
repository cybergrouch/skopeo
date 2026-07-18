import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PlayerStandingCard } from './PlayerStandingCard'

const { useGetApiV1PlayersCodeStanding } = vi.hoisted(() => ({
  useGetApiV1PlayersCodeStanding: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1PlayersCodeStanding }))

describe('PlayerStandingCard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a loading state', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({ data: undefined, isLoading: true })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows points (not a rating) under the POINTS source (#457)', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({
      // The backend serializes points from a NUMERIC(12,4) BigDecimal, so it carries a .0000 tail;
      // the card formats it as a signed integer (#467).
      data: { band: '4.0', bandLabel: 'NTRP 4.0 Band Race', sex: 'Male', rank: 4, points: '240.0000', source: 'POINTS' },
      isLoading: false,
    })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText('#4')).toBeInTheDocument()
    expect(screen.getByText(/\+240 pts · 4.0 Men/)).toBeInTheDocument()
  })

  it('shows the rating (labeled NTRP) under the RATING source when it is revealed (#457, #186)', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({
      data: { band: '4.0', bandLabel: 'NTRP 4.0 Band Race', sex: 'Male', rank: 1, rating: '4.200000', source: 'RATING' },
      isLoading: false,
    })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText(/NTRP 4.200000 · 4.0 Men/)).toBeInTheDocument()
  })

  it('shows rank + band only under RATING when the rating is not revealed (#457, #186)', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({
      data: { band: '4.0', bandLabel: 'NTRP 4.0 Band Race', sex: 'Male', rank: 1, source: 'RATING' },
      isLoading: false,
    })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText('#1')).toBeInTheDocument()
    expect(screen.getByText(/· 4.0 Men/)).toBeInTheDocument()
    // No rating value is rendered — the response omitted it, so nothing leaks.
    expect(screen.queryByText(/NTRP 4/)).not.toBeInTheDocument()
    expect(screen.queryByText(/pts/)).not.toBeInTheDocument()
  })

  it('drops the sex label for an Unspecified group', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({
      data: { band: '4.0', bandLabel: 'NTRP 4.0 Band Race', sex: null, rank: 4, points: '240', source: 'POINTS' },
      isLoading: false,
    })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText(/\+240 pts · 4.0/)).toBeInTheDocument()
    expect(screen.queryByText(/Men|Women/)).not.toBeInTheDocument()
  })

  it('shows "Unranked" when the player is absent from the standings (204 → no data)', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({ data: undefined, isLoading: false })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText('Unranked')).toBeInTheDocument()
  })
})
