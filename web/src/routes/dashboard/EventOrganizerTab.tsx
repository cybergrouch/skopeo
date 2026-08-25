import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useGetApiV1Events } from "@/api/generated/events/events";
import type { EventResponse } from "@/api/generated/model";
import { Badge } from "@/components/ui/badge";
import { plural } from "@/lib/plural";
import { ContentLink } from "@/components/ContentLink";
import { EventBuckets } from "@/features/event/EventBucketSections";
import { NewEventForm } from "@/features/event/NewEventForm";
import { todayIso } from "@/features/event/eventBuckets";

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
}) {  const date = upcoming
    ? `Starts ${event.startDate}`
    : `Ended ${event.endDate}`;
  return (
    <li>
      <button
        type="button"
        className="flex w-full items-start justify-between gap-2 rounded-lg border p-3 text-left text-sm hover:bg-muted/50"
        onClick={onSelect}
      >
        <span className="flex min-w-0 flex-col">
          <span className="flex flex-wrap items-center gap-2 font-medium">
            {event.name}
            {/* Finalizing only QUEUES an event's matches for rating (#403); the ratings land later, when
                an administrator runs the calculation. This badge is the difference between the two —
                and the precondition for Reverse Ratings (#478), which a not-yet-rated event refuses. */}
            {event.isRated ? <Badge variant="default">Rated</Badge> : null}
          </span>
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
  /** The club's shareable code (#780), for linking to its public page; absent for the "Open" group. */
  publicCode?: string;
  events: EventResponse[];
}

const OPEN_GROUP_KEY = "__open__";

function groupByClub(events: EventResponse[]): ClubGroup[] {
  const groups = new Map<string, ClubGroup>();
  for (const event of events) {
    // One nested `club` object (#780): present with every detail, or absent for a clubless event — so a
    // single check decides both the grouping key and the label.
    const key = event.club?.id ?? OPEN_GROUP_KEY;
    const group = groups.get(key) ?? {
      key,
      label: event.club?.name ?? "Open",
      publicCode: event.club?.publicCode,
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
  onSelect: (publicCode: string) => void;
}) {
  return (
    <div className="space-y-3">
      {/* The club name links to its public page (#780) while a separate chevron keeps the collapse
          (#367). Two distinct controls rather than a link nested inside a button — that markup is
          invalid and a known screen-reader trap. The clubless "Open" group has no page to link to. */}
      <div className="flex w-full items-center justify-between gap-2 text-sm font-semibold">
        {group.publicCode ? (
          <ContentLink to={`/clubs/${group.publicCode}`}>
            {group.label} ({group.events.length})
          </ContentLink>
        ) : (
          <span>
            {group.label} ({group.events.length})
          </span>
        )}
        <button
          type="button"
          className="text-muted-foreground hover:text-foreground/80"
          aria-expanded={isOpen}
          aria-label={`${isOpen ? "Collapse" : "Expand"} ${group.label}`}
          onClick={onToggle}
        >
          <span aria-hidden>{isOpen ? "▾" : "▸"}</span>
        </button>
      </div>
      {isOpen ? (
        <EventBuckets
          events={group.events}
          today={today}
          renderRow={(event, { upcoming }) => (
            <EventRow
              key={event.id}
              event={event}
              upcoming={upcoming}
              onSelect={() => onSelect(event.publicCode)}
            />
          )}
        />
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
  const navigate = useNavigate();
  const eventsQuery = useGetApiV1Events();
  const events = eventsQuery.data ?? [];
  // Today counts as upcoming; the split mirrors the Profile Events history card (#271).
  const today = todayIso();

  const groups = groupByClub(events);
  // Expanded-group keys (#367). Default: all collapsed (#591) — every club group starts minimized so
  // the Events list loads as a short set of club headers; the user expands the club they want.
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const toggle = (key: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <NewEventForm />

      <Card>
        <CardHeader>
          <CardTitle>Events</CardTitle>
          <CardDescription>
            Grouped by club; clubless events are under “Open”. Select an
            event to open its page, where you manage its participants,
            fixtures, and results.
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
                isOpen={expanded.has(group.key)}
                onToggle={() => toggle(group.key)}
                onSelect={(code) => navigate(`/events/${code}`)}
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
