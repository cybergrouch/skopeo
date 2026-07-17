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
import { usePostApiV1StandingsCalculations } from "@/api/generated/standings/standings";
import { getGetApiV1StandingsQueryKey } from "@/api/generated/standings/standings";
import type { StandingsCalculationResponse } from "@/api/generated/model";
import { isAdministrator, type Capability } from "@/auth/capabilities";
import { plural } from "@/lib/plural";

/**
 * Admin trigger for the points/standings recompute (#447). Mirrors the rating "Pending calculation"
 * flow (Preview/dry-run + Commit): Preview posts `{ dryRun: true }` and shows the projected groups
 * with no writes; Commit posts `{ dryRun: false }` and publishes a POINTS snapshot, after which the
 * Standings tab (in Points mode) serves the points-derived standings (#424/#428).
 *
 * The endpoint is ADMINISTRATOR-only, but this section lives in the Points Management tab which is
 * also visible to POINTS_MANAGER — so the trigger self-gates to ADMINISTRATOR, showing a hint for
 * non-admin points managers instead of a button the server would 403.
 */
export function StandingsCalculationSection({
  capabilities,
}: {
  capabilities: readonly Capability[];
}) {
  const queryClient = useQueryClient();
  const canCalculate = isAdministrator(capabilities);
  const [preview, setPreview] = useState<StandingsCalculationResponse | null>(
    null,
  );
  const [committed, setCommitted] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const calculate = usePostApiV1StandingsCalculations({
    mutation: {
      onSuccess: (data) => {
        setError(null);
        if (data.dryRun) {
          setPreview(data);
          setCommitted(null);
        } else {
          setPreview(null);
          setCommitted(data.groupsComputed);
          // The published snapshot changes what Points-mode standings serve (#424/#428).
          queryClient.invalidateQueries({
            queryKey: getGetApiV1StandingsQueryKey(),
          });
        }
      },
      onError: () => setError("Could not run the standings calculation. Try again."),
    },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Standings calculation</CardTitle>
        <CardDescription>
          Recompute the points-based standings from the ranking-points ledger. Preview shows the
          projected per-band groups with nothing saved; Commit publishes a Points snapshot, which
          the Standings tab serves once its source is set to Points.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {!canCalculate ? (
          <p className="text-sm text-muted-foreground" role="note">
            Running the standings calculation requires the Administrator capability.
          </p>
        ) : (
          <>
            <div className="flex flex-wrap gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={calculate.isPending}
                onClick={() => calculate.mutate({ data: { dryRun: true } })}
              >
                Preview
              </Button>
              {preview ? (
                <>
                  <Button
                    size="sm"
                    disabled={calculate.isPending}
                    onClick={() => calculate.mutate({ data: { dryRun: false } })}
                  >
                    Commit
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={calculate.isPending}
                    onClick={() => setPreview(null)}
                  >
                    Discard
                  </Button>
                </>
              ) : null}
            </div>

            {preview ? (
              <div className="space-y-2" data-testid="standings-preview">
                <p className="text-sm text-muted-foreground" role="status">
                  Preview ready — {preview.groupsComputed} group
                  {plural(preview.groupsComputed, "s")}, no changes saved yet.
                </p>
                {preview.groups.length > 0 ? (
                  <ul className="space-y-1 text-sm">
                    {preview.groups.map((group) => (
                      <li key={`${group.band}-${group.sex ?? "all"}`}>
                        <span className="font-medium">{group.band}</span>
                        {group.sex ? ` · ${group.sex}` : ""} — {group.entries.length}{" "}
                        player{plural(group.entries.length, "s")}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>
            ) : null}

            {committed !== null ? (
              <p className="text-sm text-foreground" role="status">
                Published a Points snapshot with {committed} group
                {plural(committed, "s")}.
              </p>
            ) : null}

            {error ? (
              <p className="text-sm text-destructive" role="alert">
                {error}
              </p>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}
