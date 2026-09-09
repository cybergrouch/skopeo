import { Link } from 'react-router-dom'
import type { MatchPublicResponse } from '@/api/generated/model'
import { Badge } from '@/components/ui/badge'
import { playerLabel } from '@/lib/playerLabel'

type StatusBadge = { label: string; variant: 'default' | 'secondary' | 'outline' }

/**
 * The read-only lifecycle status of a fixture (#361), mirroring the organizer's derivation: a match
 * the calculation has committed is "Rated"; one with a recorded result but not yet rated is "Awaiting
 * rating"; one being scored right now is "In progress"; anything else is still "Scheduled".
 *
 * IN_PROGRESS fell through to "Scheduled" until #945, because the status had no writer until #930 and
 * the fallback was written for a world where it could not occur.
 */
function statusBadge(match: MatchPublicResponse): StatusBadge {
  if (match.rated) return { label: 'Rated', variant: 'default' }
  if (match.status === 'COMPLETED') return { label: 'Awaiting rating', variant: 'secondary' }
  // "Live" rather than "In progress": it is what the spectator scoreboard already calls this state, and
  // it does not collide with the section heading of the same name.
  if (match.status === 'IN_PROGRESS') return { label: 'Live', variant: 'default' }
  return { label: 'Scheduled', variant: 'outline' }
}

/** A one-line, read-only match summary with a status badge, linking to its public page. */
function MatchRow({ match }: { match: MatchPublicResponse }) {
  const side = (players: MatchPublicResponse['team1']) =>
    players.map((pl) => playerLabel(pl.displayName, pl.publicCode, '')).join(' & ')
  const score = match.sets.map((s) => `${s.team1Games}-${s.team2Games}`).join(' ')
  const badge = statusBadge(match)
  return (
    <li>
      <Link to={`/matches/${match.publicCode}`} className="block rounded-lg border p-2 hover:bg-muted/50">
        <span className="flex items-center gap-2">
          {/* The match number (#898) leads the row: it is the handle people use out loud during an
              event ("we're on Match #3"), so it needs to be the first thing read, not a detail. */}
          <span className="shrink-0 text-xs font-semibold tabular-nums text-muted-foreground">
            Match #{match.matchNumber}
          </span>
          <span className="flex-1">
            {side(match.team1)} vs {side(match.team2)}
          </span>
          <Badge variant={badge.variant}>{badge.label}</Badge>
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

/** A read-only list of matches under a heading. */
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
 * The event's fixtures for a viewer with no data-entry rights (#741), split the same way the organizer
 * splits them (#321) — a fixture with recorded set scores is a result; one without is still awaiting
 * play — but read-only throughout. Match managers get the editable `AwaitingResultsSection` /
 * `RecordedResultsSection` instead: same split, different surface, so the two are not interchangeable.
 */
export function EventMatchSections({ matches }: { matches: MatchPublicResponse[] }) {
  // Split on status FIRST, then on whether a result exists. Ordering matters: a match being scored has
  // no sets yet, so a sets-only split would file it under "awaiting" — which is how #945 arose.
  const inProgress = matches.filter((m) => m.status === 'IN_PROGRESS')
  const rest = matches.filter((m) => m.status !== 'IN_PROGRESS')

  return (
    <>
      {/* First, and rendered only when there is one: while an event is running the matches actually
          being played are the most interesting thing on the page. An always-present empty section would
          spend that space on nothing for the other 99% of an event's life. */}
      {inProgress.length > 0 ? (
        <MatchSection
          title="In progress"
          matches={inProgress}
          emptyText=""
        />
      ) : null}
      <MatchSection
        title="Awaiting results"
        matches={rest.filter((m) => m.sets.length === 0)}
        emptyText="No fixtures awaiting results."
      />
      <MatchSection
        title="Recorded results"
        matches={rest.filter((m) => m.sets.length > 0)}
        emptyText="No recorded results yet."
      />
    </>
  )
}
