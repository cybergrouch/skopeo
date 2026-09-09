import { useEffect, useState } from 'react'

/** `mm:ss`, or `h:mm:ss` once a match passes the hour — which tennis matches do. */
export function formatElapsed(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds))
  const hours = Math.floor(safe / 3600)
  const minutes = Math.floor((safe % 3600) / 60)
  const seconds = safe % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`
}

/**
 * A match clock that ticks locally between server updates (#937).
 *
 * The server sends the authoritative elapsed time and whether it is advancing. This adds the seconds
 * since that arrived, so the display moves without polling — and **re-syncs on every response**, which
 * for the umpire view means every action they take.
 *
 * Counting locally rather than from a server timestamp is deliberate: comparing a server instant against
 * the browser's clock would show whatever skew exists between them, and a scoreboard thirty seconds out
 * because a phone's clock is wrong looks broken in a way that is impossible to explain. Counting ticks
 * since arrival never compares the two clocks at all.
 *
 * The trade is that a throttled or backgrounded tab under-counts between syncs. That is the right way
 * round for this: the number is only ever *behind* and is corrected by the next response, whereas a
 * skewed clock can be wrong in either direction and stays wrong.
 */
export function useMatchClock(elapsedSeconds: number, isRunning: boolean): number {
  // Seconds counted locally since the server's figure last changed. Reset by comparing against the
  // previous value DURING RENDER — React's documented way to derive state from a changing prop, and the
  // only one available here: this codebase lints against both refs and setState-in-effect.
  const [seenElapsed, setSeenElapsed] = useState(elapsedSeconds)
  const [ticks, setTicks] = useState(0)
  if (seenElapsed !== elapsedSeconds) {
    setSeenElapsed(elapsedSeconds)
    setTicks(0)
  }

  useEffect(() => {
    if (!isRunning) return
    const id = setInterval(() => setTicks((n) => n + 1), 1000)
    return () => clearInterval(id)
  }, [isRunning, elapsedSeconds])

  return isRunning ? elapsedSeconds + ticks : elapsedSeconds
}
