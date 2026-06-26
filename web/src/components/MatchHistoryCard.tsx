import { useGetApiV1PlayersCodeMatchHistory } from '@/api/generated/users/users'
import type { PlayerMatchHistoryEntry } from '@/api/generated/model'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'

interface MatchHistoryCardProps {
  /** The player's shareable public code; the same endpoint serves own- and public-profile views. */
  code: string
}

/** How far along the rating pipeline a match is — drives the per-row status badge. */
function statusLabel(match: PlayerMatchHistoryEntry): string {
  if (match.rated) return 'Rated'
  if (match.status === 'SCHEDULED') return 'Scheduled'
  return 'Awaiting rating'
}

/**
 * A player's match history (issue #65), shown on the owner's Profile tab and the public profile
 * alike. Ratings appear only as the published NTRP band at the time of the match, never the
 * precise value, and only for matches that have been rated.
 */
export function MatchHistoryCard({ code }: MatchHistoryCardProps) {
  const query = useGetApiV1PlayersCodeMatchHistory(code, {
    query: { enabled: Boolean(code) },
  })
  const matches = query.data ?? []

  return (
    <Card>
      <CardHeader>
        <CardTitle>Match history</CardTitle>
        <CardDescription>
          Matches played, with each player's NTRP band at the time. Bands appear
          once a match has been rated.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : matches.length > 0 ? (
          <ul className="space-y-2">
            {matches.map((match) => (
              <li key={match.matchId} className="rounded-lg border p-3 text-sm">
                <div className="flex items-center gap-3">
                  {match.opponent?.photoUrl ? (
                    <img
                      src={match.opponent.photoUrl}
                      alt=""
                      referrerPolicy="no-referrer"
                      className="h-9 w-9 shrink-0 rounded-full object-cover"
                    />
                  ) : (
                    <div
                      aria-hidden="true"
                      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted text-sm font-medium text-muted-foreground"
                    >
                      {(match.opponent?.displayName ?? 'P')
                        .charAt(0)
                        .toUpperCase()}
                    </div>
                  )}
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium">
                        vs {match.opponent?.displayName ?? 'Player'}
                      </span>
                      <Badge variant="secondary">{statusLabel(match)}</Badge>
                    </div>
                    <div className="text-muted-foreground">
                      {match.matchDate}
                      {match.result ? ` · ${match.result}` : ''}
                      {match.setScores.length > 0
                        ? ` · ${match.setScores.join(' ')}`
                        : ''}
                    </div>
                    {match.rated ? (
                      <div className="mt-1 text-muted-foreground">
                        NTRP {match.playerLevelAtMatch ?? '—'} vs{' '}
                        {match.opponentLevelAtMatch ?? '—'} (at the time)
                      </div>
                    ) : null}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">No matches yet.</p>
        )}
      </CardContent>
    </Card>
  )
}
