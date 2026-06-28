import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useGetApiV1Standings } from '@/api/generated/standings/standings'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import type { StandingEntryResponse } from '@/api/generated/model'

/** "Female · 34" — sex and age, omitting whatever is missing. */
function metaLine(entry: StandingEntryResponse): string {
  const parts: string[] = []
  if (entry.sex) parts.push(entry.sex)
  if (entry.age != null) parts.push(String(entry.age))
  return parts.join(' · ')
}

/**
 * The Standings tab (#113, Phase 1): a read-only, per-NTRP-band "Ranking Race".
 * Bands and entries arrive pre-ordered (strongest band first, entries already
 * ranked). Ratings are intentionally absent from the payload — this view never
 * shows a rating number; it is an interim, rating-derived ordering only.
 */
export function StandingsTab() {
  const standingsQuery = useGetApiV1Standings()
  const meQuery = useGetApiV1UsersMe()

  const bands = standingsQuery.data ?? []
  const meId = meQuery.data?.id

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Standings</CardTitle>
          <CardDescription>
            Interim standings — players are ordered by their current rating
            within each NTRP band. A points-based ranking will replace this.
          </CardDescription>
        </CardHeader>
      </Card>

      {standingsQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading standings…</p>
      ) : (
        bands.map((band) => (
          <Card key={band.band}>
            <CardHeader>
              <CardTitle>{band.band}</CardTitle>
            </CardHeader>
            <CardContent>
              {band.entries.length > 0 ? (
                <ol className="space-y-2">
                  {band.entries.map((entry) => {
                    const isMe = entry.userId === meId
                    const meta = metaLine(entry)
                    return (
                      <li
                        key={entry.userId}
                        aria-label={isMe ? 'Your standing' : undefined}
                        className={`flex items-center gap-3 rounded-lg border p-3 text-sm ${
                          isMe ? 'bg-muted ring-1 ring-ring' : ''
                        }`}
                      >
                        <span className="w-6 shrink-0 text-right font-medium tabular-nums text-muted-foreground">
                          {entry.rank}
                        </span>
                        <div className="min-w-0 flex-1">
                          <div className="font-medium">
                            {entry.displayName ?? entry.publicCode}
                            {isMe ? (
                              <span className="ml-2 text-xs font-normal text-muted-foreground">
                                You
                              </span>
                            ) : null}
                          </div>
                          {meta ? (
                            <div className="text-muted-foreground">{meta}</div>
                          ) : null}
                        </div>
                      </li>
                    )
                  })}
                </ol>
              ) : (
                <p className="text-sm text-muted-foreground">No players yet.</p>
              )}
            </CardContent>
          </Card>
        ))
      )}
    </div>
  )
}
