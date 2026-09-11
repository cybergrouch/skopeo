import type { EventParticipantResponse } from "@/api/generated/model";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { usePutApiV1MatchesIdPlayers } from "@/api/generated/matches/matches";
import type { MatchResponse } from "@/api/generated/model";
import { playerLabel } from "@/lib/playerLabel";

/** A side being edited: the chosen ids, in slot order, with the names to show them by. */
interface Side {
  ids: string[];
  names: string[];
}

/**
 * Change who is playing a fixture (#957).
 *
 * Until this, the only way was to delete the fixture and create a new one — which burns the match
 * number (an identifier that is never recycled, #898), leaves a permanent gap in the event's numbering,
 * and requires dragging the replacement back into position.
 *
 * **Both sides are replaced wholesale**, matching the API: a fixture has two sides, and sending both
 * makes the resulting line-up unambiguous where a patch would leave "unchanged" and "cleared" looking
 * the same.
 *
 * **The picker offers only the event's participants** (#971), because the server requires it:
 * `ensureEditableLineUp` refuses anyone else with "All players must be participants of the event".
 * It used to search every player in the system, so most picks failed on save — the #867 shape, an
 * option presented that cannot work.
 *
 * A plain select over the participants the caller already holds, rather than a filtered search. That
 * is how `EventFixtureForm` has always done it, it needs no `eventId` on the search API, and a roster
 * is short enough to list. The earlier note here claimed the filter was impossible for want of those
 * things; the neighbouring form was already the counter-example.
 */
export function EditFixturePlayersDialog({
  match,
  participants,
  nameOf,
  onClose,
}: {
  match: MatchResponse;
  /**
   * The event's roster — the only people this fixture may be crewed from (#971).
   *
   * Required, not optional: the one caller always has it, and a default would quietly restore the
   * global search for any future caller that forgot to pass it.
   */
  participants: EventParticipantResponse[];
  /** Resolves a user id to a display name — MatchSideResponse carries ids only. */
  nameOf: (userId: string) => string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const sideFrom = (userIds: string[]): Side => ({
    ids: userIds,
    names: userIds.map(nameOf),
  });
  const [team1, setTeam1] = useState<Side>(() => sideFrom(match.team1.userIds));
  const [team2, setTeam2] = useState<Side>(() => sideFrom(match.team2.userIds));

  const perSide = match.matchFormat === "SINGLES" ? 1 : 2;
  const complete = team1.ids.length === perSide && team2.ids.length === perSide;
  const changed =
    team1.ids.join() !== match.team1.userIds.join() ||
    team2.ids.join() !== match.team2.userIds.join();

  const save = usePutApiV1MatchesIdPlayers({
    mutation: {
      onSuccess: () => {
        toast.success("Players updated");
        void queryClient.invalidateQueries();
        onClose();
      },
      onError: (err: unknown) => {
        const message = (err as { response?: { data?: { message?: string } } })
          ?.response?.data?.message;
        toast.error(message ?? "Could not change the players");
      },
    },
  });

  function renderSide(
    label: string,
    side: Side,
    set: (s: Side) => void,
    other: Side,
  ) {
    return (
      <div className="space-y-2">
        <div className="text-xs font-medium uppercase text-muted-foreground">
          {label}
        </div>
        {side.ids.map((id, i) => (
          <div
            key={id}
            className="flex items-center justify-between gap-2 rounded border px-2 py-1"
          >
            <span className="truncate text-sm">{side.names[i]}</span>
            <Button
              size="sm"
              variant="ghost"
              aria-label={`Remove ${side.names[i]} from ${label}`}
              onClick={() =>
                set({
                  ids: side.ids.filter((_, j) => j !== i),
                  names: side.names.filter((_, j) => j !== i),
                })
              }
            >
              Remove
            </Button>
          </div>
        ))}
        {side.ids.length < perSide ? (
          <label className="block text-sm">
            <span className="mb-1 block text-muted-foreground">{`Add to ${label}`}</span>
            <select
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
              value=""
              onChange={(e) => {
                const picked = participants.find(
                  (p) => p.userId === e.target.value,
                );
                if (!picked) return;
                set({
                  ids: [...side.ids, picked.userId],
                  names: [...side.names, picked.displayName ?? "Unknown"],
                });
              }}
            >
              <option value="">Select…</option>
              {participants
                // Nobody already on either side: the server rejects a duplicate, so offering one
                // would be a control that answers 400.
                .filter((p) => ![...side.ids, ...other.ids].includes(p.userId))
                .map((p) => (
                  <option key={p.userId} value={p.userId}>
                    {playerLabel(p.displayName, p.publicCode, p.userId)}
                    {p.isPlaceholder ? " (Unclaimed)" : ""}
                  </option>
                ))}
            </select>
          </label>
        ) : null}
      </div>
    );
  }

  return (
    <div className="space-y-4 rounded-lg border p-4">
      <div className="grid gap-4 sm:grid-cols-2">
        {renderSide("Side 1", team1, setTeam1, team2)}
        {renderSide("Side 2", team2, setTeam2, team1)}
      </div>
      <div className="flex items-center gap-2">
        <Button
          disabled={!complete || !changed || save.isPending}
          onClick={() =>
            save.mutate({
              id: match.id,
              data: { team1: team1.ids, team2: team2.ids },
            })
          }
        >
          Save players
        </Button>
        <Button variant="secondary" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        {!complete ? (
          <span className="text-xs text-muted-foreground">
            {match.matchFormat === "SINGLES" ? "One player" : "Two players"} per
            side.
          </span>
        ) : null}
      </div>
    </div>
  );
}
