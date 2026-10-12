import { useId } from 'react'
import { MOMENTUM_WINDOW, SIDE_COLOUR, setBoundariesOf } from './replayCharts'
import type { MomentumPlotProps } from './ReplayMomentumChart'

/**
 * viewBox units of the narrow (below `md`) plot. The width is fixed and scaled to the screen; the height is
 * {@link ROW} per point, so it grows with the match and nothing ever scrolls sideways (#1156).
 */
const WIDTH = 300
const TOP = 26
const ROW = 7
const BOTTOM = 6
const SHARE_LEFT = 8
const SHARE_RIGHT = 214
const SHARE_CENTRE = (SHARE_LEFT + SHARE_RIGHT) / 2
const SHARE_HALF = (SHARE_RIGHT - SHARE_LEFT) / 2
const DIFF_LEFT = 232
const DIFF_RIGHT = 292
const DIFF_CENTRE = (DIFF_LEFT + DIFF_RIGHT) / 2
const DIFF_HALF = (DIFF_RIGHT - DIFF_LEFT) / 2

/**
 * Momentum below `md` (#1156): time runs top to bottom. Team 1's rolling share is a horizontal deviation from
 * a centre line — left when team 1 was on top, right when team 2 was — and the running point difference sits
 * in a narrow column beside it on the same vertical axis, team 1 ahead to the left as well. Set boundaries are
 * horizontal dashed rules.
 */
export function VerticalMomentum({ samples, names, current, onSelect }: MomentumPlotProps) {
  const clipId = useId()
  const total = samples.length
  const height = TOP + total * ROW + BOTTOM
  const yOf = (ordinal: number) => TOP + ordinal * ROW
  const shareX = (share: number) => SHARE_CENTRE - (share - 0.5) * 2 * SHARE_HALF
  const maxSwing = Math.max(1, ...samples.map((s) => Math.abs(s.difference)))
  const diffX = (difference: number) => DIFF_CENTRE - (difference / maxSwing) * DIFF_HALF
  const bottom = yOf(total)

  // Both lines start at the 0-0 position: an even share and no difference.
  const shareLine = `M ${SHARE_CENTRE} ${yOf(0)} ` + samples.map((s) => `L ${shareX(s.team1Share)} ${yOf(s.ordinal)}`).join(' ')
  const shareArea = `${shareLine} L ${SHARE_CENTRE} ${bottom} Z`
  const diffLine = `M ${DIFF_CENTRE} ${yOf(0)} ` + samples.map((s) => `L ${diffX(s.difference)} ${yOf(s.ordinal)}`).join(' ')
  const markerY = yOf(current?.ordinal ?? 0)

  return (
    <svg viewBox={`0 0 ${WIDTH} ${height}`} className="block h-auto w-full" aria-hidden="true" data-orientation="vertical">
      <defs>
        <clipPath id={`${clipId}-left`}>
          <rect x={0} y={0} width={SHARE_CENTRE} height={height} />
        </clipPath>
        <clipPath id={`${clipId}-right`}>
          <rect x={SHARE_CENTRE} y={0} width={WIDTH - SHARE_CENTRE} height={height} />
        </clipPath>
      </defs>

      <text x={SHARE_LEFT} y={10} fontSize={8} fill={SIDE_COLOUR.TEAM1} fontWeight={600}>
        ← {names.TEAM1}
      </text>
      <text x={SHARE_RIGHT} y={10} textAnchor="end" fontSize={8} fill={SIDE_COLOUR.TEAM2} fontWeight={600}>
        {names.TEAM2} →
      </text>
      <text x={SHARE_CENTRE} y={20} textAnchor="middle" fontSize={7} fill="var(--muted-foreground)">
        Share of the last {MOMENTUM_WINDOW} points
      </text>
      <text x={DIFF_CENTRE} y={10} textAnchor="middle" fontSize={7} fill="var(--muted-foreground)">
        Difference
      </text>
      <text x={DIFF_CENTRE} y={20} textAnchor="middle" fontSize={7} fill="var(--muted-foreground)">
        0
      </text>

      <line x1={SHARE_CENTRE} x2={SHARE_CENTRE} y1={yOf(0)} y2={bottom} stroke="var(--border)" strokeDasharray="2 2" />
      <path d={shareArea} fill={SIDE_COLOUR.TEAM1} opacity={0.35} clipPath={`url(#${clipId}-left)`} />
      <path d={shareArea} fill={SIDE_COLOUR.TEAM2} opacity={0.35} clipPath={`url(#${clipId}-right)`} />
      <path d={shareLine} fill="none" stroke="var(--foreground)" strokeWidth={1.25} data-testid="momentum-share" />

      <line x1={DIFF_CENTRE} x2={DIFF_CENTRE} y1={yOf(0)} y2={bottom} stroke="var(--border)" />
      <path d={diffLine} fill="none" stroke="var(--muted-foreground)" strokeWidth={1.25} data-testid="momentum-difference" />

      {setBoundariesOf(samples).map((b) => (
        <g key={b.setNumber} data-testid="momentum-set-boundary">
          <line
            x1={SHARE_LEFT}
            x2={DIFF_RIGHT}
            y1={yOf(b.afterOrdinal)}
            y2={yOf(b.afterOrdinal)}
            stroke="var(--muted-foreground)"
            strokeDasharray="3 2"
          />
          <text x={SHARE_LEFT} y={yOf(b.afterOrdinal) + 8} fontSize={7} fill="var(--muted-foreground)">
            Set {b.setNumber}
          </text>
        </g>
      ))}

      <line
        data-testid="momentum-selected"
        x1={SHARE_LEFT}
        x2={DIFF_RIGHT}
        y1={markerY}
        y2={markerY}
        stroke="var(--foreground)"
        strokeWidth={1.5}
      />

      {/* One full-width hit target per point, centred on it, so a tap anywhere in its row selects it. */}
      {samples.map((s) => (
        <rect
          key={s.index}
          data-testid="momentum-hit"
          x={0}
          y={yOf(s.ordinal) - ROW / 2}
          width={WIDTH}
          height={ROW}
          fill="transparent"
          className="cursor-pointer"
          onClick={() => onSelect(s.index)}
        />
      ))}
    </svg>
  )
}
