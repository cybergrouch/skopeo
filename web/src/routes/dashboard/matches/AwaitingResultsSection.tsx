import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { PublicPageLink } from "@/components/PublicPageLink";
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { GripVertical } from "lucide-react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  getGetApiV1MatchesQueryKey,
  useGetApiV1Matches,
  usePostApiV1MatchesIdResult,
  usePutApiV1MatchesCalculationOrder,
  usePutApiV1MatchesIdState,
} from "@/api/generated/matches/matches";
import { useGetApiV1Users } from "@/api/generated/users/users";
import { GetApiV1MatchesFilter } from "@/api/generated/model";
import type { MatchResponse, SetScoreRequest } from "@/api/generated/model";

const AWAITING = { filter: GetApiV1MatchesFilter["awaiting-results"] };
const MAX_SETS = 5;

/** Today's local date as yyyy-MM-dd, comparable lexicographically with a match's matchDate. */
function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

type BadgeVariant = "default" | "secondary" | "outline";

/**
 * Where a scheduled fixture sits relative to today (issue #71). The schedule is only suggestive —
 * fixtures aren't filtered by date — so this is just an at-a-glance cue. matchDate and today are
 * zero-padded yyyy-MM-dd, so string comparison is chronological.
 */
function scheduleBadge(
  matchDate: string,
  today: string,
): { label: string; variant: BadgeVariant } {
  if (matchDate < today) return { label: "Overdue", variant: "default" };
  if (matchDate === today) return { label: "Today", variant: "secondary" };
  return { label: "Upcoming", variant: "outline" };
}

interface SetRow {
  t1: string;
  t2: string;
}

function toSets(rows: SetRow[]): SetScoreRequest[] {
  return rows
    .filter((r) => r.t1.trim() !== "" && r.t2.trim() !== "")
    .map((r) => ({ team1Games: Number(r.t1), team2Games: Number(r.t2) }));
}

/** The set-score rows prefilled from an already-recorded match (games only; tiebreaks aren't edited). */
function rowsFromMatch(match: MatchResponse): SetRow[] {
  return match.sets.map((s) => ({
    t1: String(s.team1Games),
    t2: String(s.team2Games),
  }));
}

