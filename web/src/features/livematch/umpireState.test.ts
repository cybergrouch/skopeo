import { describe, it, expect } from 'vitest'
import type { LiveMatchResponse } from '@/api/generated/model'
import {
  scoringPrompt,
  umpireStateOf,
  visibleControls,
  type Control,
  type UmpireState,
} from './umpireState'

/**
 * The state machine on its own (#1083), away from the DOM.
 *
 * `LiveScoringPage.test.tsx` asserts that the view renders what the table says; this asserts what the
 * table SAYS. Keeping them apart is what makes a visibility change a one-line diff here rather than a
 * hunt through render assertions — and it is why the table can be read as the specification it is.
 */
const base: LiveMatchResponse = {
  matchId: 'm-1',
  sequence: 4,
  hasStarted: true,
  isPaused: false,
  isBetweenSets: false,
  isInGame: true,
  isTiebreak: false,
  canUndo: true,
  serverId: 'p-1',
  pointsTeam1: '30',
  pointsTeam2: '15',
  gamesTeam1: 2,
  gamesTeam2: 1,
  sets: [],
  // `undefined`, not null: the generated type is optional rather than nullable, and `umpireStateOf`
  // tests `!= null` so it covers both — which is what an older payload without the field sends.
  outcome: undefined,
}

/** The states, each expressed the way the server would send it. */
const states: Record<UmpireState, LiveMatchResponse> = {
  PRE_MATCH: { ...base, hasStarted: false, serverId: null, isInGame: false, canUndo: false },
  READY: { ...base, hasStarted: false, isInGame: false },
  MATCH_TRANSITION: { ...base, isBetweenSets: true, isInGame: false },
  SET_TRANSITION: { ...base, isInGame: false },
  SCORING_GAME: base,
  SCORING_TIEBREAK: { ...base, isInGame: false, isTiebreak: true },
  PAUSED: { ...base, isPaused: true },
  MATCH_CLOSED: {
    ...base,
    outcome: { kind: 'RETIRED', winner: 'TEAM1', concededBy: 'TEAM2' },
  },
}

describe('umpireStateOf', () => {
  it('names each state from the flags the server sends', () => {
    for (const [expected, view] of Object.entries(states)) {
      expect(umpireStateOf(view)).toBe(expected)
    }
  })

  it('reads outside-in, so an outer state always wins', () => {
    // The flags are independent booleans on the wire. The engine keeps them consistent, but a client
    // that trusted the innermost one would show a live board for a set that had ended.
    expect(umpireStateOf({ ...base, isBetweenSets: true, isInGame: true })).toBe('MATCH_TRANSITION')
    expect(umpireStateOf({ ...base, isPaused: true, isInGame: true })).toBe('PAUSED')
    expect(umpireStateOf({ ...states.MATCH_CLOSED, isPaused: true })).toBe('MATCH_CLOSED')
    expect(umpireStateOf({ ...base, isTiebreak: true, isInGame: true })).toBe('SCORING_TIEBREAK')
  })

  it('splits the pre-match states on whether anyone is serving', () => {
    expect(umpireStateOf({ ...base, hasStarted: false, serverId: null })).toBe('PRE_MATCH')
    expect(umpireStateOf({ ...base, hasStarted: false, serverId: 'p-2' })).toBe('READY')
  })
})

