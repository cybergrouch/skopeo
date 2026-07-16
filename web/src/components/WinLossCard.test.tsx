import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { WinLossCard } from './WinLossCard'

const { useGetApiV1PlayersCodeResultsSummary } = vi.hoisted(() => ({
  useGetApiV1PlayersCodeResultsSummary: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1PlayersCodeResultsSummary,
}))

/** The cells of a labelled table row: [Played, Wins, Losses, Win rate]. */
function rowCells(label: string): string[] {
  const row = screen.getByRole('rowheader', { name: label }).closest('tr')
  if (!row) throw new Error(`row ${label} not found`)
  return within(row)
    .getAllByRole('cell')
    .map((cell) => cell.textContent ?? '')
}

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

  it('sums the buckets into per-type and overall totals with win rates', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      data: {
        singles: [
          { period: '2026-01', wins: 2, losses: 1 },
          { period: '2026-02', wins: 1, losses: 0 },
        ],
        doubles: [{ period: '2026-02', wins: 1, losses: 3 }],
      },
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)

    // Total matches played: singles 4 (3W/1L) + doubles 4 (1W/3L) = 8.
    const total = screen.getByText('Total matches played:').closest('p')
    if (!total) throw new Error('total line not found')
    expect(total).toHaveTextContent('Total matches played: 8')

    // Singles: 4 played, 3 wins, 1 loss, 3/4 = 75%.
    expect(rowCells('Singles')).toEqual(['4', '3', '1', '75%'])
    // Doubles: 4 played, 1 win, 3 losses, 1/4 = 25%.
    expect(rowCells('Doubles')).toEqual(['4', '1', '3', '25%'])
    // Overall: 8 played, 4 wins, 4 losses, 4/8 = 50%.
    expect(rowCells('Overall')).toEqual(['8', '4', '4', '50%'])
  })

  it('shows "n/a" for a format with no decided matches instead of 0/0 or NaN', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      data: {
        singles: [{ period: '2026-01', wins: 2, losses: 1 }],
        doubles: [],
      },
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)

    expect(rowCells('Singles')).toEqual(['3', '2', '1', '67%'])
    // Doubles has no matches: rate is "n/a", not "0%".
    expect(rowCells('Doubles')).toEqual(['0', '0', '0', 'n/a'])
    // Overall is driven entirely by singles here.
    expect(rowCells('Overall')).toEqual(['3', '2', '1', '67%'])
  })

  it('does not query when no code is provided', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({ data: undefined, isLoading: false })
    render(<WinLossCard code="" />)
    // enabled: Boolean(code) is false, so the empty state shows rather than a table.
    expect(screen.getByText('No completed matches yet.')).toBeInTheDocument()
    expect(useGetApiV1PlayersCodeResultsSummary).toHaveBeenCalledWith('', {
      query: { enabled: false },
    })
  })
})
