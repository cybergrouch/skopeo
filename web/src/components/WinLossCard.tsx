import { useGetApiV1PlayersCodeResultsSummary } from '@/api/generated/users/users'
import type { ResultsTotals } from '@/api/generated/model'
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

/** One "Matches / Wins / Losses / Win rate" row for a format (or the combined overall). */
function SummaryRow({ label, totals }: { label: string; totals: ResultsTotals }) {
  return (
    <tr className="border-t">
      <th scope="row" className="py-2 pr-4 text-left font-medium">
        {label}
      </th>
      <td className="py-2 pr-4 text-right tabular-nums">{totals.played}</td>
      <td className="py-2 pr-4 text-right tabular-nums">{totals.wins}</td>
      <td className="py-2 pr-4 text-right tabular-nums">{totals.losses}</td>
      <td className="py-2 text-right tabular-nums">
        {totals.winRate == null ? 'n/a' : `${totals.winRate}%`}
      </td>
    </tr>
  )
}

/**
 * A player's win–loss record (#276), shown on the owner's Profile tab and the public profile alike.
 *
 * Every figure is **assembled server-side** (#845) — totals and win rate included — so this component
 * only presents. A null [ResultsTotals.winRate] means nothing is decided and renders "n/a"; that is the
 * server's call, not a zero-denominator branch here. No rating is ever shown.
 */
export function WinLossCard({ code }: WinLossCardProps) {
  const query = useGetApiV1PlayersCodeResultsSummary(code, {
    query: { enabled: Boolean(code) },
  })
  const summary = query.data
  const hasResults = Boolean(summary && summary.overall.played > 0)

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
              <span className="font-medium tabular-nums">{summary.overall.played}</span>
            </p>
            {/* Five columns on a phone: let the table scroll inside the card rather than widening it
                (#768). Every other table in the app already does this. */}
            <div className="overflow-x-auto">
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
                  <SummaryRow label="Singles" totals={summary.singles} />
                  <SummaryRow label="Doubles" totals={summary.doubles} />
                  <SummaryRow label="Overall" totals={summary.overall} />
                </tbody>
              </table>
            </div>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">No completed matches yet.</p>
        )}
      </CardContent>
    </Card>
  )
}
