import type { OpponentBandSeriesRelation } from '@/api/generated/model'

/** Reader-facing name per relation (#845) — the API's SAME/HIGHER/LOWER are not user copy. */
export const RELATION_LABELS: Record<OpponentBandSeriesRelation, string> = {
  SAME: 'Same band',
  HIGHER: 'Higher band',
  LOWER: 'Lower band',
}

/**
 * The shared encoding for both opponent-band charts (#845): **hue carries the band relation, shade carries
 * the outcome**. Defined once here so the donut and the sparklines cannot drift apart — the whole reason a
 * reader can carry the legend from one to the other is that the pairing is identical.
 *
 * Theme tokens rather than literals, because the app has a dark mode and a dozen club themes; the dark
 * variants invert the lightness relationship so the "muted" shade still reads as *less* on a dark surface.
 * Red is deliberately unused — it belongs to `--destructive`, and a loss is not an error.
 */
export const RELATION_HUES: Record<OpponentBandSeriesRelation, { win: string; loss: string }> = {
  SAME: { win: 'var(--chart-1)', loss: 'var(--chart-1-muted)' },
  HIGHER: { win: 'var(--chart-2)', loss: 'var(--chart-2-muted)' },
  LOWER: { win: 'var(--chart-3)', loss: 'var(--chart-3-muted)' },
}
