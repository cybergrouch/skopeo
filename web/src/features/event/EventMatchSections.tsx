import { Link } from 'react-router-dom'
import type { MatchPublicResponse } from '@/api/generated/model'
import { Badge } from '@/components/ui/badge'
import { playerLabel } from '@/lib/playerLabel'

type StatusBadge = { label: string; variant: 'default' | 'secondary' | 'outline' }

/**
 * The read-only lifecycle status of a fixture (#361), mirroring the organizer's derivation: a match
 * the calculation has committed is "Rated"; one with a recorded result but not yet rated is "Awaiting
 * rating"; anything else is still "Scheduled".
 */
function statusBadge(match: MatchPublicResponse): StatusBadge {
  if (match.rated) return { label: 'Rated', variant: 'default' }
  if (match.status === 'COMPLETED') return { label: 'Awaiting rating', variant: 'secondary' }
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
  return (
    <>
      <MatchSection
        title="Awaiting results"
        matches={matches.filter((m) => m.sets.length === 0)}
        emptyText="No fixtures awaiting results."
      />
      <MatchSection
        title="Recorded results"
        matches={matches.filter((m) => m.sets.length > 0)}
        emptyText="No recorded results yet."
      />
    </>
  )
}
