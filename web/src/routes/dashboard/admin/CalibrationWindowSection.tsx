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
  getGetApiV1SettingsCalibrationMatchesQueryKey,
  useGetApiV1SettingsCalibrationMatches,
  usePutApiV1SettingsCalibrationMatches,
} from "@/api/generated/settings/settings";

const MIN = 1;
const MAX = 100;

/**
 * The calibration window, N (#881) — how many rated matches a manually-rated player stays in calibration
 * for.
 *
 * Its own card rather than a row in Feature flags: that card holds booleans, and this is a policy number
 * whose consequences need explaining. The Admin tab is already ADMINISTRATOR-gated, so there is no extra
 * gating here — the server enforces it regardless.
 *
 * The warning about changing it is not decoration. N is read at evaluation time and never copied onto a
 * player, so **lowering it ends several in-flight calibrations at once** and raising it re-opens them.
 * That is the intended behaviour, and an administrator should know before saving rather than after.
 */
export function CalibrationWindowSection() {
  const queryClient = useQueryClient();
  const { data } = useGetApiV1SettingsCalibrationMatches();
  // `null` means untouched, so the field shows the saved value. An empty STRING is a legitimate edited
  // state — the user cleared it — and must not snap back to the saved number, which is what using "" as
  // the sentinel did.
  const [draft, setDraft] = useState<string | null>(null);

  const save = usePutApiV1SettingsCalibrationMatches({
    mutation: {
      onSuccess: () => {
        toast.success("Saved");
        setDraft(null);
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1SettingsCalibrationMatchesQueryKey(),
        });
      },
      onError: (error) =>
        toastError("Could not update the calibration window. Try again.", {
          cause: error,
          duration: 8000,
        }),
    },
  });

  const current = data?.matches;
  const value = draft ?? current?.toString() ?? "";
  const parsed = Number(value);
  const valid = Number.isInteger(parsed) && parsed >= MIN && parsed <= MAX;
  // Which way the change cuts, or null when there is nothing to save. Computed as one value rather than a
  // boolean plus a comparison at render time: doing it there needed `current ?? 0`, a fallback that could
  // never fire (a change requires `current != null`) and so could never be covered.
  const direction =
    current != null && valid && parsed !== current ? (parsed < current ? "lower" : "raise") : null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Calibration window</CardTitle>
        <CardDescription>
          How many rated matches a manually-rated player stays in calibration for.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">
          When a rater or administrator assigns a rating by hand, that rating is a judgement rather than
          something earned. For the next {current ?? "N"} rated matches the player&apos;s own rating still
          moves, but their opponents&apos; and partners&apos; do not — so a mis-assessment cannot drag
          settled players with it. Players in calibration also earn no ranking points.
        </p>
        <div className="flex items-end gap-2">
          <label className="space-y-1">
            <span className="text-sm font-medium">Rated matches</span>
            <Input
              type="number"
              inputMode="numeric"
              min={MIN}
              max={MAX}
              value={value}
              aria-label="Calibration window in rated matches"
              className="w-28"
              onChange={(event) => setDraft(event.target.value)}
            />
          </label>
          <Button
            type="button"
            disabled={direction === null || save.isPending}
            onClick={() => save.mutate({ data: { matches: parsed } })}
          >
            {save.isPending ? "Saving…" : "Save"}
          </Button>
        </div>
        {draft !== null && !valid ? (
          <p className="text-xs text-destructive">
            Enter a whole number between {MIN} and {MAX}.
          </p>
        ) : null}
        {direction ? (
          <p className="text-xs text-muted-foreground">
            This applies immediately to everyone.{" "}
            {direction === "lower"
              ? "Lowering it will end calibration for players who have already played more than this many rated matches."
              : "Raising it will put players who recently finished calibration back into it."}
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}
