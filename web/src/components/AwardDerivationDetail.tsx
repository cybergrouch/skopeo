import type { AwardDerivationResponse, AwardSetDerivation } from "@/api/generated/model";
import { formatPoints } from "@/lib/points";

/** Reader-facing wording for the band relation — EQUAL/FAVORITE/UPSET are not user copy. */
const RELATION_LABEL: Record<string, string> = {
  EQUAL: "same band",
  FAVORITE: "favourite won",
  UPSET: "upset",
};

/**
 * How one award's amount was reached, rendered from the shared `AwardDerivationResponse` (#862).
 *
 * Shared deliberately: the Points Management popup (#862) and the public match card (#858) show the
 * *same* payload to different audiences, and the only thing separating them is the server-side gate that
 * decides whether the field is present at all. Two renderings of one payload would drift, and the way
 * they'd drift is in what they imply about the arithmetic.
 *
 * Set [heading] to false where the surrounding card already names the amount, so it is not printed twice.
 */
export function AwardDerivationDetail({
  derivation,
  heading = true,
}: {
  derivation: AwardDerivationResponse;
  heading?: boolean;
}) {
  // An award that cannot be explained says so. Substituting today's rates would produce a confident
  // panel whose numbers do not add up to the figure the reader just clicked (#862).
  if (!derivation.recorded) {
    return (
      <div className="space-y-1 text-sm">
        {heading ? (
          <p className="font-medium">
            {formatPoints(derivation.points) ?? derivation.points} points
          </p>
        ) : null}
        <p className="text-muted-foreground">
          {derivation.unavailableReason ?? "How this amount was reached was not recorded."}
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-2 text-sm">
      {heading ? (
        <p className="font-medium">
          {formatPoints(derivation.points) ?? derivation.points} points
          <span className="ml-2 text-xs font-normal text-muted-foreground">
            {derivation.pointClass} · schedule v{derivation.scheduleVersion}
          </span>
        </p>
      ) : null}

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
