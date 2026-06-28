import { useState } from 'react'
import type { RatingHistoryResponse } from '@/api/generated/model'
import { useGetApiV1MatchesIdCalculation } from '@/api/generated/matches/matches'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { CalculationBreakdownDetail } from '@/components/CalculationBreakdownDetail'

interface RatingHistoryCardProps {
  entries: RatingHistoryResponse[]
  isLoading?: boolean
  /** Card subtitle — differs slightly between the owner's own view and an admin's. */
  description?: string
}

/**
 * The match result + the calculation that produced a rating change (issue #97), shown when a
 * match-driven history entry is expanded. The breakdown is read from what was persisted at commit
 * time (never recomputed), so it stays faithful even if the algorithm constants change.
 */
function MatchCalculationDetail({ matchId }: { matchId: string }) {
  const { data, isLoading } = useGetApiV1MatchesIdCalculation(matchId, {
    query: { enabled: Boolean(matchId) },
  })

  if (isLoading) {
    return <p className="text-xs text-muted-foreground">Loading…</p>
  }
  if (!data) {
    return (
      <p className="text-xs text-muted-foreground">
        Calculation details aren’t available for this entry.
      </p>
    )
  }

  const nameOf = (id: string) =>
    data.changes.find((c) => c.userId === id)?.displayName ?? id.slice(0, 8)
  const scores = data.match.sets
    .map((s) => `${s.team1Games}-${s.team2Games}`)
    .join(' ')
  const winnerSide =
    data.match.winnerTeamId === data.match.team1.teamId
      ? data.match.team1
      : data.match.winnerTeamId === data.match.team2.teamId
        ? data.match.team2
        : null
  const winner = winnerSide ? winnerSide.userIds.map(nameOf).join(', ') : null

  return (
    <div className="space-y-2 text-sm">
      <div className="text-muted-foreground">
        {data.match.matchDate}
        {scores ? ` · ${scores}` : ''}
        {winner ? ` · Winner: ${winner}` : ''}
      </div>
      <ul className="space-y-2">
        {data.changes.map((change) => (
          <li key={change.userId}>
            <div>
              {nameOf(change.userId)}: {change.previousRating} →{' '}
              {change.newRating} ({change.change})
            </div>
            {change.breakdown ? (
              <CalculationBreakdownDetail breakdown={change.breakdown} />
            ) : null}
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * Rating history (issue #73): the precise, audit-style view shown on the owner's Profile tab and,
 * for admins, on a player's public profile. Unlike match history this shows the full rating value
 * alongside the published NTRP band, and highlights rows where the band changed. A match-driven
 * entry is clickable and expands to show that match's result and calculation (issue #97); an
 * initial assessment (no match) is not clickable.
 */
export function RatingHistoryCard({
  entries,
  isLoading = false,
  description = 'Changes from rated matches.',
}: RatingHistoryCardProps) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

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
              const isOpen = expanded.has(entry.id)
              const content = (
                <>
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-muted-foreground">
                      {entry.calculatedAt.slice(0, 10)}
                    </span>
                    <span className="flex items-center gap-2">
                      {entry.levelChanged ? (
                        <Badge variant="secondary">{`Band ${prevBand} → ${newBand}`}</Badge>
                      ) : null}
                      {entry.matchId ? (
                        <span aria-hidden="true" className="text-muted-foreground">
                          {isOpen ? '▾' : '▸'}
                        </span>
                      ) : null}
                    </span>
                  </div>
                  <div className="mt-1">{`${entry.previousRating} → ${entry.newRating}`}</div>
                  <div className="text-muted-foreground">{`NTRP ${prevBand} → ${newBand}`}</div>
                </>
              )
              return (
                <li
                  key={entry.id}
                  className={`rounded-lg border text-sm ${
                    entry.levelChanged ? 'border-primary bg-primary/5' : ''
                  }`}
                >
                  {entry.matchId ? (
                    <button
                      type="button"
                      className="block w-full p-3 text-left hover:bg-muted/50"
                      aria-expanded={isOpen}
                      onClick={() => toggle(entry.id)}
                    >
                      {content}
                    </button>
                  ) : (
                    <div className="p-3">{content}</div>
                  )}
                  {entry.matchId && isOpen ? (
                    <div className="border-t px-3 py-2">
                      <MatchCalculationDetail matchId={entry.matchId} />
                    </div>
                  ) : null}
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
