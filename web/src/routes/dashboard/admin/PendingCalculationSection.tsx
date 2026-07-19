import { useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { GripVertical } from 'lucide-react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  getGetApiV1MatchesQueryKey,
  useGetApiV1Matches,
} from '@/api/generated/matches/matches'
import {
  getGetApiV1EventsQueryKey,
  useGetApiV1Events,
  usePutApiV1EventsIdCalculationPriority,
} from '@/api/generated/events/events'
import { usePostApiV1RatingsCalculations } from '@/api/generated/ratings/ratings'
import { useGetApiV1Users } from '@/api/generated/users/users'
import { GetApiV1MatchesFilter } from '@/api/generated/model'
import type {
  MatchCalculationResponse,
  CalculationResponse,
  MatchResponse,
} from '@/api/generated/model'
import { plural } from '@/lib/plural'
import { CalculationBreakdownDetail } from '@/components/CalculationBreakdownDetail'

const PENDING_FILTER = { filter: GetApiV1MatchesFilter['pending-calculation'] }

/** The winning side's resolved name for a completed match, or null if no winner is recorded. */
function winnerName(
  match: MatchResponse,
  nameOf: (id: string) => string,
): string | null {
  if (match.winnerTeamId === match.team1.teamId) return match.team1.userIds.map(nameOf).join(', ')
  if (match.winnerTeamId === match.team2.teamId) return match.team2.userIds.map(nameOf).join(', ')
  return null
}

/** A yyyy-MM-dd date as an epoch-day number — the scale event calc priorities live on (#335). */
function epochDay(iso: string): number {
  return Math.round(new Date(`${iso}T00:00:00Z`).getTime() / 86_400_000)
}

/** An event's processing key: its calc_priority override if set, else its end date as an epoch day. */
type EventInfo = { name: string; endDate: string; calcPriority?: number | null }

/** The backend's `{ error, message }` body carries the ordering-guard reason (#479). */
function errorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message ?? fallback
}

// The pending list arrives already in processing order; walk it into display entries: consecutive
// same-event matches form one event group; each eventless match is its own "Open" entry (#335).
type Entry =
  | { kind: 'event'; id: string; eventId: string; matches: MatchResponse[] }
  | { kind: 'open'; id: string; match: MatchResponse }

function buildEntries(pending: MatchResponse[]): Entry[] {
  const entries: Entry[] = []
  for (const match of pending) {
    if (!match.eventId) {
      entries.push({ kind: 'open', id: `open:${match.id}`, match })
      continue
    }
    const last = entries[entries.length - 1]
    if (last && last.kind === 'event' && last.eventId === match.eventId) {
      last.matches.push(match)
    } else {
      entries.push({ kind: 'event', id: `event:${match.eventId}`, eventId: match.eventId, matches: [match] })
    }
  }
  return entries
}

