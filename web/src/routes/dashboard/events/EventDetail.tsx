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
  getGetApiV1EventsIdQueryKey,
  useDeleteApiV1EventsIdParticipantsUserId,
  useGetApiV1EventsId,
  usePostApiV1EventsIdParticipants,
} from '@/api/generated/events/events'
import {
  getGetApiV1MatchesQueryKey,
  usePostApiV1Matches,
} from '@/api/generated/matches/matches'
import { UserSearchSelect } from '@/components/UserSearchSelect'
import { AwaitingResultsSection } from '../matches/AwaitingResultsSection'

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

/**
 * One event's working page (#138): the same matches UI as the global tab, but the fixture's player
 * pickers are scoped to this event's participants (and the API enforces it). Hosts manage the roster
 * here and record results below.
 */
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
  const participants = event?.participants ?? []

  const [team1, setTeam1] = useState('')
  const [team2, setTeam2] = useState('')
  const [matchType, setMatchType] = useState<(typeof MATCH_TYPES)[number]>('OPEN_PLAY')
  const [date, setDate] = useState('')
  const [fixtureError, setFixtureError] = useState<string | null>(null)
  const [rosterError, setRosterError] = useState<string | null>(null)

  function refreshEvent() {
    void queryClient.invalidateQueries({ queryKey: getGetApiV1EventsIdQueryKey(eventId) })
  }

  const addParticipant = usePostApiV1EventsIdParticipants({
    mutation: { onSuccess: refreshEvent },
  })
  const removeParticipant = useDeleteApiV1EventsIdParticipantsUserId({
    mutation: { onSuccess: refreshEvent },
  })
  const createFixture = usePostApiV1Matches({
    mutation: {
      onSuccess: () => {
        setTeam1('')
        setTeam2('')
        setDate('')
        void queryClient.invalidateQueries({ queryKey: getGetApiV1MatchesQueryKey() })
      },
    },
  })

  function scheduleFixture(e: FormEvent) {
    e.preventDefault()
    setFixtureError(null)
    createFixture.mutate(
      {
        data: {
          matchFormat: 'SINGLES',
          matchType,
          matchDate: date,
          team1: [team1],
          team2: [team2],
          eventId,
        },
      },
      {
        onError: () =>
          setFixtureError('Could not schedule the fixture. Both players must be participants and already rated.'),
      },
    )
  }

  const canSchedule = team1 !== '' && team2 !== '' && team1 !== team2 && date !== ''

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
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-xs font-medium uppercase text-muted-foreground">Participants</div>
              {participants.length > 0 ? (
                <ul className="space-y-1 text-sm">
                  {participants.map((p) => (
                    <li key={p.userId} className="flex items-center justify-between gap-2">
                      <span>
                        {p.displayName ?? p.publicCode ?? p.userId.slice(0, 8)}
                        {p.publicCode ? <span className="text-muted-foreground"> ({p.publicCode})</span> : null}
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
                  ))}
                </ul>
              ) : (
                <p className="text-sm text-muted-foreground">No participants yet.</p>
              )}
              <div className="space-y-1">
                <UserSearchSelect
                  label="Add a participant"
                  placeholder="Search players…"
                  excludeIds={participants.map((p) => p.userId)}
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

          <Card>
            <CardHeader>
              <CardTitle>Schedule a fixture</CardTitle>
              <CardDescription>
                Both players must be participants of this event. Recording results later doesn’t move
                ratings — that’s the admin calculation step.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={scheduleFixture} className="grid gap-3">
                <div className="grid grid-cols-2 gap-2">
                  <div className="space-y-1">
                    <Label htmlFor="event-team1" className="text-xs">
                      Player 1
                    </Label>
                    <select
                      id="event-team1"
                      className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                      value={team1}
                      onChange={(e) => setTeam1(e.target.value)}
                    >
                      <option value="">Select…</option>
                      {participants.map((p) => (
                        <option key={p.userId} value={p.userId}>
                          {p.displayName ?? p.publicCode ?? p.userId.slice(0, 8)}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="event-team2" className="text-xs">
                      Player 2
                    </Label>
                    <select
                      id="event-team2"
                      className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                      value={team2}
                      onChange={(e) => setTeam2(e.target.value)}
                    >
                      <option value="">Select…</option>
                      {participants.map((p) => (
                        <option key={p.userId} value={p.userId}>
                          {p.displayName ?? p.publicCode ?? p.userId.slice(0, 8)}
                        </option>
                      ))}
                    </select>
                  </div>
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
        </>
      )}
    </div>
  )
}
