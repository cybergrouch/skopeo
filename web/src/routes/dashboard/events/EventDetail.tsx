import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
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
  getGetApiV1EventsIdQueryKey,
  getGetApiV1EventsQueryKey,
  useDeleteApiV1EventsId,
  useDeleteApiV1EventsIdParticipantsUserId,
  useGetApiV1EventsId,
  usePostApiV1EventsIdParticipants,
  usePostApiV1EventsIdParticipantsUserIdDecision,
} from '@/api/generated/events/events'
import {
  getGetApiV1MatchesQueryKey,
  usePostApiV1Matches,
} from '@/api/generated/matches/matches'
import { UserSearchSelect } from '@/components/UserSearchSelect'
import { playerLabel } from '@/lib/playerLabel'
import type { EventParticipantResponse } from '@/api/generated/model'
import { ShareCard } from '@/components/ShareCard'
import { AwaitingResultsSection, RecordedResultsSection } from '../matches/AwaitingResultsSection'

/** "Female · 34 · NTRP 4.0" — a participant's sex, age, and NTRP band, omitting whatever is missing. */
function participantMeta(p: EventParticipantResponse): string {
  const parts: string[] = []
  if (p.sex) parts.push(p.sex)
  if (p.age != null) parts.push(String(p.age))
  if (p.rating) parts.push(`NTRP ${p.rating.level ?? p.rating.value}`)
  return parts.join(' · ')
}

const MATCH_TYPES = [
  'OPEN_PLAY',
  'LEAGUE_PLAY',
  'TOURNAMENT_INITIAL_ROUND',
  'LEAGUE_PLAYOFFS',
  'TOURNAMENT_PLAYOFFS',
] as const
const MATCH_TYPE_LABELS: Record<(typeof MATCH_TYPES)[number], string> = {
  OPEN_PLAY: 'Open play',
  LEAGUE_PLAY: 'League play',
  TOURNAMENT_INITIAL_ROUND: 'Tournament — initial round',
  LEAGUE_PLAYOFFS: 'League playoffs',
  TOURNAMENT_PLAYOFFS: 'Tournament playoffs',
}

const MATCH_FORMATS = ['SINGLES', 'DOUBLES', 'MIXED_DOUBLES'] as const
const MATCH_FORMAT_LABELS: Record<(typeof MATCH_FORMATS)[number], string> = {
  SINGLES: 'Singles',
  DOUBLES: 'Doubles',
  MIXED_DOUBLES: 'Mixed doubles',
}

/**
 * One event's working page (#138): the same matches UI as the global tab, but the fixture's player
 * pickers are scoped to this event's participants (and the API enforces it). Hosts manage the roster
 * here and record results below.
 */
/** Prefer the server's message (e.g. the 409 delete-guard advice), falling back to a generic one. */
function eventErrorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message && message.trim() !== '' ? message : fallback
}

