import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { RatingHistoryResponse } from '@/api/generated/model'
import { BandHistoryChart } from './BandHistoryChart'

/** History arrives newest-first from the API; these fixtures keep that order deliberately. */
const entry = (overrides: Partial<RatingHistoryResponse> = {}): RatingHistoryResponse => ({
  id: 'h1',
  previousRating: '3.500000',
  newRating: '3.600000',
  ratingChange: '0.100000',
  previousLevel: '3.5',
  newLevel: '3.5',
  levelChanged: false,
  smoothingApplied: false,
  calculatedAt: '2026-06-01T12:00:00',
  ...overrides,
})

const TODAY = '2026-09-01'

const pathOf = (container: HTMLElement) =>
  container.querySelector('path')?.getAttribute('d') ?? ''

/** The vertical jumps in a step path — one per band change. */
const jumps = (d: string) => d.split(' ').filter((token) => token === 'V').length

describe('BandHistoryChart', () => {
  it('draws horizontal runs joined by vertical jumps, never a diagonal', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        entries={[
          entry({ id: 'h2', previousLevel: '3.5', newLevel: '4.0', levelChanged: true, calculatedAt: '2026-07-01T12:00:00' }),
          entry({ id: 'h1', previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-05-01T12:00:00' }),
        ]}
      />,
    )
    const d = pathOf(container)
    // A band is a state held, not a value passed through: only H and V segments, so no week is ever
    // drawn at a "3.2" the player never had.
    expect(d).toMatch(/^M [\d.]+ [\d.]+( H [\d.]+ V [\d.]+)+$/)
    // Two changes plus the run carried forward to today.
    expect(jumps(d)).toBe(3)
    expect(screen.getByText('3 bands held over this period.')).toBeInTheDocument()
  })

  it('starts from the band held before the first change, not mid-air', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        entries={[entry({ previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-05-01T12:00:00' })]}
      />,
    )
    // The 3.0 → 3.5 jump is the point of the chart; seeding from previousLevel is what makes it visible.
    const labels = Array.from(container.querySelectorAll('text')).map((t) => t.textContent)
    expect(labels).toContain('3.0')
    expect(labels).toContain('3.5')
  })

  it('draws a single rating as a flat line running to today', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        // An initial assessment: no previous band, so nothing to jump from.
        entries={[entry({ previousLevel: null, newLevel: '3.5', calculatedAt: '2026-06-01T12:00:00' })]}
      />,
    )
    const d = pathOf(container)
    // One sample plus the carry-to-today: a horizontal line at 3.5 with a V that returns to the same
    // height. A lone point would be invisible, and stopping at the entry date would imply the player
    // stopped holding the band.
    const heights = d.match(/[\d.]+/g)?.map(Number) ?? []
    expect(jumps(d)).toBe(1)
    // Start y and end y are the same — the line is flat.
    expect(heights[1]).toBeCloseTo(heights[3], 5)
    expect(screen.getByText('One band held throughout.')).toBeInTheDocument()
  })

  it('extends the last band to today rather than ending at the last match', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        entries={[entry({ previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-06-01T12:00:00' })]}
      />,
    )
    // The x-axis ends this month, because the player still holds 3.5 — the band is current, not
    // historical. Month precision also keeps the tick from reading as one of the card's own ISO rows.
    expect(screen.getByText('Sep 2026')).toBeInTheDocument()
    expect(screen.getByText('Jun 2026')).toBeInTheDocument()
    expect(container.querySelector('path')).toBeInTheDocument()
  })

  it('treats a repeated band as one continuing run', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        entries={[
          entry({ id: 'h3', newLevel: '3.5', calculatedAt: '2026-07-01T12:00:00' }),
          entry({ id: 'h2', newLevel: '3.5', calculatedAt: '2026-06-15T12:00:00' }),
          entry({ id: 'h1', previousLevel: '3.5', newLevel: '3.5', calculatedAt: '2026-06-01T12:00:00' }),
        ]}
      />,
    )
    // An admin sees every rated match, most of which move the rating without moving the band. Those
    // must not draw as jumps.
    expect(screen.getByText('One band held throughout.')).toBeInTheDocument()
    expect(Array.from(container.querySelectorAll('text')).filter((t) => t.textContent === '3.5')).toHaveLength(1)
  })

  it('labels one gridline per band actually held', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        entries={[
          entry({ id: 'h2', previousLevel: '3.5', newLevel: '4.0', levelChanged: true, calculatedAt: '2026-07-01T12:00:00' }),
          entry({ id: 'h1', previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-05-01T12:00:00' }),
        ]}
      />,
    )
    // Discrete data, discrete axis: three bands held means three labelled rules, not interpolated ticks.
    expect(container.querySelectorAll('line')).toHaveLength(3)
    const labels = Array.from(container.querySelectorAll('text')).map((t) => t.textContent)
    expect(labels.slice(0, 3)).toEqual(['3.0', '3.5', '4.0'])
  })

  it('draws a full-width flat line for a player rated for the first time today', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        // Rated today: there is no span to scale against, and a date-scaled point would land on the
        // left edge with zero length — a chart that renders as nothing at all.
        entries={[entry({ previousLevel: null, newLevel: '3.5', calculatedAt: `${TODAY}T12:00:00` })]}
      />,
    )
    const d = pathOf(container)
    expect(d).toBe('M 26 50 H 314 V 50')
    expect(screen.getByText('One band held throughout.')).toBeInTheDocument()
  })

  it('spreads same-day changes evenly rather than stacking them on one point', () => {
    const { container } = render(
      <BandHistoryChart
        today={`${TODAY}T00:00:00`}
        // Two band changes committed in one sitting, all dated today: the ordering is real, the
        // spacing cannot be, so the samples are laid out by position instead of by date.
        entries={[
          entry({ id: 'h2', previousLevel: '3.5', newLevel: '4.0', levelChanged: true, calculatedAt: `${TODAY}T14:00:00` }),
          entry({ id: 'h1', previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: `${TODAY}T10:00:00` }),
        ]}
      />,
    )
    const d = pathOf(container)
    // Three samples across the full plot width, at the left edge, the midpoint and the right edge.
    expect(d).toMatch(/^M 26 /)
    expect(d).toContain('H 170 ')
    expect(d).toContain('H 314 ')
    expect(screen.getByText('3 bands held over this period.')).toBeInTheDocument()
  })

  it('ignores a band label that is not a number', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        // Anything unparseable would otherwise put NaN in the path, which blanks the whole drawing
        // rather than dropping the one bad entry.
        entries={[entry({ previousLevel: 'Unrated', newLevel: '3.5', calculatedAt: '2026-06-01T12:00:00' })]}
      />,
    )
    const labels = Array.from(container.querySelectorAll('text')).map((t) => t.textContent)
    expect(labels).not.toContain('NaN')
    expect(labels).toContain('3.5')
    expect(pathOf(container)).not.toContain('NaN')
  })

  it('hides the drawing from assistive tech, since the card lists every entry as text', () => {
    const { container } = render(<BandHistoryChart today={TODAY} entries={[entry()]} />)
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('renders nothing when there is no history to plot', () => {
    // The card keeps its own "No rating changes yet." branch; the chart must not add a second empty state.
    const { container } = render(<BandHistoryChart today={TODAY} entries={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('ignores an entry whose band cannot be read', () => {
    const { container } = render(
      <BandHistoryChart
        today={TODAY}
        entries={[entry({ previousLevel: null, newLevel: null })]}
      />,
    )
    // Non-admin viewers get band-only entries, so a null band is a real shape here; plotting it would
    // put NaN in the path and blank the chart.
    expect(container).toBeEmptyDOMElement()
  })

  describe('merged accounts (#853)', () => {
    const paths = (c: HTMLElement) => Array.from(c.querySelectorAll('path'))
    const strokes = (c: HTMLElement) => paths(c).map((p) => p.getAttribute('stroke'))

    it('draws one line per source account, in distinct colours', () => {
      const { container } = render(
        <BandHistoryChart
          today={TODAY}
          entries={[
            entry({ id: 's2', sourcePublicCode: 'QCST68', fromMergedAccount: false, previousLevel: '4.0', newLevel: '4.5', levelChanged: true, calculatedAt: '2026-08-20T12:00:00' }),
            entry({ id: 's1', sourcePublicCode: 'QCST68', fromMergedAccount: false, previousLevel: '3.5', newLevel: '4.0', levelChanged: true, calculatedAt: '2026-07-06T12:00:00' }),
            entry({ id: 'r2', sourcePublicCode: '9S9PJS', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-07-26T12:00:00' }),
            entry({ id: 'r1', sourcePublicCode: '9S9PJS', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.0', calculatedAt: '2026-07-20T12:00:00' }),
          ]}
        />,
      )
      // The survivor takes the primary colour — it is the trajectory leading to the current rating.
      expect(strokes(container)).toEqual(['var(--chart-1)', 'var(--chart-2)'])
    })

    it('carries only the survivor to today, never a merged account', () => {
      const { container } = render(
        <BandHistoryChart
          today={TODAY}
          entries={[
            entry({ id: 's1', sourcePublicCode: 'QCST68', fromMergedAccount: false, previousLevel: '4.0', newLevel: '4.5', levelChanged: true, calculatedAt: '2026-08-20T12:00:00' }),
            entry({ id: 'r2', sourcePublicCode: '9S9PJS', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-07-26T12:00:00' }),
            entry({ id: 'r1', sourcePublicCode: '9S9PJS', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.0', calculatedAt: '2026-07-20T12:00:00' }),
          ]}
        />,
      )
      const [survivor, merged] = paths(container).map((p) => p.getAttribute('d') ?? '')
      const lastX = (d: string) => {
        const xs = [...d.matchAll(/[MH] ([\d.]+)/g)].map((m) => Number(m[1]))
        return Math.max(...xs)
      }
      // A merged account's band stopped being current at the merge; running its line to today would
      // claim it still holds that band. The survivor's does reach the right edge.
      expect(lastX(survivor)).toBeCloseTo(314, 5)
      expect(lastX(merged)).toBeLessThan(314)
    })

    it('renders overlapping ranges as concurrent lines rather than flattening them', () => {
      const { container } = render(
        <BandHistoryChart
          today={TODAY}
          entries={[
            // The retired range is nested INSIDE the survivor's — the shape two production merges have.
            entry({ id: 's2', sourcePublicCode: 'QCST68', fromMergedAccount: false, previousLevel: '4.0', newLevel: '4.5', levelChanged: true, calculatedAt: '2026-09-01T12:00:00' }),
            entry({ id: 's1', sourcePublicCode: 'QCST68', fromMergedAccount: false, previousLevel: '3.5', newLevel: '4.0', levelChanged: true, calculatedAt: '2026-07-06T12:00:00' }),
            entry({ id: 'r2', sourcePublicCode: '9S9PJS', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.0', calculatedAt: '2026-07-26T12:00:00' }),
            entry({ id: 'r1', sourcePublicCode: '9S9PJS', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.0', calculatedAt: '2026-07-20T12:00:00' }),
          ]}
        />,
      )
      // Two lines, both present over the same period — the person genuinely held two ratings at once,
      // and one line with breaks cannot say that.
      expect(paths(container)).toHaveLength(2)
      expect(screen.getByText(/ratings from merged accounts are not continuous/)).toBeInTheDocument()
    })

    it('draws a merged account with a single rating as a marker, not an invisible line', () => {
      const { container } = render(
        <BandHistoryChart
          today={TODAY}
          entries={[
            entry({ id: 's1', sourcePublicCode: 'W75HMR', fromMergedAccount: false, previousLevel: '4.0', newLevel: '4.5', levelChanged: true, calculatedAt: '2026-08-27T12:00:00' }),
            // AVE9MM has exactly one history row in production. A zero-length path renders as nothing.
            entry({ id: 'r1', sourcePublicCode: 'AVE9MM', fromMergedAccount: true, previousLevel: null, newLevel: '3.5', calculatedAt: '2026-08-13T12:00:00' }),
          ]}
        />,
      )
      const marker = container.querySelector('circle')
      expect(marker).toBeInTheDocument()
      expect(marker).toHaveAttribute('fill', 'var(--chart-2)')
    })

    it('names each account in the legend with its role', () => {
      render(
        <BandHistoryChart
          today={TODAY}
          entries={[
            entry({ id: 's1', sourcePublicCode: 'QCST68', fromMergedAccount: false, previousLevel: '4.0', newLevel: '4.5', levelChanged: true, calculatedAt: '2026-08-20T12:00:00' }),
            entry({ id: 'r1', sourcePublicCode: '9S9PJS', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-07-26T12:00:00' }),
          ]}
        />,
      )
      // Real public codes, with the survivor unmistakable.
      expect(screen.getByText('QCST68')).toBeInTheDocument()
      expect(screen.getByText('current account')).toBeInTheDocument()
      expect(screen.getByText('9S9PJS')).toBeInTheDocument()
      expect(screen.getByText('merged in')).toBeInTheDocument()
    })

    it('handles a chain of three accounts', () => {
      const { container } = render(
        <BandHistoryChart
          today={TODAY}
          entries={[
            entry({ id: 'c', sourcePublicCode: '1FDXVB', fromMergedAccount: false, previousLevel: '4.0', newLevel: '4.5', levelChanged: true, calculatedAt: '2026-09-01T12:00:00' }),
            entry({ id: 'b2', sourcePublicCode: 'P2W8YG', fromMergedAccount: true, previousLevel: '3.5', newLevel: '4.0', levelChanged: true, calculatedAt: '2026-08-25T12:00:00' }),
            entry({ id: 'b1', sourcePublicCode: 'P2W8YG', fromMergedAccount: true, previousLevel: '3.5', newLevel: '3.5', calculatedAt: '2026-08-20T12:00:00' }),
            entry({ id: 'a2', sourcePublicCode: '6GNWA6', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-07-10T12:00:00' }),
            entry({ id: 'a1', sourcePublicCode: '6GNWA6', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.0', calculatedAt: '2026-07-01T12:00:00' }),
          ]}
        />,
      )
      // 6GNWA6 -> P2W8YG -> 1FDXVB, which exists in production: three trajectories, three lines.
      expect(paths(container)).toHaveLength(3)
      expect(strokes(container)).toEqual(['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)'])
    })

    it('falls back to a neutral label when an account code could not be resolved', () => {
      render(
        <BandHistoryChart
          today={TODAY}
          entries={[
            // sourcePublicCode is nullable: the server could not resolve the account. The legend still
            // has to name the line, or the reader sees a colour with no referent.
            entry({ id: 's1', sourcePublicCode: null, fromMergedAccount: false, previousLevel: '4.0', newLevel: '4.5', levelChanged: true, calculatedAt: '2026-08-20T12:00:00' }),
            entry({ id: 'r1', sourcePublicCode: '9S9PJS', fromMergedAccount: true, previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-07-26T12:00:00' }),
          ]}
        />,
      )
      expect(screen.getByText('this account')).toBeInTheDocument()
      expect(screen.getByText('9S9PJS')).toBeInTheDocument()
    })

    it('shows no legend for a single-account player', () => {
      render(
        <BandHistoryChart
          today={TODAY}
          entries={[entry({ sourcePublicCode: 'QCST68', fromMergedAccount: false, previousLevel: '3.0', newLevel: '3.5', levelChanged: true, calculatedAt: '2026-06-01T12:00:00' })]}
        />,
      )
      // Almost every player. Their card must look exactly as it did before #853.
      expect(screen.queryByText('current account')).not.toBeInTheDocument()
      expect(screen.getByText(/bands held over this period/)).toBeInTheDocument()
    })
  })
})
