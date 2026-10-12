import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { ReplayMomentumChart } from './ReplayMomentumChart'
import { REPLAY_START } from './replayFeatures'
import { RETIRED_REPLAY, replayOf, replayPoint } from '@/test/replayFixtures'

function renderChart(selected = REPLAY_START, onSelect = vi.fn(), replay = RETIRED_REPLAY) {
  const view = render(
    <ReplayMomentumChart replay={replay} selected={selected} onSelect={onSelect} team1Name="Ana" team2Name="Bob" />,
  )
  return { ...view, onSelect }
}

describe('ReplayMomentumChart', () => {
  it('selects a point from the chart', () => {
    const { container, onSelect } = renderChart()
    const hits = container.querySelectorAll('[data-testid="momentum-hit"]')
    expect(hits).toHaveLength(12)
    fireEvent.click(hits[9])
    expect(onSelect).toHaveBeenCalledWith(10)
  })

  it('reads a level match and a trailing team 1', () => {
    const { rerender } = renderChart(1)
    expect(screen.getByText(/level on points overall/)).toBeInTheDocument()
    const bobAhead = replayOf([replayPoint({ wonBy: 'TEAM2' })])
    rerender(<ReplayMomentumChart replay={bobAhead} selected={0} onSelect={vi.fn()} team1Name="Ana" team2Name="Bob" />)
    expect(screen.getByText(/Bob ahead by 1 overall/)).toBeInTheDocument()
  })

  it('has a table equivalent whose rows select their point', () => {
    const { onSelect } = renderChart(4)
    const rows = within(screen.getByRole('table')).getAllByRole('row')
    expect(rows).toHaveLength(13)
    expect(rows[5]).toHaveAttribute('aria-current', 'true')
    expect(rows[5]).toHaveTextContent('51Ana4 of 5 (80%)+3')
    fireEvent.click(within(rows[10]).getByRole('button', { name: '10' }))
    expect(onSelect).toHaveBeenCalledWith(10)
  })

  it('says so when no point was played', () => {
    renderChart(REPLAY_START, vi.fn(), replayOf([replayPoint({ wonBy: 'TEAM1', kind: 'GAME', gameWon: true })]))
    expect(screen.getByText('No points were played in this match.')).toBeInTheDocument()
  })

  it('falls back to generic side names', () => {
    render(<ReplayMomentumChart replay={RETIRED_REPLAY} selected={REPLAY_START} onSelect={vi.fn()} />)
    expect(screen.getByText('Team 1 on top')).toBeInTheDocument()
  })
})

// Below `md` time runs top to bottom (#1156): the chart grows downward and fits the screen's width.
describe('ReplayMomentumChart, vertical at every width (#1156)', () => {
  /** The [x, y] points of a path made of M/L commands. */
  const pointsOf = (d: string) =>
    d
      .split(/[ML]/)
      .map((part) => part.trim())
      .filter(Boolean)
      .map((part) => part.split(' ').map(Number))
  const svg = (container: HTMLElement) => container.querySelector('svg[data-orientation="vertical"]') as SVGElement

  it('runs time downward, with team 1 on top drawn to the left of the centre line', () => {
    const { container } = renderChart()
    expect(svg(container).getAttribute('class')).toContain('w-full')
    const share = pointsOf(container.querySelector('[data-testid="momentum-share"]')?.getAttribute('d') ?? '')
    // The 0-0 start plus one point per point played, each lower than the last.
    expect(share).toHaveLength(13)
    const ys = share.map(([, y]) => y)
    expect(ys).toEqual([...ys].sort((a, b) => a - b))
    // After point 8 Ana had won 7 of the last 8: well left of the start's centre.
    expect(share[8][0]).toBeLessThan(share[0][0])
    // The difference runs on the same vertical axis.
    const diff = pointsOf(container.querySelector('[data-testid="momentum-difference"]')?.getAttribute('d') ?? '')
    expect(diff.map(([, y]) => y)).toEqual(ys)
    expect(diff[8][0]).toBeLessThan(diff[0][0])
  })

  it('grows taller with the match, not wider', () => {
    const { container, rerender } = renderChart()
    const [, , width, height] = (svg(container).getAttribute('viewBox') ?? '').split(' ').map(Number)
    const short = replayOf([replayPoint({ wonBy: 'TEAM1' }), replayPoint({ wonBy: 'TEAM2' })])
    rerender(<ReplayMomentumChart replay={short} selected={REPLAY_START} onSelect={vi.fn()} />)
    const [, , shortWidth, shortHeight] = (svg(container).getAttribute('viewBox') ?? '').split(' ').map(Number)
    expect(shortWidth).toBe(width)
    expect(shortHeight).toBeLessThan(height)
  })

  it('marks set boundaries with horizontal dashed rules', () => {
    const { container } = renderChart()
    const boundaries = Array.from(container.querySelectorAll('[data-testid="momentum-set-boundary"]'))
    expect(boundaries.map((b) => b.textContent)).toEqual(['Set 2', 'Set 3'])
    const line = boundaries[0].querySelector('line') as SVGLineElement
    expect(line.getAttribute('y1')).toBe(line.getAttribute('y2'))
    expect(line.getAttribute('stroke-dasharray')).toBe('3 2')
  })

  it('moves its marker down with the selection, and selects a point from its row', () => {
    const { container, rerender, onSelect } = renderChart(REPLAY_START)
    const markerY = () => Number(container.querySelector('[data-testid="momentum-selected"]')?.getAttribute('y1'))
    const atStart = markerY()
    rerender(<ReplayMomentumChart replay={RETIRED_REPLAY} selected={7} onSelect={onSelect} team1Name="Ana" team2Name="Bob" />)
    expect(markerY()).toBeGreaterThan(atStart)

    const hits = container.querySelectorAll('[data-testid="momentum-hit"]')
    expect(hits).toHaveLength(12)
    fireEvent.click(hits[9])
    expect(onSelect).toHaveBeenCalledWith(10)
  })

  it('keeps the readout and the table equivalent', () => {
    renderChart(7)
    expect(screen.getByText(/^After point 8 \(set 1\)/)).toBeInTheDocument()
    expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(13)
  })
})
