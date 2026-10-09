import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { toastError } from "@/observability/toastError";
import { Button } from "@/components/ui/button";
import {
  getGetApiV1RatingsCalibrationsQueryKey,
  usePutApiV1UsersUserIdCalibrationOverride,
} from "@/api/generated/ratings/ratings";
import { getGetApiV1UsersSearchQueryKey } from "@/api/generated/users/users";
import { CALIBRATION_OVERRIDES, type CalibrationOverrideValue } from "@/lib/calibration";

const MAX_REASON = 500;

/**
 * Change a player's calibration override (#1126): Automatic, Forced off or Forced on, with a required
 * reason. Collapsed to one button until asked for, like the rest of the Ratings tab's inline controls.
 *
 * Shown only on the Ratings tab, which is gated to exactly the roles the server allows (`canRate` =
 * RATING_ROLES), so it never offers a control the API would refuse (#867). The server re-checks anyway.
 */
export function CalibrationOverrideForm({
  userId,
  current,
}: {
  userId: string;
  current: string;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [choice, setChoice] = useState<CalibrationOverrideValue>(current as CalibrationOverrideValue);
  const [reason, setReason] = useState("");

  const save = usePutApiV1UsersUserIdCalibrationOverride({
    mutation: {
      onSuccess: () => {
        toast.success("Calibration updated");
        setOpen(false);
        setReason("");
        // Both lists show calibration, so both are stale the moment it changes.
        void queryClient.invalidateQueries({ queryKey: getGetApiV1RatingsCalibrationsQueryKey() });
        void queryClient.invalidateQueries({ queryKey: getGetApiV1UsersSearchQueryKey() });
      },
      onError: (error) =>
        toastError("Could not update calibration. Try again.", { cause: error, duration: 8000 }),
    },
  });

  if (!open) {
    return (
      <Button type="button" size="sm" variant="outline" onClick={() => setOpen(true)}>
        Change calibration
      </Button>
    );
  }

  const trimmed = reason.trim();
  function onSubmit(event: FormEvent) {
    event.preventDefault();
    save.mutate({ userId, data: { override: choice, reason: trimmed } });
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3 rounded-md border p-3">
      <fieldset className="space-y-2">
        <legend className="text-xs font-medium">Calibration</legend>
        {CALIBRATION_OVERRIDES.map((option) => (
          <label key={option.value} className="flex items-start gap-2 text-sm">
            <input
              type="radio"
              name={`calibration-${userId}`}
              value={option.value}
              checked={choice === option.value}
              onChange={() => setChoice(option.value)}
              className="mt-1"
            />
            <span>
              <span className="font-medium">{option.label}</span>
              <span className="block text-xs text-muted-foreground">{option.hint}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <label className="block space-y-1">
        <span className="text-xs font-medium">Reason (required)</span>
        <textarea
          value={reason}
          maxLength={MAX_REASON}
          rows={2}
          onChange={(event) => setReason(event.target.value)}
          aria-label="Reason for the calibration change"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm"
        />
      </label>
      <p className="text-xs text-muted-foreground">
        Applies from the next rating run and points award. Matches already rated and points already
        awarded do not change. A manual re-rating resets this to Automatic.
      </p>
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={trimmed === "" || save.isPending}>
          {save.isPending ? "Saving…" : "Save"}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={() => setOpen(false)}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
