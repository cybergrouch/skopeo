import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { PlayerSearchForm } from '@/components/PlayerSearchForm'
import { NumberedPager } from '@/components/NumberedPager'
import { SetRatingForm } from '@/components/SetRatingForm'
import { getGetApiV1UsersSearchQueryKey, useGetApiV1UsersSearch } from '@/api/generated/users/users'
import { formatConfidence } from '@/lib/confidence'
import type { GetApiV1UsersParams, UserSummaryResponse } from '@/api/generated/model'

const PAGE_SIZE = 25

/** "Female · 34" — sex and age, omitting whatever is missing. */
function metaLine(user: UserSummaryResponse): string {
  const parts: string[] = []
  if (user.sex) parts.push(user.sex)
  if (user.age != null) parts.push(String(user.age))
  return parts.join(' · ')
}

/**
 * Ratings-tab search-and-rate (#205): a RATER searches all players (name/sex/age/rating, paginated
 * 25 per page) and sets or overrides each result's rating inline. Reuses the shared search form,
 * pager, and set-rating form.
 */
export function RatingsSearchSection() {
  const queryClient = useQueryClient()
  const [applied, setApplied] = useState<GetApiV1UsersParams | null>(null)
  const [page, setPage] = useState(0)

  // Paged search (#232): the endpoint returns { items, total } so we can show numbered pages.
  const query = useGetApiV1UsersSearch(
    applied ? { ...applied, limit: PAGE_SIZE, offset: page * PAGE_SIZE } : {},
    { query: { enabled: applied !== null } },
  )
  const results = query.data?.items ?? []
  const total = query.data?.total ?? 0

  function applySearch(params: GetApiV1UsersParams | null) {
    setPage(0)
    setApplied(params)
  }

  // Refresh the search results so an updated rating shows immediately.
  const invalidate = () => queryClient.invalidateQueries({ queryKey: getGetApiV1UsersSearchQueryKey() })

  return (
    <Card>
      <CardHeader>
        <CardTitle>Search &amp; rate</CardTitle>
        <CardDescription>
          Find any player by name, sex, age, or NTRP rating, then set or override their rating.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <PlayerSearchForm onApply={applySearch} />

        {applied !== null ? (
          query.isLoading ? (
            <p className="text-sm text-muted-foreground">Searching…</p>
          ) : query.isError ? (
            <p className="text-sm text-destructive" role="alert">
              Invalid filters. Check the age/rating ranges.
            </p>
          ) : results.length > 0 ? (
            <>
              <ul className="space-y-3">
                {results.map((user) => {
                  const meta = metaLine(user)
                  return (
                    <li key={user.id} className="rounded-lg border p-3">
                      <div className="mb-2 flex items-center justify-between gap-2 text-sm">
                        <span className="min-w-0">
                          <span className="block font-medium">
                            {user.displayName ?? user.id}{' '}
                            <span className="font-normal text-muted-foreground">· {user.publicCode}</span>
                          </span>
                          {meta ? <span className="block text-xs text-muted-foreground">{meta}</span> : null}
                        </span>
                        {user.rating ? (
                          <span className="shrink-0 text-xs font-medium">
                            NTRP {user.rating.level ?? user.rating.value}
                            {formatConfidence(user.rating.confidence)
                              ? ` · ${formatConfidence(user.rating.confidence)}`
                              : ''}
                          </span>
                        ) : (
                          <span className="shrink-0 text-xs text-muted-foreground">Unrated</span>
                        )}
                      </div>
                      <SetRatingForm userId={user.id} initialValue={user.rating?.level ?? ''} onSaved={invalidate} />
                    </li>
                  )
                })}
              </ul>
              <NumberedPager page={page} total={total} pageSize={PAGE_SIZE} onPage={setPage} />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">No matching players.</p>
          )
        ) : null}
      </CardContent>
    </Card>
  )
}
