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
import { RETIRED_REPLAY, replayOf, replayPoint, replayScore } from '@/test/replayFixtures'

/**
 * RETIRED_REPLAY by position: set 1 is 0–4 (Ana holds), 5–8 (Ana breaks), 9 (set awarded); set 2 is a
 * two-point tiebreak 10–11 won by Bob; set 3 is one point (12) and the retirement (13).
 */
const points = RETIRED_REPLAY.points

describe('scoreLabel', () => {
  it('labels each step by the score it produced, team 1 first', () => {
    expect(points.slice(0, 5).map(scoreLabel)).toEqual(['15-0', '15-15', '30-15', '40-15', 'Game'])
  })

  it('labels tiebreak points by their count, and set-winning steps and the ending by name', () => {
    expect(scoreLabel(points[10])).toBe('0-1')
    expect(scoreLabel(points[9])).toBe('Set')
    expect(scoreLabel(points[11])).toBe('Set')
    expect(scoreLabel(points[13])).toBe('End')
  })

  it('names deuce, keeps advantage as a score, and labels an awarded game', () => {
    const deuce = replayPoint({ wonBy: 'TEAM2', score: replayScore({ pointsTeam1: '40', pointsTeam2: '40' }) })
    const advantage = replayPoint({ wonBy: 'TEAM2', score: replayScore({ pointsTeam1: '40', pointsTeam2: 'AD' }) })
    expect(scoreLabel(deuce)).toBe('Deuce')
    expect(scoreLabel(advantage)).toBe('40-AD')
    expect(scoreLabel(replayPoint({ wonBy: 'TEAM1', kind: 'GAME', gameWon: true }))).toBe('Game')
  })

  it('does not call 40-40 inside a tiebreak deuce', () => {
    const point = replayPoint({ wonBy: 'TEAM1', tiebreak: true, score: replayScore({ pointsTeam1: '40', pointsTeam2: '40' }) })
    expect(scoreLabel(point)).toBe('40-40')
  })
})

describe('setLanesOf', () => {
  const sets = setLanesOf(RETIRED_REPLAY, true)

  it('gives each set its own lanes, starting where the previous set closed', () => {
    expect(sets.map((s) => s.setNumber)).toEqual([1, 2, 3])
    expect(sets.map((s) => s.startIndex)).toEqual([REPLAY_START, 9, 11])
  })

  it("places every drawn step once, in order, in its winner's lane — but never the ending", () => {
    expect(sets.flatMap((s) => s.steps.map((step) => step.index))).toEqual([...Array(13).keys()])
    expect(sets[0].steps.map((s) => s.column)).toEqual([...Array(10).keys()].map((i) => i + 1))
    expect(sets[0].steps[1].side).toBe('TEAM2')
    expect(sets[1].steps.every((s) => s.tiebreak)).toBe(true)
  })

  it('distinguishes points, holds, breaks and umpire awards', () => {
    const marks = Object.fromEntries(sets.flatMap((s) => s.steps).map((s) => [s.index, s.mark]))
    expect(marks[0]).toBe('point')
    expect(marks[4]).toBe('hold')
    expect(marks[8]).toBe('break')
    expect(marks[9]).toBe('awarded')
  })

  it('draws a plain game marker when the replay carries no break data', () => {
    const marks = setLanesOf(RETIRED_REPLAY, false).flatMap((s) => s.steps).map((s) => s.mark)
    expect(marks).not.toContain('hold')
    expect(marks).not.toContain('break')
    expect(marks.filter((m) => m === 'game')).toHaveLength(3)
  })

  it("reports each set's final games with the tiebreak, and the games as they stood for the set the match ended in", () => {
    expect(sets.map((s) => s.finalScore)).toEqual(['6-0', '6-7 (5-7)', '0-0'])
  })

  it('reads a missing tiebreak flag as an ordinary point', () => {
    const point = replayPoint({ wonBy: 'TEAM1' })
    delete point.tiebreak
    expect(setLanesOf(replayOf([point]), false)[0].steps[0].tiebreak).toBe(false)
  })

  it('has nothing to lay out for a replay without steps', () => {
    expect(setLanesOf(replayOf([]), true)).toEqual([])
  })
})

describe('momentumOf', () => {
  const samples = momentumOf(RETIRED_REPLAY)

  it('counts only points played, not awards or the ending', () => {
    expect(samples.map((s) => s.index)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12])
    expect(samples.map((s) => s.ordinal)).toEqual([...Array(12).keys()].map((i) => i + 1))
  })

  it('takes the share over a full window, counting the unplayed rest as even until it fills, then rolls', () => {
    expect(MOMENTUM_WINDOW).toBe(8)
    // One point won is not total domination: (1 + 3.5) / 8, not 1 of 1 (#1156).
    expect(samples[0]).toMatchObject({ team1InWindow: 1, windowSize: 1, team1Share: 4.5 / 8 })
    // Level after two points is level: (1 + 3) / 8.
    expect(samples[1]).toMatchObject({ team1InWindow: 1, windowSize: 2, team1Share: 0.5 })
    // Positions 0–7: one point to Bob among eight.
    expect(samples[7]).toMatchObject({ team1InWindow: 7, windowSize: 8, team1Share: 7 / 8 })
  })

  it('runs the window across the set boundaries', () => {
    // Positions 3–8, then 10 and 11: six to Ana, then Bob's two tiebreak points.
    expect(samples[10]).toMatchObject({ setNumber: 2, team1InWindow: 6, windowSize: 8 })
  })

  it('keeps a running point difference, +1 for team 1 and -1 for team 2', () => {
    expect(samples.map((s) => s.difference)).toEqual([1, 0, 1, 2, 3, 4, 5, 6, 7, 6, 5, 6])
  })

  it('honours a custom window', () => {
    expect(momentumOf(RETIRED_REPLAY, 2)[1]).toMatchObject({ team1InWindow: 1, windowSize: 2, team1Share: 0.5 })
    // With a custom window the padding scales too: (1 + 0.5) / 2 after the first point.
    expect(momentumOf(RETIRED_REPLAY, 2)[0]).toMatchObject({ team1Share: 0.75 })
  })
})

describe('setBoundariesOf', () => {
  it('marks where each new set begins', () => {
    expect(setBoundariesOf(momentumOf(RETIRED_REPLAY))).toEqual([
      { setNumber: 2, afterOrdinal: 9 },
      { setNumber: 3, afterOrdinal: 11 },
    ])
  })
})

describe('sampleAtOrBefore', () => {
  const samples = momentumOf(RETIRED_REPLAY)

  it('finds the drawn sample a selection lands on', () => {
    expect(sampleAtOrBefore(samples, REPLAY_START)).toBeNull()
    expect(sampleAtOrBefore(samples, 4)?.index).toBe(4)
    // An awarded set and the ending show the position they leave the match in.
    expect(sampleAtOrBefore(samples, 9)?.index).toBe(8)
    expect(sampleAtOrBefore(samples, 13)?.index).toBe(12)
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
