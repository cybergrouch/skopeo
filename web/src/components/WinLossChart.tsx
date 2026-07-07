import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { PlayerResultsSummary, ResultsBucket } from '@/api/generated/model'

/**
 * The win–loss-over-time histogram (#276), rendered with Recharts. This module is the ONLY place that
 * imports Recharts and is loaded lazily (see WinLossCard) so its weight never hits the initial bundle.
 * Purely presentational — all bucketing happens server-side.
 */

/** One format's stacked wins/losses bars per month, or a small note when that format has no matches. */
function FormatChart({ title, buckets }: { title: string; buckets: ResultsBucket[] }) {
  return (
    <div>
      <div className="text-xs font-medium uppercase text-muted-foreground">{title}</div>
      {buckets.length > 0 ? (
        <ResponsiveContainer width="100%" height={180}>
          <BarChart data={buckets} margin={{ top: 8, right: 8, bottom: 0, left: -20 }}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
            <XAxis dataKey="period" fontSize={11} tickLine={false} />
            <YAxis allowDecimals={false} fontSize={11} tickLine={false} axisLine={false} />
            <Tooltip />
            <Legend />
            <Bar dataKey="wins" name="Wins" stackId="record" fill="var(--primary)" />
            <Bar dataKey="losses" name="Losses" stackId="record" fill="var(--destructive)" />
          </BarChart>
        </ResponsiveContainer>
      ) : (
        <p className="mt-1 text-sm text-muted-foreground">No {title.toLowerCase()} matches yet.</p>
      )}
    </div>
  )
}

export default function WinLossChart({ summary }: { summary: PlayerResultsSummary }) {
  return (
    <div className="space-y-4">
      <FormatChart title="Singles" buckets={summary.singles} />
      <FormatChart title="Doubles" buckets={summary.doubles} />
    </div>
  )
}
