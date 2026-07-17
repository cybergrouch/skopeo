import { ContentLink } from '@/components/ContentLink'
import { useGetApiV1PlayersCodeMatchHistory } from '@/api/generated/users/users'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { MatchHistoryRow } from '@/components/MatchHistoryRow'

/** Recent matches shown inline on the profile; the full, searchable history lives on its own page (#284). */
const PREVIEW_COUNT = 5

interface MatchHistoryCardProps {
  /** The player's shareable public code; the same endpoint serves own- and public-profile views. */
  code: string
}

/**
 * A bounded preview of a player's match history (issue #65), on the owner's Profile tab and the public
 * profile alike. Shows the most recent {@link PREVIEW_COUNT} matches with a link to the full,
 * paginated + searchable history page (#284). Ratings appear only as the published NTRP band.
 */
export function MatchHistoryCard({ code }: MatchHistoryCardProps) {
  const query = useGetApiV1PlayersCodeMatchHistory(
    code,
    { limit: PREVIEW_COUNT },
    { query: { enabled: Boolean(code) } },
  )
  const items = query.data?.items ?? []
  const total = query.data?.total ?? 0

  return (
    <Card>
      <CardHeader>
        <CardTitle>Match history</CardTitle>
        <CardDescription>
          Recent matches, with each player's NTRP band at the time. Bands appear once a match has been
          rated.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
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
          <p className="text-sm text-muted-foreground">No matches yet.</p>
        )}
      </CardContent>
    </Card>
  )
}
