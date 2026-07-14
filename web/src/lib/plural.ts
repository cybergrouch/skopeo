/**
 * The plural suffix for a [count]: "" for exactly one, otherwise [suffix]. Defaults to "s"
 * (player → players); pass "es" for words like match → matches.
 */
export function plural(count: number, suffix = 's'): string {
  return count === 1 ? '' : suffix
}
