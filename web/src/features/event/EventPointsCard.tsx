import type { AwardedPointsPlayerRow } from '@/api/generated/model'
import { useGetApiV1EventsCodeCodePoints } from '@/api/generated/events/events'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ContentLink } from '@/components/ContentLink'
import { PlaceholderTag } from '@/components/PlaceholderTag'
import { formatPoints } from '@/lib/points'

/**
 * What an event awarded, per player (#857) — on the public event page, under the roster.
 *
 * **Absent, not empty, when there is nothing to show.** An event legitimately has no awards for two
 * ordinary reasons — it is not finalized yet, or its "Award ranking points" flag is off (#831) — and a
 * card sitting there empty reads as a fault rather than as an absence. So the whole card disappears.
 *
 * **Amounts only.** No derivation: this page is viewable anonymously, and explaining an amount means
 * surfacing the band relation, which is rating-adjacent (#583/#654) and gated separately (#858). The
 * amounts themselves are already public — a points total is public under the POINTS standings source, and
 * rank and band are public (#64/#114).
 *
 * Every figure is assembled server-side, including the total, so this component never adds points up.
 */
export function EventPointsCard({ code }: { code: string }) {
  const query = useGetApiV1EventsCodeCodePoints(code, { query: { enabled: Boolean(code), retry: false } })
  const rows = query.data?.rows ?? []

  // Nothing awarded (or still loading, or the request failed): show nothing at all rather than a shell.
  if (rows.length === 0) {
    return null
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Ranking points awarded</CardTitle>
        <CardDescription>
          What this event paid each player. Revoked awards are excluded; points remain listed here after
          they expire, since this records what was awarded.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        <ul className="space-y-1 text-sm">
          {rows.map((row) => (
            <PointsRow key={row.userId} row={row} />
          ))}
        </ul>
        <p className="text-xs text-muted-foreground">
          Total awarded:{' '}
          <span className="font-medium tabular-nums">
            {formatPoints(query.data?.totalPoints) ?? query.data?.totalPoints}
          </span>
        </p>
      </CardContent>
    </Card>
  )
}

/** One player's line: name (linked when they have a public code) and their total for this event. */
function PointsRow({ row }: { row: AwardedPointsPlayerRow }) {
  const name = row.displayName ?? row.publicCode ?? row.userId
  return (
    <li className="flex items-baseline justify-between gap-2">
      <span className="min-w-0">
        {row.publicCode ? (
          <ContentLink to={`/players/${row.publicCode}`}>{name}</ContentLink>
        ) : (
          name
        )}
        <PlaceholderTag show={row.isPlaceholder} deleted={row.isDeleted} />
      </span>
      <span className="shrink-0 font-medium tabular-nums">
        {formatPoints(row.points) ?? row.points}
      </span>
    </li>
  )
}
