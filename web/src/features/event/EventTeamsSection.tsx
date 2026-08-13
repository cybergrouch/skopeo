import { useState, type FormEvent } from "react";
import type {
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
import { playerLabel } from "@/lib/playerLabel";

/**
 * Durable event teams (#720): purely organizational groupings of this event's participants. They
 * don't affect ratings or seeding; they just populate fixtures. Editing/dissolving a team later leaves
 * existing fixtures (which snapshot players) untouched.
 *
 * The new-team draft — the two member slots, the optional name, and the message a refused create came
 * back with — is this component's business; the mutation stays with the surface that owns the event's
 * queries, the same split as `EventHeaderManager`. [onCreate] resolves with the reason a create was
 * refused (or `null` when it landed), so the form keeps the members the host picked instead of
 * clearing them under an error they still have to act on.
 *
 * Membership is exclusive: a participant already on a team is not offered again, which is why the
 * pickers need the whole roster rather than a pre-filtered list — a slot must keep showing its own
 * pick so the selection doesn't vanish mid-edit.
 */
export function EventTeamsSection({
  participants,
  teams,
  creating = false,
  dissolving = false,
  onCreate,
  onDissolve,
}: {
  participants: EventParticipantResponse[];
  teams: EventTeamResponse[];
  creating?: boolean;
  dissolving?: boolean;
  onCreate: (team: {
    memberUserIds: string[];
    name?: string;
  }) => Promise<string | null>;
  onDissolve: (teamId: string) => void;
}) {
  // New-team form (#720): members are drawn from APPROVED participants; name is optional (auto-named).
  const [newTeamA, setNewTeamA] = useState("");
  const [newTeamB, setNewTeamB] = useState("");
  const [newTeamName, setNewTeamName] = useState("");
  const [teamError, setTeamError] = useState<string | null>(null);

  // The participants already on some team (exclusive membership), so the new-team pickers can exclude
  // them. The chosen slots may still show themselves so they don't vanish mid-edit.
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

  async function submitTeam(e: FormEvent) {
    e.preventDefault();
    if (!canCreateTeam) return;
    setTeamError(null);
    const refusal = await onCreate({
      memberUserIds: newTeamMembers,
      // Blank name → the server auto-names from the members' display names.
      ...(newTeamName.trim() !== "" ? { name: newTeamName.trim() } : {}),
    });
    if (refusal !== null) {
      setTeamError(refusal);
      return;
    }
    setNewTeamA("");
    setNewTeamB("");
    setNewTeamName("");
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
    <Card>
      <CardHeader>
        <CardTitle>Teams</CardTitle>
        <CardDescription>
          Group participants into durable teams for this event (1 or 2 players
          each — 2 is the usual case). Teams are organizational only — they
          don’t affect ratings or seeding, and they help you fill fixtures
          below.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {teams.length > 0 ? (
          <ul className="space-y-1 text-sm" data-testid="team-list">
            {teams.map((t) => (
              <li key={t.id} className="flex items-center justify-between gap-2">
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
                  disabled={dissolving}
                  onClick={() => onDissolve(t.id)}
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
          <Button type="submit" size="sm" disabled={!canCreateTeam || creating}>
            Create team
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
