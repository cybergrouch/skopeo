import { useMemo, useState, type FormEvent } from "react";
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
import { useGetApiV1PointsPolicies } from "@/api/generated/points-budget/points-budget";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import type {
  ClubResponse,
  EventResponse,
  UserSummaryResponse,
} from "@/api/generated/model";
import { Capability, canRate, hasCapability } from "@/auth/capabilities";
import { PlayerPicker } from "@/components/PlayerPicker";
import { plural } from "@/lib/plural";
import { playerLabel } from "@/lib/playerLabel";
import { PlaceholderTag } from "@/components/PlaceholderTag";
import { EventDetail } from "./events/EventDetail";
import { PlaceholderPlayersSection } from "./PlaceholderPlayersSection";

/** The event classes a host can pick at creation (#403); mirrors the backend EventType enum. */
type EventType = "OPEN_PLAY" | "LEAGUE" | "TOURNAMENT";

const EVENT_TYPE_OPTIONS: ReadonlyArray<{ value: EventType; label: string }> = [
  { value: "OPEN_PLAY", label: "Open play" },
  { value: "LEAGUE", label: "League" },
  { value: "TOURNAMENT", label: "Tournament" },
];

/**
 * The single club a CLUB_OWNER should default the create-event Club selector to (#364), or "" when
 * there is no unambiguous default: own exactly one club → that club's id; own multiple → "" (don't
 * guess); own zero, or not a CLUB_OWNER → "". Non-owners are unaffected.
 */
function defaultOwnedClubId(
  clubs: ClubResponse[],
  meId: string | undefined,
  capabilities: readonly Capability[] | undefined,
): string {
  if (!meId || !hasCapability(capabilities, Capability.CLUB_OWNER)) return "";
  const owned = clubs.filter((club) =>
    club.owners.some((owner) => owner.userId === meId),
  );
  return owned.length === 1 ? owned[0].id : "";
}

