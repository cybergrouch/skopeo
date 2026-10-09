import { useState } from "react";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { toastError } from "@/observability/toastError";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  getGetApiV1UsersSearchQueryKey,
  usePostApiV1UsersStaleSweeps,
} from "@/api/generated/users/users";
import { getGetApiV1UsersPendingAssessmentQueryKey } from "@/api/generated/ratings/ratings";
import type { StaleAccountSweepResponse } from "@/api/generated/model";
import { plural } from "@/lib/plural";

/**
 * Run the stale-account sweep now (#1122), on demand, besides the nightly Cloud Scheduler job.
 *
 * The same Preview → Commit shape as the standings calculation card: Preview posts `{ dryRun: true }`
 * and lists who would be removed, with nothing changed; Commit appears only after a preview, so the
 * preview IS the confirmation step. Commit posts `{ dryRun: false }`, which re-applies the rule at that
 * moment — anyone rated since the preview is kept — and soft-deletes the rest, each restorable from
 * Deleted Accounts. Both are audited under the administrator's own name.
 *
 * Independent of the scheduled job's mode: a commit here sweeps once, and the nightly job keeps doing
 * whatever it is set to (previewing, until it is switched on). The Admin tab is ADMINISTRATOR-gated,
 * matching the endpoint, so no extra gating is needed here.
 */
export function StaleAccountSweepSection() {
  const queryClient = useQueryClient();
  const [preview, setPreview] = useState<StaleAccountSweepResponse | null>(null);

  const sweep = usePostApiV1UsersStaleSweeps({
    mutation: {
      onSuccess: (result) => {
        if (result.dryRun) {
          setPreview(result);
          return;
        }
        setPreview(null);
        toast.success(
          result.accounts.length === 0
            ? "Nothing to remove: no account matched the rule any more."
            : `Removed ${result.accounts.length} stale account${plural(result.accounts.length)}. Deleted Accounts can restore any of them.`,
        );
        // Swept accounts leave the pending list and become Deleted Accounts entries.
        void queryClient.invalidateQueries({ queryKey: getGetApiV1UsersPendingAssessmentQueryKey() });
        void queryClient.invalidateQueries({ queryKey: getGetApiV1UsersSearchQueryKey() });
      },
      onError: (error) =>
        toastError("Could not run the stale-account sweep. Try again.", { cause: error, duration: 8000 }),
    },
  });

  const count = preview?.accounts.length ?? 0;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Run the stale-account sweep now</CardTitle>
        <CardDescription>
          Preview who the sweep would remove right now, then remove them in one go. This is separate
          from the nightly scheduled run, which keeps its own mode.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={sweep.isPending}
            onClick={() => sweep.mutate({ data: { dryRun: true } })}
          >
            Preview
          </Button>
          {preview && count > 0 ? (
            <Button
              size="sm"
              disabled={sweep.isPending}
              onClick={() => sweep.mutate({ data: { dryRun: false } })}
            >
              Remove {count} account{plural(count)} now
            </Button>
          ) : null}
          {preview ? (
            <Button variant="ghost" size="sm" disabled={sweep.isPending} onClick={() => setPreview(null)}>
              Discard
            </Button>
          ) : null}
        </div>

        {preview ? (
          <div className="space-y-2" data-testid="stale-sweep-preview">
            <p className="text-sm text-muted-foreground" role="status">
              {count === 0
                ? `No account matches the rule (unrated, no history, signed up over ${preview.thresholdDays} days ago). Nothing to remove.`
                : `${count} account${plural(count)} would be removed: unrated, with no match or event history, signed up over ${preview.thresholdDays} days ago. Nothing has changed yet.`}
            </p>
            {count > 0 ? (
              <>
                <ul className="space-y-1 text-sm">
                  {preview.accounts.map((account) => (
                    <li key={account.userId}>
                      <Link to={`/players/${account.publicCode}`} className="font-medium hover:underline">
                        {account.publicCode}
                      </Link>{" "}
                      <span className="text-muted-foreground">· signed up {account.createdAt.slice(0, 10)}</span>
                    </li>
                  ))}
                </ul>
                <p className="text-xs text-muted-foreground">
                  Removing re-checks the rule at that moment, so anyone rated in the meantime is kept.
                  Each removal is a soft delete, restorable from Deleted Accounts.
                </p>
              </>
            ) : null}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
