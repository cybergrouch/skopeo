import { useMemo, useState, type FormEvent } from "react";
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
  getGetApiV1EventsQueryKey,
  usePostApiV1Events,
} from "@/api/generated/events/events";
import { getGetApiV1ClubsCodeCodeQueryKey, useGetApiV1Clubs } from "@/api/generated/clubs/clubs";
import { useGetApiV1Circuits } from "@/api/generated/circuits/circuits";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import { useGetApiV1SettingsAwardRankingPoints } from "@/api/generated/settings/settings";
import type {
  ClubResponse,
  CreateEventRequestFormat,
  UserSummaryResponse,
} from "@/api/generated/model";
import {
  Capability,
  hasCapability,
  canRate,
  isAdministrator,
} from "@/auth/capabilities";
import { ownedClubs } from "@/auth/clubAccess";
import { PlayerPicker } from "@/components/PlayerPicker";
import { playerLabel } from "@/lib/playerLabel";
import { PlaceholderTag } from "@/components/PlaceholderTag";

/** The event classes a host can pick at creation (#403); mirrors the backend EventType enum. */
type EventType = "OPEN_PLAY" | "TOURNAMENT";

const EVENT_TYPE_OPTIONS: ReadonlyArray<{ value: EventType; label: string }> = [
  { value: "OPEN_PLAY", label: "Open play" },
  { value: "TOURNAMENT", label: "Tournament" },
];

/** The organizing formats a host can pick at creation (#720); mirrors the backend TeamType enum. */
const EVENT_FORMAT_OPTIONS: ReadonlyArray<{
  value: CreateEventRequestFormat;
  label: string;
}> = [
  { value: "SINGLES", label: "Singles" },
  { value: "DOUBLES", label: "Doubles" },
  { value: "MIXED_DOUBLES", label: "Mixed doubles" },
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
  const owned = ownedClubs(clubs, meId);
  return owned.length === 1 ? owned[0].id : "";
}

/**
 * The new-event roster being assembled before the event is created.
 *
 * Shared by the Event Organizer tab and a club's own public page (#780). Passing [fixedClubPublicCode]
 * files the event under that club and hides the Club selector entirely: the page already answers the
 * question, so offering the choice again (and the CLUB_OWNER default of #364, which exists precisely to
 * guess when the context is ambiguous) would only invite filing it somewhere else by accident.
 *
 * The club is identified by its PUBLIC code rather than its id because the caller is a public page, whose
 * payload deliberately carries no internal ids. The id is resolved from the clubs list this form already
 * loads — safe here because the form only renders for a match manager, who can read that endpoint — and
 * submission is blocked until that resolution lands, so a create can never silently file as "Open".
 *
 * [publicCodeToRefresh] is the club public code whose page query should be invalidated after a create, so
 * a new event appears in the club page's own listing without a reload.
 */
