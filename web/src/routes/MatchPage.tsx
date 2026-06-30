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
  MatchPublicResponse,
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

/** A player's display name, falling back to their code or "Unknown". */
function playerName(player?: MatchPublicPlayer): string {
  return player?.displayName ?? player?.publicCode ?? 'Unknown'
}

/**
 * Head-to-head record between the two players (#188): the running win tally and prior meetings,
 * newest first, each linking to its own public match page. Hidden when there are no prior meetings
 * (the backend returns no head-to-head for those, or for non-singles matches).
 */
function HeadToHeadCard({ match }: { match: MatchPublicResponse }) {
  const h2h = match.headToHead
  if (!h2h) return null
  const team1 = match.team1[0]
  const team2 = match.team2[0]

  function winnerName(code?: string | null): string | null {
    if (!code) return null
    if (code === team1?.publicCode) return playerName(team1)
    if (code === team2?.publicCode) return playerName(team2)
    return null
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Head-to-head</CardTitle>
        <CardDescription>
          Prior meetings between {playerName(team1)} and {playerName(team2)}.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <div className="flex items-center justify-center gap-3 font-medium">
          <span>{playerName(team1)}</span>
          <span className="tabular-nums">
            {h2h.team1Wins} – {h2h.team2Wins}
          </span>
          <span>{playerName(team2)}</span>
        </div>
        <ul className="space-y-2">
          {h2h.meetings.map((meeting) => {
            const score = meeting.sets
              .map((s) => `${s.team1Games}-${s.team2Games}`)
              .join(' ')
            const won = winnerName(meeting.winnerPublicCode)
            return (
              <li key={meeting.publicCode} className="rounded-lg border p-3">
                <div className="text-muted-foreground">
                  {meeting.matchDate}
                  {score ? ` · ${score}` : ''}
                  {won ? ` · ${won} won` : ''}
                </div>
                <Link
                  to={`/matches/${meeting.publicCode}`}
                  className="mt-1 inline-block text-xs text-primary hover:underline"
                >
                  Public page (QR)
                </Link>
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
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

        {match ? <HeadToHeadCard match={match} /> : null}

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
