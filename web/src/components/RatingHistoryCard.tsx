import type { RatingHistoryResponse } from '@/api/generated/model'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'

interface RatingHistoryCardProps {
  entries: RatingHistoryResponse[]
  isLoading?: boolean
  /** Card subtitle — differs slightly between the owner's own view and an admin's. */
  description?: string
}

/**
 * Rating history (issue #73): the precise, audit-style view shown on the owner's Profile tab and,
 * for admins, on a player's public profile. Unlike match history this shows the full rating value
 * alongside the published NTRP band, and highlights rows where the band changed.
 */
export function RatingHistoryCard({
  entries,
  isLoading = false,
  description = 'Changes from rated matches.',
}: RatingHistoryCardProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Rating history</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : entries.length > 0 ? (
          <ul className="space-y-2">
            {entries.map((entry) => {
              const prevBand = entry.previousLevel ?? '—'
              const newBand = entry.newLevel ?? '—'
              return (
                <li
                  key={entry.id}
                  className={`rounded-lg border p-3 text-sm ${
                    entry.levelChanged ? 'border-primary bg-primary/5' : ''
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-muted-foreground">
                      {entry.calculatedAt.slice(0, 10)}
                    </span>
                    {entry.levelChanged ? (
                      <Badge variant="secondary">{`Band ${prevBand} → ${newBand}`}</Badge>
                    ) : null}
                  </div>
                  <div className="mt-1">{`${entry.previousRating} → ${entry.newRating}`}</div>
                  <div className="text-muted-foreground">{`NTRP ${prevBand} → ${newBand}`}</div>
                </li>
              )
            })}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">No rating changes yet.</p>
        )}
      </CardContent>
    </Card>
  )
}
