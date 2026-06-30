import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { PlayerSearchForm } from '@/components/PlayerSearchForm'
import { SearchPager } from '@/components/SearchPager'
import { useGetApiV1Users } from '@/api/generated/users/users'
import type {
  GetApiV1UsersParams,
  UserSummaryResponse,
} from '@/api/generated/model'

const PAGE_SIZE = 25

/** "Female · 34" — sex and age, omitting whatever is missing. */
function metaLine(user: UserSummaryResponse): string {
  const parts: string[] = []
  if (user.sex) parts.push(user.sex)
  if (user.age != null) parts.push(String(user.age))
  return parts.join(' · ')
}

export function ResearchTab() {
  const [applied, setApplied] = useState<GetApiV1UsersParams | null>(null)
  const [page, setPage] = useState(0)

  // Fetch one extra row beyond the page so we can tell whether a next page exists without a
  // separate total-count call; the extra row is trimmed before rendering.
  const query = useGetApiV1Users(
    applied ? { ...applied, limit: PAGE_SIZE + 1, offset: page * PAGE_SIZE } : {},
    { query: { enabled: applied !== null } },
  )
  const fetched = query.data ?? []
  const results = fetched.slice(0, PAGE_SIZE)
  const hasMore = fetched.length > PAGE_SIZE

  function applySearch(params: GetApiV1UsersParams | null) {
    setPage(0) // a new search restarts at the first page
    setApplied(params)
  }

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Player research</CardTitle>
          <CardDescription>
            Find members by name, sex, age, and NTRP rating. Combine any filters.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <PlayerSearchForm onApply={applySearch} />
        </CardContent>
      </Card>

      {applied !== null ? (
        <Card>
          <CardHeader>
            <CardTitle>Results</CardTitle>
          </CardHeader>
          <CardContent>
            {query.isLoading ? (
              <p className="text-sm text-muted-foreground">Searching…</p>
            ) : query.isError ? (
              <p className="text-sm text-destructive" role="alert">
                Invalid filters. Check the age/rating ranges.
              </p>
            ) : results.length > 0 ? (
              <ul className="space-y-2">
                {results.map((user) => {
                  const meta = metaLine(user)
                  return (
                    <li key={user.id}>
                      <Link
                        to={`/players/${user.publicCode}`}
                        className="flex items-center gap-3 rounded-lg border p-3 text-sm hover:bg-muted/50"
                      >
                        {user.photoUrl ? (
                          <img
                            src={user.photoUrl}
                            alt=""
                            referrerPolicy="no-referrer"
                            className="h-9 w-9 shrink-0 rounded-full object-cover"
                          />
                        ) : (
                          <div
                            aria-hidden="true"
                            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted text-sm font-medium text-muted-foreground"
                          >
                            {(user.displayName ?? 'P').charAt(0).toUpperCase()}
                          </div>
                        )}
                        <div className="min-w-0 flex-1">
                          <div className="font-medium">
                            {user.displayName ?? user.id}{' '}
                            <span className="font-normal text-muted-foreground">
                              · {user.publicCode}
                            </span>
                          </div>
                          {meta ? (
                            <div className="text-muted-foreground">{meta}</div>
                          ) : null}
                        </div>
                        {user.rating ? (
                          <span className="shrink-0 font-medium">
                            NTRP {user.rating.level ?? user.rating.value}
                          </span>
                        ) : null}
                      </Link>
                    </li>
                  )
                })}
              </ul>
            ) : (
              <p className="text-sm text-muted-foreground">No matching players.</p>
            )}
            {!query.isLoading && !query.isError ? (
              <SearchPager
                page={page}
                hasMore={hasMore}
                onPrev={() => setPage((p) => Math.max(0, p - 1))}
                onNext={() => setPage((p) => p + 1)}
              />
            ) : null}
          </CardContent>
        </Card>
      ) : null}
    </div>
  )
}
