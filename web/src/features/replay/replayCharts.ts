import type { ReplayDocument, ReplayPoint, ReplaySet } from '@/api/generated/model'

/**
 * Pure derivations behind the replay charts (#1147): lane layout, score labels, rolling point share and the
 * running point difference. The chart components only draw what these return.
 *
 * Every index here is a **position in `replay.points`** — the same number the replay card selects by — so a
 * selection made in one place means the same step everywhere.
 */

/** How many recent points the momentum chart's rolling share looks back over. Tune once there is real data. */
export const MOMENTUM_WINDOW = 8

export type Side = 'TEAM1' | 'TEAM2'

/** Each side's theme token, the same in every replay chart and orientation, light and dark. */
export const SIDE_COLOUR: Record<Side, string> = {
  TEAM1: 'var(--chart-1)',
  TEAM2: 'var(--chart-2)',
}

/**
 * How a step is drawn in its lane.
 *
 * - `point`: an ordinary point — a small mark.
 * - `hold` / `break`: a game won on serve / against it. Only when the replay carries break data.
 * - `game`: a game won, when the replay cannot say whether it was a hold or a break.
 * - `awarded`: a game or set the umpire awarded directly, not played out.
 */
export type LaneMark = 'point' | 'hold' | 'break' | 'game' | 'awarded'

export interface LaneStep {
  /** Position in `replay.points`. */
  index: number
  /** 1-based column within the set; column 0 is the set's 0-0 start. */
  column: number
  side: Side
  label: string
  mark: LaneMark
  tiebreak: boolean
}

export interface SetLanes {
  setNumber: number
  /** The selection that shows this set's 0-0 start: the step that closed the previous set, or `REPLAY_START`. */
  startIndex: number
  steps: LaneStep[]
  /** The set's games as it finished — or as it stood, for a set the match ended inside. */
  finalScore: string
}

/** A step that the lanes and the momentum chart draw. The ending (END) is a state, not a point anyone won. */
function isDrawn(point: ReplayPoint): boolean {
  return point.kind !== 'END'
}

/** A point actually played — what the momentum chart counts. Awarded games and sets were not played. */
function isPlayed(point: ReplayPoint): boolean {
  return point.kind === 'POINT'
}

/**
 * The label for the score a step produced: "15-0", "Deuce", "AD-40", a tiebreak count such as "3-2" — or
 * "Game" / "Set" for a step that won one. Always team 1's figure first, so the labels read as one scoreline.
 */
export function scoreLabel(point: ReplayPoint): string {
  if (point.kind === 'END') return 'End'
  if (point.setWon || point.kind === 'SET') return 'Set'
  if (point.gameWon || point.kind === 'GAME') return 'Game'
  const { pointsTeam1, pointsTeam2 } = point.score
  if (!point.tiebreak && pointsTeam1 === '40' && pointsTeam2 === '40') return 'Deuce'
  return `${pointsTeam1}-${pointsTeam2}`
}

function markOf(point: ReplayPoint, hasBreaks: boolean): LaneMark {
  if (point.kind === 'GAME' || point.kind === 'SET') return 'awarded'
  if (!point.gameWon) return 'point'
  if (!hasBreaks) return 'game'
  return point.breakOfServe ? 'break' : 'hold'
}

/** "6-4", or "7-6 (7-5)" when the set went to a tiebreak. */
export function formatSet(set: ReplaySet): string {
  const games = `${set.team1}-${set.team2}`
  if (set.tiebreakTeam1 == null || set.tiebreakTeam2 == null) return games
  return `${games} (${set.tiebreakTeam1}-${set.tiebreakTeam2})`
}

function finalScoreOf(last: ReplayPoint, setNumber: number): string {
  const completed = last.score.sets[setNumber - 1]
  if (completed) return formatSet(completed)
  return `${last.score.gamesTeam1}-${last.score.gamesTeam2}`
}

