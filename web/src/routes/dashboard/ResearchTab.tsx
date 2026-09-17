import { useState, type ReactNode } from 'react'
import { NtrpLabel } from '@/components/NtrpLabel'
import { Avatar } from '@/components/Avatar'
import { Link } from 'react-router-dom'
import { AccountStatusChip } from '@/components/AccountStatusChip'
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
import type {
  GetApiV1UsersParams,
  GetApiV1UsersSearchParams,
  UserSummaryResponse,
} from '@/api/generated/model'

const PAGE_SIZE = 25

type SortColumn = NonNullable<GetApiV1UsersSearchParams['sort']>
type SortDirection = NonNullable<GetApiV1UsersSearchParams['direction']>

/**
 * The sortable columns, in the order they appear. Icon and code are deliberately absent: an avatar has
 * no order, and the code is an opaque identifier nobody wants ranked.
 *
 * The rating header is plain text, NOT `<NtrpLabel />` — that renders a button, and a button inside the
 * sort button would be nested interactive content (#697). The framework disclaimer still sits one click
 * away in this card's description and on every rating value in the column.
 */
const SORTABLE: ReadonlyArray<{ column: SortColumn; label: ReactNode }> = [
  { column: 'DISPLAY_NAME', label: 'Display name' },
  { column: 'LAST_NAME', label: 'Last name' },
  { column: 'FIRST_NAME', label: 'First name' },
  { column: 'SEX', label: 'Sex' },
  { column: 'AGE', label: 'Age' },
  { column: 'RATING', label: 'Rating' },
  { column: 'STATUS', label: 'Status' },
]

/**
 * A column header that sorts. Clicking the active column flips direction; clicking another starts it
 * ascending. `aria-sort` carries the state to screen readers, and is what the arrow glyph mirrors.
 */
function SortHeader({
  column,
  label,
  sort,
  direction,
  onSort,
}: {
  column: SortColumn
  label: ReactNode
  sort: SortColumn | null
  direction: SortDirection
  onSort: (column: SortColumn) => void
}) {
  const active = sort === column
  return (
    <th
      scope="col"
      className="py-1 pr-3 font-medium"
      aria-sort={active ? (direction === 'ASC' ? 'ascending' : 'descending') : 'none'}
    >
      <button
        type="button"
        onClick={() => onSort(column)}
        className="inline-flex items-center gap-1 hover:text-foreground"
      >
        {label}
        <span aria-hidden="true" className={active ? '' : 'opacity-0'}>
          {direction === 'ASC' ? '▲' : '▼'}
        </span>
      </button>
    </th>
  )
}

/**
 * Whether the player's rating is still calibrating (#881). Rendered as a word rather than a tick so it
 * reads the same to a screen reader and in a copied-out table.
 *
 * `inCalibration` is nullable on the wire — null means the endpoint was not asked, not that the player
 * is settled — but this table always calls the endpoint that answers it, so null here would be a bug
 * upstream and shows as the same em-dash as "no".
 */
function CalibrationCell({ user }: { user: UserSummaryResponse }) {
  if (user.inCalibration) {
    return (
      <span title="The first matches after this rating was assigned by hand; the rating still moves freely.">
        Calibrating
      </span>
    )
  }
  return (
    <span className="text-muted-foreground" aria-label="Not calibrating">
      —
    </span>
  )
}

export function ResearchTab() {
  const [applied, setApplied] = useState<GetApiV1UsersParams | null>(null)
  const [page, setPage] = useState(0)
  const [sort, setSort] = useState<SortColumn | null>(null)
  const [direction, setDirection] = useState<SortDirection>('ASC')

  // Paged search (#232): the endpoint returns { items, total } so we can show numbered pages. Research
  // includes soft-deleted accounts (#518) so they stay discoverable for history/lookup; the Status
  // column says which state each one is in. Sorting is a server concern (#1050) — it decides WHICH page
  // you get, so it must be a query param rather than a sort of the 25 rows already fetched.
  const query = useGetApiV1UsersSearch(
    applied
      ? {
          ...applied,
          limit: PAGE_SIZE,
          offset: page * PAGE_SIZE,
          includeInactive: true,
          ...(sort ? { sort, direction } : {}),
        }
      : {},
    { query: { enabled: applied !== null } },
  )
  const results = query.data?.items ?? []
  const total = query.data?.total ?? 0

  function applySearch(params: GetApiV1UsersParams | null) {
    setPage(0) // a new search restarts at the first page
    setApplied(params)
  }

  function onSort(column: SortColumn) {
    // Re-sorting resets to page 1: page 3 of the old order holds different players than page 3 of the
    // new one, so keeping the offset would silently move the user somewhere they never asked for.
    setPage(0)
    if (sort === column) {
      setDirection(direction === 'ASC' ? 'DESC' : 'ASC')
    } else {
      setSort(column)
      setDirection('ASC')
    }
  }

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Player research</CardTitle>
          <CardDescription>
            Find members by name, sex, age, status, and <NtrpLabel /> rating. Combine any filters.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <PlayerSearchForm onApply={applySearch} showStatus />
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
              // The table is wider than a phone; scrolling it sideways keeps every column rather than
              // hiding some, which on a research screen is the wrong trade.
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-xs text-muted-foreground">
                    <tr>
                      <th scope="col" className="py-1 pr-3">
                        <span className="sr-only">Photo</span>
                      </th>
                      <th scope="col" className="py-1 pr-3 font-medium">
                        Code
                      </th>
                      {SORTABLE.map((column) => (
                        <SortHeader
                          key={column.column}
                          column={column.column}
                          label={column.label}
                          sort={sort}
                          direction={direction}
                          onSort={onSort}
                        />
                      ))}
                      <th scope="col" className="py-1 font-medium">
                        Calibration
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {results.map((user) => (
                      <tr key={user.id} className="border-t align-middle">
                        <td className="py-2 pr-3">
                          {/* Non-enlargeable: the code cell already links to the profile, and a nested
                              button inside a link is invalid (#697). */}
                          <Avatar photoUrl={user.photoUrl} name={user.displayName} size="sm" />
                        </td>
                        {/* Both identifying cells link to the profile, which is how a table keeps the
                            card's whole-row navigation: a <tr> cannot wrap an <a>, and stretching one
                            invisibly across the row would make every cell's text a link target. */}
                        <td className="py-2 pr-3">
                          <Link
                            to={`/players/${user.publicCode}`}
                            className="font-medium hover:underline"
                          >
                            {user.publicCode}
                          </Link>
                        </td>
                        <td className="py-2 pr-3">
                          {user.displayName ? (
                            <Link to={`/players/${user.publicCode}`} className="hover:underline">
                              {user.displayName}
                            </Link>
                          ) : (
                            '—'
                          )}
                        </td>
                        <td className="py-2 pr-3">{user.lastName ?? '—'}</td>
                        <td className="py-2 pr-3">{user.firstName ?? '—'}</td>
                        <td className="py-2 pr-3">{user.sex ?? '—'}</td>
                        <td className="py-2 pr-3">{user.age ?? '—'}</td>
                        <td className="py-2 pr-3">
                          {user.rating ? (
                            <NtrpLabel value={user.rating.level ?? user.rating.value} />
                          ) : (
                            '—'
                          )}
                        </td>
                        <td className="py-2 pr-3">
                          {/* The Status column REPLACES the inline PlaceholderTag here (#1050) — with
                              both, an unclaimed player would read "Unclaimed" twice in one row. */}
                          <AccountStatusChip status={user.status} />
                        </td>
                        <td className="py-2">
                          <CalibrationCell user={user} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
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
