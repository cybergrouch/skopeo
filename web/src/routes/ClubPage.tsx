import { Link, useParams } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useGetApiV1ClubsCodeCode } from '@/api/generated/clubs/clubs'
import type { ClubPublicEventResponse } from '@/api/generated/model'
import { ShareCard } from '@/components/ShareCard'
import { PublicPageNav } from '@/components/PublicPageNav'

/** One event under a heading, linking to its own public event page. */
function EventRow({ event }: { event: ClubPublicEventResponse }) {
  return (
    <li>
      <Link
        to={`/events/${event.publicCode}`}
        className="block rounded-lg border p-2 hover:bg-muted/50"
      >
        <span className="block">{event.name}</span>
        <span className="block text-xs text-muted-foreground">
          {event.startDate} – {event.endDate}
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

/**
 * Public, read-only club page reached via `/clubs/:code` (issue #327). Resolves the club by its
 * shareable code and shows its name plus the events it organizes, split into "Upcoming" and "Past"
 * (each event links to its own public page), with a QR for sharing. Viewable without login (#193);
 * no owner or roster PII is exposed.
 */
export function ClubPage() {
  const { code = '' } = useParams()
  const query = useGetApiV1ClubsCodeCode(code)
  const club = query.data

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