/**
 * One pair of lanes per set, each drawn step placed in its winner's lane in order (#1147).
 *
 * [hasBreaks] comes from the feature table: without break data, a game won is drawn as a plain game marker
 * rather than guessed at as a hold.
 */
export function setLanesOf(replay: ReplayDocument, hasBreaks: boolean): SetLanes[] {
  const sets: SetLanes[] = []
  replay.points.forEach((point, index) => {
    if (!isDrawn(point)) return
    let current = sets[sets.length - 1]
    if (current?.setNumber !== point.setNumber) {
      // The step before a set's first one is what leaves the score at 0-0 in it — and before the very first
      // step that is the opening position, REPLAY_START (-1), so `index - 1` is right in both cases.
      current = { setNumber: point.setNumber, startIndex: index - 1, steps: [], finalScore: '' }
      sets.push(current)
    }
    current.steps.push({
      index,
      column: current.steps.length + 1,
      side: point.wonBy,
      label: scoreLabel(point),
      mark: markOf(point, hasBreaks),
      tiebreak: point.tiebreak ?? false,
    })
  })
  for (const set of sets) {
    const lastStep = set.steps[set.steps.length - 1]
    set.finalScore = finalScoreOf(replay.points[lastStep.index], set.setNumber)
  }
  return sets
}

export interface MomentumSample {
  /** Position in `replay.points`. */
  index: number
  /** 1-based count of points played so far — the momentum chart's x. */
  ordinal: number
  setNumber: number
  wonBy: Side
  /** Points team 1 won within the window ending here. */
  team1InWindow: number
  /** The window's actual size: smaller than {@link MOMENTUM_WINDOW} for the match's first few points. */
  windowSize: number
  /** Team 1's share of the window, 0..1. */
  team1Share: number
  /** Running points won by team 1 minus team 2. */
  difference: number
}

/**
 * Rolling point share and running point difference over every point played (#1147).
 *
 * Only played points count: an awarded game or set and the ending say nothing about who was winning rallies.
 * The window spans set boundaries, because momentum does not reset at a changeover.
 */
export function momentumOf(replay: ReplayDocument, window: number = MOMENTUM_WINDOW): MomentumSample[] {
  const samples: MomentumSample[] = []
  const winners: Side[] = []
  let difference = 0
  replay.points.forEach((point, index) => {
    if (!isPlayed(point)) return
    winners.push(point.wonBy)
    difference += point.wonBy === 'TEAM1' ? 1 : -1
    const recent = winners.slice(-window)
    const team1InWindow = recent.filter((side) => side === 'TEAM1').length
    samples.push({
      index,
      ordinal: winners.length,
      setNumber: point.setNumber,
      wonBy: point.wonBy,
      team1InWindow,
      windowSize: recent.length,
      team1Share: team1InWindow / recent.length,
      difference,
    })
  })
  return samples
}

/** The ordinal after which each new set begins — where the momentum chart draws its set boundaries. */
export function setBoundariesOf(samples: MomentumSample[]): { setNumber: number; afterOrdinal: number }[] {
  const boundaries: { setNumber: number; afterOrdinal: number }[] = []
  for (let i = 1; i < samples.length; i++) {
    if (samples[i].setNumber !== samples[i - 1].setNumber) {
      boundaries.push({ setNumber: samples[i].setNumber, afterOrdinal: samples[i - 1].ordinal })
    }
  }
  return boundaries
}

/**
 * The drawn sample a selection lands on: the last one at or before [selected]. A selection on a step the
 * chart does not draw (an awarded game, the ending) shows the position it leaves the match in. `null` for
 * `REPLAY_START` or anything before the first drawn step.
 */
export function sampleAtOrBefore<T extends { index: number }>(samples: T[], selected: number): T | null {
  let found: T | null = null
  for (const sample of samples) {
    if (sample.index > selected) break
    found = sample
  }
  return found
}

/** "+3", "-2", "0" — the point difference as a signed figure. */
export function signed(value: number): string {
  return value > 0 ? `+${value}` : `${value}`
}
