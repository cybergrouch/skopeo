import { useState } from 'react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useGetApiV1Standings } from '@/api/generated/standings/standings'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import type { StandingEntryResponse } from '@/api/generated/model'

type SexKey = 'Male' | 'Female' | 'none'

/** The sex toggle, in display order; "none" is the Unspecified group (rare). */
const SEX_TABS: { key: SexKey; label: string }[] = [
  { key: 'Male', label: 'Men' },
  { key: 'Female', label: 'Women' },
  { key: 'none', label: 'Unspecified' },
]

/** Map a band row's sex (Male/Female/null) to its toggle key. */
function sexKey(sex: string | null | undefined): SexKey {
  if (sex === 'Male') return 'Male'
  if (sex === 'Female') return 'Female'
  return 'none'
}

/** "34" — just the age here, since the sex is already given by the selected toggle. */
function metaLine(entry: StandingEntryResponse): string {
  return entry.age != null ? String(entry.age) : ''
}

/**
 * The Standings tab (#113, Phase 1; sex split #212): a read-only, per-NTRP-band "Ranking Race",
 * split into Men's and Women's standings (and an Unspecified group only if any such players exist).
 * A toggle switches the visible sex. Bands and entries arrive pre-ordered (strongest band first,
 * entries already ranked from 1 per group). Ratings are intentionally absent from the payload — this
 * view never shows a rating number; it is an interim, rating-derived ordering only.
 */
export function StandingsTab() {
  const standingsQuery = useGetApiV1Standings()
  const meQuery = useGetApiV1UsersMe()

  const bands = standingsQuery.data ?? []
  const meId = meQuery.data?.id

  const [chosen, setChosen] = useState<SexKey | null>(null)
  const availableTabs = SEX_TABS.filter((tab) => bands.some((band) => sexKey(band.sex) === tab.key))
  const activeKey =
    chosen && availableTabs.some((tab) => tab.key === chosen) ? chosen : availableTabs[0]?.key
  const shownBands = bands.filter((band) => sexKey(band.sex) === activeKey)

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Standings</CardTitle>
          <CardDescription>
            Interim standings — players are ordered by their current rating within
            each NTRP band, split into Men's and Women's standings. A points-based
            ranking will replace this.
          </CardDescription>
        </CardHeader>
      </Card>

      {standingsQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading standings…</p>
      ) : bands.length === 0 ? (
        <p className="text-sm text-muted-foreground">No standings yet.</p>
      ) : (
        <>
          {availableTabs.length > 1 ? (
            <div className="flex gap-2" role="tablist" aria-label="Standings by sex">
              {availableTabs.map((tab) => (
                <Button
                  key={tab.key}
                  type="button"
                  size="sm"
                  variant={tab.key === activeKey ? 'default' : 'outline'}
                  aria-pressed={tab.key === activeKey}
                  onClick={() => setChosen(tab.key)}
                >
                  {tab.label}
                </Button>
              ))}
            </div>
          ) : null}

          {shownBands.map((band) => (
            <Card key={band.band}>
              <CardHeader>
                <CardTitle>{band.band}</CardTitle>
              </CardHeader>
              <CardContent>
                <ol className="space-y-2">
                  {band.entries.map((entry) => {
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
                            {entry.displayName ?? entry.publicCode}
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
                      </li>
                    )
                  })}
                </ol>
              </CardContent>
            </Card>
          ))}
        </>
      )}
    </div>
  )
}
