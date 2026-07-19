import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { PublicPageLink } from "@/components/PublicPageLink";
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
  getGetApiV1EventsIdQueryKey,
  getGetApiV1EventsQueryKey,
  useDeleteApiV1EventsId,
  useDeleteApiV1EventsIdParticipantsUserId,
  useGetApiV1EventsId,
  usePatchApiV1EventsId,
  usePostApiV1EventsIdFinalize,
  usePostApiV1EventsIdUnfinalize,
  usePostApiV1EventsIdReverseRatings,
  usePostApiV1EventsIdParticipants,
  usePostApiV1EventsIdParticipantsUserIdDecision,
  usePutApiV1EventsIdClub,
  usePutApiV1EventsIdPointsConfig,
} from "@/api/generated/events/events";
import { useGetApiV1Clubs } from "@/api/generated/clubs/clubs";
import {
  useGetApiV1PointsBudgets,
  useGetApiV1PointsPolicies,
} from "@/api/generated/points-budget/points-budget";
import {
  getGetApiV1MatchesQueryKey,
  usePostApiV1Matches,
} from "@/api/generated/matches/matches";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import { canEditEndedEvents, isAdministrator } from "@/auth/capabilities";
import { PlayerPicker } from "@/components/PlayerPicker";
import { HandicapField } from "@/components/HandicapField";
import { playerLabel } from "@/lib/playerLabel";
import { formatConfidence } from "@/lib/confidence";
import type { EventParticipantResponse } from "@/api/generated/model";
import { ShareCard } from "@/components/ShareCard";
import {
  AwaitingResultsSection,
  RecordedResultsSection,
} from "../matches/AwaitingResultsSection";

/** "Female · 34 · NTRP 4.0" — a participant's sex, age, and NTRP band, omitting whatever is missing. */
function participantMeta(p: EventParticipantResponse): string {
  const parts: string[] = [];
  if (p.sex) parts.push(p.sex);
  if (p.age != null) parts.push(String(p.age));
  if (p.rating) {
    const pct = formatConfidence(p.rating.confidence);
    parts.push(`NTRP ${p.rating.level ?? p.rating.value}${pct ? ` · ${pct}` : ""}`);
  }
  return parts.join(" · ");
}

const MATCH_TYPES = [
  "OPEN_PLAY",
  "LEAGUE_PLAY",
  "TOURNAMENT_INITIAL_ROUND",
  "LEAGUE_PLAYOFFS",
  "TOURNAMENT_PLAYOFFS",
] as const;
const MATCH_TYPE_LABELS: Record<(typeof MATCH_TYPES)[number], string> = {
  OPEN_PLAY: "Open play",
  LEAGUE_PLAY: "League play",
  TOURNAMENT_INITIAL_ROUND: "Tournament — initial round",
  LEAGUE_PLAYOFFS: "League playoffs",
  TOURNAMENT_PLAYOFFS: "Tournament playoffs",
};

/** Human labels for the event's class (#403). */
const EVENT_TYPE_LABELS: Record<string, string> = {
  OPEN_PLAY: "Open play",
  LEAGUE: "League",
  TOURNAMENT: "Tournament",
};

const MATCH_FORMATS = ["SINGLES", "DOUBLES", "MIXED_DOUBLES"] as const;
const MATCH_FORMAT_LABELS: Record<(typeof MATCH_FORMATS)[number], string> = {
  SINGLES: "Singles",
  DOUBLES: "Doubles",
  MIXED_DOUBLES: "Mixed doubles",
};

/**
 * One event's working page (#138): the same matches UI as the global tab, but the fixture's player
 * pickers are scoped to this event's participants (and the API enforces it). Hosts manage the roster
 * here and record results below.
 */
/** Prefer the server's message (e.g. the 409 delete-guard advice), falling back to a generic one. */
function eventErrorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: string } } })
    ?.response?.data?.message;
  return message && message.trim() !== "" ? message : fallback;
}

