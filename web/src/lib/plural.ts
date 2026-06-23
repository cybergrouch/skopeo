/** The "es" suffix for counts other than one (match → matches). */
export function plural(count: number): string {
  return count === 1 ? '' : 'es'
}
