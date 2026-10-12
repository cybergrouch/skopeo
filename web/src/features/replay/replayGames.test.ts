import { describe, it, expect } from 'vitest'
import { locate, setGroupsOf } from './replayGames'
import { REPLAY_START } from './replayFeatures'
import { RETIRED_REPLAY, TIEBREAK_REPLAY, replayOf, replayPoint, replayScore } from '@/test/replayFixtures'

/**
 * RETIRED_REPLAY by position: set 1 is 0–4 (Ana holds), 5–8 (Ana breaks), 9 (set awarded); set 2 is a
 * two-point tiebreak 10–11 won by Bob; set 3 is one point (12) and the retirement (13).
 */
describe('setGroupsOf', () => {
  it('groups each set into its games, closing a game at the step flagged as winning it', () => {
    const groups = setGroupsOf(RETIRED_REPLAY, true, true)
    expect(groups.map((g) => [g.setNumber, g.firstIndex, g.lastIndex])).toEqual([
      [1, 0, 9],
      [2, 10, 11],
      [3, 12, 13],
    ])
    const set1 = groups[0].rows
    expect(set1.map((row) => [row.kind, row.gameNumber, row.indices])).toEqual([
      ['game', 1, [0, 1, 2, 3, 4]],
      ['game', 2, [5, 6, 7, 8]],
      ['awarded', null, [9]],
    ])
  })

  it('reads the server, a hold or a break, the deciding score and the games after it off the steps', () => {
    const [hold, brk, awarded] = setGroupsOf(RETIRED_REPLAY, true, true)[0].rows
    expect(hold).toMatchObject({ server: 'TEAM1', outcome: 'hold', wonBy: 'TEAM1', lastScore: '40-15', scoreAfter: '1-0' })
    expect(brk).toMatchObject({ server: 'TEAM2', outcome: 'break', wonBy: 'TEAM1', lastScore: '40-0', scoreAfter: '2-0' })
    // A set the umpire awarded with no game in progress: no number, no deciding score, the set as its score.
    expect(awarded).toMatchObject({ outcome: 'awarded', wonBy: 'TEAM1', lastScore: null, scoreAfter: '6-0' })
  })

  it('names no server and calls no hold or break when the replay lacks that data', () => {
    const [hold, brk] = setGroupsOf(RETIRED_REPLAY, false, false)[0].rows
    expect(hold).toMatchObject({ server: null, outcome: 'won' })
    expect(brk).toMatchObject({ server: null, outcome: 'won' })
  })

  it('leaves the game the match ended in open, and gives the ending a row of its own', () => {
    const [game, end] = setGroupsOf(RETIRED_REPLAY, true, true)[2].rows
    expect(game).toMatchObject({ kind: 'game', gameNumber: 1, indices: [12], outcome: 'open', wonBy: null, lastScore: '15-0', scoreAfter: null })
    expect(end).toMatchObject({ kind: 'end', gameNumber: null, indices: [13] })
  })

  it('splits a tiebreak into serving pairs: point 1, then 2-3, 4-5 and so on', () => {
    const rows = setGroupsOf(TIEBREAK_REPLAY, true, true)[0].rows
    expect(rows.map((row) => [row.kind, row.tiebreakPoints, row.indices, row.server])).toEqual([
      ['tiebreak', [1, 1], [0], 'TEAM1'],
      ['tiebreak', [2, 3], [1, 2], 'TEAM2'],
      ['tiebreak', [4, 5], [3, 4], 'TEAM1'],
      ['tiebreak', [6, 7], [5, 6], 'TEAM2'],
      ['tiebreak', [8, 9], [7, 8], 'TEAM1'],
    ])
    // Only the last pair closes the tiebreak — and with it the set.
    expect(rows.slice(0, 4).every((row) => row.outcome === 'open')).toBe(true)
    expect(rows[3].lastScore).toBe('5-2')
    expect(rows[4]).toMatchObject({ wonBy: 'TEAM1', lastScore: '6-2', scoreAfter: '7-6 (7-2)' })
    expect(new Set(rows.map((row) => row.key)).size).toBe(5)
  })

  it('closes a tiebreak pair early when the tiebreak ends on its first point', () => {
    const rows = setGroupsOf(RETIRED_REPLAY, true, true)[1].rows
    expect(rows.map((row) => row.tiebreakPoints)).toEqual([
      [1, 1],
      [2, 2],
    ])
    expect(rows[1]).toMatchObject({ wonBy: 'TEAM2', scoreAfter: '6-7 (5-7)' })
  })

  it('numbers an umpire-awarded game as a game, and closes the one in progress with it', () => {
    const replay = replayOf([
      replayPoint({ wonBy: 'TEAM1', score: replayScore({ pointsTeam1: '15' }) }),
      replayPoint({ kind: 'GAME', wonBy: 'TEAM2', gameWon: true, score: replayScore({ gamesTeam2: 1 }) }),
      replayPoint({ kind: 'GAME', wonBy: 'TEAM2', gameWon: true, score: replayScore({ gamesTeam2: 2 }) }),
    ])
    const rows = setGroupsOf(replay, false, true)[0].rows
    expect(rows.map((row) => [row.gameNumber, row.indices, row.outcome, row.lastScore])).toEqual([
      [1, [0, 1], 'awarded', '15-0'],
      [2, [2], 'awarded', null],
    ])
  })

  it('names no server for a game whose first step has none, though later games have one', () => {
    // The umpire can assign the server after play has started: the replay carries serve data, but the
    // opening game does not.
    const replay = replayOf([
      replayPoint({ wonBy: 'TEAM1', gameWon: true, score: replayScore({ gamesTeam1: 1 }) }),
      replayPoint({ wonBy: 'TEAM2', server: 'TEAM2', gameWon: true, score: replayScore({ gamesTeam1: 1, gamesTeam2: 1 }) }),
    ])
    const rows = setGroupsOf(replay, true, true)[0].rows
    expect(rows.map((row) => row.server)).toEqual([null, 'TEAM2'])
  })

  it('as production records it, a tiebreak ending mid-pair closes that pair and the set award gets its own row', () => {
    // The backend's shape: every tiebreak point is a plain POINT, and the umpire's SET step that follows
    // carries the set (tiebreak, gameWon and setWon). A 7-1 tiebreak is 8 points, so point 8 opens a pair
    // that the award must close rather than join.
    const tiebreakPoint = (team1: number, team2: number) =>
      replayPoint({
        wonBy: team1 > team2 ? 'TEAM1' : 'TEAM2',
        tiebreak: true,
        score: replayScore({ gamesTeam1: 6, gamesTeam2: 6, pointsTeam1: String(team1), pointsTeam2: String(team2) }),
      })
    const scores = [[1, 0], [2, 0], [2, 1], [3, 1], [4, 1], [5, 1], [6, 1], [7, 1]]
    const finalSet = { team1: 7, team2: 6, tiebreakTeam1: 7, tiebreakTeam2: 1 }
    const replay = replayOf([
      ...scores.map(([a, b]) => tiebreakPoint(a, b)),
      replayPoint({ kind: 'SET', wonBy: 'TEAM1', tiebreak: true, gameWon: true, setWon: true, score: replayScore({ sets: [finalSet] }) }),
    ])
    const rows = setGroupsOf(replay, false, true)[0].rows
    expect(rows.map((row) => [row.kind, row.tiebreakPoints, row.indices])).toEqual([
      ['tiebreak', [1, 1], [0]],
      ['tiebreak', [2, 3], [1, 2]],
      ['tiebreak', [4, 5], [3, 4]],
      ['tiebreak', [6, 7], [5, 6]],
      ['tiebreak', [8, 8], [7]],
      ['awarded', null, [8]],
    ])
    expect(rows[5]).toMatchObject({ outcome: 'awarded', wonBy: 'TEAM1', scoreAfter: '7-6 (7-1)' })
  })

  it('has nothing to group in an empty replay', () => {
    expect(setGroupsOf(replayOf([]), true, true)).toEqual([])
  })
})

describe('locate', () => {
  const groups = setGroupsOf(RETIRED_REPLAY, true, true)

  it('finds the set and game holding a selection', () => {
    expect(locate(groups, 2)).toEqual({ setNumber: 1, rowKey: '1-g-1' })
    expect(locate(groups, 8)).toEqual({ setNumber: 1, rowKey: '1-g-2' })
    expect(locate(groups, 11)).toEqual({ setNumber: 2, rowKey: '2-tb-2' })
    expect(locate(groups, 13)).toEqual({ setNumber: 3, rowKey: '3-end-13' })
  })

  it('puts the 0-0 start in the first set, inside no game', () => {
    expect(locate(groups, REPLAY_START)).toEqual({ setNumber: 1, rowKey: null })
  })

  it('is nowhere for a selection that matches no step', () => {
    expect(locate([], REPLAY_START)).toEqual({ setNumber: null, rowKey: null })
    expect(locate(groups, 99)).toEqual({ setNumber: null, rowKey: null })
  })
})
