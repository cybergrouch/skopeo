import type { ReplayDocument, ReplayPoint } from '@/api/generated/model'
import { formatSet, scoreLabel, type Side } from './replayCharts'
import { REPLAY_START } from './replayFeatures'

/**
 * The replay's points grouped into sets and games (#1156): the shape the timeline and the vertical swimlane
 * both draw, derived once here so the two cannot disagree about where a game starts.
 *
 * Nothing here knows tennis. A game ends at the step the server flagged `gameWon` (or an umpire-awarded GAME
 * or SET); a tiebreak is the run of steps flagged `tiebreak`; the ending is the step of kind END. Every index
 * is a **position in `replay.points`**, the number the replay's selection is made in.
 */

/**
 * - `game`: an ordinary game, numbered within its set.
 * - `tiebreak`: one serving pair of a tiebreak — its first point alone, then points 2-3, 4-5 and so on.
 * - `awarded`: a set the umpire awarded with no game in progress. It is not a game, so it has no number.
 * - `end`: the match's ending, which is a state rather than a point anyone won.
 */
export type GameRowKind = 'game' | 'tiebreak' | 'awarded' | 'end'

/**
 * How a game row finished.
 *
 * - `hold` / `break`: won on serve / against it. Only when the replay carries break data.
 * - `won`: won, when the replay cannot say whether it was a hold or a break.
 * - `awarded`: closed by an umpire-awarded GAME or SET step.
 * - `open`: not closed inside this row — a game the match ended in, or a tiebreak pair that is not the last.
 */
export type GameOutcome = 'hold' | 'break' | 'won' | 'awarded' | 'open'

export interface GameRow {
  /** Unique across the replay, stable across renders: what open/closed state is keyed by. */
  key: string
  kind: GameRowKind
  setNumber: number
  /** 1-based game number within the set; `null` for a row that is not a game (`awarded`, `end`). */
  gameNumber: number | null
  /** For a `tiebreak` row, the 1-based tiebreak point numbers it spans, e.g. `[2, 3]`. */
  tiebreakPoints: [number, number] | null
  /** Positions in `replay.points`, in order. Never empty. */
  indices: number[]
  /** The recorded server of the row's first step, when the replay carries serve data. */
  server: Side | null
  outcome: GameOutcome
  /** Who won the row's closing step; `null` while the row is `open`. */
  wonBy: Side | null
  /**
   * The point score the game was decided from — the label of the step before the closing one, e.g. "40-15" —
   * or, for an `open` row, the label of its last step. `null` when there is no such step.
   */
  lastScore: string | null
  /** The games (or, after a set, the set) once the row closed: "2-1", "6-4 (7-5)". `null` while `open`. */
  scoreAfter: string | null
}

export interface SetGroup {
  setNumber: number
  rows: GameRow[]
  /** Positions in `replay.points` of the set's first and last steps. */
  firstIndex: number
  lastIndex: number
}

/** Whether [point] closes the row it sits in. */
function closes(point: ReplayPoint): boolean {
  return point.gameWon || point.setWon || point.kind === 'GAME' || point.kind === 'SET'
}

function outcomeOf(closing: ReplayPoint, hasBreaks: boolean): GameOutcome {
  if (closing.kind === 'GAME' || closing.kind === 'SET') return 'awarded'
  if (!hasBreaks) return 'won'
  return closing.breakOfServe ? 'break' : 'hold'
}

function scoreAfterOf(closing: ReplayPoint, setNumber: number): string {
  const set = closing.score.sets[setNumber - 1]
  if (closing.setWon && set) return formatSet(set)
  return `${closing.score.gamesTeam1}-${closing.score.gamesTeam2}`
}

