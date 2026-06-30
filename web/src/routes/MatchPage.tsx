import { Link, useParams } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useGetApiV1MatchesCodeCode } from '@/api/generated/matches/matches'
import type {
  MatchPublicPlayer,
  MatchPublicRatingChange,
} from '@/api/generated/model'
import { ShareCard } from '@/components/ShareCard'

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

/** A signed delta, e.g. "+0.123456" / "-0.045000" — already-negative strings keep their sign. */
function signed(value: string): string {
  return value.startsWith('-') ? value : `+${value}`
}

/**
 * One player's rating change for a rated match (#136). Everyone sees the NTRP band move
 * (previousLevel → newLevel); raters/admins additionally get the precise rates, which the
 * backend only populates for them — so their presence is what gates the detailed view.
 */
function RatingChangeRow({ change }: { change: MatchPublicRatingChange }) {
  const name = change.displayName ?? change.publicCode ?? 'Unknown'
  const showRates = change.previousRating != null && change.newRating != null
  return (
    <div className="flex items-center justify-between gap-2">
      <span>
        {change.publicCode ? (
          <Link
            to={`/players/${change.publicCode}`}
            className="text-primary hover:underline"
          >
            {name}
          </Link>
        ) : (
          name
        )}
      </span>
      {showRates ? (
        <span className="font-mono text-xs">
          {change.previousRating} → {change.newRating}
          {change.ratingChange ? (
            <span className="ml-1 text-muted-foreground">
              ({signed(change.ratingChange)})
            </span>
          ) : null}
        </span>
      ) : (
        <span className="font-medium">
          {change.previousLevel ?? '—'} → {change.newLevel ?? '—'}
        </span>
      )}
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
              {match.ratingChanges && match.ratingChanges.length > 0 ? (
                <div className="space-y-1.5 border-t pt-3">
                  <div className="font-medium">Rating changes</div>
                  {match.ratingChanges.map((change, i) => (
                    <RatingChangeRow key={change.publicCode ?? i} change={change} />
                  ))}
                </div>
              ) : null}
            </CardContent>
          </Card>
        ) : null}

        {match ? (
          <ShareCard
            url={`${window.location.origin}/matches/${match.publicCode}`}
            title="Share this match"
            description="Scan this code or copy the link to open this match."
          />
        ) : null}
      </div>
    </div>
  )
}
