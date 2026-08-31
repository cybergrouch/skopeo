import { useMemo, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { toastError } from "@/observability/toastError";
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
  CreateEventRequestFormat,
  UserSummaryResponse,
} from "@/api/generated/model";
import { canRate } from "@/auth/capabilities";
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
  clubPublicCode,
  publicCodeToRefresh,
}: {
  clubPublicCode: string;
  publicCodeToRefresh?: string;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [roster, setRoster] = useState<UserSummaryResponse[]>([]);
  // The event's class (#403); OPEN_PLAY is the default and the backward-compatible choice.
  const [type, setType] = useState<EventType>("OPEN_PLAY");
  // The event's organizing format (#720): REQUIRED. Sets durable team size and the default fixture format.
  const [format, setFormat] = useState<CreateEventRequestFormat>("SINGLES");
  // The circuit a TOURNAMENT belongs to (#525); required for tournaments, ignored otherwise.
  const [circuitId, setCircuitId] = useState("");
  // "Award Ranking Points" checkbox (#559): when set, finalizing the event awards ranking points per the
  // global schedules; unticking opts the whole event out of awarding.
  // Defaults ON (#831), so a host must deliberately opt an event out rather than remember to opt in —
  // forgetting the tick would otherwise cost their players points with no error and nothing to notice.
  // (This is the revert #567 asked for; it defaulted OFF while finalize behaviour was being validated.)
  // The submit payload is still gated on the feature flag, so a hidden checkbox cannot send `true`.
  const [awardRankingPoints, setAwardRankingPoints] = useState(true);

  // Clubs to optionally file the event under (#313). Readable by staff; empty when none exist.
  const clubsData = useGetApiV1Clubs().data;
  const clubs = useMemo(() => clubsData ?? [], [clubsData]);
  // Circuits to file a TOURNAMENT under (#525). Staff-readable; empty when none exist.
  const circuits = useGetApiV1Circuits().data ?? [];
  const me = useGetApiV1UsersMe().data;
  // Award-points checkbox is gated behind a feature flag (#641), default off — only show it when an
  // admin has explicitly enabled it; while loading / unset it stays hidden (matching the backend default).
  const awardPointsEnabled =
    useGetApiV1SettingsAwardRankingPoints({ query: { retry: false } }).data?.enabled === true;

  // Default the selector to a CLUB_OWNER's own club (#364), but only while the field is untouched;
  // once the user selects anything (including "Open") their choice wins.
  // The club comes from the surface, resolved by its public code — the caller is a public page, whose
  // payload carries no internal ids. Reading it from the clubs list is safe because the form only renders
  // for a match manager, who may read that endpoint.
  const club = clubs.find((c) => c.publicCode === clubPublicCode);
  const clubId = club?.id ?? "";

  const create = usePostApiV1Events({
    mutation: {
      onSuccess: () => {
        // Confirm the create (#667) before the fields reset — `name` is still the submitted value here.
        toast.success(`Created event “${name.trim()}”.`);
        setName("");
        setStartDate("");
        setEndDate("");
        setRoster([]);
        setType("OPEN_PLAY");
        setFormat("SINGLES");
        setCircuitId("");
        // Back to the default for the next event, matching the initial state (#831).
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
          clubId,
          ...(type === "TOURNAMENT" ? { circuitId } : {}),
          // A single "Award Ranking Points" flag (#559) replaces the old per-event points config.
          // Gated on the feature flag (#641): while it is off the checkbox is not rendered, so the host was
          // never offered the choice and the event must not claim to award. Gating here rather than in the
          // initial state avoids depending on the flag query having resolved before mount (#831).
          awardRankingPoints: awardPointsEnabled && awardRankingPoints,
        },
      },
      {
        onError: (error) =>
          toastError(
            "Could not create the event. Check the name and dates.",
            { cause: error, duration: 8000 },
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
    // Never submit before the club id resolves, or the create would be rejected for a missing club.
    Boolean(clubId);

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