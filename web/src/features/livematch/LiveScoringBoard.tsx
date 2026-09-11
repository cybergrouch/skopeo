import { Button } from '@/components/ui/button'
import type { LiveMatchResponse } from '@/api/generated/model'

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
  const finished = view.outcome != null
  // Scoring is inert until the match is started (#986), while between sets (#984), and once finished.
  // The server rule (#985) applies to points only — a game or set is an umpire declaration, which does
  // not need one. Disabled rather than hidden: a board that vanishes and returns is disorienting, and
  // a greyed one beside a prominent action reads as "do that first".
  const scorable = view.hasStarted && !view.isBetweenSets && !finished
  const canScorePoints = scorable && view.serverId != null

  return (
    <div className="grid min-h-0 flex-1 grid-cols-2 gap-[1dvh]">
      {ordered.map((side) => (
        <div key={side.id} className="flex min-h-0 flex-col gap-[0.6dvh]">
          <button
            type="button"
            disabled={busy || !canScorePoints}
            aria-label={`Point to ${side.name}`}
            onClick={() => onPoint(side.id)}
            className="flex min-h-0 flex-1 flex-col items-center justify-center gap-[0.5dvh] rounded-lg
                       bg-muted px-[1dvw] transition-colors hover:bg-muted/70 active:bg-muted/50
                       disabled:opacity-60"
          >
            {/* Truncated rather than wrapped: a long doubles pairing must not grow the row and push the
                action bar off a screen that is not allowed to scroll. */}
            <span className="max-w-full truncate text-[3.4dvh] font-semibold leading-none">
              {side.name}
            </span>
            <span className="text-[13dvh] font-bold leading-none tabular-nums">{side.points}</span>
            <span className="text-[3.6dvh] leading-none text-muted-foreground tabular-nums">
              {side.games} {side.games === 1 ? 'game' : 'games'}
            </span>
          </button>

          {/*
            Per-side actions live UNDER their side rather than in the shared row (#944). Two reasons: a
            single row could not hold twelve buttons on a screen that is not allowed to scroll, and
            sitting beneath the named side makes "Game" unambiguous without repeating the name on every
            button. They cannot be nested inside the tap target above — a button inside a button is
            invalid HTML and the inner clicks would not be reachable.
          */}
          <div className="flex shrink-0 flex-wrap justify-center gap-[0.6dvw]">
            <Button
              size="sm"
              variant="outline"
              disabled={busy || !scorable}
              aria-label={`Game to ${side.name}`}
              onClick={() => onGame(side.id)}
            >
              Game
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy || !scorable}
              aria-label={`Set to ${side.name}`}
              onClick={() => onEndSet(side.id)}
            >
              Set
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={busy || finished || !view.hasStarted}
              aria-label={`${side.name} retires`}
              onClick={() => onRetire(side.id)}
            >
              Retire
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={busy || finished || !view.hasStarted}
              aria-label={`${side.name} defaults`}
              onClick={() => onDefault(side.id)}
            >
              Default
            </Button>
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * Who is serving, and a one-tap way to change it (#943).
 *
 * A **cycle** rather than a dropdown: the candidates are the two or four players in this match, and an
 * umpire mid-game wants one tap, not a menu. The alternative — re-picking from a list every game — is
 * enough friction that the field simply stops being maintained, and a serving indicator nobody updates
 * is worse than none because the scoreboard then shows something confidently wrong.
 *
 * Nothing auto-rotates. Whose turn it is is a format rule and the umpire is the authority (#928); this
 * only makes saying so cheap.
 */
export function ServerControl({
  view,
  busy,
  onAssign,
}: {
  view: LiveMatchResponse
  busy: boolean
  onAssign: (playerId: string) => void
}) {
  const players = view.players ?? []
  if (players.length === 0) return null

  const current = players.findIndex((p) => p.userId === view.serverId)
  const next = players[(current + 1) % players.length]

  return (
    <Button
      size="sm"
      variant={view.serverId ? 'secondary' : 'outline'}
      disabled={busy}
      aria-label={
        view.serverName ? `Serving: ${view.serverName}. Tap to change.` : 'Set who is serving'
      }
      onClick={() => onAssign(next.userId)}
    >
      {view.serverName ? `Serving: ${view.serverName}` : 'Set server'}
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
  busy,
  canFinalize,
  onUndo,
  onTiebreak,
  onPauseResume,
  onFlip,
  onStartSet,
  onFinalize,
}: {
  view: LiveMatchResponse
  busy: boolean
  /**
   * Whether this caller may write the result. Scoring and finalizing are **different rights** (#934):
   * a plain SCORER keys points in, but recording the result keeps the #789 organizer gate. Hiding the
   * button rather than letting it 403 is the #867 lesson.
   */
  canFinalize: boolean
  onUndo: () => void
  onTiebreak: () => void
  onPauseResume: () => void
  onFlip: () => void
  onStartSet: () => void
  onFinalize: () => void
}) {
  const finished = view.outcome != null
  const betweenSets = view.isBetweenSets && !finished
  return (
    <div className="flex shrink-0 flex-wrap items-center gap-[0.8dvw] py-[0.6dvh]">
      <Button size="sm" variant="secondary" disabled={busy} onClick={onUndo}>
        Undo
      </Button>
      <Button
        size="sm"
        variant="outline"
        disabled={busy || finished || betweenSets || !view.hasStarted || view.isTiebreak}
        onClick={onTiebreak}
      >
        Start tiebreak
      </Button>
      <Button
        size="sm"
        variant="outline"
        disabled={busy || finished || !view.hasStarted}
        onClick={onPauseResume}
      >
        {view.isPaused ? 'Resume' : 'Pause'}
      </Button>
      {/* A display preference only. Never recorded, so the log cannot be corrupted by a flip (#911 §6). */}
      <Button size="sm" variant="ghost" disabled={busy} onClick={onFlip}>
        Switch sides
      </Button>
      {/*
        The decision point (#984). Awarding a set stops the match here rather than rolling into the
        next, so the umpire chooses: play on, or this was the last set. Before this existed there was
        no moment at which Finalize could be reached on a match that simply finished.
      */}
      {betweenSets && (
        <Button size="sm" variant="outline" disabled={busy} onClick={onStartSet}>
          Start next set
        </Button>
      )}
      {canFinalize && (
        <Button
          size="sm"
          disabled={busy || !(finished || betweenSets)}
          onClick={onFinalize}
          className="ml-auto"
        >
          Finalize
        </Button>
      )}
    </div>
  )
}
