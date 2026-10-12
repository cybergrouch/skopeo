import type { LaneMark } from './replayCharts'

/**
 * A step's mark in the swimlanes, in either orientation (#1147, #1156): a small dot for a point, a large one
 * for a game (held, or of unknown kind), a diamond for a break and a hollow square for an umpire's award — so
 * a break differs from a hold in shape and not only in colour.
 */
export function ReplayMarkShape({
  mark,
  x,
  y,
  colour,
  highlighted,
}: {
  mark: LaneMark
  x: number
  y: number
  colour: string
  highlighted: boolean
}) {
  const ring = highlighted ? 'var(--foreground)' : 'var(--background)'
  if (mark === 'break') {
    return <path d={`M ${x} ${y - 7} L ${x + 7} ${y} L ${x} ${y + 7} L ${x - 7} ${y} Z`} fill={colour} stroke={ring} strokeWidth={2} />
  }
  if (mark === 'awarded') {
    return <rect x={x - 5} y={y - 5} width={10} height={10} fill="none" stroke={colour} strokeWidth={2} />
  }
  return <circle cx={x} cy={y} r={mark === 'point' ? 3 : 6} fill={colour} stroke={highlighted ? ring : 'none'} strokeWidth={2} />
}
