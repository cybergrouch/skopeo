import { useGetApiV1RankingPointsAwardIdDerivation } from "@/api/generated/ranking-points/ranking-points";
import { AwardDerivationDetail } from "@/components/AwardDerivationDetail";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { formatPoints } from "@/lib/points";

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
  return <AwardDerivationDetail derivation={data} />;
}
