import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { ReplayMomentumChart } from './ReplayMomentumChart'
import { REPLAY_START } from './replayFeatures'
import { REPLAY, replayOf } from './replayChartsFixture'

function renderChart(selected = REPLAY_START, onSelect = vi.fn(), replay = REPLAY) {
  const view = render(
    <ReplayMomentumChart replay={replay} selected={selected} onSelect={onSelect} team1Name="Ana" team2Name="Bea" />,
  )
  return { ...view, onSelect }
}

const markerX = (container: HTMLElement) =>
  Number(container.querySelector('[data-testid="momentum-selected"]')?.getAttribute('x1'))

describe('ReplayMomentumChart', () => {
  it('draws the rolling share and the point difference across the whole match, with set boundaries', () => {
    const { container } = renderChart()
    // A start at 0-0 plus one segment per point played (15).
    const share = container.querySelector('[data-testid="momentum-share"]')?.getAttribute('d') ?? ''
    expect(share.split('L')).toHaveLength(16)
    expect(container.querySelector('[data-testid="momentum-difference"]')).not.toBeNull()
    const boundaries = container.querySelectorAll('[data-testid="momentum-set-boundary"]')
    expect(boundaries).toHaveLength(1)
    expect(boundaries[0]).toHaveTextContent('Set 2')
    expect(screen.getByText("Ana's share of the last 8 points")).toBeInTheDocument()
  })

  it('selects a point from the chart', () => {
    const { container, onSelect } = renderChart()
    const hits = container.querySelectorAll('[data-testid="momentum-hit"]')
    expect(hits).toHaveLength(15)
    fireEvent.click(hits[13])
    expect(onSelect).toHaveBeenCalledWith(15)
  })

  it('moves its marker with the selection, and reads it out', () => {
    const { container, rerender } = renderChart(REPLAY_START)
    const atStart = markerX(container)
    expect(screen.getByText('Start of the match: no points played yet.')).toBeInTheDocument()

    rerender(<ReplayMomentumChart replay={REPLAY} selected={12} onSelect={vi.fn()} team1Name="Ana" team2Name="Bea" />)
    expect(markerX(container)).toBeGreaterThan(atStart)
    expect(
      screen.getByText('After point 13 (set 1): Ana won 3 of the last 8 (38%), Ana ahead by 1 overall.'),
    ).toBeInTheDocument()
  })

  it('reads a level match and a trailing team 1', () => {
    const { rerender } = renderChart(1)
    expect(screen.getByText(/level on points overall/)).toBeInTheDocument()
    rerender(<ReplayMomentumChart replay={REPLAY} selected={16} onSelect={vi.fn()} team1Name="Ana" team2Name="Bea" />)
    expect(screen.getByText(/Bea ahead by 1 overall/)).toBeInTheDocument()
  })

  it('has a table equivalent whose rows select their point', () => {
    const { onSelect } = renderChart(4)
    const rows = within(screen.getByRole('table')).getAllByRole('row')
    expect(rows).toHaveLength(16)
    expect(rows[5]).toHaveAttribute('aria-current', 'true')
    expect(rows[5]).toHaveTextContent('51Ana4 of 5 (80%)+3')
    fireEvent.click(within(rows[14]).getByRole('button', { name: '14' }))
    expect(onSelect).toHaveBeenCalledWith(15)
  })

  it('says so when no point was played', () => {
    const replay = replayOf([{ wonBy: 'TEAM1', kind: 'GAME', gameWon: true, score: { gamesTeam1: 1 } }])
    renderChart(REPLAY_START, vi.fn(), replay)
    expect(screen.getByText('No points were played in this match.')).toBeInTheDocument()
  })

  it('falls back to generic side names', () => {
    render(<ReplayMomentumChart replay={REPLAY} selected={REPLAY_START} onSelect={vi.fn()} />)
    expect(screen.getByText('Team 1 on top')).toBeInTheDocument()
  })
})
