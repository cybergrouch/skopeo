import { useState } from 'react'
import { NtrpLabel } from '@/components/NtrpLabel'
import { ContentLink } from '@/components/ContentLink'
import { PlaceholderTag } from '@/components/PlaceholderTag'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useGetApiV1ReportsBandHops } from '@/api/generated/reports/reports'
import type { BandHopBucket, BandHopUserRow } from '@/api/generated/model'

/** yyyy-MM-dd for `n` days before today (local date). */
function isoDaysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return d.toISOString().slice(0, 10)
}

function playerName(user: BandHopUserRow): string {
  return user.displayName ?? user.publicCode
}

/**
 * A player's row: name → public profile, showing BOTH band-movement metrics (#724) — the farthest
 * excursion reached in-window and the net move to the closing band. A round-tripper reads net 0.
 */
function UserRow({ user }: { user: BandHopUserRow }) {
  return (
    <li className="flex items-center justify-between gap-2 py-1 text-sm">
      <span>
        <ContentLink to={`/players/${user.publicCode}`}>{playerName(user)}</ContentLink>
        <PlaceholderTag show={user.isPlaceholder} deleted={user.isDeleted} />
      </span>
      <span className="font-mono text-xs text-muted-foreground">
        excursion {user.excursionDistance} ({user.fromBand} → {user.excursionToBand}) · net{' '}
        {user.netDistance} ({user.fromBand} → {user.netToBand})
      </span>
    </li>
  )
}

/** A non-zero hop bucket (the exceptions to inspect), expanded by default. */
function JumpBucket({ bucket }: { bucket: BandHopBucket }) {
  const bands = bucket.hopDistance === 1 ? 'band' : 'bands'
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">
          Moved {bucket.hopDistance} {bands} — {bucket.count}{' '}
          {bucket.count === 1 ? 'player' : 'players'}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="divide-y">
          {bucket.users.map((u) => (
            <UserRow key={u.publicCode} user={u} />
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

/**
 * One bucketing (excursion or net): the jump buckets to inspect, plus the stayed-in-band players
 * collapsed behind a disclosure. Wrapped in a labelled region so each metric can be scoped.
 */
function MetricBreakdown({
  heading,
  buckets,
  emptyNote,
}: {
  heading: string
  buckets: BandHopBucket[]
  emptyNote: string
}) {
  const jumpBuckets = buckets.filter((b) => b.hopDistance > 0)
  const stayedBucket = buckets.find((b) => b.hopDistance === 0)
  return (
    <section aria-label={heading} className="space-y-4">
      <h3 className="text-sm font-semibold text-muted-foreground">{heading}</h3>
      {jumpBuckets.length > 0 ? (
        jumpBuckets.map((b) => <JumpBucket key={b.hopDistance} bucket={b} />)
      ) : (
        <p className="text-sm text-muted-foreground">{emptyNote}</p>
      )}
      {stayedBucket && stayedBucket.count > 0 ? (
        <details className="rounded-lg border p-3">
          <summary className="cursor-pointer text-sm font-medium">
            Stayed in band — {stayedBucket.count}{' '}
            {stayedBucket.count === 1 ? 'player' : 'players'}
          </summary>
          <ul className="mt-2 divide-y">
            {stayedBucket.users.map((u) => (
              <UserRow key={u.publicCode} user={u} />
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  )
}

/**
 * Admin Report tab (#216/#724): NTRP band-hop report over a date range, reported with BOTH metrics —
 * the farthest EXCURSION reached in-window (a transient crossing counts, #289) and the NET move to the
 * closing band (a round-tripper reads net 0). Both bucket breakdowns are shown, and each player row
 * carries both distances. Band labels only — no exact ratings.
 */
export function ReportTab() {
  const [startDate, setStartDate] = useState(() => isoDaysAgo(30))
  const [endDate, setEndDate] = useState(() => isoDaysAgo(0))

  const query = useGetApiV1ReportsBandHops(
    { startDate, endDate },
    { query: { enabled: startDate !== '' && endDate !== '' } },
  )
  const report = query.data

  const netStayedPct =
    report && report.totalPlayers > 0
      ? Math.round((report.netStayedCount / report.totalPlayers) * 100)
      : 0

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle><NtrpLabel /> band-hop report</CardTitle>
          <CardDescription>
            Over the chosen range, how far players moved from their starting <NtrpLabel /> band — both the
            farthest excursion they reached and where they ended (net). Most players should end in
            band; excursions and net jumps are the exceptions to review.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <Label htmlFor="report-start">Start date</Label>
            <Input
              id="report-start"
              type="date"
              value={startDate}
              max={endDate}
              onChange={(e) => setStartDate(e.target.value)}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="report-end">End date</Label>
            <Input
              id="report-end"
              type="date"
              value={endDate}
              min={startDate}
              onChange={(e) => setEndDate(e.target.value)}
            />
          </div>
        </CardContent>
      </Card>

      {query.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading report…</p>
      ) : query.isError ? (
        <p className="text-sm text-destructive" role="alert">
          Could not load the report. Check the date range and try again.
        </p>
      ) : report && report.totalPlayers === 0 ? (
        <p className="text-sm text-muted-foreground">No rated players to report on for this range.</p>
      ) : report ? (
        <>
          <Card>
            <CardHeader>
              <CardTitle className="text-base">
                {report.netStayedCount} of {report.totalPlayers} players ({netStayedPct}%) ended in
                their starting band
              </CardTitle>
              <CardDescription>
                {report.excursionJumpedCount}{' '}
                {report.excursionJumpedCount === 1 ? 'player' : 'players'} left their band at some
                point (excursion); {report.netJumpedCount} ended in a different band (net) over{' '}
                {report.startDate} → {report.endDate}.
              </CardDescription>
            </CardHeader>
          </Card>

          <MetricBreakdown
            heading="By farthest excursion"
            buckets={report.excursionBuckets}
            emptyNote="No excursions in this range — no one left their band at any point."
          />
          <MetricBreakdown
            heading="By net movement"
            buckets={report.netBuckets}
            emptyNote="No net band changes in this range — everyone ended where they started."
          />
        </>
      ) : null}
    </div>
  )
}
