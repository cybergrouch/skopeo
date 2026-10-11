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

const markerX = (container: HTMLElement) =>
  Number(container.querySelector('[data-testid="momentum-selected"]')?.getAttribute('x1'))

describe('ReplayMomentumChart', () => {
  it('draws the rolling share and the point difference across the whole match, with set boundaries', () => {
    const { container } = renderChart()
    // A start at 0-0 plus one segment per point played (12).
    const share = container.querySelector('[data-testid="momentum-share"]')?.getAttribute('d') ?? ''
    expect(share.split('L')).toHaveLength(13)
    expect(container.querySelector('[data-testid="momentum-difference"]')).not.toBeNull()
    const boundaries = container.querySelectorAll('[data-testid="momentum-set-boundary"]')
    expect(Array.from(boundaries).map((b) => b.textContent)).toEqual(['Set 2', 'Set 3'])
    expect(screen.getByText("Ana's share of the last 8 points")).toBeInTheDocument()
  })

  it('selects a point from the chart', () => {
    const { container, onSelect } = renderChart()
    const hits = container.querySelectorAll('[data-testid="momentum-hit"]')
    expect(hits).toHaveLength(12)
    fireEvent.click(hits[9])
    expect(onSelect).toHaveBeenCalledWith(10)
  })

  it('moves its marker with the selection, and reads it out', () => {
    const { container, rerender } = renderChart(REPLAY_START)
    const atStart = markerX(container)
    expect(screen.getByText('Start of the match: no points played yet.')).toBeInTheDocument()

    rerender(<ReplayMomentumChart replay={RETIRED_REPLAY} selected={7} onSelect={vi.fn()} team1Name="Ana" team2Name="Bob" />)
    expect(markerX(container)).toBeGreaterThan(atStart)
    expect(
      screen.getByText('After point 8 (set 1): Ana won 7 of the last 8 (88%), Ana ahead by 6 overall.'),
    ).toBeInTheDocument()
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