/** The new-event roster being assembled before the event is created. */
function NewEventForm() {
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  // Undefined until the user picks a club; that keeps the CLUB_OWNER default (#364) from clobbering
  // an explicit choice (including clearing to "Open", i.e. an empty string the user chose).
  const [clubIdChoice, setClubIdChoice] = useState<string | undefined>(
    undefined,
  );
  const [roster, setRoster] = useState<UserSummaryResponse[]>([]);
  // The event's class (#403); OPEN_PLAY is the default and the backward-compatible choice.
  const [type, setType] = useState<EventType>("OPEN_PLAY");
  const [error, setError] = useState<string | null>(null);
  // "Award ranking points" checkbox (#466): opt-in, default UNCHECKED → the event awards no points and
  // the config fields are hidden. Ticking it reveals + requires the per-match reward + validity window.
  const [awardPoints, setAwardPoints] = useState(false);
  // Points config (#466): kept as strings so the inputs can be cleared while typing.
  const [minPoints, setMinPoints] = useState("");
  const [maxPoints, setMaxPoints] = useState("");
  const [validityStart, setValidityStart] = useState("");
  const [validityEnd, setValidityEnd] = useState("");

  // Clubs to optionally file the event under (#313). Readable by staff; empty when none exist.
  const clubsData = useGetApiV1Clubs().data;
  const clubs = clubsData ?? [];
  const me = useGetApiV1UsersMe().data;
  // Global per-type policy bounds (#403 Phase C), for the client-side hints + validation. A
  // non-manager caller simply gets nothing (retry off keeps a 403 quiet); the fields still render.
  const policies = useGetApiV1PointsPolicies({ query: { retry: false } }).data;

  // Default the selector to a CLUB_OWNER's own club (#364), but only while the field is untouched;
  // once the user selects anything (including "Open") their choice wins.
  const ownerDefault = useMemo(
    () => defaultOwnedClubId(clubsData ?? [], me?.id, me?.capabilities),
    [clubsData, me?.id, me?.capabilities],
  );
  const clubId = clubIdChoice ?? ownerDefault;

  const create = usePostApiV1Events({
    mutation: {
      onSuccess: () => {
        setName("");
        setStartDate("");
        setEndDate("");
        setClubIdChoice(undefined);
        setRoster([]);
        setType("OPEN_PLAY");
        setAwardPoints(false);
        setMinPoints("");
        setMaxPoints("");
        setValidityStart("");
        setValidityEnd("");
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
      },
    },
  });

  // Points are opt-in via the "Award ranking points" checkbox (#466), for any event type/club. When
  // ticked the config fields appear + are required and validated against the global per-type policy.
  const showPointsConfig = awardPoints;
  const globalPolicy = policies?.find((p) => p.eventType === type);

  // Client-side validation of the points window against the global policy (#429), mirroring the
  // EventDetail editor. Returns an error message, or null when the window is valid (or not required).
  const pointsError = useMemo<string | null>(() => {
    if (!showPointsConfig) return null;
    if (
      minPoints === "" ||
      maxPoints === "" ||
      validityStart === "" ||
      validityEnd === ""
    ) {
      return "Min/max points and a validity window are required.";
    }
    const min = Number(minPoints);
    const max = Number(maxPoints);
    if (
      !Number.isInteger(min) ||
      !Number.isInteger(max) ||
      min <= 0 ||
      max <= 0
    ) {
      return "Min and max points must be positive whole numbers.";
    }
    if (min > max) return "Min points cannot exceed max points.";
    if (globalPolicy && min < globalPolicy.minPoints) {
      return `Min points must be at least the global minimum of ${globalPolicy.minPoints}.`;
    }
    if (globalPolicy && max > globalPolicy.maxPoints) {
      return `Max points must not exceed the global maximum of ${globalPolicy.maxPoints}.`;
    }
    if (validityEnd < validityStart) {
      return "Validity end cannot be before the start.";
    }
    return null;
  }, [
    showPointsConfig,
    minPoints,
    maxPoints,
    validityStart,
    validityEnd,
    globalPolicy,
  ]);

  function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (pointsError) {
      setError(pointsError);
      return;
    }
    create.mutate(
      {
        data: {
          name,
          startDate,
          endDate,
          type,
          participantIds: roster.map((u) => u.id),
          ...(clubId ? { clubId } : {}),
          // Only send a points config when "Award ranking points" is ticked (#466); off = no points.
          ...(showPointsConfig
            ? {
                minPointsPerMatch: Number(minPoints),
                maxPointsPerMatch: Number(maxPoints),
                pointValidityStart: validityStart,
                pointValidityEnd: validityEnd,
              }
            : {}),
        },
      },
      {
        onError: () =>
          setError("Could not create the event. Check the name and dates."),
      },
    );
  }

  const canCreate =
    name.trim() !== "" &&
    startDate !== "" &&
    endDate !== "" &&
    pointsError === null;

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
          <div className="space-y-1">
            <Label htmlFor="event-type" className="text-xs">
              Type
            </Label>
            <select
              id="event-type"
              value={type}
              onChange={(e) => setType(e.target.value as EventType)}
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
            >
              {EVENT_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          {clubs.length > 0 ? (
            <div className="space-y-1">
              <Label htmlFor="event-club" className="text-xs">
                Club
              </Label>
              <select
                id="event-club"
                value={clubId}
                onChange={(e) => setClubIdChoice(e.target.value)}
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
          {/* "Award ranking points" checkbox (#466): opt-in, default off. Ticking reveals + requires
              the points config below, validated against the global per-type policy. */}
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={awardPoints}
              onChange={(e) => setAwardPoints(e.target.checked)}
              aria-label="Award ranking points"
            />
            Award ranking points
          </label>
          {/* Points config (#466): shown only when the "Award ranking points" checkbox is ticked. */}
          {showPointsConfig ? (
            <div className="grid gap-3 rounded-md border border-input p-3">
              <p className="text-xs font-medium uppercase text-muted-foreground">
                Points config
              </p>
              {globalPolicy ? (
                <p className="text-xs text-muted-foreground">
                  The global{" "}
                  {EVENT_TYPE_OPTIONS.find((o) => o.value === type)?.label ??
                    type}{" "}
                  policy allows {globalPolicy.minPoints}–{globalPolicy.maxPoints}{" "}
                  points and up to {globalPolicy.maxValidityDays} validity days.
                </p>
              ) : null}
              <div className="grid grid-cols-2 gap-2">
                <div className="space-y-1">
                  <Label htmlFor="event-min-points" className="text-xs">
                    Min points
                  </Label>
                  <Input
                    id="event-min-points"
                    type="number"
                    value={minPoints}
                    placeholder={
                      globalPolicy ? String(globalPolicy.minPoints) : ""
                    }
                    onChange={(e) => setMinPoints(e.target.value)}
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="event-max-points" className="text-xs">
                    Max points
                  </Label>
                  <Input
                    id="event-max-points"
                    type="number"
                    value={maxPoints}
                    placeholder={
                      globalPolicy ? String(globalPolicy.maxPoints) : ""
                    }
                    onChange={(e) => setMaxPoints(e.target.value)}
                  />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="space-y-1">
                  <Label htmlFor="event-validity-start" className="text-xs">
                    Validity start
                  </Label>
                  <Input
                    id="event-validity-start"
                    type="date"
                    value={validityStart}
                    onChange={(e) => setValidityStart(e.target.value)}
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="event-validity-end" className="text-xs">
                    Validity end
                  </Label>
                  <Input
                    id="event-validity-end"
                    type="date"
                    value={validityEnd}
                    onChange={(e) => setValidityEnd(e.target.value)}
                  />
                </div>
              </div>
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
                      {playerLabel(u.displayName, u.publicCode, u.id)}
                      <PlaceholderTag show={u.isPlaceholder} deleted={u.isDeleted} /> ✕
                    </Button>
                  </li>
                ))}
              </ul>
            ) : null}
            <PlayerPicker
              label="Add participant"
              placeholder="Search players to add…"
              excludeIds={roster.map((u) => u.id)}
              canSetRating={canRate(me?.capabilities)}
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

