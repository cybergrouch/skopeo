import type { AwardedPointsPlayerRow } from "@/api/generated/model";
import { useGetApiV1MatchesCodeCodePoints } from "@/api/generated/matches/matches";
import { AwardDerivationDetail } from "@/components/AwardDerivationDetail";
import { ContentLink } from "@/components/ContentLink";
import { PlaceholderTag } from "@/components/PlaceholderTag";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { formatPoints } from "@/lib/points";

/**
 * What this match awarded, per player (#858) — on the public match page.
 *
 * **Absent, not empty, when there is nothing to show.** A match legitimately has no awards for ordinary
 * reasons — its event is not finalized, or has "Award ranking points" off (#831), or the points are
 * suppressed for this viewer (#865) — and a card sitting there empty reads as a fault rather than as an
 * absence. So the whole card disappears.
 *
 * **One row per award, and the point class is named on each.** One tournament fixture can pay either a
 * placement amount or the per-set schedule (#836/#837), and the two differ by an order of magnitude:
 * "1000" beside "7" with nothing distinguishing them reads as a bug.
 *
 * **The derivation is server-gated, not client-hidden.** `row.derivation` is simply absent for a reader
 * not entitled to it, because the band relation it carries is rating-adjacent (#583/#654). So this
 * component renders whatever it is given and makes no access decision of its own — there is no
 * capability check here to fall out of step with the server's.
 */
export function MatchPointsCard({ code }: { code: string }) {
  const query = useGetApiV1MatchesCodeCodePoints(code, {
    query: { enabled: Boolean(code), retry: false },
  });
  const rows = query.data?.rows ?? [];

  // Nothing awarded (or still loading, or the request failed): show nothing at all rather than a shell.
  if (rows.length === 0) {
    return null;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Ranking points awarded</CardTitle>
        <CardDescription>
          What this match paid each player. Revoked awards are excluded; points
          remain listed here after they expire, since this records what was
          awarded.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {rows.map((row) => (
          <PointsRow key={row.awardId ?? row.userId} row={row} />
        ))}
        <p className="text-xs text-muted-foreground">
          Total awarded:{" "}
          <span className="font-medium tabular-nums">
            {formatPoints(query.data?.totalPoints) ?? query.data?.totalPoints}
          </span>
        </p>
      </CardContent>
    </Card>
  );
}

/** One award: who, how much, under which point class — and, when permitted, how it was reached. */
function PointsRow({ row }: { row: AwardedPointsPlayerRow }) {
  const name = row.displayName ?? row.publicCode ?? row.userId;
  return (
    <div className="space-y-1 border-b pb-2 last:border-b-0 last:pb-0">
      <div className="flex items-baseline justify-between gap-2">
        <span className="min-w-0">
          {row.publicCode ? (
            <ContentLink to={`/players/${row.publicCode}`}>{name}</ContentLink>
          ) : (
            name
          )}
          <PlaceholderTag show={row.isPlaceholder} deleted={row.isDeleted} />
          {row.pointClass ? (
            <span className="ml-2 text-xs text-muted-foreground">
              {POINT_CLASS_LABEL[row.pointClass] ?? row.pointClass}
            </span>
          ) : null}
        </span>
        <span className="shrink-0 font-medium tabular-nums">
          {formatPoints(row.points) ?? row.points}
        </span>
      </div>
      {/* Heading suppressed: the row above already states the amount and class. */}
      {row.derivation ? (
        <AwardDerivationDetail derivation={row.derivation} heading={false} />
      ) : null}
    </div>
  );
}

/**
 * Reader-facing names for the point classes. The enum values are not user copy, and the distinction that
 * matters to a reader is *why the amounts differ so much* — a placing versus a per-set payout.
 */
const POINT_CLASS_LABEL: Record<string, string> = {
  ANNUAL_TOURNAMENT: "tournament placing",
  OPEN_PLAY: "per set",
  FULL_MATCH: "per set",
  EXTERNAL: "manual grant",
};
