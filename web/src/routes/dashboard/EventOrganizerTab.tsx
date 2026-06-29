import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  getGetApiV1EventsQueryKey,
  useGetApiV1Events,
  usePostApiV1Events,
} from '@/api/generated/events/events'
import type { UserSummaryResponse } from '@/api/generated/model'
import { UserSearchSelect } from '@/components/UserSearchSelect'
import { plural } from '@/lib/plural'
import { playerLabel } from '@/lib/playerLabel'
import { EventDetail } from './events/EventDetail'

/** The new-event roster being assembled before the event is created. */
function NewEventForm() {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [roster, setRoster] = useState<UserSummaryResponse[]>([])
  const [error, setError] = useState<string | null>(null)

  const create = usePostApiV1Events({
    mutation: {
      onSuccess: () => {
        setName('')
        setStartDate('')
        setEndDate('')
        setRoster([])
        void queryClient.invalidateQueries({ queryKey: getGetApiV1EventsQueryKey() })
      },
    },
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    create.mutate(
      {
        data: {
          name,
          startDate,
          endDate,
          participantIds: roster.map((u) => u.id),
        },
      },
      { onError: () => setError('Could not create the event. Check the name and dates.') },
    )
  }

  const canCreate = name.trim() !== '' && startDate !== '' && endDate !== ''

  return (
    <Card>
      <CardHeader>
        <CardTitle>New event</CardTitle>
        <CardDescription>Name it, set a date range, and add participants.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid gap-3">
          <div className="space-y-1">
            <Label htmlFor="event-name" className="text-xs">
              Name
            </Label>
            <Input id="event-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Summer Open" />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <Label htmlFor="event-start" className="text-xs">
                Start date
              </Label>
              <Input id="event-start" type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="event-end" className="text-xs">
                End date
              </Label>
              <Input id="event-end" type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />
            </div>
          </div>
          <div className="space-y-1">
            <Label className="text-xs">Participants</Label>
            {roster.length > 0 ? (
              <ul className="flex flex-wrap gap-1">
                {roster.map((u) => (
                  <li key={u.id}>
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      onClick={() => setRoster((r) => r.filter((x) => x.id !== u.id))}
                    >
                      {playerLabel(u.displayName, u.publicCode, u.id)} ✕
                    </Button>
                  </li>
                ))}
              </ul>
            ) : null}
            <UserSearchSelect
              label="Add participant"
              placeholder="Search players to add…"
              excludeIds={roster.map((u) => u.id)}
              onSelect={(user) => setRoster((r) => (r.some((x) => x.id === user.id) ? r : [...r, user]))}
            />
          </div>
          <Button type="submit" size="sm" disabled={!canCreate || create.isPending}>
            Create event
          </Button>
          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
        </form>
      </CardContent>
    </Card>
  )
}

/**
 * The Event Organizer tab (#138, renamed from Matches): hosts run events/meets that contain matches.
 * The events table is the entry point; selecting a row opens that event's working page (participant-
 * scoped fixtures + results).
 */
export function EventOrganizerTab() {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const eventsQuery = useGetApiV1Events()
  const events = eventsQuery.data ?? []

  if (selectedId) {
    return <EventDetail eventId={selectedId} onBack={() => setSelectedId(null)} />
  }

  return (
    <div className="grid gap-4">
      <NewEventForm />

      <Card>
        <CardHeader>
          <CardTitle>Events</CardTitle>
          <CardDescription>Select an event to manage its participants, fixtures, and results.</CardDescription>
        </CardHeader>
        <CardContent>
          {eventsQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : events.length > 0 ? (
            <ul className="space-y-2">
              {events.map((event) => (
                <li key={event.id}>
                  <button
                    type="button"
                    className="flex w-full items-center justify-between gap-2 rounded-lg border p-3 text-left text-sm hover:bg-muted/50"
                    onClick={() => setSelectedId(event.id)}
                  >
                    <span className="font-medium">{event.name}</span>
                    <span className="text-muted-foreground">
                      {event.startDate} – {event.endDate} ·{' '}
                      {event.participants.length} player{plural(event.participants.length)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">No events yet. Create one above.</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
