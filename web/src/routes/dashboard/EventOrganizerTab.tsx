import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  getGetApiV1EventsQueryKey,
  useGetApiV1Events,
  usePostApiV1Events,
} from "@/api/generated/events/events";
import { useGetApiV1Clubs } from "@/api/generated/clubs/clubs";
import type { EventResponse, UserSummaryResponse } from "@/api/generated/model";
import { UserSearchSelect } from "@/components/UserSearchSelect";
import { plural } from "@/lib/plural";
import { playerLabel } from "@/lib/playerLabel";
import { EventDetail } from "./events/EventDetail";

/** The new-event roster being assembled before the event is created. */
function NewEventForm() {
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [clubId, setClubId] = useState("");
  const [roster, setRoster] = useState<UserSummaryResponse[]>([]);
  const [error, setError] = useState<string | null>(null);

  // Clubs to optionally file the event under (#313). Readable by staff; empty when none exist.
  const clubs = useGetApiV1Clubs().data ?? [];

  const create = usePostApiV1Events({
    mutation: {
      onSuccess: () => {
        setName("");
        setStartDate("");
        setEndDate("");
        setClubId("");
        setRoster([]);
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
      },
    },
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    create.mutate(
      {
        data: {
          name,
          startDate,
          endDate,
          participantIds: roster.map((u) => u.id),
          ...(clubId ? { clubId } : {}),
        },
      },
      {
        onError: () =>
          setError("Could not create the event. Check the name and dates."),
      },
    );
  }

  const canCreate = name.trim() !== "" && startDate !== "" && endDate !== "";

  return (
    <Card>
      <CardHeader>
        <CardTitle>New event</CardTitle>
        <CardDescription>
          Name it, set a date range, and add participants.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid gap-3">
          <div className="space-y-1">
            <Label htmlFor="event-name" className="text-xs">
              Name
            </Label>
            <Input
              id="event-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Summer Open"
            />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <Label htmlFor="event-start" className="text-xs">
                Start date
              </Label>
              <Input
                id="event-start"
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="event-end" className="text-xs">
                End date
              </Label>
              <Input
                id="event-end"
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
              />
            </div>
          </div>
          {clubs.length > 0 ? (
            <div className="space-y-1">
              <Label htmlFor="event-club" className="text-xs">
                Club
              </Label>
              <select
                id="event-club"
                value={clubId}
                onChange={(e) => setClubId(e.target.value)}
                className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
              >
                <option value="">No club (Open)</option>
                {clubs.map((club) => (
                  <option key={club.id} value={club.id}>
                    {club.name}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
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
                      onClick={() =>
                        setRoster((r) => r.filter((x) => x.id !== u.id))
                      }
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
              onSelect={(user) =>
                setRoster((r) =>
                  r.some((x) => x.id === user.id) ? r : [...r, user],
                )
              }
            />
          </div>
          <Button
            type="submit"
            size="sm"
            disabled={!canCreate || create.isPending}
          >
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
  );
}

/** Today as yyyy-MM-dd (local), comparable lexicographically with an event's ISO end date. */
function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * One selectable event row: name, filing host, the relevant date, and participant count; opens the event
 * on click. Upcoming events show only their start date, past events only their end date (#296).
 */
function EventRow({
  event,
  upcoming,
  onSelect,
}: {
  event: EventResponse;
  upcoming: boolean;
  onSelect: () => void;
}) {
  const date = upcoming
    ? `Starts ${event.startDate}`
    : `Ended ${event.endDate}`;
  return (
    <li>
      <button
        type="button"
        className="flex w-full items-start justify-between gap-2 rounded-lg border p-3 text-left text-sm hover:bg-muted/50"
        onClick={onSelect}
      >
        <span className="flex flex-col">
          <span className="font-medium">{event.name}</span>
          {/* The filing host (#270), shown as text — the whole card is a button, so no nested link. */}
          {event.creatorDisplayName ? (
            <span className="text-xs text-muted-foreground">
              Filed by {event.creatorDisplayName}
            </span>
          ) : null}
        </span>
        <span className="shrink-0 text-muted-foreground">
          {date} · {event.participants.length} player
          {plural(event.participants.length)}
        </span>
      </button>
    </li>
  );
}

/** Events grouped under a club (#313); clubless events fall under the "Open" group, shown last. */
interface ClubGroup {
  key: string;
  label: string;
  events: EventResponse[];
}

const OPEN_GROUP_KEY = "__open__";

function groupByClub(events: EventResponse[]): ClubGroup[] {
  const groups = new Map<string, ClubGroup>();
  for (const event of events) {
    const key = event.clubId ?? OPEN_GROUP_KEY;
    const group = groups.get(key) ?? {
      key,
      label: event.clubName ?? "Open",
      events: [],
    };
    group.events.push(event);
    groups.set(key, group);
  }
  // Named clubs alphabetically; the clubless "Open" group always last. A precomputed sort key
  // ("￿" sorts after any name) keeps the comparator branchless and fully covered.
  return [...groups.values()]
    .map((group) => ({
      group,
      sortKey: group.key === OPEN_GROUP_KEY ? "￿" : group.label.toLowerCase(),
    }))
    .sort((a, b) => a.sortKey.localeCompare(b.sortKey))
    .map((entry) => entry.group);
}

/** Split a group's events into upcoming (end date today or later) and past, each date-sorted. */
function splitByDate(events: EventResponse[], today: string) {
  return {
    upcoming: events
      .filter((e) => e.endDate >= today)
      .sort((a, b) => a.startDate.localeCompare(b.startDate)),
    past: events
      .filter((e) => e.endDate < today)
      .sort((a, b) => b.startDate.localeCompare(a.startDate)),
  };
}

/** A labelled subsection (Upcoming / Past) with its own empty state (#271). */
function EventSection({
  title,
  events,
  upcoming,
  emptyLabel,
  onSelect,
}: {
  title: string;
  events: EventResponse[];
  upcoming: boolean;
  emptyLabel: string;
  onSelect: (id: string) => void;
}) {
  return (
    <div>
      <div className="text-xs font-medium uppercase text-muted-foreground">
        {title}
      </div>
      {events.length > 0 ? (
        <ul className="mt-1 space-y-2">
          {events.map((event) => (
            <EventRow
              key={event.id}
              event={event}
              upcoming={upcoming}
              onSelect={() => onSelect(event.id)}
            />
          ))}
        </ul>
      ) : (
        <p className="mt-1 text-sm text-muted-foreground">{emptyLabel}</p>
      )}
    </div>
  );
}

/**
 * The Event Organizer tab (#138, renamed from Matches): hosts run events/meets that contain matches.
 * The events table is the entry point; selecting a row opens that event's working page (participant-
 * scoped fixtures + results).
 */
export function EventOrganizerTab() {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const eventsQuery = useGetApiV1Events();
  const events = eventsQuery.data ?? [];
  // Today counts as upcoming; the split mirrors the Profile Events history card (#271).
  const today = todayIso();

  if (selectedId) {
    return (
      <EventDetail eventId={selectedId} onBack={() => setSelectedId(null)} />
    );
  }

  return (
    <div className="grid gap-4">
      <NewEventForm />

      <Card>
        <CardHeader>
          <CardTitle>Events</CardTitle>
          <CardDescription>
            Grouped by club (#313); clubless events are under “Open”. Select an
            event to manage its participants, fixtures, and results.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          {eventsQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : events.length > 0 ? (
            groupByClub(events).map((group) => {
              const { upcoming, past } = splitByDate(group.events, today);
              return (
                <div key={group.key} className="space-y-3">
                  <div className="text-sm font-semibold">{group.label}</div>
                  <EventSection
                    title="Upcoming"
                    events={upcoming}
                    upcoming
                    emptyLabel="No upcoming events."
                    onSelect={setSelectedId}
                  />
                  <EventSection
                    title="Past"
                    events={past}
                    upcoming={false}
                    emptyLabel="No past events."
                    onSelect={setSelectedId}
                  />
                </div>
              );
            })
          ) : (
            <p className="text-sm text-muted-foreground">
              No events yet. Create one above.
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
