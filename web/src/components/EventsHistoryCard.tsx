import { Link } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useGetApiV1EventsMine } from '@/api/generated/events/events'
import type { MyEventResponse } from '@/api/generated/model'

/** Today as yyyy-MM-dd (local), comparable lexicographically with an event's ISO end date. */
function todayIso(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/** A short note for a not-yet-approved standing, or null when the player is a confirmed participant. */
function standingNote(status: string): string | null {
  if (status === 'PENDING') return 'Pending approval'
  if (status === 'HOLD') return 'On hold'
  return null
}

function EventRow({ event }: { event: MyEventResponse }) {
  const note = standingNote(event.status)
  return (
    <li>
      <Link
        to={`/events/${event.publicCode}`}
        className="block rounded-lg border p-2 text-sm hover:bg-muted/50"
      >
        <span className="block font-medium">{event.name}</span>
        <span className="block text-xs text-muted-foreground">
          {event.startDate} – {event.endDate}
          {note ? ` · ${note}` : ''}
        </span>
      </Link>
    </li>
  )
}

/** A labelled section of events with its own empty state (#483). */
function EventSection({
  title,
  events,
  emptyLabel,
}: {
  title: string
  events: MyEventResponse[]
  emptyLabel: string
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
        <p className="mt-1 text-muted-foreground">{emptyLabel}</p>
      )}
    </div>
  )
}

/** Recorded results present (#483) — the "has results" signal for the Unfinalized bucket. */
function hasResults(event: MyEventResponse): boolean {
  return (event.completedMatchCount ?? 0) > 0
}

/**
 * The player's "Events history" on their Profile tab (#202): the events they're signed up for, split
 * into three buckets (#483). Finalized status wins over everything — a finalized event is always
 * Finalized, even with a future end date or no results. Otherwise Unfinalized = the event ended OR has
 * recorded results (activity started, not concluded); Upcoming = future and untouched. Mirrors the
 * Match history card. Pending/held requests are labelled so confirmed participation stands out.
 */
export function EventsHistoryCard() {
  const query = useGetApiV1EventsMine()
  const events = query.data ?? []
  const today = todayIso()

  // Finalized first (precedence over date + results); the rest split over the non-finalized set.
  const finalized = events.filter((e) => e.isFinalized)
  const active = events.filter((e) => !e.isFinalized)
  const unfinalized = active.filter((e) => e.endDate < today || hasResults(e))
  const upcoming = active.filter((e) => e.endDate >= today && !hasResults(e))

  // Sort within buckets: Finalized + Unfinalized newest end date first (this DTO carries no
  // finalizedAt, so end date is the fallback), Upcoming earliest start date first.
  const byEndDesc = (a: MyEventResponse, b: MyEventResponse) => b.endDate.localeCompare(a.endDate)
  const byStartAsc = (a: MyEventResponse, b: MyEventResponse) => a.startDate.localeCompare(b.startDate)
  const upcomingSorted = [...upcoming].sort(byStartAsc)
  const unfinalizedSorted = [...unfinalized].sort(byEndDesc)
  const finalizedSorted = [...finalized].sort(byEndDesc)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Events history</CardTitle>
        <CardDescription>Events you’ve signed up for.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        {query.isLoading ? (
          <p className="text-muted-foreground">Loading…</p>
        ) : events.length === 0 ? (
          <p className="text-muted-foreground">You haven’t joined any events yet.</p>
        ) : (
          <>
            <EventSection title="Upcoming" events={upcomingSorted} emptyLabel="No upcoming events." />
            <EventSection
              title="Unfinalized"
              events={unfinalizedSorted}
              emptyLabel="No unfinalized events."
            />
            <EventSection
              title="Finalized"
              events={finalizedSorted}
              emptyLabel="No finalized events."
            />
          </>
        )}
      </CardContent>
    </Card>
  )
}
