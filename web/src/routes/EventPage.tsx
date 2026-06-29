import { Link, useParams } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useGetApiV1EventsCodeCode } from '@/api/generated/events/events'
import type { EventParticipantResponse, MatchPublicResponse } from '@/api/generated/model'
import { ShareCard } from '@/components/ShareCard'
import { playerLabel } from '@/lib/playerLabel'

/** A participant as a link to their public profile, falling back to the code / "Unknown". */
function ParticipantLink({ p }: { p: EventParticipantResponse }) {
  const label = playerLabel(p.displayName, p.publicCode, p.userId)
  if (!p.publicCode) return <span>{label}</span>
  return (
    <Link to={`/players/${p.publicCode}`} className="text-primary hover:underline">
      {label}
    </Link>
  )
}

/** A one-line match summary linking to its public page. */
function MatchRow({ match }: { match: MatchPublicResponse }) {
  const side = (players: MatchPublicResponse['team1']) =>
    players.map((pl) => playerLabel(pl.displayName, pl.publicCode, '')).join(' & ')
  const score = match.sets.map((s) => `${s.team1Games}-${s.team2Games}`).join(' ')
  return (
    <li>
      <Link to={`/matches/${match.publicCode}`} className="block rounded-lg border p-2 hover:bg-muted/50">
        <span className="block">
          {side(match.team1)} vs {side(match.team2)}
        </span>
        <span className="block text-xs text-muted-foreground">
          {match.matchDate}
          {score ? ` · ${score}` : ''}
          {match.winner === 'TEAM1' ? ' · Winner: side 1' : match.winner === 'TEAM2' ? ' · Winner: side 2' : ''}
        </span>
      </Link>
    </li>
  )
}

/**
 * Public, read-only event page reached via `/events/:code` (issue #138). Resolves the event by its
 * shareable code and shows its details, participants (linking to profiles), and matches (linking to
 * their public pages), with a QR for sharing. Auth-gated, mirroring the public player/match pages.
 */
export function EventPage() {
  const { code = '' } = useParams()
  const query = useGetApiV1EventsCodeCode(code)
  const event = query.data

  return (
    <div className="flex min-h-svh items-start justify-center bg-muted/40 p-4">
      <div className="w-full max-w-sm space-y-4 pt-10">
        <Link to="/dashboard" className="text-sm text-primary hover:underline">
          ← Back to dashboard
        </Link>

        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading event…</p>
        ) : null}

        {query.isError ? (
          <p className="text-sm text-muted-foreground">
            We couldn’t find or load this event. The link may be wrong, or try again.
          </p>
        ) : null}

        {event ? (
          <Card>
            <CardHeader>
              <CardTitle>{event.name}</CardTitle>
              <CardDescription>
                {event.startDate} – {event.endDate} · Event ID:{' '}
                <code className="select-all font-mono font-medium text-foreground">{event.publicCode}</code>
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4 text-sm">
              <div>
                <div className="text-xs font-medium uppercase text-muted-foreground">Participants</div>
                {event.participants.length > 0 ? (
                  <ul className="mt-1 flex flex-wrap gap-x-2 gap-y-1">
                    {event.participants.map((p) => (
                      <li key={p.userId}>
                        <ParticipantLink p={p} />
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-muted-foreground">No participants yet.</p>
                )}
              </div>
              <div>
                <div className="text-xs font-medium uppercase text-muted-foreground">Matches</div>
                {event.matches.length > 0 ? (
                  <ul className="mt-1 space-y-1">
                    {event.matches.map((m) => (
                      <MatchRow key={m.publicCode} match={m} />
                    ))}
                  </ul>
                ) : (
                  <p className="text-muted-foreground">No matches yet.</p>
                )}
              </div>
            </CardContent>
          </Card>
        ) : null}

        {event ? (
          <ShareCard
            url={`${window.location.origin}/events/${event.publicCode}`}
            title="Share this event"
            description="Scan this code or copy the link to open this event."
          />
        ) : null}
      </div>
    </div>
  )
}
