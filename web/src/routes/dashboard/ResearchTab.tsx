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
import { NumberedPager } from '@/components/NumberedPager'
import { useGetApiV1UsersSearch } from '@/api/generated/users/users'
import { formatConfidence } from '@/lib/confidence'
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

/**
 * "3–1 · 4 matches" — the player's decided win–loss record and total match count (#342, singles +
 * doubles combined). Null when the player has no decided matches on record.
 */
function recordLine(record: UserSummaryResponse['record']): string | null {
  if (!record) return null
  return `${record.wins}–${record.losses} · ${record.total} ${record.total === 1 ? 'match' : 'matches'}`
}

export function ResearchTab() {
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
                        {user.rating || user.record ? (
                          <div className="shrink-0 text-right">
                            {user.rating ? (
                              <div className="font-medium">
                                NTRP {user.rating.level ?? user.rating.value}
                                {formatConfidence(user.rating.confidence)
                                  ? ` · ${formatConfidence(user.rating.confidence)}`
                                  : ''}
                              </div>
                            ) : null}
                            {recordLine(user.record) ? (
                              <div className="text-xs text-muted-foreground">{recordLine(user.record)}</div>
                            ) : null}
                          </div>
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
              <NumberedPager page={page} total={total} pageSize={PAGE_SIZE} onPage={setPage} />
            ) : null}
          </CardContent>
        </Card>
      ) : null}
    </div>
  )
}
