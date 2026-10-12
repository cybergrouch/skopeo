import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { ReplaySwimlaneChart } from './ReplaySwimlaneChart'
import { REPLAY_START } from './replayFeatures'
import { OLD_FORMAT_REPLAY, RETIRED_REPLAY, replayOf } from '@/test/replayFixtures'

const steps = (container: HTMLElement) => Array.from(container.querySelectorAll('[data-testid="swimlane-step"]'))
const stepAt = (container: HTMLElement, index: number) =>
  container.querySelector(`[data-testid="swimlane-step"][data-index="${index}"]`) as Element

function renderChart(selected = REPLAY_START, onSelect = vi.fn(), replay = RETIRED_REPLAY) {
  const view = render(
    <ReplaySwimlaneChart replay={replay} selected={selected} onSelect={onSelect} team1Name="Ana" team2Name="Bob" />,
  )
  return { ...view, onSelect }
}

describe('ReplaySwimlaneChart', () => {
  it('draws games larger than points, and a break as a different shape from a hold', () => {
    const { container } = renderChart()
    expect(stepAt(container, 0).querySelector('circle')?.getAttribute('r')).toBe('3')
    expect(stepAt(container, 4).querySelector('circle')?.getAttribute('r')).toBe('6')
    expect(stepAt(container, 8).getAttribute('data-mark')).toBe('break')
    expect(stepAt(container, 8).querySelector('circle')).toBeNull()
    expect(stepAt(container, 8).querySelector('path')).not.toBeNull()
    expect(stepAt(container, 9).getAttribute('data-mark')).toBe('awarded')
    expect(screen.getByText('Break of serve')).toBeInTheDocument()
  })

  it('offers no break marker for an older replay without break data', () => {
    const { container } = renderChart(REPLAY_START, vi.fn(), OLD_FORMAT_REPLAY)
    expect(screen.queryByText('Break of serve')).toBeNull()
    expect(within(screen.getByRole('list', { name: 'Legend' })).getByText('Game')).toBeInTheDocument()
    expect(stepAt(container, 8).getAttribute('data-mark')).toBe('game')
  })

  it('selects a point when its mark is clicked', () => {
    const { container, onSelect } = renderChart()
    fireEvent.click(stepAt(container, 8))
    expect(onSelect).toHaveBeenCalledWith(8)
  })

  it('highlights the selected point, and the start when nothing has been played', () => {
    const { container, rerender } = renderChart(3)
    expect(stepAt(container, 3).getAttribute('data-selected')).toBe('true')
    expect(container.querySelectorAll('[data-selected]')).toHaveLength(1)

    rerender(<ReplaySwimlaneChart replay={RETIRED_REPLAY} selected={REPLAY_START} onSelect={vi.fn()} />)
    expect(steps(container).some((s) => s.hasAttribute('data-selected'))).toBe(false)
    expect(container.querySelector('[data-testid="swimlane-start"]')?.getAttribute('data-selected')).toBe('true')
  })

  it('highlights the last point when the ending is selected', () => {
    const { container } = renderChart(13)
    expect(stepAt(container, 12).getAttribute('data-selected')).toBe('true')
  })

  it('has a text equivalent: a button per point that selects it', () => {
    const { onSelect } = renderChart(4)
    const buttons = within(screen.getByRole('region', { name: 'Set 1 points' })).getAllByRole('button')
    expect(buttons).toHaveLength(10)
    expect(buttons[4]).toHaveTextContent('Game — Ana (game, held serve)')
    expect(buttons[4]).toHaveAttribute('aria-current', 'true')
    expect(buttons[8]).toHaveTextContent('Game — Ana (game, broke serve)')
    expect(buttons[9]).toHaveTextContent('Set — Ana (awarded by the umpire)')
    fireEvent.click(buttons[2])
    expect(onSelect).toHaveBeenCalledWith(2)
    const set2 = within(screen.getByRole('region', { name: 'Set 2 points' })).getAllByRole('button')
    expect(set2[0]).toHaveTextContent('0-1 — Bob (point, tiebreak)')
  })

  it('says so instead of drawing empty lanes', () => {
    renderChart(REPLAY_START, vi.fn(), replayOf([]))
    expect(screen.getByText('No points were recorded for this match.')).toBeInTheDocument()
  })

  it('falls back to generic side names', () => {
    render(<ReplaySwimlaneChart replay={RETIRED_REPLAY} selected={REPLAY_START} onSelect={vi.fn()} />)
    expect(screen.getAllByText('Team 1').length).toBeGreaterThan(0)
  })
})

