import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { toastError } from "@/observability/toastError";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import type { CreateFixtureRequest } from "@/api/generated/model";
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
import { SeedingTable } from "@/components/SeedingTable";
import { ShareCard } from "@/components/ShareCard";
import {
  AwaitingResultsSection,
  RecordedResultsSection,
} from "@/routes/dashboard/matches/AwaitingResultsSection";
import { EventClubSection } from "./EventClubSection";
import { EventFixtureForm } from "./EventFixtureForm";
import { EventHeaderManager } from "./EventHeaderManager";
import { EventJoinRequestsSection } from "./EventJoinRequestsSection";
import { EventLifecycleActions } from "./EventLifecycleActions";
import { EventRankingPointsCard } from "./EventRankingPointsCard";
import { EventParticipantList } from "./EventParticipantList";
import { EventTeamsSection } from "./EventTeamsSection";

/** Prefer the server's message (e.g. the 409 delete-guard advice), falling back to a generic one. */
function eventErrorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: string } } })
    ?.response?.data?.message;
  return message && message.trim() !== "" ? message : fallback;
}

/**
 * One event's working page (#138): the same matches UI as the global tab, but the fixture's player
 * pickers are scoped to this event's participants (and the API enforces it). Hosts manage the roster
 * here and record results below.
 *
 * The sections are siblings under `features/event/` (#741) rather than markup inlined here, and this
 * component keeps what they all share: the event's queries, every mutation, and the two gates
 * (`readOnly`, `finalized`) that decide what may still be edited. A section is handed data plus an
 * explicit permission or disabled flag, so no section works out for itself who the viewer is.
 */
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
  // Reverse Ratings (#478): a distinct, destructive, ADMINISTRATOR-only action for an already-rated event.
  const isAdmin = isAdministrator(me?.capabilities);
  // The club is the one finalized-event field an ADMINISTRATOR may still change (#782): re-filing an event
  // under another club is not an input to the rating calculation, so nothing needs recalculating and the
  // event stays finalized. Everyone else keeps the terminal rule, matching what the server enforces.
  const clubLocked = readOnly || (finalized && !isAdmin);
  // A finalized TOURNAMENT that opted into points already paid its placement schedule under the previous
  // club's sanctioning, and re-filing does not re-price it (#782). Say so rather than letting the change
  // look like an ordinary edit.
  const clubChangeLeavesPoints =
    finalized && isAdmin && event?.type === "TOURNAMENT" && event?.awardRankingPoints === true;

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
    mutation: { onSuccess: refreshTeams },
  });
  const dissolveTeam = useDeleteApiV1EventsIdTeamsTeamId({
    mutation: { onSuccess: refreshTeams },
  });

  // Reports why a create was refused (or null when it landed) so the Teams form can show the reason
  // beside the members the host picked, instead of clearing a draft they still have to fix.
  function saveTeam(data: {
    memberUserIds: string[];
    name?: string;
  }): Promise<string | null> {
    return new Promise((resolve) => {
      createTeam.mutate(
        { id: eventId, data },
        {
          onSuccess: () => resolve(null),
          onError: (err) =>
            resolve(
              eventErrorMessage(
                err,
                "Could not create the team. Members must be approved participants not already on a team.",
              ),
            ),
        },
      );
    });
  }

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
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1MatchesQueryKey(),
        });
      },
    },
  });

  // Reports whether the fixture was created, so the form clears its draft only on success.
  function scheduleFixture(fixture: CreateFixtureRequest): Promise<boolean> {
    return new Promise((resolve) => {
      createFixture.mutate(
        { data: fixture },
        {
          onSuccess: () => resolve(true),
          onError: (error) => {
            toastError(
              "Could not schedule the fixture. Every player must be a participant and already rated.",
              { cause: error, duration: 8000 },
            );
            resolve(false);
          },
        },
      );
    });
  }

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
        data: { clubId },
      });
    } catch (e) {
      toastError(eventErrorMessage(e, "Could not update the club."), { cause: e,
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
      toastError(eventErrorMessage(e, "Could not rename this event."), { cause: e,
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

  async function runDelete() {
    try {
      await deleteEvent.mutateAsync({ id: eventId });
    } catch (e) {
      toastError(eventErrorMessage(e, "Could not delete this event."), { cause: e,
        duration: 8000,
      });
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
      },
    },
  });

  async function runFinalize() {
    try {
      await finalizeEvent.mutateAsync({ id: eventId });
    } catch (e) {
      toastError(eventErrorMessage(e, "Could not finalize this event."), { cause: e,
        duration: 8000,
      });
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
      },
    },
  });

  async function runUnfinalize() {
    try {
      await unfinalizeEvent.mutateAsync({ id: eventId });
    } catch (e) {
      toastError(eventErrorMessage(e, "Could not un-finalize this event."), { cause: e,
        duration: 8000,
      });
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
      },
    },
  });

  async function runReverseRatings() {
    try {
      await reverseRatings.mutateAsync({ id: eventId });
    } catch (e) {
      toastError(
        eventErrorMessage(e, "Could not reverse this event's ratings."),
        { cause: e, duration: 8000 },
      );
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
      toastError(eventErrorMessage(e, "Could not generate the seeding."), { cause: e,
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
      toastError(eventErrorMessage(e, "Could not save the seeding order."), { cause: e,
        duration: 8000,
      });
    }
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
              {/* Club (#319): set, change, or clear the event's club. Still editable by an
                  ADMINISTRATOR after finalize (#782), unlike every other field on this card. */}
              <EventClubSection
                clubId={event.club?.id}
                clubs={clubs}
                disabled={setClub.isPending || clubLocked}
                onChange={saveClub}
              />
              {finalized && isAdmin ? (
                <p className="text-xs text-muted-foreground">
                  Re-filing a finalized event under another club is a
                  bookkeeping correction — no rating is recalculated and the
                  event stays finalized.
                  {clubChangeLeavesPoints
                    ? " Its placement points were already awarded under the previous club's sanctioning and are left as issued."
                    : ""}
                </p>
              ) : null}

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
                          onError: (error) =>
                            toastError("Could not add that participant.", { cause: error,
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
              This event is finalized. It is closed to changes
              {isAdmin ? " apart from its club" : ""} and its matches have been
              queued for rating.
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
            <EventTeamsSection
              participants={participants}
              teams={teams}
              creating={createTeam.isPending}
              dissolving={dissolveTeam.isPending}
              onCreate={saveTeam}
              onDissolve={(teamId) =>
                dissolveTeam.mutate(
                  { id: eventId, teamId },
                  {
                    onError: (err) =>
                      toastError(
                        eventErrorMessage(err, "Could not dissolve the team."),
                        { cause: err, duration: 8000 },
                      ),
                  },
                )
              }
            />
          )}

          {locked ? null : (
            <EventFixtureForm
              eventId={eventId}
              startDate={event.startDate}
              eventFormat={event.format}
              isTournament={event.type === "TOURNAMENT"}
              participants={participants}
              teams={teams}
              scheduling={createFixture.isPending}
              onSchedule={scheduleFixture}
            />
          )}

          <AwaitingResultsSection eventId={eventId} readOnly={locked} />
          <RecordedResultsSection eventId={eventId} readOnly={locked} />

          <ShareCard
            url={`${window.location.origin}/events/${event.publicCode}`}
            title="Share this event"
            description="Scan this code or copy the link to open this event's public page."
          />

          <EventLifecycleActions
            finalized={finalized}
            canReverseRatings={isAdmin}
            onFinalize={runFinalize}
            finalizing={finalizeEvent.isPending}
            onUnfinalize={runUnfinalize}
            unfinalizing={unfinalizeEvent.isPending}
            onReverseRatings={runReverseRatings}
            reversing={reverseRatings.isPending}
            onDelete={runDelete}
            deleting={deleteEvent.isPending}
          />
        </>
      )}
    </div>
  );
}
