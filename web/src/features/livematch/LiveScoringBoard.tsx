import { Button } from '@/components/ui/button'
import type { LiveMatchResponse } from '@/api/generated/model'

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
  onPoint,
}: {
  view: LiveMatchResponse
  /** View-only side swap for when the players change ends. NEVER recorded — see #911 §6. */
  flipped: boolean
  busy: boolean
  onPoint: (side: 'TEAM1' | 'TEAM2') => void
}) {
  const sides = [
    { id: 'TEAM1' as const, points: view.pointsTeam1, games: view.gamesTeam1 },
    { id: 'TEAM2' as const, points: view.pointsTeam2, games: view.gamesTeam2 },
  ]
  const ordered = flipped ? [sides[1], sides[0]] : sides

  return (
    <div className="grid min-h-0 flex-1 grid-cols-2 gap-[1dvh]">
      {ordered.map((side) => (
        <button
          key={side.id}
          type="button"
          disabled={busy}
          aria-label={`Point to ${side.id}`}
          onClick={() => onPoint(side.id)}
          className="flex min-h-0 flex-col items-center justify-center rounded-lg bg-muted
                     transition-colors hover:bg-muted/70 active:bg-muted/50 disabled:opacity-60"
        >
          <span className="text-[14dvh] font-bold leading-none tabular-nums">{side.points}</span>
          <span className="mt-[1dvh] text-[4dvh] leading-none text-muted-foreground tabular-nums">
            {side.games} {side.games === 1 ? 'game' : 'games'}
          </span>
        </button>
      ))}
    </div>
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

/** The umpire's controls. Kept to one row so the board keeps the rest of the screen. */
export function ScoringActions({
  view,
  busy,
  canFinalize,
  onUndo,
  onEndSet,
  onTiebreak,
  onRetire,
  onDefault,
  onPauseResume,
  onFlip,
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
  onEndSet: (side: 'TEAM1' | 'TEAM2') => void
  onTiebreak: () => void
  onRetire: (side: 'TEAM1' | 'TEAM2') => void
  onDefault: (side: 'TEAM1' | 'TEAM2') => void
  onPauseResume: () => void
  onFlip: () => void
  onFinalize: () => void
}) {
  const finished = view.outcome != null
  return (
    <div className="flex shrink-0 flex-wrap items-center gap-[0.8dvw] py-[0.6dvh]">
      <Button size="sm" variant="secondary" disabled={busy} onClick={onUndo}>
        Undo
      </Button>
      <Button size="sm" variant="outline" disabled={busy || finished} onClick={() => onEndSet('TEAM1')}>
        Set to 1
      </Button>
      <Button size="sm" variant="outline" disabled={busy || finished} onClick={() => onEndSet('TEAM2')}>
        Set to 2
      </Button>
      <Button
        size="sm"
        variant="outline"
        disabled={busy || finished || view.isTiebreak}
        onClick={onTiebreak}
      >
        Start tiebreak
      </Button>
      <Button size="sm" variant="outline" disabled={busy} onClick={onPauseResume}>
        {view.isPaused ? 'Resume' : 'Pause'}
      </Button>
      {/* A display preference only. Never recorded, so the log cannot be corrupted by a flip (#911 §6). */}
      <Button size="sm" variant="ghost" disabled={busy} onClick={onFlip}>
        Switch sides
      </Button>
      <Button size="sm" variant="ghost" disabled={busy || finished} onClick={() => onRetire('TEAM1')}>
        1 retires
      </Button>
      <Button size="sm" variant="ghost" disabled={busy || finished} onClick={() => onRetire('TEAM2')}>
        2 retires
      </Button>
      <Button size="sm" variant="ghost" disabled={busy || finished} onClick={() => onDefault('TEAM1')}>
        1 defaults
      </Button>
      <Button size="sm" variant="ghost" disabled={busy || finished} onClick={() => onDefault('TEAM2')}>
        2 defaults
      </Button>
      {canFinalize && (
        <Button size="sm" disabled={busy || !finished} onClick={onFinalize} className="ml-auto">
          Finalize
        </Button>
      )}
    </div>
  )
}