export function NewEventForm({
  fixedClubPublicCode,
  publicCodeToRefresh,
}: {
  fixedClubPublicCode?: string;
  publicCodeToRefresh?: string;
} = {}) {
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
  // The event's organizing format (#720): REQUIRED. Sets durable team size and the default fixture format.
  const [format, setFormat] = useState<CreateEventRequestFormat>("SINGLES");
  // The circuit a TOURNAMENT belongs to (#525); required for tournaments, ignored otherwise.
  const [circuitId, setCircuitId] = useState("");
  // "Award Ranking Points" checkbox (#559): when set, finalizing the event awards ranking points per the
  // global schedules; unticking opts the whole event out of awarding.
  // TEMPORARY (#567): defaulted OFF during the testing phase so new events don't award points while we
  // validate finalize behaviour. Revert this default back to `true` once we're ready to go live.
  const [awardRankingPoints, setAwardRankingPoints] = useState(false);

  // Clubs to optionally file the event under (#313). Readable by staff; empty when none exist.
  const clubsData = useGetApiV1Clubs().data;
  const clubs = clubsData ?? [];
  // Circuits to file a TOURNAMENT under (#525). Staff-readable; empty when none exist.
  const circuits = useGetApiV1Circuits().data ?? [];
  const me = useGetApiV1UsersMe().data;
  // Award-points checkbox is gated behind a feature flag (#641), default off — only show it when an
  // admin has explicitly enabled it; while loading / unset it stays hidden (matching the backend default).
  const awardPointsEnabled =
    useGetApiV1SettingsAwardRankingPoints({ query: { retry: false } }).data?.enabled === true;

  // Default the selector to a CLUB_OWNER's own club (#364), but only while the field is untouched;
  // once the user selects anything (including "Open") their choice wins.
  const ownerDefault = useMemo(
    () => defaultOwnedClubId(clubsData ?? [], me?.id, me?.capabilities),
    [clubsData, me?.id, me?.capabilities],
  );
  // The clubs this caller may actually FILE under (#789): an ADMINISTRATOR any club, anyone else only
  // the clubs they are a named owner of. The server refuses the rest, so offering them would only hand
  // out a 403; the selector shows the reachable subset instead.
  const fileableClubs = useMemo(
    () =>
      isAdministrator(me?.capabilities)
        ? clubs
        : ownedClubs(clubs, me?.id),
    [clubs, me?.id, me?.capabilities],
  );
  // A fixed club wins outright over both the picker and the #364 owner default.
  const fixedClub = fixedClubPublicCode
    ? clubs.find((club) => club.publicCode === fixedClubPublicCode)
    : undefined;
  const clubId = fixedClubPublicCode
    ? (fixedClub?.id ?? "")
    : (clubIdChoice ?? ownerDefault);

  const create = usePostApiV1Events({
    mutation: {
      onSuccess: () => {
        // Confirm the create (#667) before the fields reset — `name` is still the submitted value here.
        toast.success(`Created event “${name.trim()}”.`);
        setName("");
        setStartDate("");
        setEndDate("");
        setClubIdChoice(undefined);
        setRoster([]);
        setType("OPEN_PLAY");
        setFormat("SINGLES");
        setCircuitId("");
        setAwardRankingPoints(true);
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1EventsQueryKey(),
        });
        if (publicCodeToRefresh) {
          void queryClient.invalidateQueries({
            queryKey: getGetApiV1ClubsCodeCodeQueryKey(publicCodeToRefresh),
          });
        }
      },
    },
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate(
      {
        data: {
          name,
          startDate,
          endDate,
          type,
          format,
          participantIds: roster.map((u) => u.id),
          ...(clubId ? { clubId } : {}),
          ...(type === "TOURNAMENT" ? { circuitId } : {}),
          // A single "Award Ranking Points" flag (#559) replaces the old per-event points config.
          awardRankingPoints,
        },
      },
      {
        onError: () =>
          toast.error(
            "Could not create the event. Check the name and dates.",
            { duration: 8000 },
          ),
      },
    );
  }

  const canCreate =
    name.trim() !== "" &&
    startDate !== "" &&
    endDate !== "" &&
    format !== ("" as CreateEventRequestFormat) &&
    (type !== "TOURNAMENT" || circuitId !== "") &&
    // Never submit a fixed-club form before the club id resolves — it would file the event as "Open".
    (!fixedClubPublicCode || Boolean(fixedClub));

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
          {/* Organizing format (#720): required; sets durable team size and the default fixture format. */}
          <div className="space-y-1">
            <Label htmlFor="event-format" className="text-xs">
              Format
            </Label>
            <select
              id="event-format"
              value={format}
              onChange={(e) =>
                setFormat(e.target.value as CreateEventRequestFormat)
              }
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
            >
              {EVENT_FORMAT_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          {/* Circuit picker (#525): a tournament must belong to a circuit; shown only for TOURNAMENT. */}
          {type === "TOURNAMENT" ? (
            <div className="space-y-1">
              <Label htmlFor="event-circuit" className="text-xs">
                Circuit
              </Label>
              <select
                id="event-circuit"
                value={circuitId}
                onChange={(e) => setCircuitId(e.target.value)}
                className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
              >
                <option value="">Select a circuit…</option>
                {circuits.map((circuit) => (
                  <option key={circuit.id} value={circuit.id}>
                    {circuit.name}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
          {/* Hidden entirely when the club is fixed by the surface (#780) — the club page's form files
              under its own club, so there is nothing to choose. */}
          {!fixedClubPublicCode && fileableClubs.length > 0 ? (
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
                {fileableClubs.map((club) => (
                  <option key={club.id} value={club.id}>
                    {club.name}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
          {/* "Award Ranking Points" checkbox (#559): when set, finalizing the event awards ranking points
              per the global schedules. Gated behind an admin feature flag (#641, default off) so hosts
              can't opt an event into awarding until it's enabled; hidden → the payload stays false. */}
          {awardPointsEnabled ? (
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={awardRankingPoints}
                onChange={(e) => setAwardRankingPoints(e.target.checked)}
                aria-label="Award Ranking Points"
              />
              Award Ranking Points
            </label>
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
        </form>
      </CardContent>
    </Card>
  );
}