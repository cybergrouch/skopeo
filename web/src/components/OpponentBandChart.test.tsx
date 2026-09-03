import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { OpponentBandSeries, OpponentBandSeriesRelation } from '@/api/generated/model'
import { OpponentBandChart } from './OpponentBandChart'

function series(
  relation: OpponentBandSeriesRelation,
  wins: number,
  losses: number,
  winRate: number | null,
): OpponentBandSeries {
  return {
    relation,
    totals: { played: wins + losses, wins, losses, winRate },
    monthly: [],
  }
}

/** All three relations, always present in SAME → HIGHER → LOWER order as the server sends them. */
const threeBands = [
  series('SAME', 6, 2, 75),
  series('HIGHER', 1, 3, 25),
  series('LOWER', 3, 1, 75),
]

const arcs = (container: HTMLElement) => Array.from(container.querySelectorAll('circle'))

describe('OpponentBandChart', () => {
  it('draws one arc per non-empty segment, win before loss within each band', () => {
    const { container } = render(<OpponentBandChart series={threeBands} />)
    // 3 relations x {win, loss}, all non-zero here.
    expect(arcs(container)).toHaveLength(6)
    // Adjacency is the whole basis of the hue-groups-a-band encoding: the strokes must run
    // same-win, same-loss, higher-win, higher-loss, lower-win, lower-loss.
    expect(arcs(container).map((a) => a.getAttribute('stroke'))).toEqual([
      'var(--chart-1)',
      'var(--chart-1-muted)',
      'var(--chart-2)',
      'var(--chart-2-muted)',
      'var(--chart-3)',
      'var(--chart-3-muted)',
    ])
  })

  it('sizes each arc to its share of the total and butts it against the previous one', () => {
    const { container } = render(<OpponentBandChart series={threeBands} />)
    const circumference = 2 * Math.PI * 40
    const dashes = arcs(container).map((a) => Number(a.getAttribute('stroke-dasharray')?.split(' ')[0]))
    const offsets = arcs(container).map((a) => Number(a.getAttribute('stroke-dashoffset')))

    // 16 matches in total; the same-band win arc is 6 of them.
    expect(dashes[0]).toBeCloseTo((6 / 16) * circumference, 5)
    // The first arc starts at the origin, and each subsequent offset is the sum of everything before it
    // — a gap or an overlap here would break the ring visually.
    expect(offsets[0]).toBe(0)
    expect(-offsets[1]).toBeCloseTo(dashes[0], 5)
    expect(-offsets[2]).toBeCloseTo(dashes[0] + dashes[1], 5)
    // The arcs together consume the full circle exactly, since every match falls in exactly one segment.
    expect(dashes.reduce((sum, d) => sum + d, 0)).toBeCloseTo(circumference, 5)
  })

  it('omits an empty segment rather than drawing a zero-length arc', () => {
    // An undefeated player against lower bands: no loss arc for that relation.
    const { container } = render(
      <OpponentBandChart series={[series('SAME', 2, 1, 67), series('HIGHER', 0, 0, null), series('LOWER', 3, 0, 100)]} />,
    )
    // same-win, same-loss, lower-win. HIGHER contributes nothing and LOWER has no losses.
    expect(arcs(container)).toHaveLength(3)
  })

  it('carries every figure in the legend, so the shades are never load-bearing', () => {
    render(<OpponentBandChart series={threeBands} />)
    // Win/loss differ only in lightness in the drawing; the legend states them as text.
    expect(screen.getByRole('button', { name: /Same band 6W \/ 2L · 75%/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Higher band 1W \/ 3L · 25%/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Lower band 3W \/ 1L · 75%/ })).toBeInTheDocument()
  })

  it('hides the drawing from assistive tech, leaving the legend as the accessible route', () => {
    const { container } = render(<OpponentBandChart series={threeBands} />)
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('reveals the band detail when a legend row is clicked', async () => {
    const user = userEvent.setup()
    render(<OpponentBandChart series={threeBands} />)

    await user.click(screen.getByRole('button', { name: /Higher band/ }))
    // 4 of 16 rated singles matches = 25% of the ring, and the popover says so rather than making the
    // reader estimate an arc.
    const detail = await screen.findByRole('tooltip')
    expect(detail).toHaveTextContent('4 of your rated singles matches (25%)')
    expect(detail).toHaveTextContent('in a higher band')
    expect(detail).toHaveTextContent('1 won, 3 lost — a 25% win rate')
  })

  it('reveals the same detail when the arc itself is clicked', async () => {
    const user = userEvent.setup()
    const { container } = render(<OpponentBandChart series={threeBands} />)

    // The third arc is HIGHER's win segment. Clicking a slice is the mouse route to the datum the
    // legend row exposes to everyone else.
    await user.click(arcs(container)[2])
    expect(await screen.findByText(/4 of your rated singles matches \(25%\)/)).toBeInTheDocument()
  })

  it('phrases the same-band case as the reader’s own band', async () => {
    const user = userEvent.setup()
    render(<OpponentBandChart series={threeBands} />)

    await user.click(screen.getByRole('button', { name: /Same band/ }))
    // "in a same band" would be nonsense; the reader's own band is the reference point.
    const detail = await screen.findByRole('tooltip')
    expect(detail).toHaveTextContent('in your own band')
    expect(detail).toHaveTextContent('8 of your rated singles matches (50%)')
  })

  it('omits the win rate from the detail too when nothing is decided for a band', async () => {
    const user = userEvent.setup()
    render(
      <OpponentBandChart series={[series('SAME', 2, 1, 67), series('HIGHER', 0, 0, null), series('LOWER', 0, 0, null)]} />,
    )

    await user.click(screen.getByRole('button', { name: /Lower band/ }))
    const detail = await screen.findByRole('tooltip')
    // A null rate means no basis, so the sentence ends at the counts rather than claiming 0%.
    expect(detail).toHaveTextContent('0 won, 0 lost.')
    expect(detail.textContent).not.toContain('win rate')
  })

  it('says so when there are no rated singles matches', () => {
    // A doubles-only player: the totals table above has figures, but this cut is genuinely empty and
    // must not render as an unexplained blank ring.
    const { container } = render(
      <OpponentBandChart
        series={[series('SAME', 0, 0, null), series('HIGHER', 0, 0, null), series('LOWER', 0, 0, null)]}
      />,
    )
    expect(screen.getByText('No rated singles matches yet.')).toBeInTheDocument()
    expect(container.querySelector('svg')).toBeNull()
  })

  it('omits the win rate from the legend when nothing is decided for a band', () => {
    render(<OpponentBandChart series={[series('SAME', 2, 1, 67), series('HIGHER', 0, 0, null), series('LOWER', 0, 0, null)]} />)
    // A null rate is the server saying "no basis", which must not be shown as 0%.
    const higher = screen.getByRole('button', { name: /Higher band/ })
    expect(higher).toHaveTextContent('0W / 0L')
    expect(higher.textContent).not.toContain('%')
  })
})
