import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { OpponentBandSeries, OpponentBandSeriesRelation } from '@/api/generated/model'
import { OpponentBandSparklines } from './OpponentBandSparklines'

/** Monthly buckets as the server sends them: oldest first, gap-filled with zeroes. */
function series(
  relation: OpponentBandSeriesRelation,
  monthly: Array<[string, number, number]>,
): OpponentBandSeries {
  const wins = monthly.reduce((sum, [, w]) => sum + w, 0)
  const losses = monthly.reduce((sum, [, , l]) => sum + l, 0)
  const played = wins + losses
  return {
    relation,
    totals: { played, wins, losses, winRate: played === 0 ? null : Math.round((wins / played) * 100) },
    monthly: monthly.map(([period, w, l]) => ({ period, wins: w, losses: l })),
  }
}

const threeBands = [
  series('SAME', [['2026-07', 4, 0], ['2026-08', 1, 3]]),
  series('HIGHER', [['2026-07', 0, 0], ['2026-08', 1, 1]]),
  series('LOWER', [['2026-07', 1, 0], ['2026-08', 0, 0]]),
]

const barsOf = (container: HTMLElement, row: number) =>
  Array.from(container.querySelectorAll('svg')[row].querySelectorAll('rect'))

describe('OpponentBandSparklines', () => {
  it('draws one panel per band relation, each labelled in its own hue', () => {
    const { container } = render(
      <OpponentBandSparklines series={threeBands} monthsWindow={12} monthlyMax={4} />,
    )
    expect(container.querySelectorAll('svg')).toHaveLength(3)
    expect(screen.getByText('Same band')).toHaveStyle({ color: 'var(--chart-1)' })
    expect(screen.getByText('Higher band')).toHaveStyle({ color: 'var(--chart-2)' })
    expect(screen.getByText('Lower band')).toHaveStyle({ color: 'var(--chart-3)' })
  })

  it('scales every panel against the shared maximum, not its own', () => {
    const { container } = render(
      <OpponentBandSparklines series={threeBands} monthsWindow={12} monthlyMax={4} />,
    )
    // SAME's busiest month is 4 matches and LOWER's is 1. Per-panel scaling would draw both full
    // height and destroy the comparison the small multiples exist to make; on the shared scale of 4
    // the LOWER bar must be a quarter of the height of the SAME one.
    const sameJuly = barsOf(container, 0)[0]
    const lowerJuly = barsOf(container, 2)[0]
    expect(Number(sameJuly.getAttribute('height'))).toBeCloseTo(20, 5)
    expect(Number(lowerJuly.getAttribute('height'))).toBeCloseTo(5, 5)
  })

  it('splits each month by outcome in the donut’s encoding, wins on the baseline', () => {
    const { container } = render(
      <OpponentBandSparklines series={threeBands} monthsWindow={12} monthlyMax={4} />,
    )
    // SAME, August: 1 win and 3 losses out of a shared max of 4.
    const august = barsOf(container, 0).slice(2)
    const [win, loss] = august
    expect(win).toHaveAttribute('fill', 'var(--chart-1)')
    expect(loss).toHaveAttribute('fill', 'var(--chart-1-muted)')
    // The win rect sits on the baseline (y + height = the full 20-unit height) and the loss stacks above.
    expect(Number(win.getAttribute('height'))).toBeCloseTo(5, 5)
    expect(Number(win.getAttribute('y'))).toBeCloseTo(15, 5)
    expect(Number(loss.getAttribute('height'))).toBeCloseTo(15, 5)
    expect(Number(loss.getAttribute('y'))).toBeCloseTo(0, 5)
  })

  it('keeps a month with no play as an empty slot rather than closing the gap', () => {
    const { container } = render(
      <OpponentBandSparklines series={threeBands} monthsWindow={12} monthlyMax={4} />,
    )
    // HIGHER played nothing in July. The bar must still occupy its month, or the x-axis silently
    // stops being time.
    const july = barsOf(container, 1).slice(0, 2)
    expect(july.every((bar) => Number(bar.getAttribute('height')) === 0)).toBe(true)
    // August's bars start one slot along, not at the origin.
    expect(Number(barsOf(container, 1)[2].getAttribute('x'))).toBe(8)
  })

  it('labels the window so the reader knows what span the shapes cover', () => {
    render(<OpponentBandSparklines series={threeBands} monthsWindow={12} monthlyMax={4} />)
    expect(screen.getByText('Last 12 months, oldest first')).toBeInTheDocument()
  })

  it('hides the drawings from assistive tech', () => {
    const { container } = render(
      <OpponentBandSparklines series={threeBands} monthsWindow={12} monthlyMax={4} />,
    )
    // Shape over time is not something a screen reader can consume from an SVG, and the legend in the
    // donut above already states every total as text.
    for (const svg of container.querySelectorAll('svg')) {
      expect(svg).toHaveAttribute('aria-hidden', 'true')
    }
  })

  it('says nothing was played rather than drawing three flat lines', () => {
    const { container } = render(
      <OpponentBandSparklines
        series={[series('SAME', [['2026-08', 0, 0]]), series('HIGHER', [['2026-08', 0, 0]]), series('LOWER', [['2026-08', 0, 0]])]}
        monthsWindow={12}
        monthlyMax={0}
      />,
    )
    // A zero maximum would make every bar zero height, which reads as a broken chart, not as no play.
    expect(screen.getByText('No rated singles matches in the last 12 months.')).toBeInTheDocument()
    expect(container.querySelector('svg')).toBeNull()
  })
})
