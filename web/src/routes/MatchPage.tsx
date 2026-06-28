import { Link, useParams } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useGetApiV1MatchesCodeCode } from '@/api/generated/matches/matches'
import type { MatchPublicPlayer } from '@/api/generated/model'

/** A player's name as a link to their public profile, falling back to the code or "Unknown". */
function PlayerLink({ player }: { player: MatchPublicPlayer }) {
  const label = player.displayName ?? player.publicCode ?? 'Unknown'
  if (!player.publicCode) return <span>{label}</span>
  return (
    <Link to={`/players/${player.publicCode}`} className="text-primary hover:underline">
      {label}
    </Link>
  )
}

/** A side's players, comma-separated, marked as the winner when applicable. */
function Side({
  players,
  isWinner,
}: {
  players: MatchPublicPlayer[]
  isWinner: boolean
}) {
  return (
    <div className="flex items-center gap-2">
      <span className="flex flex-wrap gap-x-1">
        {players.map((p, i) => (
          <span key={p.publicCode ?? i}>
            <PlayerLink player={p} />
            {i < players.length - 1 ? ',' : ''}
          </span>
        ))}
      </span>
      {isWinner ? (
        <span className="rounded bg-primary/10 px-1.5 py-0.5 text-xs font-medium text-primary">
          Winner
        </span>
      ) : null}
    </div>
  )
}

/**
 * Public, read-only match page reached via `/matches/:code` (issue #136). Resolves the match by its
 * shareable public code and shows a privacy-conscious summary (players, score, date). Auth-gated,
 * mirroring the public player profile.
 */
export function MatchPage() {
  const { code = '' } = useParams()
  const query = useGetApiV1MatchesCodeCode(code)
  const match = query.data

  const score = match?.sets
    .map((s) => `${s.team1Games}-${s.team2Games}`)
    .join(' ')

  return (
    <div className="flex min-h-svh items-start justify-center bg-muted/40 p-4">
      <div className="w-full max-w-sm space-y-4 pt-10">
        <Link to="/dashboard" className="text-sm text-primary hover:underline">
          ← Back to dashboard
        </Link>

        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading match…</p>
        ) : null}

        {query.isError ? (
          <p className="text-sm text-muted-foreground">
            We couldn’t find or load this match. The link may be wrong, or try
            again.
          </p>
        ) : null}

        {match ? (
          <Card>
            <CardHeader>
              <CardTitle>Match</CardTitle>
              <CardDescription>
                Match ID:{' '}
                <code className="select-all font-mono font-medium text-foreground">
                  {match.publicCode}
                </code>
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div className="text-muted-foreground">
                {match.matchDate} · {match.matchType.replaceAll('_', ' ').toLowerCase()}
                {match.venue ? ` · ${match.venue}` : ''}
              </div>
              <Side players={match.team1} isWinner={match.winner === 'TEAM1'} />
              <div className="text-xs uppercase text-muted-foreground">vs</div>
              <Side players={match.team2} isWinner={match.winner === 'TEAM2'} />
              <div>
                <span className="font-medium">Score:</span>{' '}
                {score ? score : 'Not yet played'}
              </div>
            </CardContent>
          </Card>
        ) : null}
      </div>
    </div>
  )
}
