import { describe, it, expect } from 'vitest'
import {
  MOMENTUM_WINDOW,
  formatSet,
  momentumOf,
  sampleAtOrBefore,
  scoreLabel,
  setBoundariesOf,
  setLanesOf,
  signed,
} from './replayCharts'
import { REPLAY_START } from './replayFeatures'
import { REPLAY, replayOf } from './replayChartsFixture'

describe('scoreLabel', () => {
  it('labels each step by the score it produced, team 1 first', () => {
    expect(REPLAY.points.slice(0, 5).map(scoreLabel)).toEqual(['15-0', '15-15', '30-15', '40-15', 'Game'])
  })

  it('names deuce, keeps advantage as a score, and labels tiebreak points by their count', () => {
    expect(scoreLabel(REPLAY.points[10])).toBe('Deuce')
    expect(scoreLabel(REPLAY.points[11])).toBe('40-AD')
    expect(scoreLabel(REPLAY.points[15])).toBe('0-1')
  })

  it('labels awarded games and sets, set-winning points and the ending', () => {
    expect(scoreLabel(REPLAY.points[13])).toBe('Game')
    expect(scoreLabel(REPLAY.points[14])).toBe('Set')
    expect(scoreLabel(REPLAY.points[16])).toBe('Set')
    expect(scoreLabel(REPLAY.points[17])).toBe('End')
  })

  it('does not call 40-40 inside a tiebreak deuce', () => {
    const [point] = replayOf([{ wonBy: 'TEAM1', tiebreak: true, score: { pointsTeam1: '40', pointsTeam2: '40' } }]).points
    expect(scoreLabel(point)).toBe('40-40')
  })
})

describe('setLanesOf', () => {
  const sets = setLanesOf(REPLAY, true)

  it('gives each set its own lanes, starting where the previous set closed', () => {
    expect(sets.map((s) => s.setNumber)).toEqual([1, 2])
    expect(sets[0].startIndex).toBe(REPLAY_START)
    expect(sets[1].startIndex).toBe(14)
  })

  it('places every drawn step once, in order, in its winner\'s lane — but never the ending', () => {
    expect(sets.flatMap((s) => s.steps.map((step) => step.index))).toEqual([...Array(17).keys()])
    expect(sets[0].steps.map((s) => s.column)).toEqual([...Array(15).keys()].map((i) => i + 1))
    expect(sets[0].steps[1].side).toBe('TEAM2')
    expect(sets[1].steps.every((s) => s.tiebreak)).toBe(true)
  })

  it('distinguishes points, holds, breaks and umpire awards', () => {
    const marks = Object.fromEntries(sets.flatMap((s) => s.steps).map((s) => [s.index, s.mark]))
    expect(marks[0]).toBe('point')
    expect(marks[4]).toBe('hold')
    expect(marks[12]).toBe('break')
    expect(marks[13]).toBe('awarded')
    expect(marks[14]).toBe('awarded')
  })

  it('draws a plain game marker when the replay carries no break data', () => {
    const marks = setLanesOf(REPLAY, false).flatMap((s) => s.steps).map((s) => s.mark)
    expect(marks).not.toContain('hold')
    expect(marks).not.toContain('break')
    expect(marks.filter((m) => m === 'game')).toHaveLength(3)
  })

  it('reports each set\'s final games, with the tiebreak', () => {
    expect(sets.map((s) => s.finalScore)).toEqual(['6-1', '6-7 (0-7)'])
  })

  it('reports the games as they stood for a set the match ended inside', () => {
    const replay = replayOf([
      { wonBy: 'TEAM1', gameWon: true, score: { gamesTeam1: 1 } },
      { wonBy: 'TEAM1', score: { gamesTeam1: 1, pointsTeam1: '15' } },
      { wonBy: 'TEAM2', kind: 'END', outcome: 'RETIRED', score: { gamesTeam1: 1, pointsTeam1: '15' } },
    ])
    expect(setLanesOf(replay, false).map((s) => s.finalScore)).toEqual(['1-0'])
  })

  it('has nothing to lay out for a replay without steps', () => {
    expect(setLanesOf(replayOf([]), true)).toEqual([])
  })
})

describe('momentumOf', () => {
  const samples = momentumOf(REPLAY)

  it('counts only points played, not awards or the ending', () => {
    expect(samples.map((s) => s.index)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16])
    expect(samples.map((s) => s.ordinal)).toEqual([...Array(15).keys()].map((i) => i + 1))
  })

  it('takes the share over a window that grows to MOMENTUM_WINDOW, then rolls', () => {
    expect(MOMENTUM_WINDOW).toBe(8)
    expect(samples[0]).toMatchObject({ team1InWindow: 1, windowSize: 1, team1Share: 1 })
    expect(samples[1]).toMatchObject({ team1InWindow: 1, windowSize: 2, team1Share: 0.5 })
    // Points 6–13 (positions 5–12): T2 T2 T1 T1 T1 T2 T2 T2 — three of eight to team 1.
    expect(samples[12]).toMatchObject({ team1InWindow: 3, windowSize: 8, team1Share: 3 / 8 })
  })

  it('runs the window across the set boundary', () => {
    // Positions 7–12 then 15, 16: T1 T1 T1 T2 T2 T2 T2 T2.
    expect(samples[14]).toMatchObject({ setNumber: 2, team1InWindow: 3, windowSize: 8 })
  })

  it('keeps a running point difference, +1 for team 1 and -1 for team 2', () => {
    expect(samples.slice(0, 5).map((s) => s.difference)).toEqual([1, 0, 1, 2, 3])
    expect(samples[samples.length - 1].difference).toBe(-1)
  })

  it('honours a custom window', () => {
    expect(momentumOf(REPLAY, 2)[4]).toMatchObject({ team1InWindow: 2, windowSize: 2 })
  })
})

describe('setBoundariesOf', () => {
  it('marks where each new set begins', () => {
    expect(setBoundariesOf(momentumOf(REPLAY))).toEqual([{ setNumber: 2, afterOrdinal: 13 }])
  })
})

describe('sampleAtOrBefore', () => {
  const samples = momentumOf(REPLAY)

  it('finds the drawn sample a selection lands on', () => {
    expect(sampleAtOrBefore(samples, REPLAY_START)).toBeNull()
    expect(sampleAtOrBefore(samples, 4)?.index).toBe(4)
    // An awarded game and the ending show the position they leave the match in.
    expect(sampleAtOrBefore(samples, 13)?.index).toBe(12)
    expect(sampleAtOrBefore(samples, 17)?.index).toBe(16)
  })
})

describe('formatting', () => {
  it('formats sets and signed differences', () => {
    expect(formatSet({ team1: 6, team2: 4 })).toBe('6-4')
    expect(formatSet({ team1: 7, team2: 6, tiebreakTeam1: 7, tiebreakTeam2: 5 })).toBe('7-6 (7-5)')
    expect(signed(3)).toBe('+3')
    expect(signed(-2)).toBe('-2')
    expect(signed(0)).toBe('0')
  })
})
