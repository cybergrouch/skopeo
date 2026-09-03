import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import type { OpponentBandSeries, OpponentBandSeriesRelation } from '@/api/generated/model'
import { WinLossCard } from './WinLossCard'

const { useGetApiV1PlayersCodeResultsSummary } = vi.hoisted(() => ({
  useGetApiV1PlayersCodeResultsSummary: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1PlayersCodeResultsSummary,
}))

/** A finished totals object as the server now sends it (#845) — the card does no arithmetic. */
function totals(played: number, wins: number, losses: number, winRate: number | null) {
  return { played, wins, losses, winRate }
}

/** One band relation with its totals and a one-month series, as the server assembles it (#845). */
function band(
  relation: OpponentBandSeriesRelation,
  wins: number,
  losses: number,
  winRate: number | null,
): OpponentBandSeries {
  return {
    relation,
    totals: { played: wins + losses, wins, losses, winRate },
    monthly: [{ period: '2026-08', wins, losses }],
  }
}

/** All three relations, always present and always in SAME → HIGHER → LOWER order. */
const emptyBands = [band('SAME', 0, 0, null), band('HIGHER', 0, 0, null), band('LOWER', 0, 0, null)]

/** The three cuts plus the band series, matching the response shape. */
function summary(
  singles: ReturnType<typeof totals>,
  doubles: ReturnType<typeof totals>,
  overall: ReturnType<typeof totals>,
  opponentBands: OpponentBandSeries[] = emptyBands,
  monthlyMax = 0,
) {
  return { singles, doubles, overall, opponentBands, monthsWindow: 12, monthlyMax }
}

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
      data: summary(totals(0, 0, 0, null), totals(0, 0, 0, null), totals(0, 0, 0, null)),
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)
    expect(screen.getByText('No completed matches yet.')).toBeInTheDocument()
  })

  it('renders the server-computed per-type and overall totals', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      // The card no longer sums anything (#845) — it renders what it is given, so these fixtures are
      // the finished figures rather than monthly buckets.
      data: summary(totals(4, 3, 1, 75), totals(4, 1, 3, 25), totals(8, 4, 4, 50)),
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

  it('renders a null win rate as "n/a" rather than 0%', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      // "n/a" and "0%" are different claims, and the server decides which — a null winRate means
      // nothing was decided, so the card must not turn it into a zero.
      data: summary(totals(3, 2, 1, 67), totals(0, 0, 0, null), totals(3, 2, 1, 67)),
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)

    expect(rowCells('Singles')).toEqual(['3', '2', '1', '67%'])
    // Doubles has no matches: rate is "n/a", not "0%".
    expect(rowCells('Doubles')).toEqual(['0', '0', '0', 'n/a'])
    // Overall is driven entirely by singles here.
    expect(rowCells('Overall')).toEqual(['3', '2', '1', '67%'])
  })

  it('renders the opponent-band charts from the assembled series', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      // 8 rated singles matches split across the three relations; doubles is excluded from this cut,
      // which is why these do not add up to the Singles row of 10.
      data: summary(
        totals(10, 6, 4, 60),
        totals(2, 1, 1, 50),
        totals(12, 7, 5, 58),
        [band('SAME', 3, 1, 75), band('HIGHER', 1, 2, 33), band('LOWER', 1, 0, 100)],
        4,
      ),
      isLoading: false,
    })
    const { container } = render(<WinLossCard code="K7Q2MX" />)

    expect(screen.getByRole('heading', { name: 'Singles opponents by band' })).toBeInTheDocument()
    // The donut plus one sparkline per relation.
    expect(container.querySelectorAll('svg')).toHaveLength(4)
    // Every figure is legible as text regardless of whether the shades are distinguishable.
    expect(screen.getByRole('button', { name: /Same band 3W \/ 1L · 75%/ })).toBeInTheDocument()
    expect(screen.getByText('Last 12 months, oldest first')).toBeInTheDocument()
  })

  it('states both exclusions, since the band counts deliberately do not match the singles row', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      data: summary(totals(4, 3, 1, 75), totals(4, 1, 3, 25), totals(8, 4, 4, 50)),
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)
    // Unexplained, the gap between 4 singles and 0 banded matches reads as a bug rather than as a
    // narrower cut, so the caption has to name both reasons.
    const caption = screen.getByText(/Singles only/)
    expect(caption).toHaveTextContent('doubles matches are not counted')
    expect(caption).toHaveTextContent('only matches that have been rated')
  })

  it('says which window each chart covers, so the two are not read as disagreeing', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      data: summary(totals(4, 3, 1, 75), totals(0, 0, 0, null), totals(4, 3, 1, 75)),
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)
    // The donut is all-time and the sparklines are a trailing window. A reader who assumes both cover
    // the same span reads the mismatch as an error in one of them.
    expect(screen.getByText(/Singles only/)).toHaveTextContent(
      'The ring is all-time; the monthly rows below it cover the last 12 months',
    )
  })

  it('shows an empty band section for a player with only doubles or unrated matches', () => {
    useGetApiV1PlayersCodeResultsSummary.mockReturnValue({
      // Doubles-only: the totals table has real figures but there is no banded singles play at all.
      data: summary(totals(0, 0, 0, null), totals(4, 2, 2, 50), totals(4, 2, 2, 50)),
      isLoading: false,
    })
    render(<WinLossCard code="K7Q2MX" />)
    expect(screen.getByText('No rated singles matches yet.')).toBeInTheDocument()
    expect(screen.getByText('No rated singles matches in the last 12 months.')).toBeInTheDocument()
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
