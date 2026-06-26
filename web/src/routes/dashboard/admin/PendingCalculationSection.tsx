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
import { useGetApiV1Users } from '@/api/generated/users/users'
import { GetApiV1MatchesFilter } from '@/api/generated/model'
import type { CalculationResponse, MatchResponse } from '@/api/generated/model'
import { plural } from '@/lib/plural'

const PENDING_FILTER = { filter: GetApiV1MatchesFilter['pending-calculation'] }

/** The winning side's resolved name for a completed match, or null if no winner is recorded. */
function winnerName(
  match: MatchResponse,
  nameOf: (id: string) => string,
): string | null {
  if (match.winnerTeamId === match.team1.teamId) return match.team1.userIds.map(nameOf).join(', ')
  if (match.winnerTeamId === match.team2.teamId) return match.team2.userIds.map(nameOf).join(', ')
  return null
}

export function PendingCalculationSection() {
  const queryClient = useQueryClient()
  const [preview, setPreview] = useState<CalculationResponse | null>(null)
  const [committed, setCommitted] = useState<number | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  const matchesQuery = useGetApiV1Matches(PENDING_FILTER)
  const pending = matchesQuery.data ?? []

  const ids = [...new Set(pending.flatMap((m) => [...m.team1.userIds, ...m.team2.userIds]))]
  const usersQuery = useGetApiV1Users(
    { ids: ids.join(',') },
    { query: { enabled: ids.length > 0 } },
  )
  const nameById = new Map((usersQuery.data ?? []).map((u) => [u.id, u.displayName ?? u.id]))
  const nameOf = (userId: string) => nameById.get(userId) ?? userId.slice(0, 8)

  // The dry-run preview, keyed by match id, so each card can show its own projection + breakdown.
  const previewByMatch = new Map((preview?.matches ?? []).map((m) => [m.matchId, m]))

  function toggle(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

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

        {pending.length > 0 ? (
          <ul className="space-y-2">
            {pending.map((match) => {
              const player1 = match.team1.userIds.map(nameOf).join(', ')
              const player2 = match.team2.userIds.map(nameOf).join(', ')
              const scores = match.sets.map((s) => `${s.team1Games}-${s.team2Games}`).join(' ')
              const winner = winnerName(match, nameOf)
              const isOpen = expanded.has(match.id)
              const matchPreview = previewByMatch.get(match.id)
              return (
                <li key={match.id} className="rounded-lg border text-sm">
                  <button
                    type="button"
                    className="flex w-full items-start justify-between gap-2 p-3 text-left hover:bg-muted/50"
                    aria-expanded={isOpen}
                    onClick={() => toggle(match.id)}
                  >
                    <span className="min-w-0">
                      <span className="block font-medium">
                        {player1} vs {player2}
                      </span>
                      <span className="block text-muted-foreground">
                        {match.matchDate}
                        {scores ? ` · ${scores}` : ''}
                        {winner ? ` · Winner: ${winner}` : ''}
                      </span>
                    </span>
                    <span aria-hidden="true" className="shrink-0 text-muted-foreground">
                      {isOpen ? '▾' : '▸'}
                    </span>
                  </button>
                  {isOpen ? (
                    <div className="border-t px-3 py-2">
                      {matchPreview ? (
                        <ul className="space-y-2">
                          {matchPreview.changes.map((change) => (
                            <li key={change.userId}>
                              <div>
                                {nameOf(change.userId)}: {change.previousRating} →{' '}
                                {change.newRating} ({change.change})
                              </div>
                              <div className="text-xs text-muted-foreground">
                                dominance {change.breakdown.dominance} · scale{' '}
                                {change.breakdown.scale} · gap {change.breakdown.ratingGap}/
                                {change.breakdown.competitiveThresholdPct} ·{' '}
                                {change.breakdown.isUpset ? 'upset' : 'expected'} · K{' '}
                                {change.breakdown.kFactor}
                              </div>
                            </li>
                          ))}
                        </ul>
                      ) : (
                        <p className="text-xs text-muted-foreground">
                          Run Preview to see the projected ratings and how they're calculated.
                        </p>
                      )}
                    </div>
                  ) : null}
                </li>
              )
            })}
          </ul>
        ) : null}

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
          <p
            className="text-sm text-muted-foreground"
            data-testid="calculation-preview"
            role="status"
          >
            Preview ready — {preview.matchesProcessed} match
            {plural(preview.matchesProcessed)}, no changes saved yet. Expand a match
            to see its projection and how it's calculated.
          </p>
        ) : null}
      </CardContent>
    </Card>
  )
}