function MatchResultRow({
  match,
  nameOf,
  readOnly = false,
}: {
  match: MatchResponse;
  nameOf: (userId: string) => string;
  // When true (#310), suppress all data-entry controls — record/edit/delete — and just show the
  // score. Used for a HOST viewing an event that has ended; the server is still the source of truth.
  readOnly?: boolean;
}) {
  const queryClient = useQueryClient();
  // A recorded fixture (has sets) starts collapsed as a score summary that can be expanded to edit;
  // a scheduled fixture starts as the entry form. Once rated (#138) it is frozen: read-only, no edit.
  const recorded = match.sets.length > 0;
  const rated = match.ratedAt != null;
  const [editing, setEditing] = useState(false);
  const [rows, setRows] = useState<SetRow[]>(
    recorded
      ? rowsFromMatch(match)
      : [
          { t1: "", t2: "" },
          { t1: "", t2: "" },
        ],
  );
  const [error, setError] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  // Refresh every matches list (awaiting + recorded, global + event-scoped) via the base key prefix.
  const invalidateMatches = () =>
    queryClient.invalidateQueries({ queryKey: getGetApiV1MatchesQueryKey() });

  const upload = usePostApiV1MatchesIdResult({
    mutation: { onSuccess: invalidateMatches },
  });

  // Delete a fixture = soft-disable it (#138): the server refuses once a match has been rated.
  const remove = usePutApiV1MatchesIdState({
    mutation: { onSuccess: invalidateMatches },
  });

  async function deleteFixture() {
    setError(null);
    try {
      await remove.mutateAsync({ id: match.id, data: { isActive: false } });
    } catch {
      setError("Could not delete the fixture.");
      setConfirmingDelete(false);
    }
  }

  function setCell(index: number, key: keyof SetRow, value: string) {
    setRows((prev) =>
      prev.map((r, i) => (i === index ? { ...r, [key]: value } : r)),
    );
  }

  async function submit() {
    setError(null);
    const sets = toSets(rows);
    if (sets.length === 0) {
      setError("Enter at least one set.");
      return;
    }
    try {
      await upload.mutateAsync({ id: match.id, data: { sets } });
      setEditing(false);
    } catch {
      setError("Could not save the result. Each set needs a clear winner.");
    }
  }

  function cancelEdit() {
    setRows(rowsFromMatch(match));
    setError(null);
    setEditing(false);
  }

  const player1 = match.team1.userIds.map(nameOf).join(", ");
  const player2 = match.team2.userIds.map(nameOf).join(", ");
  const badge = scheduleBadge(match.matchDate, todayIso());
  const showForm = !readOnly && (!recorded || editing);
  const summary = match.sets
    .map((s) => `${s.team1Games}–${s.team2Games}`)
    .join(", ");

  // Delete control with a confirm step (#138); offered both while entering scores and when collapsed.
  const deleteControls = confirmingDelete ? (
    <>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="text-destructive hover:text-destructive"
        disabled={remove.isPending}
        onClick={deleteFixture}
      >
        {remove.isPending ? "Deleting…" : "Confirm delete"}
      </Button>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        disabled={remove.isPending}
        onClick={() => setConfirmingDelete(false)}
      >
        Cancel
      </Button>
    </>
  ) : (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      className="text-destructive hover:text-destructive"
      onClick={() => setConfirmingDelete(true)}
    >
      Delete fixture
    </Button>
  );

  return (
    <div className="rounded-lg border p-3">
      <div className="mb-2 flex items-center gap-2 text-sm">
        <span className="font-medium">
          {player1} vs {player2}
        </span>
        <span className="text-muted-foreground">· {match.matchDate}</span>
        {rated ? (
          <Badge>Rated</Badge>
        ) : recorded ? (
          <Badge variant="secondary">Recorded</Badge>
        ) : (
          <Badge variant={badge.variant}>{badge.label}</Badge>
        )}
        <PublicPageLink
          to={`/matches/${match.publicCode}`}
          className="ml-auto text-xs"
        >
          Public page (QR)
        </PublicPageLink>
      </div>
      {/* Rating handicap (#486): shown transparently to organizers and participants. */}
      {match.team1Handicap || match.team2Handicap ? (
        <div className="mb-2 text-xs text-muted-foreground">
          Handicap:
          {match.team1Handicap ? ` −${match.team1Handicap} to Side 1` : ""}
          {match.team1Handicap && match.team2Handicap ? "," : ""}
          {match.team2Handicap ? ` −${match.team2Handicap} to Side 2` : ""}
        </div>
      ) : null}
      {showForm ? (
        <>
          <div className="space-y-2">
            {rows.map((row, index) => (
              <div key={index} className="flex items-center gap-2">
                <span className="w-12 text-xs text-muted-foreground">
                  Set {index + 1}
                </span>
                <Input
                  aria-label={`set ${index + 1} player 1 games`}
                  className="w-16"
                  inputMode="numeric"
                  value={row.t1}
                  onChange={(e) => setCell(index, "t1", e.target.value)}
                />
                <span>–</span>
                <Input
                  aria-label={`set ${index + 1} player 2 games`}
                  className="w-16"
                  inputMode="numeric"
                  value={row.t2}
                  onChange={(e) => setCell(index, "t2", e.target.value)}
                />
                {rows.length > 1 ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      setRows((prev) => prev.filter((_, i) => i !== index))
                    }
                  >
                    Remove
                  </Button>
                ) : null}
              </div>
            ))}
          </div>
          <div className="mt-3 flex flex-wrap gap-2">
            {rows.length < MAX_SETS ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setRows((prev) => [...prev, { t1: "", t2: "" }])}
              >
                Add set
              </Button>
            ) : null}
            <Button size="sm" disabled={upload.isPending} onClick={submit}>
              {upload.isPending
                ? recorded
                  ? "Saving…"
                  : "Recording…"
                : recorded
                  ? "Save result"
                  : "Record result"}
            </Button>
            {recorded ? (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={upload.isPending}
                onClick={cancelEdit}
              >
                Cancel
              </Button>
            ) : null}
            {deleteControls}
          </div>
        </>
      ) : (
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-medium">
            {summary || "Awaiting result"}
          </span>
          {rated || readOnly ? null : (
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setEditing(true)}
              >
                Edit result
              </Button>
              {deleteControls}
            </div>
          )}
        </div>
      )}
      {error ? (
        <p className="mt-2 text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** Resolve the participant ids across [matches] to display names (id → name), with id-slice fallback. */
function useNameResolver(matches: MatchResponse[]): (userId: string) => string {
  const ids = [
    ...new Set(
      matches.flatMap((m) => [...m.team1.userIds, ...m.team2.userIds]),
    ),
  ];
  const usersQuery = useGetApiV1Users(
    { ids: ids.join(",") },
    { query: { enabled: ids.length > 0 } },
  );
  const nameById = new Map(
    (usersQuery.data ?? []).map((u) => [u.id, u.displayName ?? u.id]),
  );
  return (userId: string) => nameById.get(userId) ?? userId.slice(0, 8);
}

/** Group matches by match date, preserving the incoming (already date-sorted) order. */
function groupByDate(
  matches: MatchResponse[],
): { date: string; items: MatchResponse[] }[] {
  const groups: { date: string; items: MatchResponse[] }[] = [];
  for (const m of matches) {
    const last = groups[groups.length - 1];
    if (last && last.date === m.matchDate) last.items.push(m);
    else groups.push({ date: m.matchDate, items: [m] });
  }
  return groups;
}

/** A draggable wrapper around a match card (#332): a grip handle carries the drag listeners. */
function SortableMatchCard({
  match,
  nameOf,
}: {
  match: MatchResponse;
  nameOf: (userId: string) => string;
}) {
  const { attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id: match.id });
  const style = { transform: CSS.Transform.toString(transform), transition };
  return (
    <div ref={setNodeRef} style={style} className="relative">
      <button
        type="button"
        aria-label="Reorder match"
        className="absolute left-1 top-1 cursor-grab touch-none rounded p-1 text-muted-foreground hover:bg-muted"
        {...attributes}
        {...listeners}
      >
        <GripVertical className="h-4 w-4" />
      </button>
      <div className="pl-6">
        <MatchResultRow match={match} nameOf={nameOf} />
      </div>
    </div>
  );
}

/**
 * A same-date group of match cards (#331/#332). When reorderable (event-scoped, not read-only, and
 * more than one match on the date), a host can drag to set the calculation order within that date;
 * the new order persists via PUT /matches/calculation-order. Otherwise the cards render plainly.
 *
 * A group with any already-rated match is NOT reorderable (#337): ratings are frozen, so the backend
 * rejects a reorder touching a rated match — disabling drag for the whole group keeps the UI honest
 * rather than letting the host discover the refusal only on drop.
 */
function MatchDateGroup({
  items,
  nameOf,
  readOnly,
  reorderable,
}: {
  items: MatchResponse[];
  nameOf: (userId: string) => string;
  readOnly: boolean;
  reorderable: boolean;
}) {
  const queryClient = useQueryClient();
  const reorder = usePutApiV1MatchesCalculationOrder({
    mutation: {
      onSuccess: () =>
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1MatchesQueryKey(),
        }),
    },
  });
  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  // A rated match freezes the group's order (#337): the backend rejects any reorder touching one, so
  // don't offer drag handles for a group that has one — render plain cards instead.
  const anyRated = items.some((m) => m.ratedAt != null);

  if (!reorderable || readOnly || anyRated || items.length < 2) {
    return (
      <>
        {items.map((m) => (
          <MatchResultRow
            key={m.id}
            match={m}
            nameOf={nameOf}
            readOnly={readOnly}
          />
        ))}
      </>
    );
  }

  const ids = items.map((m) => m.id);

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const next = arrayMove(
      ids,
      ids.indexOf(active.id as string),
      ids.indexOf(over.id as string),
    );
    // The server order is the source of truth; persist and refetch reflects the new sequence.
    reorder.mutate({ data: { matchIds: next } });
  }

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragEnd={handleDragEnd}
    >
      <SortableContext items={ids} strategy={verticalListSortingStrategy}>
        {items.map((m) => (
          <SortableMatchCard key={m.id} match={m} nameOf={nameOf} />
        ))}
      </SortableContext>
    </DndContext>
  );
}

