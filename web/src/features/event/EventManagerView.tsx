import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
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
  getGetApiV1EventsIdSeedingQueryKey,
  getGetApiV1EventsIdTeamsQueryKey,
  getGetApiV1EventsQueryKey,
  useDeleteApiV1EventsId,
  useDeleteApiV1EventsIdParticipantsUserId,
  useDeleteApiV1EventsIdTeamsTeamId,
  useGetApiV1EventsId,
  useGetApiV1EventsIdSeeding,
  useGetApiV1EventsIdTeams,
  usePatchApiV1EventsId,
  usePostApiV1EventsIdFinalize,
  usePostApiV1EventsIdUnfinalize,
  usePostApiV1EventsIdReverseRatings,
  usePostApiV1EventsIdParticipants,
  usePostApiV1EventsIdParticipantsUserIdDecision,
  usePostApiV1EventsIdSeeding,
  usePostApiV1EventsIdTeams,
  usePutApiV1EventsIdClub,
  usePutApiV1EventsIdSeeding,
} from "@/api/generated/events/events";
import { useGetApiV1Clubs } from "@/api/generated/clubs/clubs";
import {
  getGetApiV1MatchesQueryKey,
  usePostApiV1Matches,
} from "@/api/generated/matches/matches";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import { canEditEndedEvents, canRate, isAdministrator } from "@/auth/capabilities";
import { PlayerPicker } from "@/components/PlayerPicker";
import { HandicapField } from "@/components/HandicapField";
import { playerLabel } from "@/lib/playerLabel";
import { SeedingTable } from "@/components/SeedingTable";
import { ShareCard } from "@/components/ShareCard";
import {
  AwaitingResultsSection,
  RecordedResultsSection,
} from "@/routes/dashboard/matches/AwaitingResultsSection";
import { EventClubSection } from "./EventClubSection";
import { EventHeaderManager } from "./EventHeaderManager";
import { EventJoinRequestsSection } from "./EventJoinRequestsSection";
import { EventRankingPointsCard } from "./EventRankingPointsCard";
import { EventParticipantList } from "./EventParticipantList";

