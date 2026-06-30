/**
 * Build inclusive interval notation from optional min/max bounds, e.g. "[3.0,4.0]", "[3.0,)", "(,30]".
 * Returns undefined when both bounds are blank. Shared by the player-search filters (Research + Ratings).
 */
export function interval(min: string, max: string): string | undefined {
  const lo = min.trim()
  const hi = max.trim()
  if (!lo && !hi) return undefined
  return `${lo ? `[${lo}` : '('},${hi ? `${hi}]` : ')'}`
}
