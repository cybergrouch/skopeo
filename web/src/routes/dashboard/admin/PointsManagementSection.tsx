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
import type {
  ClubBudgetResponse,
  ClubBudgetResponseEventType,
  PointsPolicyResponse,
  PointsPolicyResponseEventType,
} from "@/api/generated/model";

/**
 * Points Management (#403 Phase B, §5.2): the global master policy per event type (editable
 * min/max/validity) and a per club × event-type budget table (Budgeted editable; Allocated + Free
 * shown, Allocated = 0 until reservations/awards land in Phase C/D). Points-manager gated
 * (ADMINISTRATOR is implicitly a points manager); the API enforces it.
 */
export function PointsManagementSection() {
  return (
    <div className="grid gap-4">
      <PoliciesCard />
      <BudgetsCard />
    </div>
  );
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
