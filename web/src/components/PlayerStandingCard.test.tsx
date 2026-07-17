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

  it('renders the rank, points and band+sex group (#448)', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({
      data: { band: '4.0', bandLabel: 'NTRP 4.0 Band Race', sex: 'Male', rank: 4, points: '240', source: 'POINTS' },
      isLoading: false,
    })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText('#4')).toBeInTheDocument()
    expect(screen.getByText(/240 pts · 4.0 Men/)).toBeInTheDocument()
  })

  it('drops the sex label for an Unspecified group', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({
      data: { band: '4.0', bandLabel: 'NTRP 4.0 Band Race', sex: null, rank: 1, points: '4.200000', source: 'RATING' },
      isLoading: false,
    })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText(/4.200000 pts · 4.0/)).toBeInTheDocument()
    expect(screen.queryByText(/Men|Women/)).not.toBeInTheDocument()
  })

  it('shows "Unranked" when the player is absent from the standings (204 → no data)', () => {
    useGetApiV1PlayersCodeStanding.mockReturnValue({ data: undefined, isLoading: false })
    render(<PlayerStandingCard code="ABC123" />)
    expect(screen.getByText('Unranked')).toBeInTheDocument()
  })
})
