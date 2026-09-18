import type { LiveMatchResponse } from '@/api/generated/model'

/**
 * The umpire view's state machine (#1083).
 *
 * **One derivation, one table.** Every control's visibility comes from here, so a control cannot grow
 * its own predicate and drift from its siblings — which is what the ad-hoc mix of `disabled` flags this
 * replaces had already done: between sets the *disabled* Game/Set kept a visible border while the only
 * live controls in the row had none, so the unusable ones looked more pressable than the usable ones.
 *
 * **Derived from the replayed log, never held in component state.** The server sends the state
 * (`hasStarted`, `isBetweenSets`, `isInGame`, `isTiebreak`, `isPaused`, `outcome`), all of which it
 * folds out of the event log. Two things fall out for free: Resume returns to whatever state the pause
 * interrupted, because replaying a closed Paused/Resumed pair simply yields the prior state; and Undo
 * lands wherever truncating the log lands, with no destination to compute. A local mode flag would also
 * break leave-and-resume (#937) — the umpire would come back mid-game to dead score boxes.
 *
 * **Hidden, not disabled** (#1083). This reverses the note that used to sit in `LiveScoringBoard`: a
 * greyed control was the right call for something *temporarily* unavailable within one screen, but a
 * change of state is a change in what the screen is FOR, and a state machine whose controls merely grey
 * out is a weak signal. The only remaining `disabled` is `busy` — a write in flight — which is
 * orthogonal to the machine.
 */
export type UmpireState =
  | 'PRE_MATCH'
  | 'READY'
  | 'MATCH_TRANSITION'
  | 'SET_TRANSITION'
  | 'SCORING_GAME'
  | 'SCORING_TIEBREAK'
  | 'PAUSED'
  | 'MATCH_CLOSED'

/** Every control the view can show. `back` is deliberately absent — see [visibleControls]. */
export type Control =
  | 'undo'
  | 'point'
  | 'game'
  | 'set'
  | 'retire'
  | 'default'
  | 'startMatch'
  | 'startSet'
  | 'startGame'
  | 'startTiebreak'
  | 'pause'
  | 'resume'
  | 'toggleServer'
  | 'switchSides'
  | 'finalize'

/**
 * Which state the match is in.
 *
 * Order is the specification, not an optimisation. **The outermost truth wins**, which is the match's
 * own containment — match, then set, then game — read from the outside in:
 * - an **outcome** outranks everything: a retired match is not "between sets"
 * - **paused** outranks every scoring state, because a pause is what the screen is about while it lasts
 * - **not started** next, since nothing inside a match matters before there is one
 * - **between sets** before either scoring state: banking a set closes whatever was open inside it, so
 *   a game or tiebreak flag surviving alongside it could only be stale
 * - a **tiebreak** before `isInGame`, which a tiebreak never sets anyway, so the two cannot disagree
 * - the fallthrough is `SET_TRANSITION`, i.e. exactly "in a set, between games"
 *
 * Reading outside-in matters because the flags are independent booleans on the wire. The engine keeps
 * them consistent — `setTo` clears `isInGame` and `isTiebreak` as it banks — but a client that trusted
 * the innermost flag would show a live board for a set that had ended if the two ever disagreed, and
 * that is the one disagreement worth being defensive about.
 *
 * `MATCH_STARTED` leaves the match between sets (#1083), so the first set begins with a `SET_STARTED`
 * like every other one and `MATCH_TRANSITION` covers both "before the first set" and "between sets"
 * with no special case.
 */
export function umpireStateOf(view: LiveMatchResponse): UmpireState {
  if (view.outcome != null) return 'MATCH_CLOSED'
  if (view.isPaused) return 'PAUSED'
  if (!view.hasStarted) return view.serverId == null ? 'PRE_MATCH' : 'READY'
  if (view.isBetweenSets) return 'MATCH_TRANSITION'
  if (view.isTiebreak) return 'SCORING_TIEBREAK'
  if (view.isInGame) return 'SCORING_GAME'
  return 'SET_TRANSITION'
}

/**
 * The table: which controls are interactable — and therefore visible — in each state.
 *
 * A direct transcription of the state diagram in
 * `docs/engineering/architecture/LIVE_MATCH.md`. Read it as the diagram's edges grouped by origin.
 *
 * Three readings worth stating, because each rules out a mis-tap rather than merely tidying the screen:
 * - **no `set` in `SCORING_GAME`** — award the game first, so a stray tap cannot end a set mid-rally
 * - **no `point` outside a game or a tiebreak** — a point between games has nowhere to go
 * - **`finalize` only in `MATCH_TRANSITION`** (and once closed), never between games, which preserves
 *   #984's decision point and rules out finalizing a partial set
 *
 * `PAUSED` is the one state that strips almost everything: a pause cements the match, and on resume
 * everything must be as it was, including ends and the server. `undo` is stripped too, and the reason is
 * the clock rather than tidiness — see [visibleControls].
 */
