import { useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { useGetApiV1PlayersCodeMatchHistory } from '@/api/generated/users/users'
import { MatchHistoryRow } from '@/components/MatchHistoryRow'
import { NumberedPager } from '@/components/NumberedPager'
import { PublicPageNav } from '@/components/PublicPageNav'
import { useDebouncedValue } from '@/hooks/useDebouncedValue'

const PAGE_SIZE = 20

/**
 * The full, paginated + searchable match-history page (#284), reached from the profile's "View all
 * matches" link at `/players/:code/matches`. Auth-gated like the public profile; search matches an
 * opponent/partner name or code server-side, and the pager loads one page at a time.
 */
export function PlayerMatchesPage() {
  const { code = '' } = useParams()
  const [page, setPage] = useState(0)
  const [searchInput, setSearchInput] = useState('')
  const search = useDebouncedValue(searchInput)

  const query = useGetApiV1PlayersCodeMatchHistory(
    code,
    { limit: PAGE_SIZE, offset: page * PAGE_SIZE, search: search.trim() || undefined },
    { query: { enabled: Boolean(code) } },
  )
  const items = query.data?.items ?? []
  const total = query.data?.total ?? 0

  return (
    <div className="flex min-h-svh items-start justify-center bg-muted/40 p-4">
      <div className="w-full max-w-md space-y-4 pt-10">
        <PublicPageNav />
        <Card>
          <CardHeader>
            <CardTitle>Match history</CardTitle>
            <CardDescription>
              Every match, newest first. Search by an opponent or partner's name or code. Ratings show
              only as the NTRP band at the time.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <Input
              type="search"
              placeholder="Search opponent…"
              value={searchInput}
              onChange={(e) => {
                setSearchInput(e.target.value)
                setPage(0)
              }}
            />
            {query.isLoading ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : items.length > 0 ? (
              <>
                <ul className="space-y-2">
                  {items.map((match) => (
                    <MatchHistoryRow key={match.matchId} match={match} />
                  ))}
                </ul>
                <NumberedPager page={page} total={total} pageSize={PAGE_SIZE} onPage={setPage} />
              </>
            ) : (
              <p className="text-sm text-muted-foreground">
                {search.trim() ? 'No matches for that search.' : 'No matches yet.'}
              </p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
