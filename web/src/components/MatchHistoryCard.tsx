import { useState } from 'react'
import { NtrpLabel } from '@/components/NtrpLabel'
import { ContentLink } from '@/components/ContentLink'
import { useGetApiV1PlayersCodeMatchHistory } from '@/api/generated/users/users'
import { CollapsibleCard } from '@/components/CollapsibleCard'
import { MatchHistoryRow } from '@/components/MatchHistoryRow'
import { NTRP_LEVELS } from '@/lib/ntrp'

/** Recent matches shown inline on the profile; the full, searchable history lives on its own page (#284). */
const PREVIEW_COUNT = 5

interface MatchHistoryCardProps {
  /** The player's shareable public code; the same endpoint serves own- and public-profile views. */
  code: string
  /** Start minimized behind a "Show all" toggle (#589) — used on the owner's Profile tab. */
  collapsible?: boolean
}

/**
 * A bounded preview of a player's match history (issue #65), on the owner's Profile tab and the public
 * profile alike. Shows the most recent {@link PREVIEW_COUNT} matches with a link to the full,
 * paginated + searchable history page (#284). Ratings appear only as the published NTRP band.
 */
export function MatchHistoryCard({ code, collapsible = false }: MatchHistoryCardProps) {
  const [opponentBand, setOpponentBand] = useState('')
  const query = useGetApiV1PlayersCodeMatchHistory(
    code,
    { limit: PREVIEW_COUNT, opponentBand: opponentBand || undefined },
    { query: { enabled: Boolean(code) } },
  )
  const items = query.data?.items ?? []
  const total = query.data?.total ?? 0

  return (
    <CollapsibleCard
      title="Match history"
      description={
        <>
          Recent matches, with each player's <NtrpLabel /> band at the time. Bands appear once a match
          has been rated.
        </>
      }
      contentClassName="space-y-3"
      collapsible={collapsible}
    >
      <select
          aria-label="Filter by opponent NTRP band"
          className="h-9 w-full rounded-md border bg-background px-2 text-sm"
          value={opponentBand}
          onChange={(e) => setOpponentBand(e.target.value)}
        >
          <option value="">All opponent bands</option>
          {NTRP_LEVELS.map((level) => (
            <option key={level} value={level}>
              NTRP {level}
            </option>
          ))}
        </select>
        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : items.length > 0 ? (
          <>
            <ul className="space-y-2">
              {items.map((match) => (
                <MatchHistoryRow key={match.matchId} match={match} />
              ))}
            </ul>
            {total > items.length ? (
              <ContentLink
                to={`/players/${code}/matches`}
                className="inline-block text-sm"
              >
                View all {total} matches
              </ContentLink>
            ) : null}
          </>
        ) : (
          <p className="text-sm text-muted-foreground">
            {opponentBand ? 'No matches vs that band.' : 'No matches yet.'}
          </p>
        )}
    </CollapsibleCard>
  )
}
