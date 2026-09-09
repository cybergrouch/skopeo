import { formatElapsed, useMatchClock } from './useMatchClock'

/**
 * Elapsed playing time, ticking while the match is live (#937).
 *
 * Renders nothing until the match has officially started — a `0:00` on a fixture nobody has begun would
 * claim the clock is running when it is not.
 */
export function MatchClock({
  elapsedSeconds,
  isRunning,
  className,
}: {
  elapsedSeconds: number
  isRunning: boolean
  className?: string
}) {
  const seconds = useMatchClock(elapsedSeconds, isRunning)
  if (elapsedSeconds === 0 && !isRunning) return null
  return (
    <span className={className} aria-label="Elapsed playing time">
      {formatElapsed(seconds)}
    </span>
  )
}
