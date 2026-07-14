import { useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
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
  getGetApiV1EventsCodeCodeQueryKey,
  useGetApiV1EventsCodeCode,
  usePostApiV1EventsCodeCodeSignup,
} from '@/api/generated/events/events'
import type { EventParticipantResponse, MatchPublicResponse } from '@/api/generated/model'
import { ShareCard } from '@/components/ShareCard'
import { PublicPageNav } from '@/components/PublicPageNav'
import { useAuth } from '@/auth/useAuth'
import { playerLabel } from '@/lib/playerLabel'

/** The join card on the public event page (#201): request to join, or the viewer's current standing. */
function JoinCard({ code, viewerStatus }: { code: string; viewerStatus?: string | null }) {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const signup = usePostApiV1EventsCodeCodeSignup({
    mutation: {
      onSuccess: () => {
        setError(null)
        queryClient.invalidateQueries({ queryKey: getGetApiV1EventsCodeCodeQueryKey(code) })
      },
      onError: () => setError('Could not sign up for this event. Please try again.'),
    },
  })

  if (viewerStatus === 'APPROVED') {
    return <p className="text-sm text-muted-foreground">You’re confirmed for this event.</p>
  }
  if (viewerStatus === 'PENDING') {
    return <p className="text-sm text-muted-foreground">Your request to join is pending the host’s approval.</p>
  }
  if (viewerStatus === 'HOLD') {
    return <p className="text-sm text-muted-foreground">Your request is on hold — the host will review it.</p>
  }
  // Joining needs an account (#193): prompt an anonymous viewer to log in / sign up first.
  if (!user) {
    return (
      <p className="text-sm text-muted-foreground">
        <Link to="/login" state={{ from: location }} className="font-medium text-primary hover:underline">
          Log in
        </Link>
        {' or '}
        <Link to="/signup" className="font-medium text-primary hover:underline">
          sign up
        </Link>
        {' to request to join.'}
      </p>
    )
  }
  return (
    <div className="space-y-2">
      <Button
        type="button"
        size="sm"
        disabled={signup.isPending}
        onClick={() => signup.mutate({ code })}
      >
        {signup.isPending ? 'Requesting…' : 'Request to join'}
      </Button>
      {error ? (
        <p className="text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  )
}

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
 * A read-only list of matches under a heading, mirroring the private organizer view's
 * "Awaiting results" / "Recorded results" split (#321) — but without any data-entry controls.
 */
function MatchSection({
  title,
  matches,
  emptyText,
}: {
  title: string
  matches: MatchPublicResponse[]
  emptyText: string
}) {
  return (
    <div>
      <div className="text-xs font-medium uppercase text-muted-foreground">{title}</div>
      {matches.length > 0 ? (
        <ul className="mt-1 space-y-1">
          {matches.map((m) => (
            <MatchRow key={m.publicCode} match={m} />
          ))}
        </ul>
      ) : (
        <p className="text-muted-foreground">{emptyText}</p>
      )}
    </div>
  )
}

/**
 * Public, read-only event page reached via `/events/:code` (issue #138). Resolves the event by its
 * shareable code and shows its details, participants (linking to profiles), and matches (linking to
 * their public pages), with a QR for sharing. Viewable without login (#193); joining prompts sign-in.
 */
export function EventPage() {
  const { code = '' } = useParams()
  const query = useGetApiV1EventsCodeCode(code)
  const event = query.data

  return (
    <div className="flex min-h-svh items-start justify-center bg-muted/40 p-4">
      <div className="w-full max-w-sm space-y-4 pt-10">
        <PublicPageNav />

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
              {/* A soft-deleted event stays reachable by link for traceability (#325) — flag it. */}
              {event.isActive === false ? (
                <p
                  role="status"
                  className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
                >
                  This event has been deleted. It’s kept for reference only.
                </p>
              ) : null}
              {/* The organizing club (#313), read-only; omitted for a clubless ("Open") event. */}
              {event.clubName ? (
                <div>
                  <div className="text-xs font-medium uppercase text-muted-foreground">Club</div>
                  <p className="mt-1">{event.clubName}</p>
                </div>
              ) : null}
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
              {/* Matches, grouped like the private organizer view but read-only (#321): a fixture
                  with recorded set scores is a result; one without is still awaiting play. */}
              <MatchSection
                title="Awaiting results"
                matches={event.matches.filter((m) => m.sets.length === 0)}
                emptyText="No fixtures awaiting results."
              />
              <MatchSection
                title="Recorded results"
                matches={event.matches.filter((m) => m.sets.length > 0)}
                emptyText="No recorded results yet."
              />
              <div className="border-t pt-3">
                <JoinCard code={event.publicCode} viewerStatus={event.viewerStatus} />
              </div>
            </CardContent>
          </Card>
        ) : null}

        {event ? (
          <ShareCard
            url={`${window.location.origin}/events/${event.publicCode}`}
            title="Share this event"
            description="Scan this code or copy the link to open this event."
            shareText={`${event.name} on Skopeo`}
          />
        ) : null}
      </div>
    </div>
  )
}