const MATCH_TYPES = ["OPEN_PLAY", "TOURNAMENT"] as const;
const MATCH_TYPE_LABELS: Record<(typeof MATCH_TYPES)[number], string> = {
  OPEN_PLAY: "Open play",
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

export function EventManagerView({ eventId }: { eventId: string }) {
  const navigate = useNavigate();
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
  // Fixture date defaults to the event's start date (#668). Seeded during render (React's "adjust state
  // when a prop changes" pattern — no effect) once the event loads, and only while the field is still
  // untouched, so a date the host already entered isn't clobbered. Reset to the start date after create.
  const [date, setDate] = useState("");
  const [dateSeededFor, setDateSeededFor] = useState<string | undefined>(undefined);
  if (event?.startDate && dateSeededFor !== event.startDate) {
    setDateSeededFor(event.startDate);
    if (date === "") setDate(event.startDate);
  }
  // The fixture format defaults to the event's organizing format (#720), but the host can override it
  // per fixture. Seeded once per event (React's "adjust state when a prop changes" pattern — no effect).
  const [formatSeededFor, setFormatSeededFor] = useState<string | undefined>(undefined);
  if (event?.format && formatSeededFor !== event.id) {
    setFormatSeededFor(event.id);
    setFormat(event.format as (typeof MATCH_FORMATS)[number]);
  }
  // Tournament placement match (#525): mark a fixture as deciding a placement + which bracket.
  const [isPlacement, setIsPlacement] = useState(false);
  const [placementBracket, setPlacementBracket] = useState<
    | "CHAMPIONSHIP_FINALS"
    | "SEMI_FINALS_NO_PLATE"
    | "SEMI_FINALS_WITH_PLATE"
    | "PLATE_FINALS"
  >("CHAMPIONSHIP_FINALS");
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [confirmingFinalize, setConfirmingFinalize] = useState(false);
  const [confirmingUnfinalize, setConfirmingUnfinalize] = useState(false);
  // Reverse Ratings (#478): a distinct, destructive, ADMINISTRATOR-only action for an already-rated event.
  const [confirmingReverse, setConfirmingReverse] = useState(false);
  const isAdmin = isAdministrator(me?.capabilities);

  // Per-side handicap (#486): hidden behind an explicit checkbox (discouraged by design). Un-ticking
  // clears both drafts. Drafts are strings so the number inputs can be cleared while editing.
  const [applyHandicap, setApplyHandicap] = useState(false);
  const [team1HandicapDraft, setTeam1HandicapDraft] = useState("");
  const [team2HandicapDraft, setTeam2HandicapDraft] = useState("");

  // Durable event teams (#720): fill a fixture side from a team ref instead of raw players. The toggle
  // is only offered once at least one team exists; each side then picks a team.
  const [useTeams, setUseTeams] = useState(false);
  const [team1Ref, setTeam1Ref] = useState("");
  const [team2Ref, setTeam2Ref] = useState("");

  // New-team form (#720): members are drawn from APPROVED participants; name is optional (auto-named).
  const [newTeamA, setNewTeamA] = useState("");
  const [newTeamB, setNewTeamB] = useState("");
  const [newTeamName, setNewTeamName] = useState("");
  const [teamError, setTeamError] = useState<string | null>(null);

  // Clubs to (re)assign the event to (#319); staff-readable, empty when none exist.
  const clubs = useGetApiV1Clubs().data ?? [];

  // Durable teams for this event (#720). Empty until any are created; drives the fixture team refs.
  const teamsQuery = useGetApiV1EventsIdTeams(eventId);
  const teams = teamsQuery.data ?? [];

  function refreshTeams() {
    void queryClient.invalidateQueries({
      queryKey: getGetApiV1EventsIdTeamsQueryKey(eventId),
    });
  }

  const createTeam = usePostApiV1EventsIdTeams({
    mutation: {
      onSuccess: () => {
        setNewTeamA("");
        setNewTeamB("");
        setNewTeamName("");
        setTeamError(null);
        refreshTeams();
      },
    },
  });
  const dissolveTeam = useDeleteApiV1EventsIdTeamsTeamId({
    mutation: { onSuccess: refreshTeams },
  });

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
        setTeam1Ref("");
        setTeam2Ref("");
        // Reset to the event's start date (#668), not blank, so back-to-back fixtures keep the default.
        setDate(event?.startDate ?? "");
        setIsPlacement(false);
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
    try {
      await setClub.mutateAsync({
        id: eventId,
        data: { clubId: clubId || null },
      });
    } catch (e) {
      toast.error(eventErrorMessage(e, "Could not update the club."), {
        duration: 8000,
      });
    }
  }

  // Reports whether the rename landed so the header can keep its editor open on failure.
  async function saveRename(name: string): Promise<boolean> {
    try {
      await renameEvent.mutateAsync({ id: eventId, data: { name } });
      return true;
    } catch (e) {
      toast.error(eventErrorMessage(e, "Could not rename this event."), {
        duration: 8000,
      });
      return false;
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
        // The event is gone, so its page is too — return to the organizer list.
        void navigate("/dashboard");
      },
    },
  });

  async function confirmDelete() {
    try {
      await deleteEvent.mutateAsync({ id: eventId });
    } catch (e) {
      toast.error(eventErrorMessage(e, "Could not delete this event."), {
        duration: 8000,
      });
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
    try {
      await finalizeEvent.mutateAsync({ id: eventId });
    } catch (e) {
      toast.error(eventErrorMessage(e, "Could not finalize this event."), {
        duration: 8000,
      });
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
    try {
      await unfinalizeEvent.mutateAsync({ id: eventId });
    } catch (e) {
      toast.error(eventErrorMessage(e, "Could not un-finalize this event."), {
        duration: 8000,
      });
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
    try {
      await reverseRatings.mutateAsync({ id: eventId });
    } catch (e) {
      toast.error(
        eventErrorMessage(e, "Could not reverse this event's ratings."),
        { duration: 8000 },
      );
      setConfirmingReverse(false);
    }
  }

  // Event seeding (#714): the same deterministic seeding + CSV export as the Seeding tab, sourced from
  // this event's APPROVED participants. The GET 404s until one is generated, so entries default to [].
  const eventSeeding = useGetApiV1EventsIdSeeding(eventId);
  const seedingEntries = eventSeeding.data?.entries ?? [];
  const hasSeeding = seedingEntries.length > 0;
  const generateSeeding = usePostApiV1EventsIdSeeding({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsIdSeedingQueryKey(eventId),
        });
      },
    },
  });

  async function onGenerateSeeding() {
    try {
      await generateSeeding.mutateAsync({ id: eventId });
    } catch (e) {
      toast.error(eventErrorMessage(e, "Could not generate the seeding."), {
        duration: 8000,
      });
    }
  }

  const saveSeedingOrder = usePutApiV1EventsIdSeeding({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsIdSeedingQueryKey(eventId),
        });
      },
    },
  });

  async function onSaveSeedingOrder(userIds: string[]) {
    try {
      await saveSeedingOrder.mutateAsync({ id: eventId, data: { userIds } });
    } catch (e) {
      toast.error(eventErrorMessage(e, "Could not save the seeding order."), {
        duration: 8000,
      });
    }
  }

  const isDoubles = format !== "SINGLES";
  // Placement-match input is only meaningful for a tournament (#525).
  const isTournament = event?.type === "TOURNAMENT";
  // Only the slots this format uses; "b" slots participate for doubles/mixed doubles.
  const chosen = isDoubles
    ? [team1a, team1b, team2a, team2b]
    : [team1a, team2a];

  // Fixture format sets the effective side size (#720): 2 for doubles/mixed, 1 for singles.
  const expectedSideSize = isDoubles ? 2 : 1;
  const team1RefObj = teams.find((t) => t.id === team1Ref);
  const team2RefObj = teams.find((t) => t.id === team2Ref);
  // A team can fill a side only when its size matches the fixture's effective format (#736). Since a
  // team may now hold 1 *or* 2 members regardless of the event format (#734), a 1-member team simply
  // can't fill a doubles side. Ineligible teams stay listed but unselectable, labelled with why.
  const teamFitsFormat = (t: (typeof teams)[number]) =>
    t.members.length === expectedSideSize;
  const playerCount = (n: number) => `${n} player${n === 1 ? "" : "s"}`;
  const teamOptionLabel = (t: (typeof teams)[number]) =>
    teamFitsFormat(t)
      ? `${t.name} (${playerCount(t.members.length)})`
      : `${t.name} (${playerCount(t.members.length)} — needs ${expectedSideSize})`;
  const eligibleTeams = teams.filter(teamFitsFormat);
  // Still needed as a backstop: the host can pick a team and *then* switch the fixture format, which
  // leaves an already-selected team stranded at the wrong size.
  const mismatchedTeams = [team1RefObj, team2RefObj].filter(
    (t) => t != null && !teamFitsFormat(t),
  );
  const teamSizeMismatch = useTeams && mismatchedTeams.length > 0;

  function scheduleFixture(e: FormEvent) {
    e.preventDefault();
    createFixture.mutate(
      {
        data: {
          matchFormat: format,
          matchType,
          matchDate: date,
          // A side is EITHER raw players OR a durable team ref (#720), never both.
          ...(useTeams
            ? { team1Id: team1Ref, team2Id: team2Ref }
            : {
                team1: isDoubles ? [team1a, team1b] : [team1a],
                team2: isDoubles ? [team2a, team2b] : [team2a],
              }),
          eventId,
          // Per-side handicap (#486): only sent when the "Apply handicap" box is ticked and non-empty.
          ...(applyHandicap && team1HandicapDraft !== ""
            ? { team1Handicap: team1HandicapDraft }
            : {}),
          ...(applyHandicap && team2HandicapDraft !== ""
            ? { team2Handicap: team2HandicapDraft }
            : {}),
          // Tournament placement match (#525): winner/loser get placement points at finalize.
          ...(isTournament && isPlacement
            ? { isPlacementMatch: true, placementBracket }
            : {}),
        },
      },
      {
        onError: () =>
          toast.error(
            "Could not schedule the fixture. Every player must be a participant and already rated.",
            { duration: 8000 },
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
  const canScheduleTeams =
    team1Ref !== "" && team2Ref !== "" && team1Ref !== team2Ref && !teamSizeMismatch;
  const canSchedulePlayers =
    filled.length === chosen.length && new Set(filled).size === chosen.length;
  const canSchedule =
    (useTeams ? canScheduleTeams : canSchedulePlayers) &&
    date !== "" &&
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
                {p.isPlaceholder ? " (Unclaimed)" : ""}
              </option>
            ))}
        </select>
      </div>
    );
  }

  // Durable teams (#720): the participants already on some team (exclusive membership), so the new-team
  // pickers can exclude them. The chosen slots may still show themselves so they don't vanish mid-edit.
  const takenTeamMemberIds = new Set(
    teams.flatMap((t) => t.members.map((m) => m.userId)),
  );
  // A team may have 1 or 2 members, capped at 2, independent of the event format (#734). Two is the
  // normal case; a one-member team is allowed but degenerate. Build the roster from the non-empty slots.
  const newTeamMembers = [newTeamA, newTeamB].filter((id) => id !== "");
  const canCreateTeam =
    newTeamMembers.length >= 1 &&
    newTeamMembers.length <= 2 &&
    new Set(newTeamMembers).size === newTeamMembers.length;

  function submitTeam(e: FormEvent) {
    e.preventDefault();
    if (!canCreateTeam) return;
    setTeamError(null);
    createTeam.mutate(
      {
        id: eventId,
        data: {
          memberUserIds: newTeamMembers,
          // Blank name → the server auto-names from the members' display names.
          ...(newTeamName.trim() !== "" ? { name: newTeamName.trim() } : {}),
        },
      },
      {
        onError: (err) =>
          setTeamError(
            eventErrorMessage(
              err,
              "Could not create the team. Members must be approved participants not already on a team.",
            ),
          ),
      },
    );
  }

  // One member dropdown for the new-team form, scoped to APPROVED participants, excluding those already
  // on a team and whoever's picked in the other slot.
  function teamMemberSelect(
    id: string,
    label: string,
    value: string,
    onChange: (v: string) => void,
    otherValue: string,
  ) {
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
              (p) =>
                p.userId === value ||
                (!takenTeamMemberIds.has(p.userId) && p.userId !== otherValue),
            )
            .map((p) => (
              <option key={p.userId} value={p.userId}>
                {playerLabel(p.displayName, p.publicCode, p.userId)}
                {p.isPlaceholder ? " (Unclaimed)" : ""}
              </option>
            ))}
        </select>
      </div>
    );
  }

  return (
    <div className="grid gap-4">
      {eventQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading event…</p>
      ) : !event ? (
        <p className="text-sm text-muted-foreground">
          This event could not be loaded.
        </p>
      ) : (
        <>
          <Card>
            <EventHeaderManager
              event={event}
              finalized={finalized}
              onRename={saveRename}
              renaming={renameEvent.isPending}
            />
            <CardContent className="space-y-3">
              {/* Club (#319): set, change, or clear the event's club. */}
              <EventClubSection
                clubId={event.clubId}
                clubs={clubs}
                disabled={setClub.isPending || locked}
                onChange={saveClub}
              />

              <div className="text-xs font-medium uppercase text-muted-foreground">
                Participants
              </div>
              <EventParticipantList
                participants={participants}
                showCodes
                removing={removeParticipant.isPending}
                onRemove={
                  locked
                    ? undefined
                    : (userId) => removeParticipant.mutate({ id: eventId, userId })
                }
              />
              {locked ? null : (
                <div className="space-y-1">
                  <PlayerPicker
                    label="Add a participant"
                    placeholder="Search players…"
                    excludeIds={allParticipants.map((p) => p.userId)}
                    canSetRating={canRate(me?.capabilities)}
                    onSelect={(user) => {
                      addParticipant.mutate(
                        { id: eventId, data: { userId: user.id } },
                        {
                          onError: () =>
                            toast.error("Could not add that participant.", {
                              duration: 8000,
                            }),
                        },
                      );
                    }}
                  />
                </div>
              )}
            </CardContent>
          </Card>

          {/* Ranking points (#559): a single per-event flag, set at creation. Read-only here. */}
          <EventRankingPointsCard awards={event.awardRankingPoints} />

          {/* Seeding (#714): generate a deterministic, server-sorted seeding from this event's approved
              participants and export it as CSV — the same flow as the Seeding tab. */}
          <Card>
            <CardHeader>
              <CardTitle>Seeding</CardTitle>
              <CardDescription>
                Generate a rating-sorted seeding from this event's approved
                participants and export it as CSV. Regenerating refreshes it from
                the current roster.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {!hasSeeding ? (
                <Button
                  type="button"
                  size="sm"
                  disabled={generateSeeding.isPending}
                  onClick={onGenerateSeeding}
                >
                  {generateSeeding.isPending
                    ? "Generating…"
                    : "Generate seeding"}
                </Button>
              ) : null}
              <SeedingTable
                entries={seedingEntries}
                generatedAt={eventSeeding.data?.generatedAt ?? ""}
                name={event.name}
                emptyMessage="No seeding yet. Generate one from the approved participants above."
                onSaveOrder={onSaveSeedingOrder}
                savingOrder={saveSeedingOrder.isPending}
                onRegenerate={onGenerateSeeding}
                regenerating={generateSeeding.isPending}
                manuallyEdited={eventSeeding.data?.manuallyEdited ?? false}
              />
            </CardContent>
          </Card>

          <EventJoinRequestsSection
            requests={requests}
            disabled={decideParticipant.isPending || locked}
            onDecide={(userId, status) =>
              decideParticipant.mutate({ id: eventId, userId, data: { status } })
            }
          />

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

          {/* Durable teams (#720): purely organizational groupings of this event's participants. They
              don't affect ratings or seeding; they just populate fixtures. Editing/dissolving a team
              later leaves existing fixtures (which snapshot players) untouched. */}
          {locked ? null : (
            <Card>
              <CardHeader>
                <CardTitle>Teams</CardTitle>
                <CardDescription>
                  Group participants into durable teams for this event (1 or 2
                  players each — 2 is the usual case). Teams are organizational
                  only — they don’t affect ratings or seeding, and they help you
                  fill fixtures below.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-3">
                {teams.length > 0 ? (
                  <ul className="space-y-1 text-sm" data-testid="team-list">
                    {teams.map((t) => (
                      <li
                        key={t.id}
                        className="flex items-center justify-between gap-2"
                      >
                        <span className="min-w-0">
                          <span className="block font-medium">{t.name}</span>
                          <span className="block text-xs text-muted-foreground">
                            {t.members
                              .map((m) =>
                                playerLabel(m.displayName, m.publicCode, m.userId),
                              )
                              .join(" / ")}
                          </span>
                        </span>
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          disabled={dissolveTeam.isPending}
                          onClick={() =>
                            dissolveTeam.mutate(
                              { id: eventId, teamId: t.id },
                              {
                                onError: (err) =>
                                  toast.error(
                                    eventErrorMessage(
                                      err,
                                      "Could not dissolve the team.",
                                    ),
                                    { duration: 8000 },
                                  ),
                              },
                            )
                          }
                        >
                          Dissolve
                        </Button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-sm text-muted-foreground">No teams yet.</p>
                )}
                <form onSubmit={submitTeam} className="grid gap-3">
                  <div className="grid grid-cols-2 gap-2">
                    {teamMemberSelect(
                      "team-member-a",
                      "Member 1",
                      newTeamA,
                      setNewTeamA,
                      newTeamB,
                    )}
                    {teamMemberSelect(
                      "team-member-b",
                      "Member 2 (optional)",
                      newTeamB,
                      setNewTeamB,
                      newTeamA,
                    )}
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="team-name" className="text-xs">
                      Team name (optional)
                    </Label>
                    <Input
                      id="team-name"
                      value={newTeamName}
                      onChange={(e) => setNewTeamName(e.target.value)}
                      placeholder="Auto-named from members if left blank"
                    />
                  </div>
                  {teamError ? (
                    <p className="text-sm text-destructive" role="alert">
                      {teamError}
                    </p>
                  ) : null}
                  <Button
                    type="submit"
                    size="sm"
                    disabled={!canCreateTeam || createTeam.isPending}
                  >
                    Create team
                  </Button>
                </form>
              </CardContent>
            </Card>
          )}

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
                  {/* Players-vs-team toggle (#720): offered once teams exist. When on, each side is a
                      team ref; the team's size must match the fixture's (overridable) format. When no
                      teams exist yet, say so rather than hiding the capability entirely (#736). */}
                  {teams.length > 0 ? (
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={useTeams}
                        onChange={(e) => setUseTeams(e.target.checked)}
                        aria-label="Pick sides from teams"
                      />
                      Pick sides from teams
                    </label>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      Create teams for this event to pick a whole team as a side
                      instead of individual players.
                    </p>
                  )}
                  {useTeams ? (
                    <>
                      <div className="grid grid-cols-2 gap-2">
                        <div className="space-y-1">
                          <Label htmlFor="fixture-team1" className="text-xs">
                            Team 1
                          </Label>
                          <select
                            id="fixture-team1"
                            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                            value={team1Ref}
                            onChange={(e) => setTeam1Ref(e.target.value)}
                          >
                            <option value="">Select a team…</option>
                            {teams.map((t) => (
                              <option
                                key={t.id}
                                value={t.id}
                                disabled={t.id === team2Ref || !teamFitsFormat(t)}
                              >
                                {teamOptionLabel(t)}
                              </option>
                            ))}
                          </select>
                        </div>
                        <div className="space-y-1">
                          <Label htmlFor="fixture-team2" className="text-xs">
                            Team 2
                          </Label>
                          <select
                            id="fixture-team2"
                            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                            value={team2Ref}
                            onChange={(e) => setTeam2Ref(e.target.value)}
                          >
                            <option value="">Select a team…</option>
                            {teams.map((t) => (
                              <option
                                key={t.id}
                                value={t.id}
                                disabled={t.id === team1Ref || !teamFitsFormat(t)}
                              >
                                {teamOptionLabel(t)}
                              </option>
                            ))}
                          </select>
                        </div>
                      </div>
                      {/* Every team is the wrong size for this format — explain instead of showing two
                          dropdowns whose every option is disabled (#736). */}
                      {eligibleTeams.length === 0 ? (
                        <p className="text-sm text-muted-foreground" role="status">
                          No team fits {MATCH_FORMAT_LABELS[format]} — it needs{" "}
                          {playerCount(expectedSideSize)} a side. Pick players
                          instead, or edit a team’s members below.
                        </p>
                      ) : null}
                    </>
                  ) : (
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
                  )}
                  {teamSizeMismatch ? (
                    <p className="text-sm text-destructive" role="alert">
                      {mismatchedTeams.map((t) => t?.name).join(" and ")}{" "}
                      {mismatchedTeams.length === 1 ? "doesn’t" : "don’t"} match
                      the fixture format. {MATCH_FORMAT_LABELS[format]} needs{" "}
                      {playerCount(expectedSideSize)} a side. Reselect a team, or
                      change the format back.
                    </p>
                  ) : null}
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
                  {/* Placement match (#525): only for tournaments. Marks the fixture as a Super/Plate
                      Finals so its winner/loser get placement points (1st/2nd or 3rd/4th) at finalize. */}
                  {isTournament ? (
                    <div className="space-y-2 rounded-md border border-input p-3">
                      <label className="flex items-center gap-2 text-sm">
                        <input
                          type="checkbox"
                          checked={isPlacement}
                          onChange={(e) => setIsPlacement(e.target.checked)}
                          aria-label="Placement match"
                        />
                        Placement match
                      </label>
                      {isPlacement ? (
                        <div className="space-y-1">
                          <Label htmlFor="event-placement" className="text-xs">
                            Placement
                          </Label>
                          <select
                            id="event-placement"
                            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                            value={placementBracket}
                            onChange={(e) =>
                              setPlacementBracket(
                                e.target.value as
                                  | "CHAMPIONSHIP_FINALS"
                                  | "SEMI_FINALS_NO_PLATE"
                                  | "SEMI_FINALS_WITH_PLATE"
                                  | "PLATE_FINALS",
                              )
                            }
                          >
                            <option value="CHAMPIONSHIP_FINALS">
                              Championship Finals (1st / 2nd)
                            </option>
                            <option value="SEMI_FINALS_NO_PLATE">
                              Semi-Finals — no plate (losers → 3rd)
                            </option>
                            <option value="SEMI_FINALS_WITH_PLATE">
                              Semi-Finals — with plate (→ Plate Finals)
                            </option>
                            <option value="PLATE_FINALS">
                              Plate Finals (3rd / 4th)
                            </option>
                          </select>
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
                    onClick={() => setConfirmingFinalize(true)}
                  >
                    Finalize event
                  </Button>
                )}
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
                    onClick={() => setConfirmingUnfinalize(true)}
                  >
                    Un-finalize event
                  </Button>
                )}
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
                    onClick={() => setConfirmingReverse(true)}
                  >
                    Reverse ratings
                  </Button>
                )}
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
                  onClick={() => setConfirmingDelete(true)}
                >
                  Delete event
                </Button>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
