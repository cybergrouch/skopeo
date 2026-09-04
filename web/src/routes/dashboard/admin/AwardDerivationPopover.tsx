import type { AwardDerivationResponse, AwardSetDerivation } from "@/api/generated/model";
import { useGetApiV1RankingPointsAwardIdDerivation } from "@/api/generated/ranking-points/ranking-points";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { formatPoints } from "@/lib/points";

/** Reader-facing wording for the band relation — EQUAL/FAVORITE/UPSET are not user copy. */
const RELATION_LABEL: Record<string, string> = {
  EQUAL: "same band",
  FAVORITE: "favourite won",
  UPSET: "upset",
};

/**
 * Click a points figure to see how it was reached (#862).
 *
 * Fetched **on open**, not with the table: the ledger pages 25 rows and a reader opens one, so assembling
 * 25 derivations to show a single popover would be waste the server does every page.
 *
 * The trigger is a real `<button>` — keyboard-focusable, not hover-only. Safe to nest here because a
 * ledger row is a `<tr>` rather than a button; #852's DOM-nesting guard fails the suite if that ever
 * changes.
 */
export function AwardDerivationPopover({
  awardId,
  points,
}: {
  awardId: string;
  points: string;
}) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="tabular-nums underline decoration-dotted underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm"
          aria-label={`How these ${formatPoints(points) ?? points} points were derived`}
        >
          {formatPoints(points) ?? points}
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-96">
        <DerivationBody awardId={awardId} />
      </PopoverContent>
    </Popover>
  );
}

/** Loaded lazily, so the query only runs for the row a reader actually opens. */
function DerivationBody({ awardId }: { awardId: string }) {
  const { data, isLoading } = useGetApiV1RankingPointsAwardIdDerivation(awardId, {
    query: { retry: false },
  });

  if (isLoading) return <p className="text-sm text-muted-foreground">Loading…</p>;
  if (!data) {
    return <p className="text-sm text-muted-foreground">Couldn’t load the derivation.</p>;
  }
  return <Derivation derivation={data} />;
}

function Derivation({ derivation }: { derivation: AwardDerivationResponse }) {
  // An award that cannot be explained says so. Substituting today's rates would produce a confident
  // panel whose numbers do not add up to the figure the reader just clicked (#862).
  if (!derivation.recorded) {
    return (
      <div className="space-y-1 text-sm">
        <p className="font-medium">
          {formatPoints(derivation.points) ?? derivation.points} points
        </p>
        <p className="text-muted-foreground">
          {derivation.unavailableReason ?? "How this amount was reached was not recorded."}
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-2 text-sm">
      <p className="font-medium">
        {formatPoints(derivation.points) ?? derivation.points} points
        <span className="ml-2 text-xs font-normal text-muted-foreground">
          {derivation.pointClass} · schedule v{derivation.scheduleVersion}
        </span>
      </p>

      {derivation.placement ? (
        <p className="text-muted-foreground">
          Placed {ordinal(derivation.placement.place)} in a{" "}
          {derivation.placement.sanctioned ? "sanctioned" : "non-sanctioned"} club’s tournament, which pays{" "}
          <span className="font-medium tabular-nums">{derivation.placement.scheduleAmount}</span>.
        </p>
      ) : null}

      {derivation.reason ? <p className="text-muted-foreground">{derivation.reason}</p> : null}

      {derivation.sets && derivation.sets.length > 0 ? (
        <>
          <p className="text-xs text-muted-foreground">
            Paid per set. {derivation.teamBand} vs {derivation.opponentBand}.
          </p>
          <ul className="space-y-1">
            {derivation.sets.map((set) => (
              <SetLine key={set.setNumber} set={set} />
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}

/**
 * One set's line.
 *
 * Shows the amount the reader's row was paid **and** the other side's, because "the loser is paid at all"
 * is the least self-evident part of the schedule (#525) — seeing both is what makes a small positive figure
 * on a lost set read as intentional rather than as a bug.
 */
function SetLine({ set }: { set: AwardSetDerivation }) {
  return (
    <li className="flex items-baseline justify-between gap-2">
      <span className="text-muted-foreground">
        Set {set.setNumber}: {set.score}{" "}
        <span className="text-xs">
          (margin {set.margin}, {RELATION_LABEL[set.relation] ?? set.relation.toLowerCase()},{" "}
          {set.wonSet ? "won" : "lost"})
        </span>
      </span>
      <span className="shrink-0 tabular-nums">
        <span className="font-medium">{signed(set.pointsForThisPlayer)}</span>
        <span className="ml-1 text-xs text-muted-foreground">
          (winner {signed(set.winnerPoints)}, loser {signed(set.loserPoints)})
        </span>
      </span>
    </li>
  );
}

/** Points are signed here — a beaten favourite can lose points (#525), so "-2" must not read as "2". */
function signed(value: number): string {
  return value >= 0 ? `+${value}` : `${value}`;
}

function ordinal(place: number): string {
  const suffix = place === 1 ? "st" : place === 2 ? "nd" : place === 3 ? "rd" : "th";
  return `${place}${suffix}`;
}
