import { Link } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useGetApiV1MatchesUpcoming } from '@/api/generated/matches/matches'
import type { MatchPublicPlayer, UpcomingMatchResponse } from '@/api/generated/model'

/** Opponent name(s) for the row: display names (or codes), comma-separated; "TBD" if unresolved. */
function opponentLabel(opponents: MatchPublicPlayer[]): string {
  const names = opponents.map((o) => o.displayName ?? o.publicCode).filter((n): n is string => !!n)
  return names.length > 0 ? names.join(', ') : 'TBD'
}

function MatchRow({ match }: { match: UpcomingMatchResponse }) {
  return (
    <li>
      <Link
        to={`/matches/${match.publicCode}`}
        className="block rounded-lg border p-2 text-sm hover:bg-muted/50"
      >
        <span className="block font-medium">vs {opponentLabel(match.opponents)}</span>
        <span className="block text-xs text-muted-foreground">
          {match.matchDate} · {match.matchType.replaceAll('_', ' ').toLowerCase()}
          {match.venue ? ` · ${match.venue}` : ''}
        </span>
      </Link>
    </li>
  )
}

/**
 * The player's "Upcoming matches" on their Profile tab (#251): their scheduled, not-yet-played
 * fixtures (soonest first), each linking to the match's public page. Owner-only — backed by the
 * authenticated `/matches/upcoming` feed (derived from the caller's token), not the public by-code one.
 */
export function UpcomingMatchesCard() {
  const query = useGetApiV1MatchesUpcoming()
  const matches = query.data ?? []

  return (
    <Card>
      <CardHeader>
        <CardTitle>Upcoming matches</CardTitle>
        <CardDescription>Scheduled matches you’re set to play.</CardDescription>
      </CardHeader>
      <CardContent className="text-sm">
        {query.isLoading ? (
          <p className="text-muted-foreground">Loading…</p>
        ) : query.isError ? (
          <p className="text-destructive" role="alert">
            Could not load your upcoming matches.
          </p>
        ) : matches.length === 0 ? (
          <p className="text-muted-foreground">No upcoming matches.</p>
        ) : (
          <ul className="space-y-1">
            {matches.map((m) => (
              <MatchRow key={m.publicCode} match={m} />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
