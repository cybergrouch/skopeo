import { useState } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useGetApiV1RankingPoints } from "@/api/generated/ranking-points/ranking-points";
import type { AwardedPointRow } from "@/api/generated/model";
import { NumberedPager } from "@/components/NumberedPager";
import { ContentLink } from "@/components/ContentLink";
import { PlaceholderTag } from "@/components/PlaceholderTag";
import { formatPoints } from "@/lib/points";
import type { Capability } from "@/auth/capabilities";
import { canManagePointsBudget } from "@/auth/capabilities";
import { StandingsCalculationSection } from "./StandingsCalculationSection";
import { PointsSchedulesSection } from "./PointsSchedulesSection";

const AWARDS_PAGE_SIZE = 25;

/**
 * Points Management (#552/#553): the global award schedules (open-play margin table + tournament
 * placement table) plus the ranking-points ledger (#472). The per-club budget + per-event designation
 * subsystem was removed (#559) — awarding is now controlled by each event's single "Award Ranking
 * Points" flag, paying from the global schedules. Points-manager gated (ADMINISTRATOR is implicitly one);
 * the API enforces it.
 *
 * It also hosts the admin standings-calculation trigger (#447) — self-gated to ADMINISTRATOR
 * inside {@link StandingsCalculationSection} since the tab itself is visible to POINTS_MANAGER too.
 */
export function PointsManagementSection({
  capabilities,
}: {
  capabilities: readonly Capability[];
}) {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <PointsSchedulesSection />
      {canManagePointsBudget(capabilities) ? <AwardedPointsCard /> : null}
      <StandingsCalculationSection capabilities={capabilities} />
    </div>
  );
}

/**
 * Points awarded (#472): a paginated, newest-first view of the whole ranking-points ledger for points
 * managers. Server-side pagination via {@link NumberedPager} (25/page); player links wear the themed
 * {@link ContentLink}; points render as a signed integer via {@link formatPoints}.
 *
 * The granting event and match are separate columns and expiry is its own (#855) — see
 * {@link AwardedPointsRow}. Every field was already on the DTO, so this needed no backend change.
 */
function AwardedPointsCard() {
  const [page, setPage] = useState(0);
  const awardsQuery = useGetApiV1RankingPoints(
    { limit: AWARDS_PAGE_SIZE, offset: page * AWARDS_PAGE_SIZE },
    { query: { retry: false } },
  );
  const rows = awardsQuery.data?.rows ?? [];
  const total = awardsQuery.data?.total ?? 0;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Points awarded</CardTitle>
        <CardDescription>
          Every ranking-point award across all players, newest first. Includes revocation markers.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {awardsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : total === 0 ? (
          <p className="text-sm text-muted-foreground">No awards yet.</p>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs text-muted-foreground">
                    <th scope="col" className="py-1 pr-2">Player</th>
                    <th scope="col" className="py-1 pr-2">Points</th>
                    <th scope="col" className="py-1 pr-2">Class</th>
                    <th scope="col" className="py-1 pr-2">Band / sex</th>
                    {/* Event and match are separate columns (#855). Collapsing them into one "Source"
                        cell meant a match-granted award showed its match and NEVER its event — and since
                        #836 every non-placement tournament fixture pays per-match, that is the common
                        case, leaving "which event did these points come from?" unanswerable here. */}
                    <th scope="col" className="py-1 pr-2">Event</th>
                    <th scope="col" className="py-1 pr-2">Match</th>
                    <th scope="col" className="py-1 pr-2">Awarded</th>
                    {/* Expiry gets its own column (#855): it is the field a manager scans for — what is
                        about to drop out of the standings — and it could not be skimmed while wrapped in
                        a range beside two other dates. */}
                    <th scope="col" className="py-1 pr-2">Expires</th>
                    <th scope="col" className="py-1 pr-2">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <AwardedPointsRow key={row.id} row={row} />
                  ))}
                </tbody>
              </table>
            </div>
            <NumberedPager
              page={page}
              total={total}
              pageSize={AWARDS_PAGE_SIZE}
              onPage={setPage}
            />
          </>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * One awarded-points row: player, signed points, class, band/sex, the granting event and match as
 * separate cells, awarded date, expiry, status (#472/#855).
 */
function AwardedPointsRow({ row }: { row: AwardedPointRow }) {
  const player = row.playerDisplayName ?? row.playerPublicCode ?? row.userId;
  return (
    <tr className="border-t align-top">
      <td className="py-1 pr-2">
        {row.playerPublicCode ? (
          <ContentLink to={`/players/${row.playerPublicCode}`}>{player}</ContentLink>
        ) : (
          player
        )}
        <PlaceholderTag show={row.isPlaceholder} deleted={row.isDeleted} />
      </td>
      <td className="py-1 pr-2 tabular-nums">{formatPoints(row.points) ?? row.points}</td>
      {/* The class explains why two awards have different windows (#840: tournament 365 days, Full
          Match 182, open play 91) — without it the Expires column looks arbitrary. */}
      <td className="py-1 pr-2 text-xs text-muted-foreground">{row.pointClass}</td>
      <td className="py-1 pr-2">
        {row.band}
        <span className="text-muted-foreground"> · {row.sex}</span>
      </td>
      <td className="py-1 pr-2">
        <AwardSourceCell code={row.eventPublicCode} to="/events" fallback={row.source} />
      </td>
      <td className="py-1 pr-2">
        <AwardSourceCell code={row.matchPublicCode} to="/matches" />
      </td>
      <td className="py-1 pr-2 text-xs text-muted-foreground">{formatAwardDate(row.awardedAt)}</td>
      <td className="py-1 pr-2 text-xs text-muted-foreground">{formatAwardDate(row.validUntil)}</td>
      <td className="py-1 pr-2">{row.status}</td>
    </tr>
  );
}

/**
 * One of the two granting-source cells: a linked public code, or an em dash when this axis has none.
 *
 * Only the Event cell passes a [fallback]. A manual or external grant has neither code, and an empty pair
 * of cells would read as missing data rather than "not granted by a fixture" — but printing "manual" under
 * *both* headers says it twice and, worse, files it under "Event", which it is not. So the origin is
 * stated once, on the left, and the Match cell simply has nothing to show.
 */
function AwardSourceCell({
  code,
  to,
  fallback,
}: {
  code?: string | null;
  to: string;
  fallback?: string;
}) {
  if (code) {
    return <ContentLink to={`${to}/${code}`}>{code}</ContentLink>;
  }
  const noFixtureAtAll = fallback === "manual" || fallback === "EXTERNAL";
  return (
    <span className="text-muted-foreground">{noFixtureAtAll && fallback ? fallback : "—"}</span>
  );
}

/** Render an ISO date-time as a locale date; fall back to the raw string if it does not parse. */
function formatAwardDate(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString();
}