/** Builds a row from the steps gathered for it, reading every fact off the steps themselves. */
function rowOf(
  points: ReplayPoint[],
  indices: number[],
  base: Pick<GameRow, 'kind' | 'setNumber' | 'gameNumber' | 'tiebreakPoints'>,
  hasServe: boolean,
  hasBreaks: boolean,
): GameRow {
  const steps = indices.map((i) => points[i])
  const last = steps[steps.length - 1]
  const closed = base.kind !== 'end' && closes(last)
  const beforeLast = steps.length > 1 ? steps[steps.length - 2] : undefined
  const key =
    base.kind === 'tiebreak'
      ? `${base.setNumber}-tb-${base.tiebreakPoints?.[0]}`
      : base.kind === 'game'
        ? `${base.setNumber}-g-${base.gameNumber}`
        : `${base.setNumber}-${base.kind}-${indices[0]}`
  return {
    ...base,
    key,
    indices,
    server: hasServe ? (steps[0].server ?? null) : null,
    outcome: closed ? outcomeOf(last, hasBreaks) : 'open',
    wonBy: closed ? last.wonBy : null,
    lastScore: closed ? (beforeLast ? scoreLabel(beforeLast) : null) : scoreLabel(last),
    scoreAfter: closed ? scoreAfterOf(last, base.setNumber) : null,
  }
}

/**
 * The replay as sets of game rows (#1156).
 *
 * [hasServe] and [hasBreaks] come from the feature table: without serve data no row names a server, and
 * without break data a game won is `won` rather than guessed at as a hold.
 */
export function setGroupsOf(replay: ReplayDocument, hasServe: boolean, hasBreaks: boolean): SetGroup[] {
  const points = replay.points
  const groups: SetGroup[] = []
  let pending: number[] = []
  let pendingKind: 'game' | 'tiebreak' = 'game'
  let games = 0
  let tiebreakCount = 0

  const group = () => groups[groups.length - 1]
  const flush = () => {
    if (pending.length === 0) return
    const set = group()
    const first = points[pending[0]]
    let base: Pick<GameRow, 'kind' | 'setNumber' | 'gameNumber' | 'tiebreakPoints'>
    if (pendingKind === 'tiebreak') {
      base = {
        kind: 'tiebreak',
        setNumber: set.setNumber,
        gameNumber: games + 1,
        tiebreakPoints: [tiebreakCount - pending.length + 1, tiebreakCount],
      }
    } else if (pending.length === 1 && first.kind === 'SET') {
      base = { kind: 'awarded', setNumber: set.setNumber, gameNumber: null, tiebreakPoints: null }
    } else {
      games += 1
      base = { kind: 'game', setNumber: set.setNumber, gameNumber: games, tiebreakPoints: null }
    }
    set.rows.push(rowOf(points, pending, base, hasServe, hasBreaks))
    pending = []
  }

  points.forEach((point, index) => {
    if (group()?.setNumber !== point.setNumber) {
      flush()
      groups.push({ setNumber: point.setNumber, rows: [], firstIndex: index, lastIndex: index })
      games = 0
      tiebreakCount = 0
    }
    group().lastIndex = index

    if (point.kind === 'END') {
      flush()
      group().rows.push(
        rowOf(points, [index], { kind: 'end', setNumber: point.setNumber, gameNumber: null, tiebreakPoints: null }, hasServe, hasBreaks),
      )
      return
    }

    const kind = point.tiebreak === true && point.kind === 'POINT' ? 'tiebreak' : 'game'
    if (pending.length > 0 && kind !== pendingKind) flush()
    pendingKind = kind
    pending.push(index)

    if (kind === 'tiebreak') {
      tiebreakCount += 1
      // Point 1 is served alone; after it, each server serves two.
      if (tiebreakCount % 2 === 1 || closes(point)) flush()
    } else if (closes(point)) {
      flush()
    }
  })
  flush()
  return groups
}

/** Where a selection sits: its set, and its row — `null` for the 0-0 start, which belongs to no game. */
export interface ReplayLocation {
  setNumber: number | null
  rowKey: string | null
}

/**
 * The set and row holding [selected], which is what opens by default (#1156). `REPLAY_START` is the first
 * set's 0-0, inside no game; a selection that matches no step (an empty replay) is nowhere.
 */
export function locate(groups: SetGroup[], selected: number): ReplayLocation {
  if (selected === REPLAY_START) return { setNumber: groups[0]?.setNumber ?? null, rowKey: null }
  for (const set of groups) {
    for (const row of set.rows) {
      if (row.indices.includes(selected)) return { setNumber: set.setNumber, rowKey: row.key }
    }
  }
  return { setNumber: null, rowKey: null }
}
