/**
 * Ranking points (#403) are integral by design (decision #6) and effectively non-negative, but the
 * backend serializes the standings metric from a NUMERIC(12,4) BigDecimal (#458) — so it carries a
 * `.0000` fractional part and renders like a rating. Format it as a signed integer (e.g. "+240"):
 * round to a whole number and prepend the sign (`+` for ≥ 0, `-` for < 0). Returns null for
 * null/empty/unparseable input. This only formats a provided value; the UI never computes points.
 */
export function formatPoints(value: string | number | null | undefined): string | null {
  if (value == null || value === '') return null
  const n = Number(value)
  if (Number.isNaN(n)) return null
  const rounded = Math.round(n)
  return `${rounded >= 0 ? '+' : '-'}${Math.abs(rounded)}`
}
