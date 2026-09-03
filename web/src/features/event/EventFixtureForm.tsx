import { useState, type FormEvent } from "react";
import type {
  CreateFixtureRequest,
  EventParticipantResponse,
  EventTeamResponse,
} from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { HandicapField } from "@/components/HandicapField";
import { playerLabel } from "@/lib/playerLabel";

const MATCH_TYPES = ["OPEN_PLAY", "FULL_MATCH", "TOURNAMENT"] as const;
const MATCH_TYPE_LABELS: Record<(typeof MATCH_TYPES)[number], string> = {
  OPEN_PLAY: "Open play",
  FULL_MATCH: "Full match",
  TOURNAMENT: "Tournament",
};

const MATCH_FORMATS = ["SINGLES", "DOUBLES", "MIXED_DOUBLES"] as const;
const MATCH_FORMAT_LABELS: Record<(typeof MATCH_FORMATS)[number], string> = {
  SINGLES: "Singles",
  DOUBLES: "Doubles",
  MIXED_DOUBLES: "Mixed doubles",
};

/**
 * Scheduling one fixture inside an event (#138) — the organizer surface's largest form.
 *
 * It is its own component because it is almost entirely draft: four player slots, two team refs, a
 * format that reshapes the slots, a date, a tournament placement bracket, and two handicaps, plus the
 * rules that decide when that draft is coherent enough to submit. None of that is of any interest to
 * the surface around it, which only needs to know when a fixture should be created — so the drafts
 * live here and the mutation stays with the caller, the same split as `EventHeaderManager`.
 *
 * [onSchedule] reports whether the fixture was created: only then is the draft cleared, so a refused
 * fixture (an unrated player, say) leaves the host's picks in place to correct rather than re-entering.
 *
 * The two seeded defaults are deliberately one-shot. The date starts at the event's start date (#668)
 * and the format at the event's organizing format (#720), both applied during render via React's
 * "adjust state when a prop changes" pattern (no effect) and only while untouched, so the event
 * loading in after the first paint doesn't clobber what the host has already typed.
 */
export function EventFixtureForm({
  eventId,
  startDate,
  eventFormat,
  isTournament,
  participants,
  teams,
  scheduling = false,
  onSchedule,
}: {
  eventId: string;
  startDate?: string;
  eventFormat?: string;
  isTournament: boolean;
  participants: EventParticipantResponse[];
  teams: EventTeamResponse[];
  scheduling?: boolean;
  onSchedule: (fixture: CreateFixtureRequest) => Promise<boolean>;
}) {
  // Two slots per side; the "b" slots are only used (and shown) for doubles/mixed doubles.
  const [team1a, setTeam1a] = useState("");
  const [team1b, setTeam1b] = useState("");
  const [team2a, setTeam2a] = useState("");
  const [team2b, setTeam2b] = useState("");
  const [format, setFormat] =
    useState<(typeof MATCH_FORMATS)[number]>("SINGLES");
  const [matchType, setMatchType] =
    useState<(typeof MATCH_TYPES)[number]>("OPEN_PLAY");
  // Fixture date defaults to the event's start date (#668), and is reset to it (not to blank) after a
  // create so back-to-back fixtures keep the default.
  const [date, setDate] = useState("");
  const [dateSeededFor, setDateSeededFor] = useState<string | undefined>(undefined);
  if (startDate && dateSeededFor !== startDate) {
    setDateSeededFor(startDate);
    if (date === "") setDate(startDate);
  }
  // The fixture format defaults to the event's organizing format (#720), overridable per fixture.
  const [formatSeededFor, setFormatSeededFor] = useState<string | undefined>(undefined);
  if (eventFormat && formatSeededFor !== eventId) {
    setFormatSeededFor(eventId);
    setFormat(eventFormat as (typeof MATCH_FORMATS)[number]);
  }
  // Tournament placement match (#525): mark a fixture as deciding a placement + which bracket.
  const [isPlacement, setIsPlacement] = useState(false);
  const [placementBracket, setPlacementBracket] = useState<
    | "CHAMPIONSHIP_FINALS"
    | "PLATE_FINALS"
  >("CHAMPIONSHIP_FINALS");

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

  const isDoubles = format !== "SINGLES";
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
  const teamFitsFormat = (t: EventTeamResponse) =>
    t.members.length === expectedSideSize;
  const playerCount = (n: number) => `${n} player${n === 1 ? "" : "s"}`;
  const teamOptionLabel = (t: EventTeamResponse) =>
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

  async function scheduleFixture(e: FormEvent) {
    e.preventDefault();
    const created = await onSchedule({
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
    });
    if (!created) return;
    setTeam1a("");
    setTeam1b("");
    setTeam2a("");
    setTeam2b("");
    setTeam1Ref("");
    setTeam2Ref("");
    setDate(startDate ?? "");
    setIsPlacement(false);
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

  return (
    <Card>
      <CardHeader>
        <CardTitle>Schedule a fixture</CardTitle>
        <CardDescription>
          Every player must be a participant of this event. Pick a format —
          doubles and mixed doubles need two players a side. Recording results
          later doesn’t move ratings — that’s the admin calculation step.
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
                const next = e.target.value as (typeof MATCH_FORMATS)[number];
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
              Create teams for this event to pick a whole team as a side instead
              of individual players.
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
                  {playerCount(expectedSideSize)} a side. Pick players instead,
                  or edit a team’s members below.
                </p>
              ) : null}
            </>
          ) : (
            <div className="grid grid-cols-2 gap-2">
              {isDoubles ? (
                <>
                  <div className="space-y-1">
                    {playerSelect("event-team1", "Player 1", team1a, setTeam1a)}
                    {playerSelect("event-team1b", "Partner 1", team1b, setTeam1b)}
                  </div>
                  <div className="space-y-1">
                    {playerSelect("event-team2", "Player 2", team2a, setTeam2a)}
                    {playerSelect("event-team2b", "Partner 2", team2b, setTeam2b)}
                  </div>
                </>
              ) : (
                <>
                  {playerSelect("event-team1", "Player 1", team1a, setTeam1a)}
                  {playerSelect("event-team2", "Player 2", team2a, setTeam2a)}
                </>
              )}
            </div>
          )}
          {teamSizeMismatch ? (
            <p className="text-sm text-destructive" role="alert">
              {mismatchedTeams.map((t) => t?.name).join(" and ")}{" "}
              {mismatchedTeams.length === 1 ? "doesn’t" : "don’t"} match the
              fixture format. {MATCH_FORMAT_LABELS[format]} needs{" "}
              {playerCount(expectedSideSize)} a side. Reselect a team, or change
              the format back.
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
                  setMatchType(e.target.value as (typeof MATCH_TYPES)[number])
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
                          | "PLATE_FINALS",
                      )
                    }
                  >
                    <option value="CHAMPIONSHIP_FINALS">
                      Championship Finals (1st / 2nd)
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
          <Button type="submit" size="sm" disabled={!canSchedule || scheduling}>
            Schedule fixture
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
