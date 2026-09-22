import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import type { LiveMatchResponse } from '@/api/generated/model'
import type { Control } from '@/features/livematch/umpireState'

type Side = 'TEAM1' | 'TEAM2'

/**
 * The scoreboard and the two tap targets (#911).
 *
 * **Sized entirely in viewport units and `min-h-0`,** because the umpire view must fit the screen with
 * no scrolling in either axis. Nothing here may grow with its content: the set list is capped and the
 * point readout is a fixed-size grid cell. A layout that "just happens" to fit at one size would break
 * on the first phone with a different aspect ratio.
 *
 * `dvh` rather than `vh` deliberately — a mobile address bar collapsing changes `vh` on some browsers
 * and would leave the action row hanging below the fold at exactly the wrong moment.
 */
export function LiveScoringBoard({
  view,
  controls,
  flipped,
  busy,
  team1Name,
  team2Name,
  onPoint,
  onGame,
  onEndSet,
  onRetire,
  onDefault,
}: {
  view: LiveMatchResponse
  /** What this state may show (#1083). The board asks; it decides nothing itself. */
  controls: ReadonlySet<Control>
  /** View-only side swap for when the players change ends. NEVER recorded — see #911 §6. */
  flipped: boolean
  busy: boolean
  team1Name: string
  team2Name: string
  onPoint: (side: Side) => void
  onGame: (side: Side) => void
  onEndSet: (side: Side) => void
  onRetire: (side: Side) => void
  onDefault: (side: Side) => void
}) {
  const sides = [
    { id: 'TEAM1' as const, name: team1Name, points: view.pointsTeam1, games: view.gamesTeam1 },
    { id: 'TEAM2' as const, name: team2Name, points: view.pointsTeam2, games: view.gamesTeam2 },
  ]
  // The label travels WITH its side through the flip (#937). A flip that moved the scores but left the
  // names would be worse than no labels at all — it would confidently say the wrong thing.
  const ordered = flipped ? [sides[1], sides[0]] : sides
  // Every gate is now one lookup (#1083). The flags this used to compute -- `scorable`,
  // `canScorePoints` -- were the ad-hoc predicates the state machine replaces.
  //
  // This is also where the "disabled rather than hidden" note used to sit, and it is REVERSED: an
  // unavailable control is now absent. That was a recorded decision, so its reversal is recorded too --
  // see `docs/engineering/architecture/LIVE_MATCH.md` section 7a, which holds the diagram, both
  // derivation tables and the reasoning. `umpireState.ts` is the implementation of it.
  //
  // The ONE survivor of the point rule (#985) is the server check. It is not a state: a side can be
  // serving in any scoring state and in none of them, and the state machine is about which *moves*
  // exist, not whether their preconditions are met. So a point stays gated on someone serving, and
  // #1070's prompt is what says so.
  const canScorePoints = controls.has('point') && view.servingSide != null

  return (
    <div className="grid min-h-0 flex-1 grid-cols-2 gap-[1dvh]">
      {ordered.map((side) => (
        <div key={side.id} className="flex min-h-0 flex-col gap-[0.6dvh]">
          {/*
            The one place "hide what is not interactable" (#1083) needs a reading rather than a rule:
            the score box is BOTH a control and the scoreboard. Hiding it between games would hide the
            score, which is the last thing to take away from an umpire — so the *affordance* goes and
            the readout stays. Rendered as a plain element with no hover, no active state and no
            `aria-label`, it is not a button in any sense a mouse, a finger or a screen reader can find;
            the issue's words for this state are "the side score boxes are not-interactable".

            The tag itself switches, rather than `disabled` doing the work: a disabled button is still
            announced as a button, and `disabled:opacity-60` would dim the score just when the umpire is
            reading it to decide whether the set is over.
          */}
          <ScoreBox
            asButton={controls.has('point')}
            disabled={busy || !canScorePoints}
            label={`Point to ${side.name}`}
            onClick={() => onPoint(side.id)}
          >
            {/*
              One row per player (#1072), derived from `view.players` — which already carries
              `{ userId, name, side }`, so nothing new had to be threaded through the props.

              Singles renders one row at the original size and is unchanged. Doubles splits into two
              smaller rows whose combined height matches that one row, because the comment this
              replaces was right: "a long doubles pairing must not grow the row and push the action bar
              off a screen that is not allowed to scroll." Two rows here cost no height, they just
              divide it.

              The split is groundwork. The ball marks the SIDE for now — doubles serving order is a
              complicated rule set and designing a UI for it is a separate problem — but a per-player
              indicator can later hang off one of these rows without moving anything else.
            */}
            {playersOn({ view, sideId: side.id, fallback: side.name }).map((label, index, all) => (
              <span
                key={label + index}
                className={`max-w-full truncate font-semibold leading-none ${
                  all.length > 1 ? 'text-[1.7dvh]' : 'text-[3.4dvh]'
                }`}
              >
                {label}
                {view.servingSide != null && all.length === 1 && isServingSide({ view, sideId: side.id }) ? (
                  <ServingBall />
                ) : null}
              </span>
            ))}
            {/* Doubles: the ball sits with the SIDE rather than a partner, so it goes after the rows
                instead of inside one. Singles puts it inline above, where the side IS the player. */}
            {isServingSide({ view, sideId: side.id }) &&
            playersOn({ view, sideId: side.id, fallback: side.name }).length > 1 ? (
              <ServingBall />
            ) : null}
            <span className="text-[13dvh] font-bold leading-none tabular-nums">{side.points}</span>
            <span className="text-[3.6dvh] leading-none text-muted-foreground tabular-nums">
              {side.games} {side.games === 1 ? 'game' : 'games'}
            </span>
          </ScoreBox>

          {/*
            Per-side actions live UNDER their side rather than in the shared row (#944). Two reasons: a
            single row could not hold twelve buttons on a screen that is not allowed to scroll, and
            sitting beneath the named side makes "Game" unambiguous without repeating the name on every
            button. They cannot be nested inside the tap target above — a button inside a button is
            invalid HTML and the inner clicks would not be reachable.
          */}
          <div className="flex shrink-0 flex-wrap justify-center gap-[0.6dvw]">
            {/* Game is offered only inside a game, and Set only outside one (#1083) — so they are
                never both on screen, and the mis-tap that ended a set mid-rally is gone. */}
            {controls.has('game') && (
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                aria-label={`Game to ${side.name}`}
                onClick={() => onGame(side.id)}
              >
                Game
              </Button>
            )}
            {controls.has('set') && (
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                aria-label={`Set to ${side.name}`}
                onClick={() => onEndSet(side.id)}
              >
                Set
              </Button>
            )}
            {/*
              `outline` plus the destructive colour, NOT `ghost` (#1071). Ghost has only a hover state —
              no border, no shadow — so at rest these read as text. Worse, `disabled:opacity-50` applies
              to every variant, so between sets the DISABLED Game/Set above kept a visible border while
              these two, the only live controls in the row, had none: the unusable controls looked more
              pressable than the usable ones.

              The border is the affordance; the red is the caution. They are separable, and ending a
              match deserves both — promoting these to a plain `outline` would make them as inviting as
              Game, which for a mis-tap on a phone at the net is the opposite mistake.
            */}
            {controls.has('retire') && (
              <Button
                size="sm"
                variant="outline"
                className="text-destructive hover:text-destructive"
                disabled={busy}
                aria-label={`${side.name} retires`}
                onClick={() => onRetire(side.id)}
              >
                Retire
              </Button>
            )}
            {controls.has('default') && (
              <Button
                size="sm"
                variant="outline"
                className="text-destructive hover:text-destructive"
                disabled={busy}
                aria-label={`${side.name} defaults`}
                onClick={() => onDefault(side.id)}
              >
                Default
              </Button>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * The score readout, as a tap target or not (#1083).
 *
 * One component rather than two branches at the call site, so the two renderings cannot drift in
 * padding, sizing or layout — a board that shifted by a few pixels as a game started would be a
 * distracting flicker on the one screen that must not move.
 *
 * `bg-muted` and the geometry are shared; only the interactive affordances differ.
 */
function ScoreBox({
  asButton,
  disabled,
  label,
  onClick,
  children,
}: {
  asButton: boolean
  disabled: boolean
  label: string
  onClick: () => void
  children: ReactNode
}) {
  const shared =
    'flex min-h-0 flex-1 flex-col items-center justify-center gap-[0.5dvh] rounded-lg bg-muted px-[1dvw]'
  if (!asButton) {
    return <div className={shared}>{children}</div>
  }
  return (
    <button
      type="button"
      disabled={disabled}
      aria-label={label}
      onClick={onClick}
      className={`${shared} transition-colors hover:bg-muted/70 active:bg-muted/50 disabled:opacity-60`}
    >
      {children}
    </button>
  )
}

/**
 * A side's players as one label each (#1072), or the pre-joined name when the live view has no player
 * rows — an older payload, or a match whose roster never loaded.
 *
 * `view.players` already carries `side`, so this needs nothing new from the caller. The joined
 * `side.name` stays as the fallback and remains what the entry/confirmation screen shows (#956); only
 * the board splits.
 */
function playersOn({
  view,
  sideId,
  fallback,
}: {
  view: LiveMatchResponse
  sideId: 'TEAM1' | 'TEAM2'
  fallback: string
}): string[] {
  const names = (view.players ?? [])
    .filter((player) => player.side === sideId)
    .map((player) => player.name)
  return names.length > 0 ? names : [fallback]
}

/** Is this the serving side? A direct comparison since #1098 — the score carries the side itself. */
function isServingSide({
  view,
  sideId,
}: {
  view: LiveMatchResponse
  sideId: 'TEAM1' | 'TEAM2'
}): boolean {
  return view.servingSide === sideId
}

/**
 * The serving indicator (#1072).
 *
 * A ball rather than a word, so it reads at a glance on a phone at the net — but with a text
 * alternative, because a shape alone tells a screen reader nothing. Inline SVG rather than a lucide
 * icon: lucide has no tennis ball, and a generic circle would not read as one.
 *
 * `aria-hidden` on the graphic with an adjacent screen-reader-only label, so the announcement is a
 * sentence rather than "image".
 */
function ServingBall() {
  return (
    <>
      <svg
        viewBox="0 0 24 24"
        aria-hidden="true"
        className="ml-[0.6dvw] inline-block h-[2.2dvh] w-[2.2dvh] align-middle text-yellow-500"
      >
        <circle cx="12" cy="12" r="10" fill="currentColor" />
        {/* The two seams, which is what makes it a tennis ball rather than a dot. */}
        <path
          d="M4 6a12 12 0 0 1 0 12M20 6a12 12 0 0 0 0 12"
          fill="none"
          stroke="white"
          strokeWidth="1.6"
        />
      </svg>
      <span className="sr-only">serving</span>
    </>
  )
}

/**
 * A side's spoken name for assistive tech (#1072): the player in singles, "Team Ana and Bea" in
 * doubles.
 *
 * "Team" is prefixed only when there is more than one player — "Team Ana" for a singles player would
 * be odd, and the side IS the player there.
 *
 * "and" rather than the "&" the visible label uses: a screen reader reads this as a sentence, and an
 * ampersand mid-phrase is read inconsistently across engines.
 *
 * **Prefers the side's own name when there is one (#1079).** `view.team1Name`/`team2Name` are set only
 * for a standing event team (#720); for an ad-hoc fixture team the backend sends null, because its
 * stored name is a creation-time snapshot of display names that goes stale on a rename — so deriving
 * from the current roster is more accurate there.
 */
function teamLabel({
  view,
  sideId,
}: {
  view: LiveMatchResponse
  sideId: string
}): string | null {
  const official = sideId === 'TEAM1' ? view.team1Name : view.team2Name
  if (official) return official
  const names = (view.players ?? [])
    .filter((player) => player.side === sideId)
    .map((player) => player.name)
  if (names.length === 0) return null
  if (names.length === 1) return names[0]
  return `Team ${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/**
 * Which SIDE is serving, and a one-tap way to switch it (#943/#1072).
 *
 * A two-state toggle, not a four-player cycle. Doubles serving order is genuinely complicated — it
 * changes at tiebreaks, and partners alternate across games — so the app deliberately tracks only the
 * serving *team* and leaves the umpire to call out which partner is up. That is a simplification with
 * a known expiry: when the doubles rules are modelled properly this becomes per-player again.
 *
 * A toggle rather than a dropdown for the original #943 reason: an umpire mid-game wants one tap, not
 * a menu, and a serving indicator nobody maintains is worse than none because the scoreboard then
 * shows something confidently wrong.
 *
 * **The serve rotates itself** (#1097/#1098): `ScoreEngine` hands it on when a game closes and, in a
 * tiebreak, when the running total turns odd. This button is the umpire's designation and override,
 * which is what its event has always been named for. A correction composes with the schedule rather
 * than fighting it — handover timing comes from the score and cannot be wrong, so the only thing a tap
 * fixes is which side is on, and it stays fixed.
 *
 * **The wire is a side** (#1098), so there is no stand-in player to pick. `servingSide` never reaches
 * the finalized match — `MatchDomain` has no server field — so it is live display state and the point
 * gate, never a persisted claim about who served.
 */
export function ServerControl({
  view,
  busy,
  onAssign,
}: {
  view: LiveMatchResponse
  busy: boolean
  onAssign: (side: 'TEAM1' | 'TEAM2') => void
}) {
  const players = view.players ?? []
  if (players.length === 0) return null

  const servingSide = view.servingSide === 'TEAM1' || view.servingSide === 'TEAM2' ? view.servingSide : undefined
  const targetSide = servingSide === 'TEAM1' ? 'TEAM2' : 'TEAM1'
  const servingLabel = servingSide ? teamLabel({ view, sideId: servingSide }) : null

  return (
    <Button
      size="sm"
      variant={servingSide ? 'secondary' : 'outline'}
      disabled={busy}
      // Names the SIDE, not a player (#1072). Naming one would be a claim the app does not make: the
      // umpire may have called the partner, and only the team is tracked. The visible label is static
      // so the button stops being as wide as the longest name.
      aria-label={
        servingLabel ? `Serving: ${servingLabel}. Tap to switch sides.` : 'Set which side is serving'
      }
      onClick={() => onAssign(targetSide)}
    >
      {/* "Toggle server" is accurate because this really is two-state — the serving TEAM. "Set server"
          while nothing is assigned, because that is an outstanding step rather than a switch, and
          #1070's prompt relies on it reading that way. */}
      {servingSide ? 'Toggle server' : 'Set server'}
    </Button>
  )
}

/** The banked sets, one chip each. Capped so a five-setter cannot push the layout off-screen. */
export function CompletedSets({ view }: { view: LiveMatchResponse }) {
  if (view.sets.length === 0) return null
  return (
    <div className="flex shrink-0 items-center gap-[1dvw] overflow-hidden">
      {view.sets.map((set, index) => (
        <span
          key={index}
          className="whitespace-nowrap rounded bg-secondary px-[1dvw] py-[0.4dvh] text-[2.4dvh] tabular-nums"
        >
          {/* The set number, so a chip identifies itself (#1074). A bare "6-4" beside anything
              set-related invites being read as the CURRENT set's score, and with three banked sets
              "[6-4] [3-6] [7-5]" said nothing about which was which. Small and muted: it is a label,
              not data, and the score is what the umpire is reading. */}
          <span className="mr-[0.4dvw] text-[1.5dvh] font-medium text-muted-foreground">
            S{index + 1}
          </span>
          {set.gamesTeam1}-{set.gamesTeam2}
          {set.tiebreakTeam1Points != null && set.tiebreakTeam2Points != null && (
            <sup className="ml-0.5">
              {Math.min(set.tiebreakTeam1Points, set.tiebreakTeam2Points)}
            </sup>
          )}
        </span>
      ))}
    </div>
  )
}

/**
 * The **match-wide** controls. Per-side actions live under their side of the board (#944), which is what
 * keeps this row short enough to fit a screen that cannot scroll.
 */
export function ScoringActions({
  view,
  controls,
  busy,
  onUndo,
  onStartGame,
  onTiebreak,
  onPauseResume,
  onFlip,
  onFinalize,
}: {
  view: LiveMatchResponse
  /**
   * What this state may show (#1083), including whether this caller may finalize at all — scoring and
   * writing the result are **different rights** (#934), and hiding the button rather than letting it
   * 403 is the #867 lesson. The capability now enters through the same table as everything else, so
   * there is one answer to "why is this not on screen".
   */
  controls: ReadonlySet<Control>
  busy: boolean
  onUndo: () => void
  onStartGame: () => void
  onTiebreak: () => void
  onPauseResume: () => void
  onFlip: () => void
  onFinalize: () => void
}) {
  return (
    <div className="flex shrink-0 flex-wrap items-center gap-[0.8dvw] py-[0.6dvh]">
      {/* Only when there is something in force (#1083) — an Undo that provably does nothing is the
          silently-inert control this work removes. */}
      {controls.has('undo') && (
        <Button size="sm" variant="secondary" disabled={busy} onClick={onUndo}>
          Undo
        </Button>
      )}
      {/* Left of Start tiebreak, because between games they are the two ways forward and at a level
          score they are a genuine choice. */}
      {controls.has('startGame') && (
        <Button size="sm" disabled={busy} onClick={onStartGame}>
          Start game
        </Button>
      )}
      {controls.has('startTiebreak') && (
        <Button size="sm" variant="outline" disabled={busy} onClick={onTiebreak}>
          Start tiebreak
        </Button>
      )}
      {/* One control, two states. `Resume` is the label the spec settled on and the code already used;
          the pause itself is a logged event, so this is a transition like any other. */}
      {(controls.has('pause') || controls.has('resume')) && (
        <Button size="sm" variant="outline" disabled={busy} onClick={onPauseResume}>
          {view.isPaused ? 'Resume' : 'Pause'}
        </Button>
      )}
      {/* A display preference only. Never recorded, so the log cannot be corrupted by a flip (#911 §6).
          `outline` rather than `ghost` (#1071): it has no reason to be quiet — nothing is destroyed by a
          flip — and every reason to look like the control it is. No destructive colour, unlike
          Retire/Default, precisely because it changes nothing. */}
      {controls.has('switchSides') && (
        <Button size="sm" variant="outline" disabled={busy} onClick={onFlip}>
          Switch sides
        </Button>
      )}
      {/*
        The decision point (#984). Awarding a set stops the match here rather than rolling into the
        next, so the umpire chooses: play on, or this was the last set. Before this existed there was
        no moment at which Finalize could be reached on a match that simply finished.
      */}
      {/* "Start next set" moved to the header (#1075), beside where "Start match" renders. Both mean
          "begin play", both appear exactly when play is not under way, and both are the one control
          the umpire is waiting for — so they belong in one place rather than two. They are mutually
          exclusive, so a single header slot holds both. */}
      {controls.has('finalize') && (
        <Button size="sm" disabled={busy} onClick={onFinalize} className="ml-auto">
          Finalize
        </Button>
      )}
    </div>
  )
}
