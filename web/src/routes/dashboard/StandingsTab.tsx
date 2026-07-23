import { useState } from 'react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { formatPoints } from '@/lib/points'
import { ContentLink } from '@/components/ContentLink'
import { PlaceholderTag } from '@/components/PlaceholderTag'
import { NumberedPager } from '@/components/NumberedPager'
import {
  useGetApiV1Standings,
  getApiV1StandingsMe,
} from '@/api/generated/standings/standings'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import type { StandingEntryResponse } from '@/api/generated/model'

const PAGE_SIZE = 25

type SexKey = 'Male' | 'Female' | 'none'

/** The sex toggle, in display order; "none" is the Unspecified group (rare). */
const SEX_TABS: { key: SexKey; label: string }[] = [
  { key: 'Male', label: 'Men' },
  { key: 'Female', label: 'Women' },
  { key: 'none', label: 'Unspecified' },
]

/** Map a group's sex (Male/Female/null) to its toggle key, and back to the query value. */
function sexKey(sex: string | null | undefined): SexKey {
  if (sex === 'Male') return 'Male'
  if (sex === 'Female') return 'Female'
  return 'none'
}
function sexValue(key: SexKey): string | undefined {
  return key === 'none' ? undefined : key
}

/** "34" — just the age here, since the sex is already given by the selected toggle. */
function metaLine(entry: StandingEntryResponse): string {
  return entry.age != null ? String(entry.age) : ''
}

/**
 * The Standings tab (#113; snapshot serving layer #220): a persisted, paged per-NTRP-band "Ranking
 * Race" (sex split #212). The tab is selection-driven (#433): picking a band or flipping the Men/Women
 * segmented toggle loads that 25/page group immediately (nothing loads a whole band automatically); a
 * pager walks the pages. "Find me" loads the page that contains the current user, syncs the controls to
 * their group, and highlights their row. The band dropdown lists EVERY NTRP band (from the response
 * `bands` list), including empty ones (#113); the sex toggles always offer Men + Women so an empty band
 * is still queryable. The precise rating shows only when the payload includes it (RATER/ADMIN, #186).
 * Player name links wear the themed ContentLink style so they stay readable in every theme (#417).
 *
 * Exactly one backend query per user action (#433): the query args ARE the active `band`/`sex`/`offset`
 * state, and only the explicit user-action handlers (dropdown onChange, a segment onClick that actually
 * changes sex, Find-me onClick, the pager) mutate that state. There is NO effect watching selection, so
 * the programmatic sync of the controls to a served/located result can never re-fire the query: "Find me"
 * sets band+sex+offset in a single update, yielding exactly one query, and the controls simply mirror the
 * resulting active state. Clicking the already-active segment is a no-op (no state change → no call).
 */