// Below `md` the lanes run top to bottom (#1156), so a phone never scrolls sideways.
describe('ReplaySwimlaneChart, vertical at every width (#1156)', () => {
  const setToggle = (name: string) => screen.getByRole('button', { name })
  const rules = (container: Element) =>
    Array.from(container.querySelectorAll('[data-testid="swimlane-game-rule"]')).map((rule) => rule.textContent)

  it('lays each set out vertically, with no horizontal scrolling', () => {
    const { container } = renderChart(13)
    expect(container.querySelector('[data-orientation="vertical"]')).not.toBeNull()
    expect(container.querySelector('.overflow-x-auto')).toBeNull()
    expect(container.querySelector('svg[data-testid="swimlane-vertical"]')?.getAttribute('class')).toContain('w-full')
  })

  it('opens only the set holding the selected point, and opens the set the selection moves into', () => {
    const { container, rerender } = renderChart(13)
    expect(setToggle('Set 1 · 6-0')).toHaveAttribute('aria-expanded', 'false')
    expect(setToggle('Set 2 · 6-7 (5-7)')).toHaveAttribute('aria-expanded', 'false')
    expect(setToggle('Set 3 · 0-0')).toHaveAttribute('aria-expanded', 'true')
    expect(steps(container).map((s) => s.getAttribute('data-index'))).toEqual(['12'])

    rerender(<ReplaySwimlaneChart replay={RETIRED_REPLAY} selected={2} onSelect={vi.fn()} team1Name="Ana" team2Name="Bob" />)
    expect(setToggle('Set 1 · 6-0')).toHaveAttribute('aria-expanded', 'true')
    expect(setToggle('Set 3 · 0-0')).toHaveAttribute('aria-expanded', 'false')
    expect(steps(container)).toHaveLength(10)
  })

  it('opens and closes a set from its header', () => {
    const { container } = renderChart(13)
    fireEvent.click(setToggle('Set 2 · 6-7 (5-7)'))
    expect(setToggle('Set 2 · 6-7 (5-7)')).toHaveAttribute('aria-expanded', 'true')
    expect(stepAt(container, 10)).not.toBeNull()
    fireEvent.click(setToggle('Set 2 · 6-7 (5-7)'))
    expect(stepAt(container, 10)).toBeNull()
  })

  it("puts each point in its winner's column, one row per point, labelled by the score it produced", () => {
    const { container } = renderChart(0)
    const rowY = (index: number) => Number(stepAt(container, index).querySelector('circle')?.getAttribute('cy'))
    const colX = (index: number) => Number(stepAt(container, index).querySelector('circle')?.getAttribute('cx'))
    expect(stepAt(container, 0).getAttribute('data-side')).toBe('TEAM1')
    expect(stepAt(container, 1).getAttribute('data-side')).toBe('TEAM2')
    expect(colX(0)).toBeLessThan(colX(1))
    expect(colX(0)).toBe(colX(2))
    // Time runs downward.
    expect(rowY(0)).toBeLessThan(rowY(1))
    expect(rowY(1)).toBeLessThan(rowY(2))
    expect(stepAt(container, 1).querySelector('text')?.textContent).toBe('15-15')
    expect(stepAt(container, 4).querySelector('text')?.textContent).toBe('Game')
  })

  it("connects a set's marks with one line in play order, from the 0-0 start", () => {
    renderChart(0)
    const set1 = screen.getByTestId('swimlane-set-1')
    const points = (set1.querySelector('[data-testid="swimlane-path"]') as Element)
      .getAttribute('points')!
      .split(' ')
      .map((pair) => pair.split(',').map(Number))
    const marks = steps(set1 as HTMLElement)

    // The start, then one vertex per mark: a streak stays in one column, a swing crosses to the other.
    expect(points).toHaveLength(marks.length + 1)
    const centre = points[0][0]
    marks.forEach((mark, i) => {
      const x = points[i + 1][0]
      expect(mark.getAttribute('data-side') === 'TEAM1' ? x < centre : x > centre).toBe(true)
    })
    points.slice(1).forEach(([, y], i) => expect(y).toBeGreaterThan(points[i][1]))
  })

  it('ends each game with a rule carrying the score after it, and keeps the break marker', () => {
    const { container } = renderChart(0)
    expect(rules(screen.getByTestId('swimlane-set-1'))).toEqual(['1-0', '2-0', '6-0'])
    expect(stepAt(container, 8).getAttribute('data-mark')).toBe('break')
    expect(stepAt(container, 8).querySelector('path')).not.toBeNull()
    expect(stepAt(container, 9).getAttribute('data-mark')).toBe('awarded')
  })

  it('marks where a tiebreak starts, and closes it with the set score', () => {
    renderChart(10)
    expect(rules(screen.getByTestId('swimlane-set-2'))).toEqual(['Tiebreak', '6-7 (5-7)'])
  })

  it('selects a point or the set start from the drawing, and highlights the selection', () => {
    const { container, onSelect } = renderChart(3)
    expect(stepAt(container, 3).getAttribute('data-selected')).toBe('true')
    fireEvent.click(stepAt(container, 7))
    fireEvent.click(container.querySelector('[data-testid="swimlane-start"]') as Element)
    expect(onSelect.mock.calls).toEqual([[7], [REPLAY_START]])
  })

  it('keeps the list equivalent', () => {
    renderChart(13)
    expect(within(screen.getByRole('region', { name: 'Set 1 points' })).getAllByRole('button')).toHaveLength(10)
  })
})
