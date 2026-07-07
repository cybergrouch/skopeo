import { lazy, Suspense } from 'react'
import { useGetApiV1PlayersCodeResultsSummary } from '@/api/generated/users/users'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'

// Recharts lives only in WinLossChart, loaded lazily so its weight never hits the initial bundle (#276/#277).
const WinLossChart = lazy(() => import('@/components/WinLossChart'))

interface WinLossCardProps {
  /** The player's shareable public code; the same endpoint serves own- and public-profile views. */
  code: string
}

/**
 * A player's win–loss record over time (#276), shown on the owner's Profile tab and the public profile
 * alike. Wins/losses are bucketed by month and split into singles vs doubles, aggregated server-side —
 * no rating is ever shown. The chart itself (Recharts) is code-split behind Suspense.
 */
export function WinLossCard({ code }: WinLossCardProps) {
  const query = useGetApiV1PlayersCodeResultsSummary(code, {
    query: { enabled: Boolean(code) },
  })
  const summary = query.data
  const hasResults = Boolean(summary && (summary.singles.length > 0 || summary.doubles.length > 0))

  return (
    <Card>
      <CardHeader>
        <CardTitle>Win–loss record</CardTitle>
        <CardDescription>
          Wins and losses by month, for singles and doubles. Ratings are never shown.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : hasResults && summary ? (
          <Suspense fallback={<p className="text-sm text-muted-foreground">Loading chart…</p>}>
            <WinLossChart summary={summary} />
          </Suspense>
        ) : (
          <p className="text-sm text-muted-foreground">No completed matches yet.</p>
        )}
      </CardContent>
    </Card>
  )
}