export function EventDetail({
  eventId,
  onBack,
}: {
  eventId: string
  onBack: () => void
}) {
  const queryClient = useQueryClient()
  const eventQuery = useGetApiV1EventsId(eventId)
  const event = eventQuery.data
  const allParticipants = event?.participants ?? []
  // Only APPROVED members are the roster (eligible for fixtures); PENDING/HOLD are requests (#201).
  const participants = allParticipants.filter((p) => p.status === 'APPROVED')
  const requests = allParticipants.filter((p) => p.status === 'PENDING' || p.status === 'HOLD')

  // Two slots per side; the "b" slots are only used (and shown) for doubles/mixed doubles.
  const [team1a, setTeam1a] = useState('')
  const [team1b, setTeam1b] = useState('')
  const [team2a, setTeam2a] = useState('')
  const [team2b, setTeam2b] = useState('')
  const [format, setFormat] = useState<(typeof MATCH_FORMATS)[number]>('SINGLES')
  const [matchType, setMatchType] = useState<(typeof MATCH_TYPES)[number]>('OPEN_PLAY')
  const [date, setDate] = useState('')
  const [fixtureError, setFixtureError] = useState<string | null>(null)
  const [rosterError, setRosterError] = useState<string | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  function refreshEvent() {
    void queryClient.invalidateQueries({ queryKey: getGetApiV1EventsIdQueryKey(eventId) })
  }

  const addParticipant = usePostApiV1EventsIdParticipants({
    mutation: { onSuccess: refreshEvent },
  })
  const removeParticipant = useDeleteApiV1EventsIdParticipantsUserId({
    mutation: { onSuccess: refreshEvent },
  })
  const decideParticipant = usePostApiV1EventsIdParticipantsUserIdDecision({
    mutation: { onSuccess: refreshEvent },
  })
  const createFixture = usePostApiV1Matches({
    mutation: {
      onSuccess: () => {
        setTeam1a('')
        setTeam1b('')
        setTeam2a('')
        setTeam2b('')
        setDate('')
        void queryClient.invalidateQueries({ queryKey: getGetApiV1MatchesQueryKey() })
      },
    },
  })

  // Delete the event (#243). The server refuses (409) while it has recorded/rated matches — surface
  // its guidance verbatim. On success, return to the list, which no longer includes this event.
  const deleteEvent = useDeleteApiV1EventsId({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getGetApiV1EventsQueryKey() })
        onBack()
      },
    },
  })

  async function confirmDelete() {
    setDeleteError(null)
    try {
      await deleteEvent.mutateAsync({ id: eventId })
    } catch (e) {
      setDeleteError(eventErrorMessage(e, 'Could not delete this event.'))
      setConfirmingDelete(false)
    }
  }

  const isDoubles = format !== 'SINGLES'
  // Only the slots this format uses; "b" slots participate for doubles/mixed doubles.
  const chosen = isDoubles ? [team1a, team1b, team2a, team2b] : [team1a, team2a]

  function scheduleFixture(e: FormEvent) {
    e.preventDefault()
    setFixtureError(null)
    createFixture.mutate(
      {
        data: {
          matchFormat: format,
          matchType,
          matchDate: date,
          team1: isDoubles ? [team1a, team1b] : [team1a],
          team2: isDoubles ? [team2a, team2b] : [team2a],
          eventId,
        },
      },
      {
        onError: () =>
          setFixtureError('Could not schedule the fixture. Every player must be a participant and already rated.'),
      },
    )
  }

  const filled = chosen.filter((id) => id !== '')
  const canSchedule = filled.length === chosen.length && new Set(filled).size === chosen.length && date !== ''

  // One player dropdown, scoped to the roster and excluding whoever's already picked in the other slots.
  function playerSelect(id: string, label: string, value: string, onChange: (v: string) => void) {
    const takenElsewhere = chosen.filter((s) => s !== value && s !== '')
    return (
      <div className="space-y-1">
        <Label htmlFor={id} className="text-xs">
          {label}
        </Label>
        <select
          id={id}
          className="h-9 w-full rounded-md border bg-background px-2 text-sm"
          value={value}
          onChange={(e) => onChange(e.target.value)}
        >
          <option value="">Select…</option>
          {participants
            .filter((p) => p.userId === value || !takenElsewhere.includes(p.userId))
            .map((p) => (
              <option key={p.userId} value={p.userId}>
                {playerLabel(p.displayName, p.publicCode, p.userId)}
              </option>
            ))}
        </select>
      </div>
    )
  }

  return (
    <div className="grid gap-4">
      <Button type="button" variant="ghost" size="sm" className="w-fit" onClick={onBack}>
        ← All events
      </Button>

      {eventQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading event…</p>
      ) : !event ? (
        <p className="text-sm text-muted-foreground">This event could not be loaded.</p>
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle>{event.name}</CardTitle>
              <CardDescription>
                {event.startDate} – {event.endDate} · Event ID:{' '}
                <code className="font-mono font-medium text-foreground">{event.publicCode}</code>
                {' · '}
                <Link to={`/events/${event.publicCode}`} className="text-primary hover:underline">
                  Public page
                </Link>
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-xs font-medium uppercase text-muted-foreground">Participants</div>
              {participants.length > 0 ? (
                <ul className="space-y-1 text-sm">
                  {participants.map((p) => {
                    const meta = participantMeta(p)
                    return (
                      <li key={p.userId} className="flex items-center justify-between gap-2">
                        <span className="min-w-0">
                          <span className="block">
                            {playerLabel(p.displayName, p.publicCode, p.userId)}
                            {p.publicCode ? (
                              <span className="text-muted-foreground"> ({p.publicCode})</span>
                            ) : null}
                          </span>
                          {meta ? (
                            <span className="block text-xs text-muted-foreground">{meta}</span>
                          ) : null}
                        </span>
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          disabled={removeParticipant.isPending}
                          onClick={() => removeParticipant.mutate({ id: eventId, userId: p.userId })}
                        >
                          Remove
                        </Button>
                      </li>
                    )
                  })}
                </ul>
              ) : (
                <p className="text-sm text-muted-foreground">No participants yet.</p>
              )}
              <div className="space-y-1">
                <UserSearchSelect
                  label="Add a participant"
                  placeholder="Search players…"
                  excludeIds={allParticipants.map((p) => p.userId)}
                  onSelect={(user) => {
                    setRosterError(null)
                    addParticipant.mutate(
                      { id: eventId, data: { userId: user.id } },
                      { onError: () => setRosterError('Could not add that participant.') },
                    )
                  }}
                />
                {rosterError ? (
                  <p className="text-sm text-destructive" role="alert">
                    {rosterError}
                  </p>
                ) : null}
              </div>
            </CardContent>
          </Card>

          {requests.length > 0 ? (
            <Card>
              <CardHeader>
                <CardTitle>Join requests</CardTitle>
                <CardDescription>
                  Players who signed up from the shared link. Approve to add them to the roster, or hold
                  to set aside (you can approve a held request later).
                </CardDescription>
              </CardHeader>
              <CardContent>
                <ul className="space-y-1 text-sm">
                  {requests.map((p) => {
                    const meta = participantMeta(p)
                    return (
                      <li key={p.userId} className="flex items-center justify-between gap-2">
                        <span className="min-w-0">
                          <span className="block">
                            {playerLabel(p.displayName, p.publicCode, p.userId)}
                            {p.status === 'HOLD' ? (
                              <span className="text-muted-foreground"> · on hold</span>
                            ) : null}
                          </span>
                          {meta ? <span className="block text-xs text-muted-foreground">{meta}</span> : null}
                        </span>
                        <span className="flex shrink-0 items-center gap-2">
                          <Button
                            type="button"
                            size="sm"
                            disabled={decideParticipant.isPending}
                            onClick={() =>
                              decideParticipant.mutate({ id: eventId, userId: p.userId, data: { status: 'APPROVED' } })
                            }
                          >
                            Approve
                          </Button>
                          {p.status === 'HOLD' ? null : (
                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              disabled={decideParticipant.isPending}
                              onClick={() =>
                                decideParticipant.mutate({ id: eventId, userId: p.userId, data: { status: 'HOLD' } })
                              }
                            >
                              Hold
                            </Button>
                          )}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              </CardContent>
            </Card>
          ) : null}

          <Card>
            <CardHeader>
              <CardTitle>Schedule a fixture</CardTitle>
              <CardDescription>
                Every player must be a participant of this event. Pick a format — doubles and mixed doubles
                need two players a side. Recording results later doesn’t move ratings — that’s the admin
                calculation step.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={scheduleFixture} className="grid gap-3">
                <div className="space-y-1">
                  <Label htmlFor="event-format" className="text-xs">
                    Format
                  </Label>
                  <select
                    id="event-format"
                    className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                    value={format}
                    onChange={(e) => {
                      const next = e.target.value as (typeof MATCH_FORMATS)[number]
                      setFormat(next)
                      // Dropping back to singles retires the partner slots so they can't leak into the request.
                      if (next === 'SINGLES') {
                        setTeam1b('')
                        setTeam2b('')
                      }
                    }}
                  >
                    {MATCH_FORMATS.map((f) => (
                      <option key={f} value={f}>
                        {MATCH_FORMAT_LABELS[f]}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  {isDoubles ? (
                    <>
                      <div className="space-y-1">
                        {playerSelect('event-team1', 'Player 1', team1a, setTeam1a)}
                        {playerSelect('event-team1b', 'Partner 1', team1b, setTeam1b)}
                      </div>
                      <div className="space-y-1">
                        {playerSelect('event-team2', 'Player 2', team2a, setTeam2a)}
                        {playerSelect('event-team2b', 'Partner 2', team2b, setTeam2b)}
                      </div>
                    </>
                  ) : (
                    <>
                      {playerSelect('event-team1', 'Player 1', team1a, setTeam1a)}
                      {playerSelect('event-team2', 'Player 2', team2a, setTeam2a)}
                    </>
                  )}
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div className="space-y-1">
                    <Label htmlFor="event-matchType" className="text-xs">
                      Match type
                    </Label>
                    <select
                      id="event-matchType"
                      className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                      value={matchType}
                      onChange={(e) => setMatchType(e.target.value as (typeof MATCH_TYPES)[number])}
                    >
                      {MATCH_TYPES.map((t) => (
                        <option key={t} value={t}>
                          {MATCH_TYPE_LABELS[t]}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="event-date" className="text-xs">
                      Date
                    </Label>
                    <Input
                      id="event-date"
                      type="date"
                      value={date}
                      onChange={(e) => setDate(e.target.value)}
                    />
                  </div>
                </div>
                <Button type="submit" size="sm" disabled={!canSchedule || createFixture.isPending}>
                  Schedule fixture
                </Button>
                {fixtureError ? (
                  <p className="text-sm text-destructive" role="alert">
                    {fixtureError}
                  </p>
                ) : null}
              </form>
            </CardContent>
          </Card>

          <AwaitingResultsSection eventId={eventId} />
          <RecordedResultsSection eventId={eventId} />

          <ShareCard
            url={`${window.location.origin}/events/${event.publicCode}`}
            title="Share this event"
            description="Scan this code or copy the link to open this event's public page."
          />

          <Card>
            <CardHeader>
              <CardTitle>Delete event</CardTitle>
              <CardDescription>
                An event can be deleted only while it has no recorded matches. Delete recorded matches
                first; an event with rated matches can’t be deleted.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-2">
              {confirmingDelete ? (
                <div className="flex gap-2">
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="text-destructive hover:text-destructive"
                    disabled={deleteEvent.isPending}
                    onClick={confirmDelete}
                  >
                    {deleteEvent.isPending ? 'Deleting…' : 'Confirm delete'}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={deleteEvent.isPending}
                    onClick={() => setConfirmingDelete(false)}
                  >
                    Cancel
                  </Button>
                </div>
              ) : (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:text-destructive"
                  onClick={() => {
                    setDeleteError(null)
                    setConfirmingDelete(true)
                  }}
                >
                  Delete event
                </Button>
              )}
              {deleteError ? (
                <p className="text-sm text-destructive" role="alert">
                  {deleteError}
                </p>
              ) : null}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
