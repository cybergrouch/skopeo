import type { ReplayResponse } from '@/api/generated/model'
import { cn } from '@/lib/utils'
import { hasReplayFeature } from './replayFeatures'
import { scorerOf } from './replayScorers'
import { SIDE_COLOUR, sampleAtOrBefore, setLanesOf, type LaneStep, type Side } from './replayCharts'
import { setGroupsOf } from './replayGames'
import { VerticalSwimlanes } from './ReplaySwimlaneVertical'

const MARK_TEXT: Record<LaneStep['mark'], string> = {
  point: 'point',
  hold: 'game, held serve',
  break: 'game, broke serve',
  game: 'game',
  awarded: 'awarded by the umpire',
}

interface ReplayChartProps {
  replay: ReplayResponse
  /** Position in `replay.points`, or `REPLAY_START` for the opening 0-0. */
  selected: number
  onSelect: (index: number) => void
  team1Name?: string
  team2Name?: string
}

/**
 * Point-by-point swimlanes, one pair per set (#1147), running top to bottom at every screen size (#1156): each
 * step sits in the lane of the side that won it and is labelled by the score it produced. Ordinary points are small marks, games won are larger, and — when the
 * replay carries break data — a break of serve is a diamond where a hold is a circle, so the two differ in
 * shape and not only in colour.
 *
 * The drawing is `aria-hidden`; clicking a mark selects it, but the accessible route is the point list under
 * the chart, a real `<button>` per point carrying the same score as text.
 */
export function ReplaySwimlaneChart({ replay, selected, onSelect, team1Name = 'Team 1', team2Name = 'Team 2' }: ReplayChartProps) {
  const hasBreaks = hasReplayFeature(replay, 'breaks')
  const sets = setLanesOf(replay, hasBreaks)
  if (sets.length === 0) {
    return <p className="text-sm text-muted-foreground">No points were recorded for this match.</p>
  }
  const names: Record<Side, string> = { TEAM1: team1Name, TEAM2: team2Name }
  const highlighted = sampleAtOrBefore(sets.flatMap((set) => set.steps), selected)?.index ?? null

  return (
    <div className="space-y-4">
      <Legend hasBreaks={hasBreaks} names={names} />
      {/* Capped at a phone's width and centred, so the chart reads the same on every device: its geometry is
          scaled to the width, and a desktop-wide card would otherwise blow every label up. */}
      <div className="mx-auto w-full max-w-md">
        <VerticalSwimlanes
          lanes={sets}
          groups={setGroupsOf(replay, false, hasBreaks)}
          names={names}
          selected={selected}
          highlighted={highlighted}
          onSelect={onSelect}
          // Per-point scorers (#1183) only when the server sent them, which it does only to the entitled.
          scorerOf={hasReplayFeature(replay, 'scorers') ? (index) => scorerOf(replay, index) : null}
        />
      </div>
      <details className="text-sm">
        <summary className="cursor-pointer text-muted-foreground">Points as a list</summary>
        {sets.map((set) => (
          <section key={set.setNumber} aria-label={`Set ${set.setNumber} points`} className="mt-2">
            <h4 className="font-medium">
              Set {set.setNumber} · {set.finalScore}
            </h4>
            <ol className="mt-1 space-y-0.5">
              {set.steps.map((step) => (
                <li key={step.index}>
                  <button
                    type="button"
                    aria-current={step.index === selected ? 'true' : undefined}
                    onClick={() => onSelect(step.index)}
                    className={cn(
                      'rounded-sm text-left underline decoration-dotted underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      step.index === selected && 'font-semibold',
                    )}
                  >
                    {step.label} — {names[step.side]} ({MARK_TEXT[step.mark]}
                    {step.tiebreak ? ', tiebreak' : ''})
                  </button>
                </li>
              ))}
            </ol>
          </section>
        ))}
      </details>
    </div>
  )
}

function Legend({ hasBreaks, names }: { hasBreaks: boolean; names: Record<Side, string> }) {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground" aria-label="Legend">
      {(['TEAM1', 'TEAM2'] as const).map((side) => (
        <li key={side} className="flex items-center gap-1">
          <span aria-hidden="true" className="size-2.5 rounded-full" style={{ background: SIDE_COLOUR[side] }} />
          {names[side]}
        </li>
      ))}
      <li className="flex items-center gap-1">
        <svg aria-hidden="true" width={12} height={12} viewBox="0 0 12 12">
          <circle cx={6} cy={6} r={2.5} fill="currentColor" />
        </svg>
        Point
      </li>
      {hasBreaks ? (
        <>
          <li className="flex items-center gap-1">
            <svg aria-hidden="true" width={12} height={12} viewBox="0 0 12 12">
              <circle cx={6} cy={6} r={5} fill="currentColor" />
            </svg>
            Game held
          </li>
          <li className="flex items-center gap-1">
            <svg aria-hidden="true" width={12} height={12} viewBox="0 0 12 12">
              <path d="M 6 0 L 12 6 L 6 12 L 0 6 Z" fill="currentColor" />
            </svg>
            Break of serve
          </li>
        </>
      ) : (
        <li className="flex items-center gap-1">
          <svg aria-hidden="true" width={12} height={12} viewBox="0 0 12 12">
            <circle cx={6} cy={6} r={5} fill="currentColor" />
          </svg>
          Game
        </li>
      )}
    </ul>
  )
}
