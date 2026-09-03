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
})