describe('visibleControls', () => {
  const shown = (state: UmpireState, canFinalize = true): Control[] =>
    [...visibleControls(states[state], canFinalize)].sort()

  it('offers exactly the diagram edges in each state', () => {
    expect(shown('PRE_MATCH')).toEqual(['switchSides', 'toggleServer'])
    expect(shown('READY')).toEqual(['startMatch', 'switchSides', 'toggleServer', 'undo'])
    expect(shown('MATCH_TRANSITION')).toEqual([
      'default',
      'finalize',
      'pause',
      'retire',
      'startSet',
      'switchSides',
      'toggleServer',
      'undo',
    ])
    expect(shown('SET_TRANSITION')).toEqual([
      'default',
      'pause',
      'retire',
      'set',
      'startGame',
      'switchSides',
      'toggleServer',
      'undo',
    ])
    expect(shown('SCORING_GAME')).toEqual([
      'default',
      'game',
      'pause',
      'point',
      'retire',
      'switchSides',
      'toggleServer',
      'undo',
    ])
    expect(shown('SCORING_TIEBREAK')).toEqual([
      'default',
      'pause',
      'point',
      'retire',
      'set',
      'switchSides',
      'toggleServer',
      'undo',
    ])
  })

  it('leaves only Resume while paused', () => {
    // A pause cements the match: on resume everything must be as it was, including ends and the
    // server. Back is never in the table at all, so this really is the whole screen.
    expect(shown('PAUSED')).toEqual(['resume'])
  })

  it('hides Undo while paused, because it would move the clock rather than the score', () => {
    // `lastUndoableSequence` has no filter on event kind, so an undo here cancels the PAUSE. That is
    // not merely mislabelled: `matchTiming` walks the same undo-aware filter, so the engine would
    // conclude the clock never stopped and count the entire delay as playing time.
    expect(visibleControls(states.PAUSED, true).has('undo')).toBe(false)
    // Resume already covers an accidental pause, so nothing is lost.
    expect(visibleControls(states.PAUSED, true).has('resume')).toBe(true)
  })

  it('never offers a point where a point has nowhere to go', () => {
    for (const state of ['PRE_MATCH', 'READY', 'MATCH_TRANSITION', 'SET_TRANSITION', 'PAUSED'] as const) {
      expect(visibleControls(states[state], true).has('point')).toBe(false)
    }
    expect(visibleControls(states.SCORING_GAME, true).has('point')).toBe(true)
    expect(visibleControls(states.SCORING_TIEBREAK, true).has('point')).toBe(true)
  })

  it('never offers Set from inside a game, nor Game from outside one', () => {
    // The mis-tap that ended a set mid-rally, and its mirror image.
    expect(visibleControls(states.SCORING_GAME, true).has('set')).toBe(false)
    expect(visibleControls(states.SET_TRANSITION, true).has('game')).toBe(false)
    expect(visibleControls(states.SCORING_TIEBREAK, true).has('game')).toBe(false)
  })

  it('offers Finalize only between sets, and only to someone who may write a result', () => {
    // #984's decision point. Never between games, which rules out finalizing a partial set.
    expect(visibleControls(states.MATCH_TRANSITION, true).has('finalize')).toBe(true)
    expect(visibleControls(states.MATCH_CLOSED, true).has('finalize')).toBe(true)
    expect(visibleControls(states.SCORING_GAME, true).has('finalize')).toBe(false)
    expect(visibleControls(states.SET_TRANSITION, true).has('finalize')).toBe(false)
    // Scoring and finalizing are different rights (#934): hidden, not 403 (#867).
    expect(visibleControls(states.MATCH_TRANSITION, false).has('finalize')).toBe(false)
  })

  it('offers Start tiebreak only when the games are level', () => {
    const at = (gamesTeam1: number, gamesTeam2: number) =>
      visibleControls({ ...states.SET_TRANSITION, gamesTeam1, gamesTeam2 }, true).has('startTiebreak')

    expect(at(6, 6)).toBe(true)
    // Level, not even-numbered: 5-5 is a shortened format and qualifies...
    expect(at(5, 5)).toBe(true)
    // ...and 0-0 is a deciding-set match tiebreak, which is what keeps that format working.
    expect(at(0, 0)).toBe(true)
    expect(at(6, 4)).toBe(false)
    expect(at(2, 1)).toBe(false)
  })

  it('offers Undo only when something is still in force', () => {
    // An always-visible Undo is the silently-inert control this work removes: undoing nothing is a
    // no-op rather than an error, so "interactable" has to mean "has an effect".
    expect(visibleControls({ ...states.SCORING_GAME, canUndo: false }, true).has('undo')).toBe(false)
    expect(visibleControls({ ...states.SCORING_GAME, canUndo: true }, true).has('undo')).toBe(true)
    // Which is also what keeps it off a fresh match with no special case.
    expect(visibleControls(states.PRE_MATCH, true).has('undo')).toBe(false)
  })

  it('follows an undo wherever the shortened log lands', () => {
    // Undo has no destination to compute: it truncates the effective log, the server replays what is
    // left, and the state falls out of that. So undoing the GameStarted that opened a game is not a
    // special case -- it is the same derivation reading one fewer event.
    const afterUndoOfGameStarted = { ...states.SCORING_GAME, isInGame: false }
    expect(umpireStateOf(afterUndoOfGameStarted)).toBe('SET_TRANSITION')

    // Undoing the SetStarted that began a set steps back out to the between-sets decision point...
    expect(umpireStateOf({ ...states.SET_TRANSITION, isBetweenSets: true })).toBe('MATCH_TRANSITION')
    // ...and undoing the concluding event of a retirement reopens the match, which is why Undo stays
    // on offer once it is closed: `ScoreEngine.apply` documents that removing the ending from the fold
    // restores the ability to score.
    expect(umpireStateOf({ ...states.MATCH_CLOSED, outcome: undefined })).toBe('SCORING_GAME')
    expect(visibleControls(states.MATCH_CLOSED, true).has('undo')).toBe(true)
  })

  it('never puts Back in the table, so no state can take it away', () => {
    // It is the one control the gates must never touch: on an unstarted match it is the only way out.
    // Structural rather than a rule someone could add a state to and forget.
    for (const view of Object.values(states)) {
      expect([...visibleControls(view, true)]).not.toContain('back')
    }
  })

  it('a resumed match is offering whatever the pause interrupted', () => {
    // Nothing remembers an origin: the state is folded out of the log, so clearing the pause simply
    // yields the prior state again. This is that property expressed as the table.
    for (const state of ['MATCH_TRANSITION', 'SET_TRANSITION', 'SCORING_GAME', 'SCORING_TIEBREAK'] as const) {
      const paused = { ...states[state], isPaused: true }
      expect(umpireStateOf(paused)).toBe('PAUSED')
      expect(umpireStateOf({ ...paused, isPaused: false })).toBe(state)
      expect([...visibleControls({ ...paused, isPaused: false }, true)].sort()).toEqual(shown(state))
    }
  })
})