const VISIBLE: Record<UmpireState, readonly Control[]> = {
  PRE_MATCH: ['undo', 'switchSides', 'toggleServer'],
  READY: ['undo', 'switchSides', 'toggleServer', 'startMatch'],
  MATCH_TRANSITION: [
    'undo',
    'switchSides',
    'toggleServer',
    'startSet',
    'pause',
    'retire',
    'default',
    'finalize',
  ],
  SET_TRANSITION: [
    'undo',
    'switchSides',
    'toggleServer',
    'set',
    'startGame',
    'startTiebreak',
    'pause',
    'retire',
    'default',
  ],
  SCORING_GAME: ['undo', 'switchSides', 'toggleServer', 'point', 'game', 'pause', 'retire', 'default'],
  SCORING_TIEBREAK: ['undo', 'switchSides', 'toggleServer', 'point', 'set', 'pause', 'retire', 'default'],
  PAUSED: ['resume'],
  MATCH_CLOSED: ['undo', 'finalize'],
}

/**
 * The three guards that depend on data rather than state, in one place so no control carries its own.
 *
 * - **`undo`** is visible only when something is still in force. An always-visible Undo on a fresh
 *   match is precisely the silently-inert control this work exists to remove: `LiveMatchService` treats
 *   undoing nothing as a no-op, not an error, so "interactable" has to mean *has an effect*. `canUndo`
 *   comes from the same `surviving(log)` the undo target does, so the button and the endpoint cannot
 *   disagree. This is also what keeps Undo off `PRE_MATCH` without a special case — nothing has been
 *   recorded there yet.
 * - **`startTiebreak`** needs the games **level**: 6-6 ordinarily, 5-5 and below in shortened formats,
 *   and 0-0 for a deciding-set match tiebreak. Level, not even-numbered — 5-5 qualifies, 6-4 does not.
 * - **`finalize`** needs the right to write a result, which is a different right from scoring (#934).
 *   Hiding it rather than letting it 403 is the #867 lesson.
 */
const GUARDS: Partial<Record<Control, (view: LiveMatchResponse, canFinalize: boolean) => boolean>> = {
  undo: (view) => view.canUndo === true,
  startTiebreak: (view) => view.gamesTeam1 === view.gamesTeam2,
  finalize: (_view, canFinalize) => canFinalize,
}

/**
 * Which controls to render, for the state [view] is in.
 *
 * **`back` is not in here on purpose.** It is the one control the gates must never touch: on a match
 * that has not started it is the only way out, and a `busy` guard alone once stranded an umpire who had
 * opened the wrong court (#986/#1073). Leaving it out of the table is what makes that structural rather
 * than a rule someone could later add a state to and forget.
 */
export function visibleControls(
  view: LiveMatchResponse,
  canFinalize: boolean,
): ReadonlySet<Control> {
  const allowed = VISIBLE[umpireStateOf(view)].filter((control) => {
    const guard = GUARDS[control]
    return guard === undefined || guard(view, canFinalize)
  })
  return new Set(allowed)
}

/**
 * The next step, or null while play is under way (#1070/#1075).
 *
 * Keyed on the state rather than on a pile of flags, which is the point: #1070 was "no idea what to
 * click first" before the match starts and #1075 was the same complaint between sets, and they now
 * cannot drift apart in wording or placement because they come from one switch.
 *
 * Null in the scoring states: a prompt that is always on screen is furniture, and furniture is not read.
 */
export function scoringPrompt(view: LiveMatchResponse): string | null {
  switch (umpireStateOf(view)) {
    case 'PRE_MATCH':
      return 'Set which side is serving to begin.'
    case 'READY':
      return 'Ready — press Start match.'
    case 'MATCH_TRANSITION':
      return view.sets.length === 0
        ? 'Press Start set to begin the first set.'
        : 'Set complete. Start the next set, or finalize the match.'
    case 'SET_TRANSITION':
      return view.gamesTeam1 === view.gamesTeam2 && view.gamesTeam1 > 0
        ? 'Press Start game, or start a tiebreak.'
        : 'Press Start game to score the next game.'
    case 'PAUSED':
      return 'Play is suspended. Press Resume to continue.'
    default:
      return null
  }
}
