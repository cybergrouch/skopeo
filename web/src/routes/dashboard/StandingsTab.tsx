import { useState } from 'react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { ContentLink } from '@/components/ContentLink'
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
 * Race" (sex split #212). The player picks a band + sex and clicks "View" to load one 25/page group
 * (nothing loads a whole band automatically); a pager walks the pages. "Find me" loads the page that
 * contains the current user and highlights their row. The band dropdown lists EVERY NTRP band (from the
 * response `bands` list), including empty ones (#113); the sex toggles always offer Men + Women so an
 * empty band is still queryable. The precise rating shows only when the payload includes it (RATER/ADMIN,
 * #186). Player name links wear the themed ContentLink style so they stay readable in every theme (#417).
 */
export function StandingsTab() {
  const meQuery = useGetApiV1UsersMe()
  const meId = meQuery.data?.id

  // The active query: band code + sex + page offset actually being served. band === undefined lets the
  // server default to the strongest group on first load; the served page.band then seeds the controls.
  const [band, setBand] = useState<string | undefined>(undefined)
  const [sex, setSex] = useState<SexKey>('Male')
  const [offset, setOffset] = useState(0)
  // The pending band/sex in the controls, applied only on "View". null means "not yet chosen" — the
  // controls then mirror the served group, so no state has to be synced back out of the query result.
  const [pendingBand, setPendingBand] = useState<string | null>(null)
  const [pendingSex, setPendingSex] = useState<SexKey | null>(null)

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

  // The controls fall back to the served group until the user picks something (avoids syncing effect state).
  const shownBand = pendingBand ?? page?.band ?? ''
  const shownSex: SexKey = pendingSex ?? sexKey(page?.sex)

  // Always offer the standard Men + Women toggles so an empty band is still queryable; only show the
  // rare Unspecified toggle when the served snapshot actually has such a group.
  const availableSexTabs = SEX_TABS.filter(
    (tab) => tab.key !== 'none' || groups.some((g) => sexKey(g.sex) === 'none'),
  )

  function view() {
    setBand(shownBand || undefined)
    setSex(shownSex)
    setOffset(0)
  }

  async function findMe() {
    const located = await getApiV1StandingsMe({ limit: PAGE_SIZE })
    const key = sexKey(located.sex)
    setPendingBand(located.band)
    setPendingSex(key)
    setBand(located.band)
    setSex(key)
    setOffset(located.offset)
  }

  const entries = page?.entries ?? []

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Standings</CardTitle>
          <CardDescription>
            Interim standings — players are ordered by their current rating within
            each NTRP band, split into Men's and Women's standings. Pick a band and
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
              onChange={(e) => {
                setPendingBand(e.target.value)
                setPendingSex(null) // let the sex fall back to what's available in the new band
              }}
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
            <div
              className="flex gap-2"
              role="tablist"
              aria-label="Standings by sex"
            >
              {availableSexTabs.map((tab) => (
                <Button
                  key={tab.key}
                  type="button"
                  size="sm"
                  variant={tab.key === shownSex ? 'default' : 'outline'}
                  aria-pressed={tab.key === shownSex}
                  onClick={() => setPendingSex(tab.key)}
                >
                  {tab.label}
                </Button>
              ))}
            </div>
          ) : null}

          <Button type="button" size="sm" onClick={view} disabled={!shownBand}>
            View
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={findMe}
            disabled={!meId}
          >
            Find me
          </Button>
        </CardContent>
      </Card>

      {pageQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading standings…</p>
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>{page?.label ?? 'Standings'}</CardTitle>
          </CardHeader>
          <CardContent>
            {entries.length === 0 ? (
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
                      {/* Precise rating: present only for RATER/ADMINISTRATOR viewers (#186). */}
                      {entry.currentRating ? (
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