describe('scoringPrompt', () => {
  it('names the next action in every state that has one', () => {
    expect(scoringPrompt(states.PRE_MATCH)).toContain('serving')
    expect(scoringPrompt(states.READY)).toContain('Start match')
    expect(scoringPrompt(states.SET_TRANSITION)).toContain('Start game')
    expect(scoringPrompt(states.PAUSED)).toContain('Resume')
  })

  it('distinguishes the first set from a later one', () => {
    // "Start the next set" before there has been a first is wrong in the one place an umpire is most
    // likely to hesitate.
    expect(scoringPrompt(states.MATCH_TRANSITION)).toContain('first set')
    expect(
      scoringPrompt({ ...states.MATCH_TRANSITION, sets: [{ gamesTeam1: 6, gamesTeam2: 4, winner: 'TEAM1' }] }),
    ).toContain('Set complete')
  })

  it('mentions the tiebreak only when it is actually on offer', () => {
    const level = { ...states.SET_TRANSITION, gamesTeam1: 6, gamesTeam2: 6 }
    expect(scoringPrompt(level)).toContain('tiebreak')
    expect(scoringPrompt({ ...states.SET_TRANSITION, gamesTeam1: 6, gamesTeam2: 4 })).not.toContain('tiebreak')
  })

  it('says nothing while play is under way', () => {
    // A prompt that is always on screen is furniture, and furniture is not read.
    expect(scoringPrompt(states.SCORING_GAME)).toBeNull()
    expect(scoringPrompt(states.SCORING_TIEBREAK)).toBeNull()
  })
})