export function StandingsTab() {
  const meQuery = useGetApiV1UsersMe()
  const meId = meQuery.data?.id

  // The active query: band code + sex + page offset actually being served — the ONLY source that drives a
  // fetch. band === undefined lets the server default to the strongest group on first load; the served
  // page.band then seeds the dropdown until the user picks one. sex defaults to Male (Men) on first load.
  const [band, setBand] = useState<string | undefined>(undefined)
  const [sex, setSex] = useState<SexKey>('Male')
  const [offset, setOffset] = useState(0)

  const pageQuery = useGetApiV1Standings({
    band,
    sex: sexValue(sex),
    limit: PAGE_SIZE,
    offset,
  })
  const page = pageQuery.data
  const groups = page?.groups ?? []
  // Every NTRP band, empty ones included (#113) — the dropdown is no longer limited to populated groups.
  const bands = page?.bands ?? []

  // The dropdown mirrors the active band, falling back to the served group until the user picks one; the
  // segmented toggle reflects the active sex directly. Both are pure reads of state — no re-querying sync.
  const shownBand = band ?? page?.band ?? ''
  const shownSex: SexKey = sex

  // Always offer the standard Men + Women toggles so an empty band is still queryable; only show the
  // rare Unspecified toggle when the served snapshot actually has such a group.
  const availableSexTabs = SEX_TABS.filter(
    (tab) => tab.key !== 'none' || groups.some((g) => sexKey(g.sex) === 'none'),
  )

  // A user action: pick a band → load it immediately for the current sex, from the first page.
  function selectBand(code: string) {
    setBand(code || undefined)
    setOffset(0)
  }

  // A user action: flip the sex segment. Clicking the already-active segment is a no-op — no state change,
  // so no backend call. Pin the query to the band the user is looking at (shownBand, which may still be the
  // server default) so switching sex keeps the same band rather than falling back to the default band.
  function selectSex(key: SexKey) {
    if (key === shownSex) return
    setBand(shownBand || undefined)
    setSex(key)
    setOffset(0)
  }

  // A user action: locate the caller, then sync band+sex+offset in a single state update. This is the one
  // query for the action — there is no effect watching band/sex, so the forced control sync cannot trigger
  // a second (duplicate) fetch.
  async function findMe() {
    const located = await getApiV1StandingsMe({ limit: PAGE_SIZE })
    setBand(located.band ?? undefined)
    setSex(sexKey(located.sex))
    setOffset(located.offset)
  }

  const entries = page?.entries ?? []
  // POINTS mode with no computed snapshot (#428): the backend returns source=POINTS with no entries rather
  // than silently serving ratings, so the tab shows an explicit "run a points calculation" empty state.
  const isPointsEmptyState = page?.source === 'POINTS' && entries.length === 0

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Standings</CardTitle>
          <CardDescription>
            Interim standings — players are ordered by their current rating within
            each NTRP band, split into Men's and Women's standings. Pick a band to
            view one page at a time. A points-based ranking will replace this.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <label htmlFor="standings-band" className="text-xs text-muted-foreground">
              Band
            </label>
            <select
              id="standings-band"
              value={shownBand}
              onChange={(e) => selectBand(e.target.value)}
              className="h-9 rounded-md border border-input bg-transparent px-3 text-sm"
            >
              {bands.length === 0 ? <option value="">Loading…</option> : null}
              {bands.map((b) => (
                <option key={b.code} value={b.code}>
                  {b.label}
                </option>
              ))}
            </select>
          </div>

          {availableSexTabs.length > 0 ? (
            // A single fused segmented control (shared border, no gaps) so Men/Women read as a toggle.
            // Colours come from theme tokens so the active segment stays readable across seasonal themes.
            <div
              className="inline-flex h-9 items-stretch overflow-hidden rounded-md border border-input"
              role="tablist"
              aria-label="Standings by sex"
            >
              {availableSexTabs.map((tab, i) => {
                const active = tab.key === shownSex
                return (
                  <button
                    key={tab.key}
                    type="button"
                    role="tab"
                    aria-selected={active}
                    aria-pressed={active}
                    onClick={() => selectSex(tab.key)}
                    className={cn(
                      'px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset',
                      i > 0 ? 'border-l border-input' : '',
                      active
                        ? 'bg-primary text-primary-foreground'
                        : 'bg-transparent text-foreground hover:bg-accent hover:text-accent-foreground',
                    )}
                  >
                    {tab.label}
                  </button>
                )
              })}
            </div>
          ) : null}

          {/* The only button left in the row: a distinct secondary "Find me" (#433). */}
          <Button
            type="button"
            size="sm"
            variant="secondary"
            onClick={findMe}
            disabled={!meId}
            className="ml-auto"
          >
            Find me
          </Button>
        </CardContent>
      </Card>

      {pageQuery.isLoading || pageQuery.isFetching ? (
        <p className="text-sm text-muted-foreground">Loading standings…</p>
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>{page?.label ?? 'Standings'}</CardTitle>
          </CardHeader>
          <CardContent>
            {isPointsEmptyState ? (
              <p className="text-sm text-muted-foreground">
                No points standings have been computed yet — an administrator needs
                to run a points calculation.
              </p>
            ) : entries.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No players in this group.
              </p>
            ) : (
              <ol className="space-y-2">
                {entries.map((entry) => {
                  const isMe = entry.userId === meId
                  const meta = metaLine(entry)
                  return (
                    <li
                      key={entry.userId}
                      aria-label={isMe ? 'Your standing' : undefined}
                      className={`flex items-center gap-3 rounded-lg border p-3 text-sm ${
                        isMe ? 'bg-muted ring-1 ring-ring' : ''
                      }`}
                    >
                      <span className="w-6 shrink-0 text-right font-medium tabular-nums text-muted-foreground">
                        {entry.rank}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="font-medium">
                          <ContentLink to={`/players/${entry.publicCode}`}>
                            {entry.displayName ?? entry.publicCode}
                          </ContentLink>
                          <PlaceholderTag show={entry.isPlaceholder} deleted={entry.isDeleted} />
                          {isMe ? (
                            <span className="ml-2 text-xs font-normal text-muted-foreground">
                              You
                            </span>
                          ) : null}
                        </div>
                        {meta ? (
                          <div className="text-muted-foreground">{meta}</div>
                        ) : null}
                      </div>
                      {/*
                        Source-aware metric (#457): under POINTS show the public points total; under
                        RATING show the precise rating, present only for RATER/ADMINISTRATOR viewers (#186).
                      */}
                      {page?.source === 'POINTS' ? (
                        entry.points ? (
                          <span className="shrink-0 font-mono text-xs text-muted-foreground">
                            {formatPoints(entry.points)} pts
                          </span>
                        ) : null
                      ) : entry.currentRating ? (
                        <span className="shrink-0 font-mono text-xs text-muted-foreground">
                          {entry.currentRating}
                        </span>
                      ) : null}
                    </li>
                  )
                })}
              </ol>
            )}
            <NumberedPager
              page={Math.floor(offset / PAGE_SIZE)}
              total={page?.total ?? 0}
              pageSize={PAGE_SIZE}
              onPage={(p) => setOffset(p * PAGE_SIZE)}
            />
          </CardContent>
        </Card>
      )}
    </div>
  )
}
