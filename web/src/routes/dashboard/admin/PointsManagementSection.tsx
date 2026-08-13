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
 * {@link ContentLink}; points render as a signed integer via {@link formatPoints}. The source is the
 * granting match/event public code, else "manual"/"EXTERNAL".
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
                    <th className="py-1 pr-2">Player</th>
                    <th className="py-1 pr-2">Points</th>
                    <th className="py-1 pr-2">Band / sex</th>
                    <th className="py-1 pr-2">Source</th>
                    <th className="py-1 pr-2">Awarded / validity</th>
                    <th className="py-1 pr-2">Status</th>
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

/** One awarded-points row: player link, signed points, band/sex, source, awarded/validity, status. */
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
      <td className="py-1 pr-2">
        {row.band}
        <span className="text-muted-foreground"> · {row.sex}</span>
      </td>
      <td className="py-1 pr-2">{awardSource(row)}</td>
      <td className="py-1 pr-2 text-xs text-muted-foreground">
        <div>{formatAwardDate(row.awardedAt)}</div>
        <div>
          {formatAwardDate(row.validFrom)} – {formatAwardDate(row.validUntil)}
        </div>
      </td>
      <td className="py-1 pr-2">{row.status}</td>
    </tr>
  );
}

/** The granting source cell: link a match/event public code, else show "manual"/"EXTERNAL" as text. */
function awardSource(row: AwardedPointRow) {
  if (row.matchPublicCode) {
    return <ContentLink to={`/matches/${row.matchPublicCode}`}>{row.matchPublicCode}</ContentLink>;
  }
  if (row.eventPublicCode) {
    return <ContentLink to={`/events/${row.eventPublicCode}`}>{row.eventPublicCode}</ContentLink>;
  }
  return row.source;
}

/** Render an ISO date-time as a locale date; fall back to the raw string if it does not parse. */
function formatAwardDate(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString();
}
