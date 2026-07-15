/**
 * Rating confidence (#343) arrives from the API as a 0..1 decimal string (computed server-side).
 * Format it as a whole-percent label for display next to the NTRP band, or null when it's absent.
 * This only formats a provided value — the UI never calculates confidence itself.
 */
export function formatConfidence(confidence?: string | null): string | null {
  if (confidence == null || confidence === '') return null
  const fraction = Number(confidence)
  if (Number.isNaN(fraction)) return null
  return `${Math.round(fraction * 100)}%`
}
