import { Link, useParams } from 'react-router-dom'
import { ContentLink } from '@/components/ContentLink'
import { PublicPageLink } from '@/components/PublicPageLink'
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
import { PublicPageNav } from '@/components/PublicPageNav'
import { formatConfidence } from '@/lib/confidence'
import { ConfidenceValue } from '@/components/ConfidenceValue'

/** A player's name as a link to their public profile, falling back to the code or "Unknown". */
function PlayerLink({ player }: { player: MatchPublicPlayer }) {
  const label = player.displayName ?? player.publicCode ?? 'Unknown'
  if (!player.publicCode) return <span>{label}</span>
  return <ContentLink to={`/players/${player.publicCode}`}>{label}</ContentLink>
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
  // The player's current rating confidence (#343), shown beside the band/rate move as a percentage.
  const pct = formatConfidence(change.confidence)
  return (
    <div className="flex items-center justify-between gap-2">
      <span>
        {change.publicCode ? (
          <ContentLink to={`/players/${change.publicCode}`}>{name}</ContentLink>
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
          {pct ? (
            <span className="ml-1 text-muted-foreground">
              · <ConfidenceValue confidence={change.confidence} />
            </span>
          ) : null}
        </span>
      ) : (
        <span className="font-medium">
          {change.previousLevel ?? '—'} → {change.newLevel ?? '—'}
          {pct ? (
            <span className="ml-1 font-normal text-muted-foreground">
              · <ConfidenceValue confidence={change.confidence} />
            </span>
          ) : null}
        </span>
      )}
    </div>
  )
}

/** A player's display name, falling back to their code or "Unknown". */
function playerName(player?: MatchPublicPlayer): string {
  return player?.displayName ?? player?.publicCode ?? 'Unknown'
}

/** A side's players as a comma-separated name list, e.g. "Ana & Bea" reads as "Ana, Bea". */
function sideNames(players: MatchPublicPlayer[]): string {
  return players.map((p) => playerName(p)).join(', ')
}

/**
 * Head-to-head record between the two players (#188): the running win tally and prior meetings,
 * newest first, each linking to its own public match page. Shown for every singles match, including a
 * first-ever meeting — the tally reflects the match being viewed (#339) and the list reads "No prior
 * meetings" (#366). Hidden only for non-singles matches (the backend returns no head-to-head then).
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
        {h2h.meetings.length === 0 ? (
          <p className="text-center text-muted-foreground">No prior meetings.</p>
        ) : (
          <ul className="space-y-2">
            {h2h.meetings.map((meeting) => {
              const score = meeting.sets
                .map((s) => `${s.team1Games}-${s.team2Games}`)
                .join(' ')
              const won = winnerName(meeting.winnerPublicCode)
              // Show whether the meeting was singles or doubles (#285), e.g. "mixed doubles".
              const format = meeting.matchFormat.replaceAll('_', ' ').toLowerCase()
              return (
                <li key={meeting.publicCode} className="rounded-lg border p-3">
                  <div className="text-muted-foreground">
                    {format} · {meeting.matchDate}
                    {score ? ` · ${score}` : ''}
                    {won ? ` · ${won} won` : ''}
                  </div>
                  <PublicPageLink
                    to={`/matches/${meeting.publicCode}`}
                    className="mt-1 inline-block text-xs"
                  >
                    Public page (QR)
                  </PublicPageLink>
                </li>
              )
            })}
          </ul>
        )}
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
        <PublicPageNav />

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
              {/* A soft-deleted match stays reachable by link for traceability (#325) — flag it. */}
              {match.isActive === false ? (
                <p
                  role="status"
                  className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
                >
                  This match has been deleted. It’s kept for reference only.
                </p>
              ) : null}
              <div className="text-muted-foreground">
                {match.matchDate} · {match.matchType.replaceAll('_', ' ').toLowerCase()}
                {match.venue ? ` · ${match.venue}` : ''}
              </div>
              {/* When the match belongs to an event (#358), link to that event's public page. */}
              {match.event ? (
                <div>
                  Part of event:{' '}
                  <Link
                    to={`/events/${match.event.publicCode}`}
                    className="text-primary hover:underline"
                  >
                    {match.event.name}
                  </Link>
                </div>
              ) : null}
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
            shareText={`${sideNames(match.team1)} vs ${sideNames(match.team2)} on Skopeo`}
          />
        ) : null}
      </div>
    </div>
  )
}
