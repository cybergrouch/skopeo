import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  getGetApiV1PointsBudgetsQueryKey,
  getGetApiV1PointsPoliciesQueryKey,
  useGetApiV1PointsBudgets,
  useGetApiV1PointsPolicies,
  usePutApiV1ClubsClubIdPointBudgetsEventType,
  usePutApiV1PointsPoliciesEventType,
} from "@/api/generated/points-budget/points-budget";
import { useGetApiV1Clubs } from "@/api/generated/clubs/clubs";
import { useGetApiV1RankingPoints } from "@/api/generated/ranking-points/ranking-points";
import type {
  AwardedPointRow,
  ClubBudgetResponse,
  ClubBudgetResponseEventType,
  PointsPolicyResponse,
  PointsPolicyResponseEventType,
} from "@/api/generated/model";
import { NumberedPager } from "@/components/NumberedPager";
import { ContentLink } from "@/components/ContentLink";
import { PlaceholderTag } from "@/components/PlaceholderTag";
import { formatPoints } from "@/lib/points";
import type { Capability } from "@/auth/capabilities";
import { canManagePointsBudget } from "@/auth/capabilities";
import { StandingsCalculationSection } from "./StandingsCalculationSection";

const AWARDS_PAGE_SIZE = 25;

/**
 * Points Management (#403 Phase B, §5.2): the global master policy per event type (editable
 * min/max/validity) and a per club × event-type budget table (Budgeted editable; Allocated + Free
 * shown, Allocated = 0 until reservations/awards land in Phase C/D). Points-manager gated
 * (ADMINISTRATOR is implicitly a points manager); the API enforces it.
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
    <div className="grid gap-4">
      <PoliciesCard />
      <BudgetsCard />
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

function PoliciesCard() {
  const queryClient = useQueryClient();
  const policiesQuery = useGetApiV1PointsPolicies({ query: { retry: false } });
  const policies = policiesQuery.data ?? [];

  function invalidate() {
    void queryClient.invalidateQueries({
      queryKey: getGetApiV1PointsPoliciesQueryKey(),
    });
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Global points policy</CardTitle>
        <CardDescription>
          The master per-match reward bounds and max validity days, per event type. Events must
          reward within these bounds.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {policiesQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : policies.length === 0 ? (
          <p className="text-sm text-muted-foreground">No policies.</p>
        ) : (
          <ul className="space-y-3">
            {policies.map((policy) => (
              <PolicyRow
                key={policy.eventType}
                policy={policy}
                onChange={invalidate}
              />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function PolicyRow({
  policy,
  onChange,
}: {
  policy: PointsPolicyResponse;
  onChange: () => void;
}) {
  const [minPoints, setMinPoints] = useState(String(policy.minPoints));
  const [maxPoints, setMaxPoints] = useState(String(policy.maxPoints));
  const [maxValidityDays, setMaxValidityDays] = useState(
    String(policy.maxValidityDays),
  );
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = usePutApiV1PointsPoliciesEventType({
    mutation: {
      onSuccess: () => {
        setSaved(true);
        onChange();
      },
      onError: () => setError("Could not save the policy."),
    },
  });

  function onSave() {
    setSaved(false);
    setError(null);
    save.mutate({
      eventType: policy.eventType as PointsPolicyResponseEventType,
      data: {
        minPoints: Number(minPoints),
        maxPoints: Number(maxPoints),
        maxValidityDays: Number(maxValidityDays),
      },
    });
  }

  return (
    <li className="flex flex-wrap items-end gap-2 rounded-lg border p-3">
      <p className="w-full font-medium">{policy.eventType}</p>
      <div className="space-y-1">
        <Label htmlFor={`min-${policy.eventType}`} className="text-xs">
          Min
        </Label>
        <Input
          id={`min-${policy.eventType}`}
          type="number"
          className="w-20"
          value={minPoints}
          onChange={(e) => {
            setMinPoints(e.target.value);
            setSaved(false);
          }}
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor={`max-${policy.eventType}`} className="text-xs">
          Max
        </Label>
        <Input
          id={`max-${policy.eventType}`}
          type="number"
          className="w-20"
          value={maxPoints}
          onChange={(e) => {
            setMaxPoints(e.target.value);
            setSaved(false);
          }}
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor={`days-${policy.eventType}`} className="text-xs">
          Validity (days)
        </Label>
        <Input
          id={`days-${policy.eventType}`}
          type="number"
          className="w-24"
          value={maxValidityDays}
          onChange={(e) => {
            setMaxValidityDays(e.target.value);
            setSaved(false);
          }}
        />
      </div>
      <Button size="sm" onClick={onSave} disabled={save.isPending}>
        {save.isPending ? "Saving…" : "Save"}
      </Button>
      {saved ? (
        <span className="text-xs text-muted-foreground" role="status">
          Saved
        </span>
      ) : null}
      {error ? (
        <span className="text-xs text-destructive" role="alert">
          {error}
        </span>
      ) : null}
    </li>
  );
}

function BudgetsCard() {
  const queryClient = useQueryClient();
  const budgetsQuery = useGetApiV1PointsBudgets({ query: { retry: false } });
  const clubsQuery = useGetApiV1Clubs();
  const budgets = budgetsQuery.data ?? [];
  const clubs = clubsQuery.data ?? [];

  // Resolve a club id to a readable name for the table; fall back to the id.
  const clubName = (clubId: string) =>
    clubs.find((c) => c.id === clubId)?.name ?? clubId;

  function invalidate() {
    void queryClient.invalidateQueries({
      queryKey: getGetApiV1PointsBudgetsQueryKey(),
    });
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Club budgets</CardTitle>
        <CardDescription>
          Each club's per-event-type budget. Allocated is 0 until reservations and awards land
          (Phase C/D); Free is Budgeted minus Allocated.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {budgetsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : budgets.length === 0 ? (
          <p className="text-sm text-muted-foreground">No clubs yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-muted-foreground">
                <th className="py-1 pr-2">Club</th>
                <th className="py-1 pr-2">Type</th>
                <th className="py-1 pr-2">Budgeted</th>
                <th className="py-1 pr-2">Allocated</th>
                <th className="py-1 pr-2">Free</th>
                <th className="py-1" />
              </tr>
            </thead>
            <tbody>
              {budgets.map((budget) => (
                <BudgetRow
                  key={`${budget.clubId}-${budget.eventType}`}
                  budget={budget}
                  clubName={clubName(budget.clubId)}
                  onChange={invalidate}
                />
              ))}
            </tbody>
          </table>
        )}
      </CardContent>
    </Card>
  );
}

function BudgetRow({
  budget,
  clubName,
  onChange,
}: {
  budget: ClubBudgetResponse;
  clubName: string;
  onChange: () => void;
}) {
  const [budgeted, setBudgeted] = useState(String(budget.budgeted));
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = usePutApiV1ClubsClubIdPointBudgetsEventType({
    mutation: {
      onSuccess: () => {
        setSaved(true);
        onChange();
      },
      onError: () => setError("Save failed"),
    },
  });

  function onSave() {
    setSaved(false);
    setError(null);
    save.mutate({
      clubId: budget.clubId,
      eventType: budget.eventType as ClubBudgetResponseEventType,
      data: { budgetedPoints: Number(budgeted) },
    });
  }

  return (
    <tr className="border-t">
      <td className="py-1 pr-2">{clubName}</td>
      <td className="py-1 pr-2">{budget.eventType}</td>
      <td className="py-1 pr-2">
        <Input
          type="number"
          aria-label={`Budget for ${clubName} ${budget.eventType}`}
          className="w-24"
          value={budgeted}
          onChange={(e) => {
            setBudgeted(e.target.value);
            setSaved(false);
          }}
        />
      </td>
      <td className="py-1 pr-2">{budget.allocated}</td>
      <td className="py-1 pr-2">{budget.free}</td>
      <td className="py-1">
        <div className="flex items-center gap-1">
          <Button size="sm" onClick={onSave} disabled={save.isPending}>
            {save.isPending ? "Saving…" : "Save"}
          </Button>
          {saved ? (
            <span className="text-xs text-muted-foreground" role="status">
              Saved
            </span>
          ) : null}
          {error ? (
            <span className="text-xs text-destructive" role="alert">
              {error}
            </span>
          ) : null}
        </div>
      </td>
    </tr>
  );
}