/** One expandable match row (shared by event groups and Open entries), numbered by processing order. */
function MatchRow({
  match,
  position,
  nameOf,
  isOpen,
  onToggle,
  matchPreview,
}: {
  match: MatchResponse
  position: number
  nameOf: (id: string) => string
  isOpen: boolean
  onToggle: () => void
  matchPreview?: MatchCalculationResponse
}) {
  const player1 = match.team1.userIds.map(nameOf).join(', ')
  const player2 = match.team2.userIds.map(nameOf).join(', ')
  const scores = match.sets.map((s) => `${s.team1Games}-${s.team2Games}`).join(' ')
  const winner = winnerName(match, nameOf)
  return (
    <li className="rounded-lg border text-sm">
      <button
        type="button"
        className="flex w-full items-start justify-between gap-2 p-3 text-left hover:bg-muted/50"
        aria-expanded={isOpen}
        onClick={onToggle}
      >
        <span className="flex min-w-0 items-start gap-2">
          <span className="mt-0.5 shrink-0 font-mono text-xs tabular-nums text-muted-foreground">
            {position}.
          </span>
          <span className="min-w-0">
            <span className="block font-medium">
              {player1} vs {player2}
            </span>
            <span className="block text-muted-foreground">
              {match.matchDate}
              {scores ? ` · ${scores}` : ''}
              {winner ? ` · Winner: ${winner}` : ''}
            </span>
          </span>
        </span>
        <span aria-hidden="true" className="shrink-0 text-muted-foreground">
          {isOpen ? '▾' : '▸'}
        </span>
      </button>
      {isOpen ? (
        <div className="border-t px-3 py-2">
          {matchPreview ? (
            <ul className="space-y-2">
              {matchPreview.changes.map((change) => (
                <li key={change.userId}>
                  <div>
                    {nameOf(change.userId)}: {change.previousRating} →{' '}
                    {change.newRating} ({change.change})
                  </div>
                  <CalculationBreakdownDetail breakdown={change.breakdown} />
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-xs text-muted-foreground">
              Run Preview to see the projected ratings and how they're calculated.
            </p>
          )}
        </div>
      ) : null}
    </li>
  )
}

/** A draggable event group (#335): a grip-handled header + its matches; drag reorders event priority. */
function EventGroup({
  entry,
  eventName,
  renderMatch,
  selected,
  onToggleSelect,
}: {
  entry: Extract<Entry, { kind: 'event' }>
  eventName: string
  renderMatch: (match: MatchResponse) => ReactNode
  selected: boolean
  onToggleSelect: () => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({ id: entry.id })
  const style = { transform: CSS.Transform.toString(transform), transition }
  return (
    <li ref={setNodeRef} style={style} className="rounded-lg border">
      <div className="flex items-center gap-2 border-b bg-muted/40 px-2 py-1.5">
        <button
          type="button"
          aria-label={`Reorder event ${eventName}`}
          className="cursor-grab touch-none rounded p-1 text-muted-foreground hover:bg-muted"
          {...attributes}
          {...listeners}
        >
          <GripVertical className="h-4 w-4" />
        </button>
        <label className="flex items-center gap-2 text-xs font-medium uppercase text-muted-foreground">
          <input
            type="checkbox"
            className="h-3.5 w-3.5"
            checked={selected}
            onChange={onToggleSelect}
            aria-label={`Include event ${eventName}`}
          />
          {eventName}
        </label>
      </div>
      <ul className="space-y-2 p-2">{entry.matches.map(renderMatch)}</ul>
    </li>
  )
}

/** A non-draggable, date-pinned eventless match entry (#335), rendered inline among the event groups. */
function OpenEntry({
  entry,
  renderMatch,
}: {
  entry: Extract<Entry, { kind: 'open' }>
  renderMatch: (match: MatchResponse) => ReactNode
}) {
  const { setNodeRef, transform, transition } = useSortable({ id: entry.id })
  const style = { transform: CSS.Transform.toString(transform), transition }
  return (
    <li ref={setNodeRef} style={style} className="rounded-lg border">
      <div className="border-b bg-muted/40 px-3 py-1.5 text-xs font-medium uppercase text-muted-foreground">
        Open (no event)
      </div>
      <ul className="space-y-2 p-2">{renderMatch(entry.match)}</ul>
    </li>
  )
}

export function PendingCalculationSection() {
  const queryClient = useQueryClient()
  const [preview, setPreview] = useState<CalculationResponse | null>(null)
  const [committed, setCommitted] = useState<number | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  // Selected events to scope the run to (#479); empty = all pending (unchanged default).
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const matchesQuery = useGetApiV1Matches(PENDING_FILTER)
  const pending = matchesQuery.data ?? []
  const eventsById = new Map<string, EventInfo>(
    (useGetApiV1Events().data ?? []).map((e) => [
      e.id,
      { name: e.name, endDate: e.endDate, calcPriority: e.calcPriority },
    ]),
  )

  const ids = [...new Set(pending.flatMap((m) => [...m.team1.userIds, ...m.team2.userIds]))]
  const usersQuery = useGetApiV1Users(
    { ids: ids.join(',') },
    { query: { enabled: ids.length > 0 } },
  )
  const nameById = new Map((usersQuery.data ?? []).map((u) => [u.id, u.displayName ?? u.id]))
  const nameOf = (userId: string) => nameById.get(userId) ?? userId.slice(0, 8)

  const previewByMatch = new Map((preview?.matches ?? []).map((m) => [m.matchId, m]))

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  function toggle(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const calculate = usePostApiV1RatingsCalculations({
    mutation: {
      onSuccess: (data) => {
        if (data.dryRun) {
          setPreview(data)
          setCommitted(null)
        } else {
          setPreview(null)
          setCommitted(data.matchesProcessed)
          // The committed events are gone from the pending list; start fresh.
          setSelected(new Set())
          queryClient.invalidateQueries({ queryKey: getGetApiV1MatchesQueryKey(PENDING_FILTER) })
        }
      },
    },
  })

  const setPriority = usePutApiV1EventsIdCalculationPriority({
    mutation: {
      onSuccess: () => {
        queryClient.invalidateQueries({ queryKey: getGetApiV1MatchesQueryKey(PENDING_FILTER) })
        queryClient.invalidateQueries({ queryKey: getGetApiV1EventsQueryKey() })
      },
    },
  })

  const entries = buildEntries(pending)

  // Toggling an event's inclusion invalidates any preview (it no longer reflects the selection).
  function toggleSelect(eventId: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(eventId)) next.delete(eventId)
      else next.add(eventId)
      return next
    })
    setPreview(null)
  }

  // The event ids to send: undefined (all pending) when nothing is picked, else the selection (#479).
  const scopeEventIds = selected.size > 0 ? [...selected] : undefined

  // Client-side mirror of the backend ordering guard (#479): a selection must form a contiguous
  // prefix of the pending timeline. Find the last selected event; anything unselected before it
  // (an earlier event, or any eventless "Open" match — which can't be selected) breaks the prefix.
  function prefixError(): string | null {
    if (!scopeEventIds) return null
    const lastSelected = entries.reduce(
      (acc, e, i) => (e.kind === 'event' && selected.has(e.eventId) ? i : acc),
      -1,
    )
    if (lastSelected < 0) return null
    for (let i = 0; i < lastSelected; i++) {
      const e = entries[i]
      if (e.kind === 'open') {
        return `An earlier "Open (no event)" match (${e.match.matchDate}) must be included: selections must be a contiguous prefix, starting from the oldest pending match.`
      }
      if (!selected.has(e.eventId)) {
        const name = eventsById.get(e.eventId)?.name ?? 'an earlier event'
        return `${name} is older than a selected event and must also be included: selections must be a contiguous prefix, starting from the oldest pending match.`
      }
    }
    return null
  }

  const scopeError = prefixError()
  // Surface the backend's guard message too (belt-and-braces if the client check ever diverges).
  const serverError = calculate.isError ? errorMessage(calculate.error, 'Calculation failed.') : null
  const guardError = scopeError ?? serverError

  // An entry's processing key on the epoch-day scale: an event's calc_priority override or its end
  // date; an eventless entry keys off its match date (so events and Open entries interleave by date).
  function entryKey(entry: Entry): number {
    if (entry.kind === 'open') return epochDay(entry.match.matchDate)
    const info = eventsById.get(entry.eventId)
    return info?.calcPriority ?? (info ? epochDay(info.endDate) : 0)
  }

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event
    if (!over || active.id === over.id) return
    const from = entries.findIndex((e) => e.id === active.id)
    const to = entries.findIndex((e) => e.id === over.id)
    if (from < 0 || to < 0) return
    const reordered = arrayMove(entries, from, to)
    const pos = reordered.findIndex((e) => e.id === active.id)
    const dragged = reordered[pos]
    if (dragged.kind !== 'event') return
    // Slot the dragged event's key between its new neighbours (or just past the single edge).
    // A dropped event always has at least one neighbour (the drop target differs from itself).
    const before = reordered[pos - 1]
    const after = reordered[pos + 1]
    let priority: number
    if (before && after) {
      priority = (entryKey(before) + entryKey(after)) / 2
    } else if (before) {
      priority = entryKey(before) + 1
    } else {
      priority = entryKey(after as Entry) - 1
    }
    setPriority.mutate({ id: dragged.eventId, data: { priority } })
  }

  function renderMatch(match: MatchResponse) {
    return (
      <MatchRow
        key={match.id}
        match={match}
        position={pending.indexOf(match) + 1}
        nameOf={nameOf}
        isOpen={expanded.has(match.id)}
        onToggle={() => toggle(match.id)}
        matchPreview={previewByMatch.get(match.id)}
      />
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Pending calculation</CardTitle>
        <CardDescription>
          Completed matches awaiting a rating calculation, grouped by event and numbered in the exact
          order they’ll be processed — events by end date (drag to reorder), matches within an event
          by date. Tick events to scope the run to them (leave all unticked to process everything);
          a selection must be a contiguous run from the oldest pending match. Preview before committing.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {matchesQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : (
          <p className="text-sm">
            <span className="font-medium">{pending.length}</span> match
            {plural(pending.length, 'es')} pending calculation.
          </p>
        )}

        {pending.length > 0 ? (
          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
            <SortableContext items={entries.map((e) => e.id)} strategy={verticalListSortingStrategy}>
              <ul className="space-y-2">
                {entries.map((entry) =>
                  entry.kind === 'event' ? (
                    <EventGroup
                      key={entry.id}
                      entry={entry}
                      eventName={eventsById.get(entry.eventId)?.name ?? 'Event'}
                      renderMatch={renderMatch}
                      selected={selected.has(entry.eventId)}
                      onToggleSelect={() => toggleSelect(entry.eventId)}
                    />
                  ) : (
                    <OpenEntry key={entry.id} entry={entry} renderMatch={renderMatch} />
                  ),
                )}
              </ul>
            </SortableContext>
          </DndContext>
        ) : null}

        {scopeEventIds ? (
          <p className="text-sm text-muted-foreground">
            Scoped to <span className="font-medium">{scopeEventIds.length}</span> selected event
            {plural(scopeEventIds.length, 's')}.{' '}
            <button
              type="button"
              className="underline underline-offset-2 hover:text-foreground"
              onClick={() => {
                setSelected(new Set())
                setPreview(null)
              }}
            >
              Clear selection
            </button>
          </p>
        ) : null}

        {guardError ? (
          <p className="text-sm text-destructive" role="alert" data-testid="calculation-guard-error">
            {guardError}
          </p>
        ) : null}

        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={pending.length === 0 || calculate.isPending || scopeError !== null}
            onClick={() => calculate.mutate({ data: { dryRun: true, eventIds: scopeEventIds } })}
          >
            Preview
          </Button>
          {preview ? (
            <>
              <Button
                size="sm"
                disabled={calculate.isPending || scopeError !== null}
                onClick={() => calculate.mutate({ data: { dryRun: false, eventIds: scopeEventIds } })}
              >
                Commit
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={calculate.isPending}
                onClick={() => setPreview(null)}
              >
                Discard
              </Button>
            </>
          ) : null}
        </div>

        {committed !== null ? (
          <p className="text-sm text-foreground" role="status">
            Committed ratings for {committed} match{plural(committed, 'es')}.
          </p>
        ) : null}

        {preview ? (
          <p
            className="text-sm text-muted-foreground"
            data-testid="calculation-preview"
            role="status"
          >
            Preview ready — {preview.matchesProcessed} match
            {plural(preview.matchesProcessed, 'es')}, no changes saved yet. Expand a match
            to see its projection and how it's calculated.
          </p>
        ) : null}
      </CardContent>
    </Card>
  )
}
