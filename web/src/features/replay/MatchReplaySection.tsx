import { useState } from "react";
import { useGetApiV1MatchesCodeCodeReplay } from "@/api/generated/matches/matches";
import { ReplayCard } from "./ReplayCard";

/**
 * The replay of a finished live-scored match on its public page (#1146), and the selection the replay's
 * views share.
 *
 * **Absent, not empty, when there is nothing to show.** The endpoint answers 404 for a match that was
 * never scored live, or whose result was since edited away from the one the replay ends at (#1145) — the
 * server decides, and this renders nothing then, the same as while loading. A replay with no shell beats
 * an empty card that reads as a fault (the `MatchPointsCard` reasoning).
 *
 * **Selection lives here**, not in the card: it is an index into `replay.points`, or `REPLAY_START` for
 * the 0-0 position, and it starts on the LAST step — the final score, which is what a visitor came for.
 * The charts (#1147) take the same `selected`/`onSelect`, so stepping in one moves the other.
 */
export function MatchReplaySection({
  code,
  team1Name,
  team2Name,
}: {
  code: string;
  team1Name: string;
  team2Name: string;
}) {
  const query = useGetApiV1MatchesCodeCodeReplay(code, {
    query: { enabled: Boolean(code), retry: false },
  });
  // Null until the visitor moves: "the last step" is only known once the replay arrives.
  const [chosen, setChosen] = useState<number | null>(null);
  const replay = query.data;
  if (!replay) return null;

  const selected = chosen ?? replay.points.length - 1;
  const onSelect = (index: number) => setChosen(index);

  return (
    <section aria-label="Match replay" className="grid gap-4">
      <ReplayCard
        replay={replay}
        selected={selected}
        onSelect={onSelect}
        team1Name={team1Name}
        team2Name={team2Name}
      />
      {/* Charts (#1147) render here, sharing `selected`/`onSelect`. */}
    </section>
  );
}
