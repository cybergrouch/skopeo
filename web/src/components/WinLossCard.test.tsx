import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { WinLossCard } from './WinLossCard'

const { useGetApiV1PlayersCodeResultsSummary } = vi.hoisted(() => ({
  useGetApiV1PlayersCodeResultsSummary: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1PlayersCodeResultsSummary,
}))
// The Recharts chart is code-split; stub it so the card's states are testable without Recharts in jsdom.
vi.mock('@/components/WinLossChart', () => ({
  default: ({ summary }: { summary: { singles: unknown[]; doubles: unknown[] } }) => (
    <div>chart singles:{summary.singles.length} doubles:{summary.doubles.length}</div>
  ),
}))

describe('WinLossCard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a loading state', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({ data: undefined, isLoading: true })
    render(<WinLossCard code="K7Q2MX" />)
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an empty state when there are no decided matches', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      data: { singles: [], doubles: [] },
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)
    expect(screen.getByText('No completed matches yet.')).toBeInTheDocument()
  })

  it('renders the chart once there are results', async () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      data: {
        singles: [{ period: '2026-01', wins: 2, losses: 1 }],
        doubles: [{ period: '2026-02', wins: 1, losses: 0 }],
      },
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)
    // The chart is lazy-loaded behind Suspense, so wait for the (stubbed) chunk to resolve.
    expect(await screen.findByText('chart singles:1 doubles:1')).toBeInTheDocument()
  })

  it('does not query when no code is provided', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({ data: undefined, isLoading: false })
    render(<WinLossCard code="" />)
    // enabled: Boolean(code) is false, so the empty state shows rather than a chart.
    expect(screen.getByText('No completed matches yet.')).toBeInTheDocument()
    expect(useGetApiV1PlayersCodeResultsSummary).toHaveBeenCalledWith('', {
      query: { enabled: false },
    })
  })
})
