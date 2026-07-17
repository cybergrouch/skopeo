import { Link, useParams } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useGetApiV1ClubsCodeCode, useGetApiV1Clubs } from '@/api/generated/clubs/clubs'
import { useGetApiV1ClubsClubIdPointsSummary } from '@/api/generated/points-budget/points-budget'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import type {
  ClubPublicEventResponse,
  ClubBudgetResponse,
} from '@/api/generated/model'
import { ShareCard } from '@/components/ShareCard'
import { PublicPageNav } from '@/components/PublicPageNav'
import {
  canManagePointsBudget,
  canViewClubPointsSummary,
  isClubOwner,
} from '@/auth/capabilities'

/** The per-event points shown publicly: awarded once the event is finalized, else designated. */
function eventPoints(event: ClubPublicEventResponse): number {
  return event.awardedPoints > 0 ? event.awardedPoints : event.designatedPoints
}

/** One event under a heading, linking to its own public event page, with type + points. */
function EventRow({ event }: { event: ClubPublicEventResponse }) {
  const finalized = event.awardedPoints > 0
  return (
    <li>
      <Link
        to={`/events/${event.publicCode}`}
        className="block rounded-lg border p-2 hover:bg-muted/50"
      >
        <span className="flex items-center justify-between gap-2">
          <span>{event.name}</span>
          <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium uppercase text-muted-foreground">
            {event.eventType}
          </span>
        </span>
        <span className="block text-xs text-muted-foreground">
          {event.startDate} – {event.endDate}
        </span>
        <span className="block text-xs text-muted-foreground">
          {eventPoints(event)} pts {finalized ? 'awarded' : 'designated'}
        </span>
      </Link>
    </li>
  )
}

/** A read-only list of events under a heading; shows an empty note when there are none. */
function EventSection({
  title,
  events,
  emptyText,
}: {
  title: string
  events: ClubPublicEventResponse[]
  emptyText: string
}) {
  return (
    <div>
      <div className="text-xs font-medium uppercase text-muted-foreground">{title}</div>
      {events.length > 0 ? (
        <ul className="mt-1 space-y-1">
          {events.map((e) => (
            <EventRow key={e.publicCode} event={e} />
          ))}
        </ul>
      ) : (
        <p className="text-muted-foreground">{emptyText}</p>
      )}
    </div>
  )
}

/** One Budgeted/Allocated/Free row for a club's event-type utilization (owner-only). */
function UtilizationRow({ row }: { row: ClubBudgetResponse }) {
  return (
    <tr className="border-t">
      <td className="py-1 pr-2">{row.eventType}</td>
      <td className="py-1 pr-2 text-right">{row.budgeted}</td>
      <td className="py-1 pr-2 text-right">{row.allocated}</td>
      <td className="py-1 text-right">{row.free}</td>
    </tr>
  )
}

/**
 * Owner-only club utilization (#403 Phase E): Budgeted / Allocated / Free per event type. Rendered
 * only when the authenticated viewer owns this club (or is an admin/points-manager); the fetch is
 * gated by {@link enabled} so nothing leaks to anonymous or other viewers.
 */
function OwnerUtilizationSection({ clubId, enabled }: { clubId: string; enabled: boolean }) {
  const summary = useGetApiV1ClubsClubIdPointsSummary(clubId, {
    query: { enabled },
  })
  if (!enabled || !summary.data) return null
  return (
    <div>
      <div className="text-xs font-medium uppercase text-muted-foreground">
        Club utilization (owner only)
      </div>
      <table className="mt-1 w-full text-xs">
        <thead>
          <tr className="text-muted-foreground">
            <th className="py-1 pr-2 text-left font-medium">Type</th>
            <th className="py-1 pr-2 text-right font-medium">Budgeted</th>
            <th className="py-1 pr-2 text-right font-medium">Allocated</th>
            <th className="py-1 text-right font-medium">Free</th>
          </tr>
        </thead>
        <tbody>
          {summary.data.utilization.map((row) => (
            <UtilizationRow key={row.eventType} row={row} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * Public, read-only club page reached via `/clubs/:code` (issue #327). Resolves the club by its
 * shareable code and shows its name plus the events it organizes, split into "Upcoming" and "Past"
 * (each event links to its own public page, with its type + per-event points), with a QR for sharing.
 * Viewable without login (#193); no owner or roster PII is exposed.
 *
 * Additionally (#403 Phase E), when the authenticated viewer owns *this* club (or is an admin /
 * points-manager) it fetches and renders the gated per-type utilization — anonymous and other viewers
 * never see or fetch it.
 */
export function ClubPage() {
  const { code = '' } = useParams()
  const query = useGetApiV1ClubsCodeCode(code)
  const club = query.data

  const meQuery = useGetApiV1UsersMe()
  const me = meQuery.data
  const capabilities = me?.capabilities ?? []
  // The staff club list (CLUB_OWNER / admin readable) resolves this club's id + ownerIds by code; only
  // fetched when the viewer could possibly own it, so plain/anonymous viewers never call it.
  const mayBeOwner = canManagePointsBudget(capabilities) || isClubOwner(capabilities)
  const clubsQuery = useGetApiV1Clubs({ query: { enabled: mayBeOwner } })
  const matched = clubsQuery.data?.find((c) => c.publicCode === club?.publicCode)
  const ownerIds = matched?.owners.map((o) => o.userId) ?? []
  const canViewUtilization =
    matched !== undefined &&
    canViewClubPointsSummary(capabilities, ownerIds, me?.id)

  return (
    <div className="flex min-h-svh items-start justify-center bg-muted/40 p-4">
      <div className="w-full max-w-sm space-y-4 pt-10">
        <PublicPageNav />

        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading club…</p>
        ) : null}

        {query.isError ? (
          <p className="text-sm text-muted-foreground">
            We couldn’t find or load this club. The link may be wrong, or try again.
          </p>
        ) : null}

        {club ? (
          <Card>
            <CardHeader>
              <CardTitle>{club.name}</CardTitle>
              <CardDescription>
                Club ID:{' '}
                <code className="select-all font-mono font-medium text-foreground">{club.publicCode}</code>
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4 text-sm">
              {/* A soft-deleted club stays reachable by link for traceability (#325) — flag it. */}
              {club.isActive === false ? (
                <p
                  role="status"
                  className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
                >
                  This club has been deleted. It’s kept for reference only.
                </p>
              ) : null}
              <EventSection
                title="Upcoming events"
                events={club.upcoming}
                emptyText="No upcoming events."
              />
              <EventSection
                title="Past events"
                events={club.past}
                emptyText="No past events."
              />
              {matched ? (
                <OwnerUtilizationSection clubId={matched.id} enabled={canViewUtilization} />
              ) : null}
            </CardContent>
          </Card>
        ) : null}

        {club ? (
          <ShareCard
            url={`${window.location.origin}/clubs/${club.publicCode}`}
            title="Share this club"
            description="Scan this code or copy the link to open this club."
            shareText={`${club.name} on Skopeo`}
          />
        ) : null}
      </div>
    </div>
  )
}
