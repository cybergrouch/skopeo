import type { CalculationBreakdownResponse } from '@/api/generated/model'

/**
 * Renders the calculator derivatives behind a single player's rating change. The v1 (averaged)
 * calculator reports one net line; the v2 per-set calculator (issue #110) reports a step per set,
 * carrying the rating forward. We pick the shape by whether `sets` is populated, so the same
 * component serves both the dry-run preview and the persisted history detail.
 */
export function CalculationBreakdownDetail({
  breakdown,
}: {
  breakdown: CalculationBreakdownResponse
}) {
  const sets = breakdown.sets ?? []

  if (sets.length > 0) {
    return (
      <ul className="space-y-1 text-xs text-muted-foreground">
        {sets.map((set) => (
          <li key={set.setIndex}>
            <span className="font-medium">Set {set.setIndex + 1}</span> ({set.score}):
            dominance {set.dominance} · scale {set.scale} · gap {set.ratingGap}/
            {set.competitiveThresholdPct} · {set.isUpset ? 'upset' : 'expected'} · K{' '}
            {set.kFactor} · Δ {set.delta} → {set.ratingAfter}
          </li>
        ))}
      </ul>
    )
  }

  return (
    <div className="text-xs text-muted-foreground">
      dominance {breakdown.dominance} · scale {breakdown.scale} · gap{' '}
      {breakdown.ratingGap}/{breakdown.competitiveThresholdPct} ·{' '}
      {breakdown.isUpset ? 'upset' : 'expected'} · K {breakdown.kFactor}
    </div>
  )
}