export function EventDetail({
  eventId,
  onBack,
}: {
  eventId: string;
  onBack: () => void;
}) {
  const queryClient = useQueryClient();
  const eventQuery = useGetApiV1EventsId(eventId);
  const event = eventQuery.data;
  const allParticipants = event?.participants ?? [];
  // Only APPROVED members are the roster (eligible for fixtures); PENDING/HOLD are requests (#201).
  const participants = allParticipants.filter((p) => p.status === "APPROVED");
  const requests = allParticipants.filter(
    (p) => p.status === "PENDING" || p.status === "HOLD",
  );

  // Once an event has ended, a plain HOST can no longer enter data (#310) — the server rejects it, so
  // the UI suppresses the controls too. Admins and club owners stay exempt. "Ended" mirrors the
  // organizer's Past split: end date strictly before today (local yyyy-MM-dd).
  const me = useGetApiV1UsersMe().data;
  const now = new Date();
  const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
  const readOnly =
    !!event &&
    event.endDate < todayIso &&
    !canEditEndedEvents(me?.capabilities);
  // A finalized event is terminal (#403): no rename/club/participant edits, no fixtures/results. This
  // is independent of the ended-event gate and applies to everyone (the server rejects it too).
  const finalized = !!event?.isFinalized;
  const locked = readOnly || finalized;

  // Two slots per side; the "b" slots are only used (and shown) for doubles/mixed doubles.
  const [team1a, setTeam1a] = useState("");
  const [team1b, setTeam1b] = useState("");
  const [team2a, setTeam2a] = useState("");
  const [team2b, setTeam2b] = useState("");
  const [format, setFormat] =
    useState<(typeof MATCH_FORMATS)[number]>("SINGLES");
  const [matchType, setMatchType] =
    useState<(typeof MATCH_TYPES)[number]>("OPEN_PLAY");
  const [date, setDate] = useState("");
  const [fixtureError, setFixtureError] = useState<string | null>(null);
  const [rosterError, setRosterError] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState("");
  const [renameError, setRenameError] = useState<string | null>(null);
  const [clubError, setClubError] = useState<string | null>(null);
  const [confirmingFinalize, setConfirmingFinalize] = useState(false);
  const [finalizeError, setFinalizeError] = useState<string | null>(null);
  const [confirmingUnfinalize, setConfirmingUnfinalize] = useState(false);
  const [unfinalizeError, setUnfinalizeError] = useState<string | null>(null);
  // Reverse Ratings (#478): a distinct, destructive, ADMINISTRATOR-only action for an already-rated event.
  const [confirmingReverse, setConfirmingReverse] = useState(false);
  const [reverseError, setReverseError] = useState<string | null>(null);
  const isAdmin = isAdministrator(me?.capabilities);

  // Points config (#403 Phase C): the per-match reward window + validity window, edited inline for a
  // budgeted-type event. Drafts are kept as strings so the number inputs can be cleared while editing.
  const [minDraft, setMinDraft] = useState("");
  const [maxDraft, setMaxDraft] = useState("");
  const [validityStartDraft, setValidityStartDraft] = useState("");
  const [validityEndDraft, setValidityEndDraft] = useState("");
  const [pointsConfigError, setPointsConfigError] = useState<string | null>(
    null,
  );
  const [pointsConfigSaved, setPointsConfigSaved] = useState(false);
  // "Award ranking points" checkbox (#466): opt-in, seeded from whether the event already has a config.
  // Ticked → the config fields show + save; un-ticked → saving clears the config (event awards no points).
  const [awardPointsDraft, setAwardPointsDraft] = useState(false);
  // Designated points for a new fixture (#403 Phase C); blank means "use the server default".
  const [designatedDraft, setDesignatedDraft] = useState("");
  // "Award points for this match" checkbox (#466): opt-in per fixture, default CHECKED on a points event.
  const [awardFixturePoints, setAwardFixturePoints] = useState(true);
  // Per-side handicap (#486): hidden behind an explicit checkbox (discouraged by design). Un-ticking
  // clears both drafts. Drafts are strings so the number inputs can be cleared while editing.
  const [applyHandicap, setApplyHandicap] = useState(false);
  const [team1HandicapDraft, setTeam1HandicapDraft] = useState("");
  const [team2HandicapDraft, setTeam2HandicapDraft] = useState("");

  // Seed the editor drafts from the event's persisted config (#440). Without this the inputs stay
  // empty and only *show* the current values as placeholders, so saving an edit that keeps a field
  // untouched submits "" — Number("") === 0 — and fails validation ("must be positive whole numbers")
  // even though the field looks filled. We reset the drafts during render (React's "adjust state on a
  // changed value" pattern) whenever the persisted config changes — on first load and after a save's
  // refresh. Because it fires only on a *change* of the persisted values, it never clobbers an
  // in-progress edit (which leaves the persisted values, and thus the signature, unchanged).
  const configSignature = [
    event?.minPointsPerMatch ?? "",
    event?.maxPointsPerMatch ?? "",
    event?.pointValidityStart ?? "",
    event?.pointValidityEnd ?? "",
  ].join("|");
  const [seededSignature, setSeededSignature] = useState<string | null>(null);
  if (event != null && configSignature !== seededSignature) {
    setSeededSignature(configSignature);
    setMinDraft(
      event.minPointsPerMatch != null ? String(event.minPointsPerMatch) : "",
    );
    setMaxDraft(
      event.maxPointsPerMatch != null ? String(event.maxPointsPerMatch) : "",
    );
    setValidityStartDraft(event.pointValidityStart ?? "");
    setValidityEndDraft(event.pointValidityEnd ?? "");
    // Seed the "award points" checkbox from whether the event already carries a config (#466).
    setAwardPointsDraft(event.minPointsPerMatch != null);
  }

  // Clubs to (re)assign the event to (#319); staff-readable, empty when none exist.
  const clubs = useGetApiV1Clubs().data ?? [];

  // The global per-type policy bounds and the club budgets (#403 Phase C), for helper text and the
  // fixture free-budget hint. Both are points-manager reads — non-managers simply get nothing (retry
  // off keeps a 403 quiet), and the section still renders with the event's own config.
  const policies = useGetApiV1PointsPolicies({ query: { retry: false } }).data;
  const budgets = useGetApiV1PointsBudgets({ query: { retry: false } }).data;

  function refreshEvent() {
    void queryClient.invalidateQueries({
      queryKey: getGetApiV1EventsIdQueryKey(eventId),
    });
  }

  const addParticipant = usePostApiV1EventsIdParticipants({
    mutation: { onSuccess: refreshEvent },
  });
  const removeParticipant = useDeleteApiV1EventsIdParticipantsUserId({
    mutation: { onSuccess: refreshEvent },
  });
  const decideParticipant = usePostApiV1EventsIdParticipantsUserIdDecision({
    mutation: { onSuccess: refreshEvent },
  });
  const createFixture = usePostApiV1Matches({
    mutation: {
      onSuccess: () => {
        setTeam1a("");
        setTeam1b("");
        setTeam2a("");
        setTeam2b("");
        setDate("");
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1MatchesQueryKey(),
        });
      },
    },
  });

  // Rename the event (#269). On success the query is refreshed so the new name shows immediately;
  // the list is invalidated too so the Events section reflects it on return.
  const renameEvent = usePatchApiV1EventsId({
    mutation: {
      onSuccess: () => {
        refreshEvent();
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
        setRenaming(false);
      },
    },
  });

  // Set/change/clear the event's club (#319). On success refresh so the Organizer regrouping reflects
  // it on return; the empty option clears the club (event becomes "Open").
  const setClub = usePutApiV1EventsIdClub({
    mutation: {
      onSuccess: () => {
        refreshEvent();
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
      },
    },
  });

  async function saveClub(clubId: string) {
    setClubError(null);
    try {
      await setClub.mutateAsync({
        id: eventId,
        data: { clubId: clubId || null },
      });
    } catch (e) {
      setClubError(eventErrorMessage(e, "Could not update the club."));
    }
  }

  // Set the event's points config (#403 Phase C). On success refresh so the new bounds show and the
  // fixture default recomputes; the server re-validates against the global policy and existing fixtures.
  const setPointsConfig = usePutApiV1EventsIdPointsConfig({
    mutation: {
      onSuccess: () => {
        refreshEvent();
        setPointsConfigSaved(true);
      },
    },
  });

  async function savePointsConfig(e: FormEvent) {
    e.preventDefault();
    setPointsConfigError(null);
    setPointsConfigSaved(false);
    // "Award ranking points" un-ticked (#466): clear the config (empty body). The server nulls the
    // config and cascades to release its fixtures' designations.
    if (!awardPointsDraft) {
      try {
        await setPointsConfig.mutateAsync({ id: eventId, data: {} });
      } catch (err) {
        setPointsConfigError(
          eventErrorMessage(err, "Could not clear the points config."),
        );
      }
      return;
    }
    const min = Number(minDraft);
    const max = Number(maxDraft);
    if (!Number.isInteger(min) || !Number.isInteger(max) || min <= 0 || max <= 0) {
      setPointsConfigError("Min and max points must be positive whole numbers.");
      return;
    }
    if (min > max) {
      setPointsConfigError("Min points cannot exceed max points.");
      return;
    }
    if (validityStartDraft === "" || validityEndDraft === "") {
      setPointsConfigError("A validity start and end date are required.");
      return;
    }
    try {
      await setPointsConfig.mutateAsync({
        id: eventId,
        data: {
          minPointsPerMatch: min,
          maxPointsPerMatch: max,
          pointValidityStart: validityStartDraft,
          pointValidityEnd: validityEndDraft,
        },
      });
    } catch (err) {
      setPointsConfigError(
        eventErrorMessage(err, "Could not save the points config."),
      );
    }
  }

  async function saveRename() {
    const name = nameDraft.trim();
    if (name === "") {
      setRenameError("Event name is required.");
      return;
    }
    setRenameError(null);
    try {
      await renameEvent.mutateAsync({ id: eventId, data: { name } });
    } catch (e) {
      setRenameError(eventErrorMessage(e, "Could not rename this event."));
    }
  }

  // Delete the event (#243). The server refuses (409) while it has recorded/rated matches — surface
  // its guidance verbatim. On success, return to the list, which no longer includes this event.
  const deleteEvent = useDeleteApiV1EventsId({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
        onBack();
      },
    },
  });

  async function confirmDelete() {
    setDeleteError(null);
    try {
      await deleteEvent.mutateAsync({ id: eventId });
    } catch (e) {
      setDeleteError(eventErrorMessage(e, "Could not delete this event."));
      setConfirmingDelete(false);
    }
  }

  // Finalize the event (#403): terminal — closes it to changes and queues its matches for rating. On
  // success refresh so the badge shows and the controls disable; the list is invalidated for the return.
  const finalizeEvent = usePostApiV1EventsIdFinalize({
    mutation: {
      onSuccess: () => {
        refreshEvent();
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
        setConfirmingFinalize(false);
      },
    },
  });

  async function confirmFinalize() {
    setFinalizeError(null);
    try {
      await finalizeEvent.mutateAsync({ id: eventId });
    } catch (e) {
      setFinalizeError(eventErrorMessage(e, "Could not finalize this event."));
      setConfirmingFinalize(false);
    }
  }

  // Un-finalize the event (#477): reverse finalization to correct an erroneous score, revoking its
  // awarded points. On success refresh so the badge/controls reopen; the list is invalidated too.
  const unfinalizeEvent = usePostApiV1EventsIdUnfinalize({
    mutation: {
      onSuccess: () => {
        refreshEvent();
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
        setConfirmingUnfinalize(false);
      },
    },
  });

  async function confirmUnfinalize() {
    setUnfinalizeError(null);
    try {
      await unfinalizeEvent.mutateAsync({ id: eventId });
    } catch (e) {
      setUnfinalizeError(
        eventErrorMessage(e, "Could not un-finalize this event."),
      );
      setConfirmingUnfinalize(false);
    }
  }

  // Reverse Ratings (#478): the rated-path complement of un-finalize. It rewinds an already-rated event's
  // ratings, so it is ADMINISTRATOR-only, styled as a caution, and behind a mandatory confirmation. On
  // success refresh (badge/controls reopen) and invalidate the rating-affected reads so the UI recomputes.
  const reverseRatings = usePostApiV1EventsIdReverseRatings({
    mutation: {
      onSuccess: () => {
        refreshEvent();
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1MatchesQueryKey(),
        });
        setConfirmingReverse(false);
      },
    },
  });

  async function confirmReverse() {
    setReverseError(null);
    try {
      await reverseRatings.mutateAsync({ id: eventId });
    } catch (e) {
      setReverseError(
        eventErrorMessage(e, "Could not reverse this event's ratings."),
      );
      setConfirmingReverse(false);
    }
  }

  const isDoubles = format !== "SINGLES";
  // Only the slots this format uses; "b" slots participate for doubles/mixed doubles.
  const chosen = isDoubles
    ? [team1a, team1b, team2a, team2b]
    : [team1a, team2a];

  // Points are opt-in via the event's "Award ranking points" checkbox (#466), for any event type/club.
  // The points-config editor is shown for every (non-deleted) event; the global policy bounds the hint.
  const globalPolicy = policies?.find((p) => p.eventType === event?.type);
  // The event awards points when it carries a config (min/max set). A fixture may then designate points.
  const hasPointsConfig =
    event?.minPointsPerMatch != null && event?.maxPointsPerMatch != null;
  // A fixture designates points only when the event awards points (has a config); each fixture may still
  // opt out via its own "Award points for this match" checkbox (#466).
  const showDesignation = hasPointsConfig;
  const min = event?.minPointsPerMatch ?? 0;
  const max = event?.maxPointsPerMatch ?? 0;
  // Convenience default = round(avg(min, max)); the input shows it as a placeholder when left blank.
  const defaultDesignation = Math.round((min + max) / 2);
  const designatedValue =
    designatedDraft === "" ? defaultDesignation : Number(designatedDraft);
  // The team size drives the cost (each winning-team member gets the full amount): 2 for doubles, 1 else.
  const teamSize = isDoubles ? 2 : 1;
  // The club's remaining free budget for this event's type (#403 Phase C), when the event has a club.
  const clubBudget = event?.clubId
    ? budgets?.find(
        (b) => b.clubId === event.clubId && b.eventType === event.type,
      )
    : undefined;
  // The designation input applies only when the event awards points AND this fixture opts in (#466).
  const showDesignationInput = showDesignation && awardFixturePoints;
  const designationOutOfRange =
    showDesignationInput && (designatedValue < min || designatedValue > max);
  const designationOverBudget =
    showDesignationInput &&
    clubBudget != null &&
    designatedValue * teamSize > clubBudget.free;

  function scheduleFixture(e: FormEvent) {
    e.preventDefault();
    setFixtureError(null);
    createFixture.mutate(
      {
        data: {
          matchFormat: format,
          matchType,
          matchDate: date,
          team1: isDoubles ? [team1a, team1b] : [team1a],
          team2: isDoubles ? [team2a, team2b] : [team2a],
          eventId,
          // The "award points for this match" checkbox (#466): opted out → awardPoints:false (no points).
          // Opted in → send the designation when set, else let the server default it.
          ...(showDesignation && !awardFixturePoints
            ? { awardPoints: false }
            : showDesignationInput && designatedDraft !== ""
              ? { designatedPoints: Number(designatedDraft) }
              : {}),
          // Per-side handicap (#486): only sent when the "Apply handicap" box is ticked and non-empty.
          ...(applyHandicap && team1HandicapDraft !== ""
            ? { team1Handicap: team1HandicapDraft }
            : {}),
          ...(applyHandicap && team2HandicapDraft !== ""
            ? { team2Handicap: team2HandicapDraft }
            : {}),
        },
      },
      {
        onError: () =>
          setFixtureError(
            "Could not schedule the fixture. Every player must be a participant and already rated.",
          ),
      },
    );
  }

  const filled = chosen.filter((id) => id !== "");
  // A handicap draft (#486) is invalid if present but not a number in (0, 1.0].
  const handicapDraftInvalid = (raw: string): boolean => {
    if (raw === "") return false;
    const n = Number(raw);
    return Number.isNaN(n) || n <= 0 || n > 1;
  };
  const handicapOutOfRange =
    applyHandicap &&
    (handicapDraftInvalid(team1HandicapDraft) ||
      handicapDraftInvalid(team2HandicapDraft));
  const canSchedule =
    filled.length === chosen.length &&
    new Set(filled).size === chosen.length &&
    date !== "" &&
    !designationOutOfRange &&
    !designationOverBudget &&
    !handicapOutOfRange;

  // One player dropdown, scoped to the roster and excluding whoever's already picked in the other slots.
  function playerSelect(
    id: string,
    label: string,
    value: string,
    onChange: (v: string) => void,
  ) {
    const takenElsewhere = chosen.filter((s) => s !== value && s !== "");
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
            .filter(
              (p) => p.userId === value || !takenElsewhere.includes(p.userId),
            )
            .map((p) => (
              <option key={p.userId} value={p.userId}>
                {playerLabel(p.displayName, p.publicCode, p.userId)}
              </option>
            ))}
        </select>
      </div>
    );
  }

  return (
    <div className="grid gap-4">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="w-fit"
        onClick={onBack}
      >
        ← All events
      </Button>

      {eventQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading event…</p>
      ) : !event ? (
        <p className="text-sm text-muted-foreground">
          This event could not be loaded.
        </p>
      ) : (
        <>
          <Card>
            <CardHeader>
              {renaming ? (
                <div className="space-y-2">
                  <Label htmlFor="event-name" className="text-xs">
                    Event name
                  </Label>
                  <div className="flex flex-wrap items-center gap-2">
                    <Input
                      id="event-name"
                      value={nameDraft}
                      onChange={(e) => setNameDraft(e.target.value)}
                      className="max-w-xs"
                    />
                    <Button
                      type="button"
                      size="sm"
                      disabled={renameEvent.isPending}
                      onClick={saveRename}
                    >
                      {renameEvent.isPending ? "Saving…" : "Save"}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={renameEvent.isPending}
                      onClick={() => setRenaming(false)}
                    >
                      Cancel
                    </Button>
                  </div>
                  {renameError ? (
                    <p className="text-sm text-destructive" role="alert">
                      {renameError}
                    </p>
                  ) : null}
                </div>
              ) : (
                <div className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-2">
                    <CardTitle>{event.name}</CardTitle>
                    {finalized ? (
                      <span
                        className="rounded-full border border-emerald-500/50 bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:text-emerald-400"
                        data-testid="finalized-badge"
                      >
                        Finalized
                      </span>
                    ) : null}
                  </span>
                  {finalized ? null : (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setRenameError(null);
                        setNameDraft(event.name);
                        setRenaming(true);
                      }}
                    >
                      Rename
                    </Button>
                  )}
                </div>
              )}
              <CardDescription>
                {EVENT_TYPE_LABELS[event.type]}
                {" · "}
                {event.startDate} – {event.endDate} · Event ID:{" "}
                <code className="font-mono font-medium text-foreground">
                  {event.publicCode}
                </code>
                {" · "}
                <PublicPageLink to={`/events/${event.publicCode}`}>
                  Public page
                </PublicPageLink>
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {/* Club (#319): set, change, or clear the event's club. */}
              <div className="space-y-1">
                <Label
                  htmlFor="event-club-edit"
                  className="text-xs font-medium uppercase text-muted-foreground"
                >
                  Club
                </Label>
                <select
                  id="event-club-edit"
                  value={event.clubId ?? ""}
                  disabled={setClub.isPending || locked}
                  onChange={(e) => saveClub(e.target.value)}
                  className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
                >
                  <option value="">No club (Open)</option>
                  {clubs.map((club) => (
                    <option key={club.id} value={club.id}>
                      {club.name}
                    </option>
                  ))}
                </select>
                {clubError ? (
                  <p className="text-sm text-destructive" role="alert">
                    {clubError}
                  </p>
                ) : null}
              </div>

              <div className="text-xs font-medium uppercase text-muted-foreground">
                Participants
              </div>
              {participants.length > 0 ? (
                <ul className="space-y-1 text-sm">
                  {participants.map((p) => {
                    const meta = participantMeta(p);
                    return (
                      <li
                        key={p.userId}
                        className="flex items-center justify-between gap-2"
                      >
                        <span className="min-w-0">
                          <span className="block">
                            {playerLabel(p.displayName, p.publicCode, p.userId)}
                            {p.publicCode ? (
                              <span className="text-muted-foreground">
                                {" "}
                                ({p.publicCode})
                              </span>
                            ) : null}
                          </span>
                          {meta ? (
                            <span className="block text-xs text-muted-foreground">
                              {meta}
                            </span>
                          ) : null}
                        </span>
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          disabled={removeParticipant.isPending}
                          onClick={() =>
                            removeParticipant.mutate({
                              id: eventId,
                              userId: p.userId,
                            })
                          }
                        >
                          Remove
                        </Button>
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <p className="text-sm text-muted-foreground">
                  No participants yet.
                </p>
              )}
              {locked ? null : (
                <div className="space-y-1">
                  <PlayerPicker
                    label="Add a participant"
                    placeholder="Search players…"
                    excludeIds={allParticipants.map((p) => p.userId)}
                    onSelect={(user) => {
                      setRosterError(null);
                      addParticipant.mutate(
                        { id: eventId, data: { userId: user.id } },
                        {
                          onError: () =>
                            setRosterError("Could not add that participant."),
                        },
                      );
                    }}
                  />
                  {rosterError ? (
                    <p className="text-sm text-destructive" role="alert">
                      {rosterError}
                    </p>
                  ) : null}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Points config (#466): opt-in "Award ranking points" checkbox on every event. */}
          <Card>
            <CardHeader>
              <CardTitle>Points config</CardTitle>
              <CardDescription>
                The per-match reward window a fixture may designate within, and
                how long an awarded point stays valid.
                {globalPolicy ? (
                  <>
                    {" "}
                    The global {EVENT_TYPE_LABELS[event.type]} policy allows{" "}
                    {globalPolicy.minPoints}–
                    {globalPolicy.maxPoints} points and up to{" "}
                    {globalPolicy.maxValidityDays} validity days.
                  </>
                ) : null}
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {hasPointsConfig ? (
                <p className="text-sm" data-testid="points-config-summary">
                  Currently {event.minPointsPerMatch}–
                  {event.maxPointsPerMatch} points, valid{" "}
                  {event.pointValidityStart} – {event.pointValidityEnd}.
                </p>
              ) : (
                <p className="text-sm text-muted-foreground">
                  This event awards no points.
                </p>
              )}
              {locked ? null : (
                <form onSubmit={savePointsConfig} className="grid gap-3">
                  {/* Opt-in checkbox (#466): un-ticking clears the config (and its fixtures' points). */}
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={awardPointsDraft}
                      onChange={(e) => {
                        setAwardPointsDraft(e.target.checked);
                        setPointsConfigSaved(false);
                      }}
                      aria-label="Award ranking points"
                    />
                    Award ranking points
                  </label>
                  {awardPointsDraft ? (
                  <>
                  <div className="grid grid-cols-2 gap-2">
                    <div className="space-y-1">
                      <Label htmlFor="points-min" className="text-xs">
                        Min points
                      </Label>
                      <Input
                        id="points-min"
                        type="number"
                        value={minDraft}
                        placeholder={
                          event.minPointsPerMatch != null
                            ? String(event.minPointsPerMatch)
                            : globalPolicy
                              ? String(globalPolicy.minPoints)
                              : ""
                        }
                        onChange={(e) => {
                          setMinDraft(e.target.value);
                          setPointsConfigSaved(false);
                        }}
                      />
                    </div>
                    <div className="space-y-1">
                      <Label htmlFor="points-max" className="text-xs">
                        Max points
                      </Label>
                      <Input
                        id="points-max"
                        type="number"
                        value={maxDraft}
                        placeholder={
                          event.maxPointsPerMatch != null
                            ? String(event.maxPointsPerMatch)
                            : globalPolicy
                              ? String(globalPolicy.maxPoints)
                              : ""
                        }
                        onChange={(e) => {
                          setMaxDraft(e.target.value);
                          setPointsConfigSaved(false);
                        }}
                      />
                    </div>
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <div className="space-y-1">
                      <Label htmlFor="points-valid-from" className="text-xs">
                        Validity start
                      </Label>
                      <Input
                        id="points-valid-from"
                        type="date"
                        value={validityStartDraft}
                        onChange={(e) => {
                          setValidityStartDraft(e.target.value);
                          setPointsConfigSaved(false);
                        }}
                      />
                    </div>
                    <div className="space-y-1">
                      <Label htmlFor="points-valid-to" className="text-xs">
                        Validity end
                      </Label>
                      <Input
                        id="points-valid-to"
                        type="date"
                        value={validityEndDraft}
                        onChange={(e) => {
                          setValidityEndDraft(e.target.value);
                          setPointsConfigSaved(false);
                        }}
                      />
                    </div>
                  </div>
                  </>
                  ) : null}
                  <div className="flex items-center gap-2">
                    <Button
                      type="submit"
                      size="sm"
                      disabled={setPointsConfig.isPending}
                    >
                      {setPointsConfig.isPending
                        ? "Saving…"
                        : "Save points config"}
                    </Button>
                    {pointsConfigSaved ? (
                      <span
                        className="text-xs text-muted-foreground"
                        role="status"
                      >
                        Saved
                      </span>
                    ) : null}
                  </div>
                  {pointsConfigError ? (
                    <p className="text-sm text-destructive" role="alert">
                      {pointsConfigError}
                    </p>
                  ) : null}
                </form>
              )}
            </CardContent>
          </Card>

          {requests.length > 0 ? (
            <Card>
              <CardHeader>
                <CardTitle>Join requests</CardTitle>
                <CardDescription>
                  Players who signed up from the shared link. Approve to add
                  them to the roster, or hold to set aside (you can approve a
                  held request later).
                </CardDescription>
              </CardHeader>
              <CardContent>
                <ul className="space-y-1 text-sm">
                  {requests.map((p) => {
                    const meta = participantMeta(p);
                    return (
                      <li
                        key={p.userId}
                        className="flex items-center justify-between gap-2"
                      >
                        <span className="min-w-0">
                          <span className="block">
                            {playerLabel(p.displayName, p.publicCode, p.userId)}
                            {p.status === "HOLD" ? (
                              <span className="text-muted-foreground">
                                {" "}
                                · on hold
                              </span>
                            ) : null}
                          </span>
                          {meta ? (
                            <span className="block text-xs text-muted-foreground">
                              {meta}
                            </span>
                          ) : null}
                        </span>
                        <span className="flex shrink-0 items-center gap-2">
                          <Button
                            type="button"
                            size="sm"
                            disabled={decideParticipant.isPending || locked}
                            onClick={() =>
                              decideParticipant.mutate({
                                id: eventId,
                                userId: p.userId,
                                data: { status: "APPROVED" },
                              })
                            }
                          >
                            Approve
                          </Button>
                          {p.status === "HOLD" ? null : (
                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              disabled={decideParticipant.isPending || locked}
                              onClick={() =>
                                decideParticipant.mutate({
                                  id: eventId,
                                  userId: p.userId,
                                  data: { status: "HOLD" },
                                })
                              }
                            >
                              Hold
                            </Button>
                          )}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </CardContent>
            </Card>
          ) : null}

          {finalized ? (
            <p
              role="status"
              className="rounded-md border border-emerald-500/50 bg-emerald-500/10 px-3 py-2 text-sm"
            >
              This event is finalized. It is closed to changes and its matches
              have been queued for rating.
            </p>
          ) : readOnly ? (
            <p
              role="status"
              className="rounded-md border border-amber-500/50 bg-amber-500/10 px-3 py-2 text-sm"
            >
              This event has ended. Ask an administrator or club owner to add
              participants, schedule fixtures, or record results.
            </p>
          ) : null}

          {locked ? null : (
            <Card>
              <CardHeader>
                <CardTitle>Schedule a fixture</CardTitle>
                <CardDescription>
                  Every player must be a participant of this event. Pick a
                  format — doubles and mixed doubles need two players a side.
                  Recording results later doesn’t move ratings — that’s the
                  admin calculation step.
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
                        const next = e.target
                          .value as (typeof MATCH_FORMATS)[number];
                        setFormat(next);
                        // Dropping back to singles retires the partner slots so they can't leak into the request.
                        if (next === "SINGLES") {
                          setTeam1b("");
                          setTeam2b("");
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
                          {playerSelect(
                            "event-team1",
                            "Player 1",
                            team1a,
                            setTeam1a,
                          )}
                          {playerSelect(
                            "event-team1b",
                            "Partner 1",
                            team1b,
                            setTeam1b,
                          )}
                        </div>
                        <div className="space-y-1">
                          {playerSelect(
                            "event-team2",
                            "Player 2",
                            team2a,
                            setTeam2a,
                          )}
                          {playerSelect(
                            "event-team2b",
                            "Partner 2",
                            team2b,
                            setTeam2b,
                          )}
                        </div>
                      </>
                    ) : (
                      <>
                        {playerSelect(
                          "event-team1",
                          "Player 1",
                          team1a,
                          setTeam1a,
                        )}
                        {playerSelect(
                          "event-team2",
                          "Player 2",
                          team2a,
                          setTeam2a,
                        )}
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
                        onChange={(e) =>
                          setMatchType(
                            e.target.value as (typeof MATCH_TYPES)[number],
                          )
                        }
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
                  {/* Fixture points (#466): the per-match "Award points for this match" checkbox appears
                      only when the event awards points; default checked. Un-ticking opts this match out. */}
                  {showDesignation ? (
                    <div className="space-y-2">
                      <label className="flex items-center gap-2 text-sm">
                        <input
                          type="checkbox"
                          checked={awardFixturePoints}
                          onChange={(e) =>
                            setAwardFixturePoints(e.target.checked)
                          }
                          aria-label="Award points for this match"
                        />
                        Award points for this match
                      </label>
                      {showDesignationInput ? (
                        <div className="space-y-1">
                          <Label htmlFor="event-designated" className="text-xs">
                            Designated points ({min}–{max})
                          </Label>
                          <Input
                            id="event-designated"
                            type="number"
                            min={min}
                            max={max}
                            value={designatedDraft}
                            placeholder={String(defaultDesignation)}
                            onChange={(e) => setDesignatedDraft(e.target.value)}
                          />
                          <p className="text-xs text-muted-foreground">
                            Each winning-team member gets the full amount (cost ={" "}
                            {designatedValue * teamSize} for {teamSize}
                            {teamSize === 1 ? " player" : " players"}).
                            {clubBudget != null
                              ? ` ${clubBudget.free} points free in this club's ${EVENT_TYPE_LABELS[event.type]} budget.`
                              : event.clubId
                                ? ""
                                : " No club is set, so no budget is reserved."}
                          </p>
                          {designationOutOfRange ? (
                            <p
                              className="text-sm text-destructive"
                              role="alert"
                            >
                              Designated points must be between {min} and {max}.
                            </p>
                          ) : designationOverBudget ? (
                            <p
                              className="text-sm text-destructive"
                              role="alert"
                            >
                              This exceeds the club's remaining free budget.
                            </p>
                          ) : null}
                        </div>
                      ) : null}
                    </div>
                  ) : null}
                  {/* Per-side rating handicap (#486): hidden behind an explicit checkbox with a prudence
                      tooltip. Un-ticking clears both drafts. */}
                  <HandicapField
                    enabled={applyHandicap}
                    onToggle={(on) => {
                      setApplyHandicap(on);
                      if (!on) {
                        setTeam1HandicapDraft("");
                        setTeam2HandicapDraft("");
                      }
                    }}
                    team1Handicap={team1HandicapDraft}
                    team2Handicap={team2HandicapDraft}
                    onTeam1Change={setTeam1HandicapDraft}
                    onTeam2Change={setTeam2HandicapDraft}
                    team1Label="Side 1"
                    team2Label="Side 2"
                  />
                  {handicapOutOfRange ? (
                    <p className="text-sm text-destructive" role="alert">
                      A handicap must be greater than 0 and at most 1.0.
                    </p>
                  ) : null}
                  <Button
                    type="submit"
                    size="sm"
                    disabled={!canSchedule || createFixture.isPending}
                  >
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
          )}

          <AwaitingResultsSection eventId={eventId} readOnly={locked} />
          <RecordedResultsSection eventId={eventId} readOnly={locked} />

          <ShareCard
            url={`${window.location.origin}/events/${event.publicCode}`}
            title="Share this event"
            description="Scan this code or copy the link to open this event's public page."
          />

          {finalized ? null : (
            <Card>
              <CardHeader>
                <CardTitle>Finalize event</CardTitle>
                <CardDescription>
                  Finalizing closes this event to further changes and queues its
                  matches for rating. This cannot be undone.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-2">
                {confirmingFinalize ? (
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      size="sm"
                      disabled={finalizeEvent.isPending}
                      onClick={confirmFinalize}
                    >
                      {finalizeEvent.isPending
                        ? "Finalizing…"
                        : "Confirm finalize"}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={finalizeEvent.isPending}
                      onClick={() => setConfirmingFinalize(false)}
                    >
                      Cancel
                    </Button>
                  </div>
                ) : (
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => {
                      setFinalizeError(null);
                      setConfirmingFinalize(true);
                    }}
                  >
                    Finalize event
                  </Button>
                )}
                {finalizeError ? (
                  <p className="text-sm text-destructive" role="alert">
                    {finalizeError}
                  </p>
                ) : null}
              </CardContent>
            </Card>
          )}

          {finalized ? (
            <Card>
              <CardHeader>
                <CardTitle>Un-finalize event</CardTitle>
                <CardDescription>
                  Un-finalizing reopens this event so an erroneous score can be
                  corrected. It revokes the ranking points this event awarded on
                  finalize. This is refused if any of its matches have already
                  been rated.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-2">
                {confirmingUnfinalize ? (
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="text-destructive hover:text-destructive"
                      disabled={unfinalizeEvent.isPending}
                      onClick={confirmUnfinalize}
                    >
                      {unfinalizeEvent.isPending
                        ? "Un-finalizing…"
                        : "Confirm un-finalize (revokes points)"}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={unfinalizeEvent.isPending}
                      onClick={() => setConfirmingUnfinalize(false)}
                    >
                      Cancel
                    </Button>
                  </div>
                ) : (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setUnfinalizeError(null);
                      setConfirmingUnfinalize(true);
                    }}
                  >
                    Un-finalize event
                  </Button>
                )}
                {unfinalizeError ? (
                  <p className="text-sm text-destructive" role="alert">
                    {unfinalizeError}
                  </p>
                ) : null}
              </CardContent>
            </Card>
          ) : null}

          {finalized && isAdmin ? (
            <Card className="border-destructive/50">
              <CardHeader>
                <CardTitle className="text-destructive">
                  Reverse ratings
                </CardTitle>
                <CardDescription>
                  This is a destructive correction for an event whose matches
                  have already been rated. It restores every participant to their
                  pre-event rating, reverses this event’s rating history, and
                  revokes the ranking points it awarded, then reopens the event so
                  the score can be corrected and re-finalized. It is refused
                  unless this event is at the tip of the rated timeline — no later
                  match may have been rated on top of it.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-2">
                {confirmingReverse ? (
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="border-destructive text-destructive hover:bg-destructive hover:text-destructive-foreground"
                      disabled={reverseRatings.isPending}
                      onClick={confirmReverse}
                    >
                      {reverseRatings.isPending
                        ? "Reversing ratings…"
                        : "Confirm reverse (rewinds ratings, revokes points)"}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={reverseRatings.isPending}
                      onClick={() => setConfirmingReverse(false)}
                    >
                      Cancel
                    </Button>
                  </div>
                ) : (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="border-destructive text-destructive hover:bg-destructive hover:text-destructive-foreground"
                    onClick={() => {
                      setReverseError(null);
                      setConfirmingReverse(true);
                    }}
                  >
                    Reverse ratings
                  </Button>
                )}
                {reverseError ? (
                  <p className="text-sm text-destructive" role="alert">
                    {reverseError}
                  </p>
                ) : null}
              </CardContent>
            </Card>
          ) : null}

          <Card>
            <CardHeader>
              <CardTitle>Delete event</CardTitle>
              <CardDescription>
                An event can be deleted only while it has no recorded matches.
                Delete recorded matches first; an event with rated matches can’t
                be deleted.
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
                    {deleteEvent.isPending ? "Deleting…" : "Confirm delete"}
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
                    setDeleteError(null);
                    setConfirmingDelete(true);
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
  );
}
