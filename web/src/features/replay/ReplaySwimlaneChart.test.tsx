import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { ReplaySwimlaneChart } from './ReplaySwimlaneChart'
import { REPLAY_START } from './replayFeatures'
import { REPLAY, replayOf } from './replayChartsFixture'

const steps = (container: HTMLElement) => Array.from(container.querySelectorAll('[data-testid="swimlane-step"]'))
const stepAt = (container: HTMLElement, index: number) =>
  container.querySelector(`[data-testid="swimlane-step"][data-index="${index}"]`) as Element

function renderChart(selected = REPLAY_START, onSelect = vi.fn(), replay = REPLAY) {
  const view = render(
    <ReplaySwimlaneChart replay={replay} selected={selected} onSelect={onSelect} team1Name="Ana" team2Name="Bea" />,
  )
  return { ...view, onSelect }
}

describe('ReplaySwimlaneChart', () => {
  it('draws one pair of lanes per set, with each set\'s final score outside the scroll area', () => {
    const { container } = renderChart()
    const set1 = screen.getByTestId('swimlane-set-1')
    const set2 = screen.getByTestId('swimlane-set-2')
    expect(within(set1).getByText('6-1')).toBeInTheDocument()
    expect(within(set2).getByText('6-7 (0-7)')).toBeInTheDocument()
    // The caption is a sibling of the scrolling container, never inside it.
    expect(set1.querySelector('.overflow-x-auto figcaption')).toBeNull()
    expect(within(set1).getByText('Ana')).toBeInTheDocument()
    // Every step but the ending, once each.
    expect(steps(container)).toHaveLength(17)
  })

  it('puts each point in its winner\'s lane, labelled by the score it produced', () => {
    const { container } = renderChart()
    expect(stepAt(container, 0).getAttribute('data-side')).toBe('TEAM1')
    expect(stepAt(container, 1).getAttribute('data-side')).toBe('TEAM2')
    expect(stepAt(container, 1).querySelector('text')?.textContent).toBe('15-15')
    expect(stepAt(container, 10).querySelector('text')?.textContent).toBe('Deuce')
    expect(stepAt(container, 4).querySelector('text')?.textContent).toBe('Game')
    expect(container.querySelectorAll('[data-testid="swimlane-start"]')).toHaveLength(2)
  })

  it('draws games larger than points, and a break as a different shape from a hold', () => {
    const { container } = renderChart()
    expect(stepAt(container, 0).querySelector('circle')?.getAttribute('r')).toBe('3')
    expect(stepAt(container, 4).querySelector('circle')?.getAttribute('r')).toBe('6')
    expect(stepAt(container, 12).getAttribute('data-mark')).toBe('break')
    expect(stepAt(container, 12).querySelector('circle')).toBeNull()
    expect(stepAt(container, 12).querySelector('path')).not.toBeNull()
    expect(stepAt(container, 13).getAttribute('data-mark')).toBe('awarded')
    expect(screen.getByText('Break of serve')).toBeInTheDocument()
  })

  it('offers no break marker when the replay has no break data', () => {
    const replay = replayOf([{ wonBy: 'TEAM1', gameWon: true, score: { gamesTeam1: 1 } }])
    const { container } = renderChart(REPLAY_START, vi.fn(), replay)
    expect(screen.queryByText('Break of serve')).toBeNull()
    expect(within(screen.getByRole('list', { name: 'Legend' })).getByText('Game')).toBeInTheDocument()
    expect(stepAt(container, 0).getAttribute('data-mark')).toBe('game')
  })

  it('selects a point when its mark is clicked', () => {
    const { container, onSelect } = renderChart()
    fireEvent.click(stepAt(container, 12))
    expect(onSelect).toHaveBeenCalledWith(12)
  })

  it('selects a set\'s 0-0 start', () => {
    const { container, onSelect } = renderChart()
    const starts = container.querySelectorAll('[data-testid="swimlane-start"]')
    fireEvent.click(starts[0])
    fireEvent.click(starts[1])
    expect(onSelect.mock.calls).toEqual([[REPLAY_START], [14]])
  })

  it('highlights the selected point, and the start when nothing has been played', () => {
    const { container, rerender } = renderChart(3)
    expect(stepAt(container, 3).getAttribute('data-selected')).toBe('true')
    expect(container.querySelectorAll('[data-selected]')).toHaveLength(1)

    rerender(<ReplaySwimlaneChart replay={REPLAY} selected={REPLAY_START} onSelect={vi.fn()} />)
    expect(steps(container).some((s) => s.hasAttribute('data-selected'))).toBe(false)
    expect(container.querySelector('[data-testid="swimlane-start"]')?.getAttribute('data-selected')).toBe('true')
  })

  it('highlights the last point when the ending is selected', () => {
    const { container } = renderChart(17)
    expect(stepAt(container, 16).getAttribute('data-selected')).toBe('true')
  })

  it('has a text equivalent: a button per point that selects it', () => {
    const { onSelect } = renderChart(4)
    const list = screen.getByRole('region', { name: 'Set 1 points' })
    const buttons = within(list).getAllByRole('button')
    expect(buttons).toHaveLength(15)
    expect(buttons[4]).toHaveTextContent('Game — Ana (game, held serve)')
    expect(buttons[4]).toHaveAttribute('aria-current', 'true')
    expect(buttons[12]).toHaveTextContent('Game — Bea (game, broke serve)')
    fireEvent.click(buttons[2])
    expect(onSelect).toHaveBeenCalledWith(2)
    const set2 = within(screen.getByRole('region', { name: 'Set 2 points' })).getAllByRole('button')
    expect(set2[0]).toHaveTextContent('0-1 — Bea (point, tiebreak)')
  })

  it('says so instead of drawing empty lanes', () => {
    renderChart(REPLAY_START, vi.fn(), replayOf([]))
    expect(screen.getByText('No points were recorded for this match.')).toBeInTheDocument()
  })

  it('falls back to generic side names', () => {
    render(<ReplaySwimlaneChart replay={REPLAY} selected={REPLAY_START} onSelect={vi.fn()} />)
    expect(screen.getAllByText('Team 1').length).toBeGreaterThan(0)
  })
})
