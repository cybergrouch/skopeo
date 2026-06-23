import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  getGetApiV1MatchesQueryKey,
  useGetApiV1Matches,
} from '@/api/generated/matches/matches'
import { usePostApiV1RatingsCalculations } from '@/api/generated/ratings/ratings'
import { GetApiV1MatchesFilter } from '@/api/generated/model'
import type { CalculationResponse } from '@/api/generated/model'
import { plural } from '@/lib/plural'

const PENDING_FILTER = { filter: GetApiV1MatchesFilter['pending-calculation'] }

export function PendingCalculationSection() {
  const queryClient = useQueryClient()
  const [preview, setPreview] = useState<CalculationResponse | null>(null)
  const [committed, setCommitted] = useState<number | null>(null)

  const matchesQuery = useGetApiV1Matches(PENDING_FILTER)
  const pending = matchesQuery.data ?? []

  const calculate = usePostApiV1RatingsCalculations({
    mutation: {
      onSuccess: (data) => {
        if (data.dryRun) {
          setPreview(data)
          setCommitted(null)
        } else {
          setPreview(null)
          setCommitted(data.matchesProcessed)
          queryClient.invalidateQueries({
            queryKey: getGetApiV1MatchesQueryKey(PENDING_FILTER),
          })
        }
      },
    },
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>Pending calculation</CardTitle>
        <CardDescription>
          Completed matches awaiting a rating calculation, processed
          oldest-first. Preview before committing.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {matchesQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : (
          <p className="text-sm">
            <span className="font-medium">{pending.length}</span> match
            {plural(pending.length)} pending calculation.
          </p>
        )}

        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={pending.length === 0 || calculate.isPending}
            onClick={() => calculate.mutate({ data: { dryRun: true } })}
          >
            Preview
          </Button>
          {preview ? (
            <>
              <Button
                size="sm"
                disabled={calculate.isPending}
                onClick={() => calculate.mutate({ data: { dryRun: false } })}
              >
                Commit
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={calculate.isPending}
                onClick={() => setPreview(null)}
              >
                Discard
              </Button>
            </>
          ) : null}
        </div>

        {committed !== null ? (
          <p className="text-sm text-foreground" role="status">
            Committed ratings for {committed} match{plural(committed)}.
          </p>
        ) : null}

        {preview ? (
          <div className="space-y-3" data-testid="calculation-preview">
            <p className="text-sm text-muted-foreground">
              Preview — {preview.matchesProcessed} match
              {plural(preview.matchesProcessed)}, no changes saved yet.
            </p>
            {preview.matches.map((match) => (
              <div key={match.matchId} className="rounded-lg border p-3">
                <p className="mb-2 text-xs text-muted-foreground">
                  {match.matchDate}
                </p>
                <ul className="space-y-1 text-sm">
                  {match.changes.map((change) => (
                    <li
                      key={change.userId}
                      className="flex items-center justify-between"
                    >
                      <span className="text-muted-foreground">
                        {change.userId.slice(0, 8)}
                      </span>
                      <span>
                        {change.previousRating} → {change.newRating} (
                        {change.change})
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}
