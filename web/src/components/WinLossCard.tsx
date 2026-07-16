import { useGetApiV1PlayersCodeResultsSummary } from '@/api/generated/users/users'
import type { ResultsBucket } from '@/api/generated/model'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'

interface WinLossCardProps {
  /** The player's shareable public code; the same endpoint serves own- and public-profile views. */
  code: string
}

/** Wins/losses summed across one format's monthly buckets. `played` counts decided matches only. */
interface FormatTotals {
  wins: number
  losses: number
  played: number
}

function totalsFor(buckets: ResultsBucket[]): FormatTotals {
  const wins = buckets.reduce((sum, bucket) => sum + bucket.wins, 0)
  const losses = buckets.reduce((sum, bucket) => sum + bucket.losses, 0)
  return { wins, losses, played: wins + losses }
}

/** Win rate as a whole-percent string, or "n/a" when there are no decided matches (never 0/0 or NaN). */
function winRate(wins: number, losses: number): string {
  const decided = wins + losses
  if (decided === 0) return 'n/a'
  return `${Math.round((wins / decided) * 100)}%`
}

/** One "Matches / Wins / Losses / Win rate" row for a format (or the combined overall). */
function SummaryRow({ label, totals }: { label: string; totals: FormatTotals }) {
  return (
    <tr className="border-t">
      <th scope="row" className="py-2 pr-4 text-left font-medium">
        {label}
      </th>
      <td className="py-2 pr-4 text-right tabular-nums">{totals.played}</td>
      <td className="py-2 pr-4 text-right tabular-nums">{totals.wins}</td>
      <td className="py-2 pr-4 text-right tabular-nums">{totals.losses}</td>
      <td className="py-2 text-right tabular-nums">{winRate(totals.wins, totals.losses)}</td>
    </tr>
  )
}

/**
 * A player's win–loss record (#276), shown on the owner's Profile tab and the public profile alike.
 * Wins/losses are bucketed by month and split into singles vs doubles server-side; here we sum those
 * buckets client-side into a compact table (#406). No rating is ever shown.
 */
export function WinLossCard({ code }: WinLossCardProps) {
  const query = useGetApiV1PlayersCodeResultsSummary(code, {
    query: { enabled: Boolean(code) },
  })
  const summary = query.data
  const hasResults = Boolean(summary && (summary.singles.length > 0 || summary.doubles.length > 0))

  const singles = summary ? totalsFor(summary.singles) : { wins: 0, losses: 0, played: 0 }
  const doubles = summary ? totalsFor(summary.doubles) : { wins: 0, losses: 0, played: 0 }
  const overall: FormatTotals = {
    wins: singles.wins + doubles.wins,
    losses: singles.losses + doubles.losses,
    played: singles.played + doubles.played,
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Win–loss record</CardTitle>
        <CardDescription>
          Singles and doubles totals across all completed matches. Ratings are never shown.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : hasResults && summary ? (
          <div className="space-y-3">
            <p className="text-sm">
              Total matches played:{' '}
              <span className="font-medium tabular-nums">{overall.played}</span>
            </p>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs uppercase text-muted-foreground">
                  <th scope="col" className="pb-1 pr-4 text-left font-medium" />
                  <th scope="col" className="pb-1 pr-4 text-right font-medium">
                    Played
                  </th>
                  <th scope="col" className="pb-1 pr-4 text-right font-medium">
                    Wins
                  </th>
                  <th scope="col" className="pb-1 pr-4 text-right font-medium">
                    Losses
                  </th>
                  <th scope="col" className="pb-1 text-right font-medium">
                    Win rate
                  </th>
                </tr>
              </thead>
              <tbody>
                <SummaryRow label="Singles" totals={singles} />
                <SummaryRow label="Doubles" totals={doubles} />
                <SummaryRow label="Overall" totals={overall} />
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">No completed matches yet.</p>
        )}
      </CardContent>
    </Card>
  )
}