/** Recorded results present (#483) — the "has results" signal for the Unfinalized bucket. */
function hasResults(event: EventResponse): boolean {
  return (event.completedMatchCount ?? 0) > 0;
}

/**
 * Split a group's events into three buckets (#483). Finalized status wins over everything: a finalized
 * event is always Finalized, even with a future end date or no results. Otherwise Unfinalized = the
 * event ended OR has recorded results (activity started, not concluded); Upcoming = future + untouched.
 * Sort: Upcoming by start date asc, Unfinalized by end date desc, Finalized by finalizedAt desc
 * (falling back to end date desc when a finalized row somehow lacks the timestamp).
 */
function splitByBucket(events: EventResponse[], today: string) {
  const finalized = events.filter((e) => e.isFinalized);
  const active = events.filter((e) => !e.isFinalized);
  const unfinalized = active.filter((e) => e.endDate < today || hasResults(e));
  const upcoming = active.filter((e) => e.endDate >= today && !hasResults(e));
  return {
    upcoming: [...upcoming].sort((a, b) => a.startDate.localeCompare(b.startDate)),
    unfinalized: [...unfinalized].sort((a, b) => b.endDate.localeCompare(a.endDate)),
    finalized: [...finalized].sort((a, b) =>
      (b.finalizedAt ?? b.endDate).localeCompare(a.finalizedAt ?? a.endDate),
    ),
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
 * A collapsible per-club group (#367): the header is an accessible toggle (aria-expanded, keyboard-
 * operable button) showing the club name and its event count; the Upcoming/Unfinalized/Finalized
 * subsections (#483) render only while expanded.
 */
function ClubGroupSection({
  group,
  today,
  isOpen,
  onToggle,
  onSelect,
}: {
  group: ClubGroup;
  today: string;
  isOpen: boolean;
  onToggle: () => void;
  onSelect: (id: string) => void;
}) {
  const { upcoming, unfinalized, finalized } = splitByBucket(group.events, today);
  return (
    <div className="space-y-3">
      <button
        type="button"
        className="flex w-full items-center justify-between gap-2 text-left text-sm font-semibold hover:text-foreground/80"
        aria-expanded={isOpen}
        onClick={onToggle}
      >
        <span>
          {group.label} ({group.events.length})
        </span>
        <span aria-hidden className="text-muted-foreground">
          {isOpen ? "▾" : "▸"}
        </span>
      </button>
      {isOpen ? (
        <>
          <EventSection
            title="Upcoming"
            events={upcoming}
            upcoming
            emptyLabel="No upcoming events."
            onSelect={onSelect}
          />
          <EventSection
            title="Unfinalized"
            events={unfinalized}
            upcoming={false}
            emptyLabel="No unfinalized events."
            onSelect={onSelect}
          />
          <EventSection
            title="Finalized"
            events={finalized}
            upcoming={false}
            emptyLabel="No finalized events."
            onSelect={onSelect}
          />
        </>
      ) : null}
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
  const me = useGetApiV1UsersMe().data;
  // Today counts as upcoming; the split mirrors the Profile Events history card (#271).
  const today = todayIso();

  const groups = groupByClub(events);
  // Collapsed-group keys (#367). Default: all expanded — nothing is hidden on first load; the user
  // opts into collapsing. A group holding the selected event is force-expanded so it stays visible.
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const toggle = (key: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  if (selectedId) {
    return (
      <EventDetail eventId={selectedId} onBack={() => setSelectedId(null)} />
    );
  }

  return (
    <div className="grid gap-4">
      <NewEventForm />

      <PlaceholderPlayersSection capabilities={me?.capabilities} />

      <Card>
        <CardHeader>
          <CardTitle>Events</CardTitle>
          <CardDescription>
            Grouped by club; clubless events are under “Open”. Select an
            event to manage its participants, fixtures, and results.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          {eventsQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : events.length > 0 ? (
            groups.map((group) => (
              <ClubGroupSection
                key={group.key}
                group={group}
                today={today}
                isOpen={!collapsed.has(group.key)}
                onToggle={() => toggle(group.key)}
                onSelect={setSelectedId}
              />
            ))
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