/** Render matches grouped by date, each group drag-reorderable within its date when [reorderable]. */
function ReorderableMatchList({
  matches,
  nameOf,
  readOnly,
  reorderable,
}: {
  matches: MatchResponse[];
  nameOf: (userId: string) => string;
  readOnly: boolean;
  reorderable: boolean;
}) {
  return (
    <>
      {groupByDate(matches).map((g) => (
        <MatchDateGroup
          key={g.date}
          items={g.items}
          nameOf={nameOf}
          readOnly={readOnly}
          reorderable={reorderable}
        />
      ))}
    </>
  );
}

export function AwaitingResultsSection({
  eventId,
  readOnly = false,
}: { eventId?: string; readOnly?: boolean } = {}) {
  // Scope to a single event's awaiting fixtures when given (#138), else the global oversight list.
  const matchesQuery = useGetApiV1Matches(
    eventId ? { ...AWAITING, eventId } : AWAITING,
  );
  const matches = matchesQuery.data ?? [];
  const nameOf = useNameResolver(matches);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Awaiting results</CardTitle>
        <CardDescription>
          Your scheduled fixtures awaiting results — they can be played anytime,
          so record the set scores whenever the match happens; the server
          derives the winner.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {matchesQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : matches.length > 0 ? (
          <ReorderableMatchList
            matches={matches}
            nameOf={nameOf}
            readOnly={readOnly}
            reorderable={eventId != null}
          />
        ) : (
          <p className="text-sm text-muted-foreground">
            No fixtures awaiting results.
          </p>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * An event's completed fixtures (#138). They stay on view after results are entered: recorded ones
 * remain editable until an admin runs the rating calculation, after which the match is frozen — it
 * stays here as a read-only record badged "Rated".
 */
export function RecordedResultsSection({
  eventId,
  readOnly = false,
}: {
  eventId: string;
  readOnly?: boolean;
}) {
  const matchesQuery = useGetApiV1Matches({
    filter: GetApiV1MatchesFilter.results,
    eventId,
  });
  const matches = matchesQuery.data ?? [];
  const nameOf = useNameResolver(matches);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Recorded results</CardTitle>
        <CardDescription>
          Fixtures with results entered — editable until an admin runs the
          rating calculation, then kept here as a read-only “Rated” record.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {matchesQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : matches.length > 0 ? (
          <ReorderableMatchList
            matches={matches}
            nameOf={nameOf}
            readOnly={readOnly}
            reorderable
          />
        ) : (
          <p className="text-sm text-muted-foreground">
            No recorded results yet.
          </p>
        )}
      </CardContent>
    </Card>
  );
}
