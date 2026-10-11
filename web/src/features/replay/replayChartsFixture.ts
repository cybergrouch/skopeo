import type { ReplayDocument, ReplayPoint, ReplayScore } from '@/api/generated/model'

/**
 * A short, hand-scripted replay shared by the chart tests (#1147). Positions matter — the tests select by them:
 *
 * - set 1, 0–4: a held game (15-0, 15-15, 30-15, 40-15, Game)
 * - set 1, 5–12: a broken game through deuce and advantage
 * - set 1, 13–14: a game and then the set awarded by the umpire
 * - set 2, 15–16: two tiebreak points, the second winning the set
 * - 17: the ending (retired)
 */
const score = (overrides: Partial<ReplayScore> = {}): ReplayScore => ({
  sets: [],
  gamesTeam1: 0,
  gamesTeam2: 0,
  pointsTeam1: '0',
  pointsTeam2: '0',
  ...overrides,
})

type Step = Omit<Partial<ReplayPoint>, 'score'> & Pick<ReplayPoint, 'wonBy'> & { score?: Partial<ReplayScore> }

export function replayOf(steps: Step[]): ReplayDocument {
  const points: ReplayPoint[] = steps.map((step, index) => ({
    kind: 'POINT',
    setNumber: 1,
    atMs: index * 30_000,
    ...step,
    index,
    score: score(step.score),
  }))
  return { version: 1, result: { sets: [{ team1: 6, team2: 1 }], winner: 'TEAM1' }, events: [], points }
}

const SET_1 = [{ team1: 6, team2: 1 }]

export const REPLAY = replayOf([
  { wonBy: 'TEAM1', server: 'TEAM1', score: { pointsTeam1: '15' } },
  { wonBy: 'TEAM2', server: 'TEAM1', score: { pointsTeam1: '15', pointsTeam2: '15' } },
  { wonBy: 'TEAM1', server: 'TEAM1', score: { pointsTeam1: '30', pointsTeam2: '15' } },
  { wonBy: 'TEAM1', server: 'TEAM1', score: { pointsTeam1: '40', pointsTeam2: '15' } },
  { wonBy: 'TEAM1', server: 'TEAM1', gameWon: true, breakOfServe: false, score: { gamesTeam1: 1 } },
  { wonBy: 'TEAM2', server: 'TEAM1', score: { gamesTeam1: 1, pointsTeam2: '15' } },
  { wonBy: 'TEAM2', server: 'TEAM1', score: { gamesTeam1: 1, pointsTeam2: '30' } },
  { wonBy: 'TEAM1', server: 'TEAM1', score: { gamesTeam1: 1, pointsTeam1: '15', pointsTeam2: '30' } },
  { wonBy: 'TEAM1', server: 'TEAM1', score: { gamesTeam1: 1, pointsTeam1: '30', pointsTeam2: '30' } },
  { wonBy: 'TEAM1', server: 'TEAM1', score: { gamesTeam1: 1, pointsTeam1: '40', pointsTeam2: '30' } },
  { wonBy: 'TEAM2', server: 'TEAM1', score: { gamesTeam1: 1, pointsTeam1: '40', pointsTeam2: '40' } },
  { wonBy: 'TEAM2', server: 'TEAM1', breakPoint: true, score: { gamesTeam1: 1, pointsTeam1: '40', pointsTeam2: 'AD' } },
  { wonBy: 'TEAM2', server: 'TEAM1', breakPoint: true, gameWon: true, breakOfServe: true, score: { gamesTeam1: 1, gamesTeam2: 1 } },
  { wonBy: 'TEAM1', kind: 'GAME', gameWon: true, score: { gamesTeam1: 2, gamesTeam2: 1 } },
  { wonBy: 'TEAM1', kind: 'SET', setWon: true, score: { sets: SET_1 } },
  { wonBy: 'TEAM2', setNumber: 2, tiebreak: true, score: { sets: SET_1, pointsTeam2: '1' } },
  {
    wonBy: 'TEAM2',
    setNumber: 2,
    tiebreak: true,
    gameWon: true,
    setWon: true,
    score: { sets: [...SET_1, { team1: 6, team2: 7, tiebreakTeam1: 0, tiebreakTeam2: 7 }] },
  },
  { wonBy: 'TEAM2', kind: 'END', outcome: 'RETIRED', setNumber: 2, score: { sets: [...SET_1, { team1: 6, team2: 7, tiebreakTeam1: 0, tiebreakTeam2: 7 }] } },
])
