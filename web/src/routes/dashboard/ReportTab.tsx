import { useState } from 'react'
import { Link } from 'react-router-dom'
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

/** A player's row within a bucket: name → public profile, showing the band move. */
function UserRow({ user }: { user: BandHopUserRow }) {
  return (
    <li className="flex items-center justify-between gap-2 py-1 text-sm">
      <Link to={`/players/${user.publicCode}`} className="text-primary hover:underline">
        {playerName(user)}
      </Link>
      <span className="font-mono text-xs text-muted-foreground">
        {user.fromBand} → {user.toBand}
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
 * Admin Report tab (#216): NTRP band-hop report over a date range. The headline is how many players
 * stayed within their band (the healthy majority); jumps are the exceptions, listed per hop distance
 * with a link to each player's public profile. Band labels only — no exact ratings.
 */
export function ReportTab() {
  const [startDate, setStartDate] = useState(() => isoDaysAgo(30))
  const [endDate, setEndDate] = useState(() => isoDaysAgo(0))

  const query = useGetApiV1ReportsBandHops(
    { startDate, endDate },
    { query: { enabled: startDate !== '' && endDate !== '' } },
  )
  const report = query.data

  const stayedPct =
    report && report.totalPlayers > 0
      ? Math.round((report.stayedCount / report.totalPlayers) * 100)
      : 0
  const jumpBuckets = (report?.buckets ?? []).filter((b) => b.hopDistance > 0)
  const stayedBucket = (report?.buckets ?? []).find((b) => b.hopDistance === 0)

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>NTRP band-hop report</CardTitle>
          <CardDescription>
            Over the chosen range, how many players stayed within their NTRP band versus jumped. Most
            players should stay in band; jumps are the exceptions to review.
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
                {report.stayedCount} of {report.totalPlayers} players ({stayedPct}%) stayed within
                their band
              </CardTitle>
              <CardDescription>
                {report.jumpedCount} {report.jumpedCount === 1 ? 'player' : 'players'} moved at least
                one band over {report.startDate} → {report.endDate}.
              </CardDescription>
            </CardHeader>
          </Card>

          {jumpBuckets.length > 0 ? (
            jumpBuckets.map((b) => <JumpBucket key={b.hopDistance} bucket={b} />)
          ) : (
            <p className="text-sm text-muted-foreground">
              No band jumps in this range — everyone stayed within their band.
            </p>
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
        </>
      ) : null}
    </div>
  )
}
