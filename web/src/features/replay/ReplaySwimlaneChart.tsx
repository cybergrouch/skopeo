import type { ReplayDocument } from '@/api/generated/model'
import { DESKTOP_QUERY, useMediaQuery } from '@/hooks/useMediaQuery'
import { cn } from '@/lib/utils'
import { hasReplayFeature } from './replayFeatures'
import { SIDE_COLOUR, sampleAtOrBefore, setLanesOf, type LaneStep, type SetLanes, type Side } from './replayCharts'
import { setGroupsOf } from './replayGames'
import { ReplayMarkShape } from './ReplayMarkShape'
import { VerticalSwimlanes } from './ReplaySwimlaneVertical'

/**
 * Pixel geometry of the wide (`md` and up) layout. Unlike the profile charts, this SVG is NOT scaled to fit: a
 * long set runs past 60 points, and squeezing that into the width would make every label illegible. The lanes
 * keep a fixed column width and the set scrolls horizontally instead (#1147). Below `md` the lanes run
 * downward instead (#1156), so a phone never scrolls sideways.
 */
const COLUMN = 34
const LANE = 36
const PAD_X = 14
const PAD_Y = 4
const HEIGHT = PAD_Y * 2 + LANE * 2

const MARK_TEXT: Record<LaneStep['mark'], string> = {
  point: 'point',
  hold: 'game, held serve',
  break: 'game, broke serve',
  game: 'game',
  awarded: 'awarded by the umpire',
}

interface ReplayChartProps {
  replay: ReplayDocument
  /** Position in `replay.points`, or `REPLAY_START` for the opening 0-0. */
  selected: number
  onSelect: (index: number) => void
  team1Name?: string
  team2Name?: string
}

const xOf = (column: number) => PAD_X + column * COLUMN
/** The centre line of a side's lane. */
const yOf = (side: Side) => PAD_Y + (side === 'TEAM1' ? LANE * 0.4 : LANE + LANE * 0.4)
const labelYOf = (side: Side) => yOf(side) + 15

/**
 * Point-by-point swimlanes, one pair per set (#1147) — side by side on a wide screen, top to bottom below `md`
 * (#1156): each step sits in the lane of the side that won it and
 * is labelled by the score it produced. Ordinary points are small marks, games won are larger, and — when the
 * replay carries break data — a break of serve is a diamond where a hold is a circle, so the two differ in
 * shape and not only in colour.
 *
 * The drawing is `aria-hidden`; clicking a mark selects it, but the accessible route is the point list under
 * the chart, a real `<button>` per point carrying the same score as text.
 */
export function ReplaySwimlaneChart({ replay, selected, onSelect, team1Name = 'Team 1', team2Name = 'Team 2' }: ReplayChartProps) {
  const wide = useMediaQuery(DESKTOP_QUERY)
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
      {wide ? (
        sets.map((set) => (
          <SetLaneRow
            key={set.setNumber}
            set={set}
            names={names}
            selected={selected}
            highlighted={highlighted}
            onSelect={onSelect}
          />
        ))
      ) : (
        <VerticalSwimlanes
          lanes={sets}
          groups={setGroupsOf(replay, false, hasBreaks)}
          names={names}
          selected={selected}
          highlighted={highlighted}
          onSelect={onSelect}
        />
      )}
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

interface SetLaneRowProps {
  set: SetLanes
  names: Record<Side, string>
  selected: number
  highlighted: number | null
  onSelect: (index: number) => void
}

/**
 * One set's lanes. The set's title and final score sit OUTSIDE the scrolling area, so they stay visible however
 * far a long set has been scrolled.
 */
function SetLaneRow({ set, names, selected, highlighted, onSelect }: SetLaneRowProps) {
  const width = xOf(set.steps.length) + PAD_X
  const startSelected = selected === set.startIndex
  return (
    <figure className="flex gap-2" aria-label={`Set ${set.setNumber}, ${set.finalScore}`} data-testid={`swimlane-set-${set.setNumber}`}>
      <figcaption className="w-24 shrink-0 text-xs">
        <p className="font-medium">
          Set {set.setNumber} <span className="tabular-nums">{set.finalScore}</span>
        </p>
        <p className="truncate text-muted-foreground" style={{ marginTop: PAD_Y }}>
          {names.TEAM1}
        </p>
        <p className="truncate text-muted-foreground" style={{ marginTop: LANE - 16 }}>
          {names.TEAM2}
        </p>
      </figcaption>
      <div className="min-w-0 flex-1 overflow-x-auto">
        <svg width={width} height={HEIGHT} aria-hidden="true" className="block">
          <line x1={0} x2={width} y1={PAD_Y + LANE} y2={PAD_Y + LANE} stroke="var(--border)" />
          <g
            data-testid="swimlane-start"
            data-selected={startSelected || undefined}
            className="cursor-pointer"
            onClick={() => onSelect(set.startIndex)}
          >
            <rect x={xOf(0) - COLUMN / 2} y={0} width={COLUMN} height={HEIGHT} fill="transparent" />
            <circle
              cx={xOf(0)}
              cy={PAD_Y + LANE}
              r={3}
              fill="var(--muted-foreground)"
              stroke={startSelected ? 'var(--foreground)' : 'none'}
              strokeWidth={2}
            />
            <text x={xOf(0)} y={PAD_Y + LANE + 14} textAnchor="middle" fontSize={9} fill="var(--muted-foreground)">
              0-0
            </text>
          </g>
          {set.steps.map((step) => (
            <StepMark key={step.index} step={step} highlighted={step.index === highlighted} onSelect={onSelect} />
          ))}
        </svg>
      </div>
    </figure>
  )
}

function StepMark({ step, highlighted, onSelect }: { step: LaneStep; highlighted: boolean; onSelect: (index: number) => void }) {
  const x = xOf(step.column)
  const y = yOf(step.side)
  const big = step.mark !== 'point'
  return (
    <g
      data-testid="swimlane-step"
      data-index={step.index}
      data-mark={step.mark}
      data-side={step.side}
      data-selected={highlighted || undefined}
      className="cursor-pointer"
      onClick={() => onSelect(step.index)}
    >
      {/* A full-height hit target: far easier to tap than a 3px dot. */}
      <rect x={x - COLUMN / 2} y={0} width={COLUMN} height={HEIGHT} fill={highlighted ? 'var(--muted)' : 'transparent'} />
      <ReplayMarkShape mark={step.mark} x={x} y={y} colour={SIDE_COLOUR[step.side]} highlighted={highlighted} />
      <text
        x={x}
        y={labelYOf(step.side)}
        textAnchor="middle"
        fontSize={9}
        fontWeight={big ? 600 : 400}
        fill={big ? 'var(--foreground)' : 'var(--muted-foreground)'}
      >
        {step.label}
      </text>
    </g>
  )
}
