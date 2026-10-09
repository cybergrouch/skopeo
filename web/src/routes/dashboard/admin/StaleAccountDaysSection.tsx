import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { toastError } from "@/observability/toastError";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  getGetApiV1SettingsStaleAccountDaysQueryKey,
  useGetApiV1SettingsStaleAccountDays,
  usePutApiV1SettingsStaleAccountDays,
} from "@/api/generated/settings/settings";
import { getGetApiV1UsersPendingAssessmentQueryKey } from "@/api/generated/ratings/ratings";

const MIN = 7;
const MAX = 365;

/**
 * The stale-account threshold (#1122) — how many days after sign-up the scheduled cleanup removes an
 * account nobody has rated or used. Mirrors the calibration window's card: a policy number with a
 * consequence worth stating before saving. The Admin tab is ADMINISTRATOR-gated, and the server enforces
 * the rule regardless.
 *
 * Saving also refreshes the pending-assessment list, whose removal dates are derived from this number.
 */
export function StaleAccountDaysSection() {
  const queryClient = useQueryClient();
  const { data } = useGetApiV1SettingsStaleAccountDays();
  // `null` means untouched, so the field shows the saved value; an empty string is a real edit.
  const [draft, setDraft] = useState<string | null>(null);

  const save = usePutApiV1SettingsStaleAccountDays({
    mutation: {
      onSuccess: () => {
        toast.success("Saved");
        setDraft(null);
        void queryClient.invalidateQueries({ queryKey: getGetApiV1SettingsStaleAccountDaysQueryKey() });
        void queryClient.invalidateQueries({ queryKey: getGetApiV1UsersPendingAssessmentQueryKey() });
      },
      onError: (error) =>
        toastError("Could not update the stale-account threshold. Try again.", {
          cause: error,
          duration: 8000,
        }),
    },
  });

  const current = data?.days;
  const value = draft ?? current?.toString() ?? "";
  const parsed = Number(value);
  const valid = Number.isInteger(parsed) && parsed >= MIN && parsed <= MAX;
  const lowering = current != null && valid && parsed < current;
  const changed = current != null && valid && parsed !== current;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Stale-account cleanup</CardTitle>
        <CardDescription>
          How many days after sign-up an unrated, unused account is removed.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">
          A scheduled cleanup removes self-sign-ups that are still unrated {current ?? "N"} days after
          joining and have no match or event history. Staff, placeholders and merged accounts are never
          removed. Removal is a soft delete: an account can be restored from Deleted Accounts. The Ratings
          tab shows each pending player&apos;s removal date.
        </p>
        <div className="flex items-end gap-2">
          <label className="space-y-1">
            <span className="text-sm font-medium">Days after sign-up</span>
            <Input
              type="number"
              inputMode="numeric"
              min={MIN}
              max={MAX}
              value={value}
              aria-label="Stale-account threshold in days"
              className="w-28"
              onChange={(event) => setDraft(event.target.value)}
            />
          </label>
          <Button
            type="button"
            disabled={!changed || save.isPending}
            onClick={() => save.mutate({ data: { days: parsed } })}
          >
            {save.isPending ? "Saving…" : "Save"}
          </Button>
        </div>
        {draft !== null && !valid ? (
          <p className="text-xs text-destructive">
            Enter a whole number between {MIN} and {MAX}.
          </p>
        ) : null}
        {lowering ? (
          <p className="text-xs text-muted-foreground">
            Lowering it brings removal dates forward: accounts already older than the new number are
            removed at the next cleanup.
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}
