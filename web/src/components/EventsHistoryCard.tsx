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

/**
 * The player's "Events history" on their Profile tab (#202): the events they're signed up for, split
 * into upcoming and past (by end date). Mirrors the Match history card. Pending/held requests are
 * labelled so they're distinguishable from confirmed participation.
 */
export function EventsHistoryCard() {
  const query = useGetApiV1EventsMine()
  const events = query.data ?? []
  const today = todayIso()
  const upcoming = events.filter((e) => e.endDate >= today)
  const past = events.filter((e) => e.endDate < today)

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
            <div>
              <div className="text-xs font-medium uppercase text-muted-foreground">Upcoming</div>
              {upcoming.length > 0 ? (
                <ul className="mt-1 space-y-1">
                  {upcoming.map((e) => (
                    <EventRow key={e.publicCode} event={e} />
                  ))}
                </ul>
              ) : (
                <p className="mt-1 text-muted-foreground">No upcoming events.</p>
              )}
            </div>
            <div>
              <div className="text-xs font-medium uppercase text-muted-foreground">Past</div>
              {past.length > 0 ? (
                <ul className="mt-1 space-y-1">
                  {past.map((e) => (
                    <EventRow key={e.publicCode} event={e} />
                  ))}
                </ul>
              ) : (
                <p className="mt-1 text-muted-foreground">No past events.</p>
              )}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}
