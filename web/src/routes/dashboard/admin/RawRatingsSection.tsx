import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  getGetApiV1UsersMeQueryKey,
  useGetApiV1UsersMe,
} from "@/api/generated/users/users";
import { usePutApiV1UsersMeRatingPreview } from "@/api/generated/settings/settings";

/**
 * Per-admin "Show raw NTRP ratings" toggle (#583). Raw NTRP values are visible to ADMINISTRATORs only;
 * this lets an admin preview the non-admin experience on LIVE (band + confidence + speedometer only,
 * band-jump-only rating history, no calculation breakdown) without affecting anyone else. It's a
 * per-admin preference — checked = raw shown (normal); unchecked = preview as a non-admin. The Admin tab
 * is already ADMINISTRATOR-gated, so no extra gating here.
 */
export function RawRatingsSection() {
  const queryClient = useQueryClient();
  const meQuery = useGetApiV1UsersMe({ query: { retry: false } });
  // "Show raw ratings" is the inverse of the stored previewAsNonAdmin flag.
  const showRaw = !(meQuery.data?.previewRatingsAsNonAdmin ?? false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const setPreview = usePutApiV1UsersMeRatingPreview({
    mutation: {
      onSuccess: () => {
        setSaved(true);
        // Refresh /me (the toggle state) and any rating-bearing queries so the change shows immediately.
        void queryClient.invalidateQueries({ queryKey: getGetApiV1UsersMeQueryKey() });
        void queryClient.invalidateQueries();
      },
      onError: () => setError("Could not update the setting. Try again."),
    },
  });

  const onToggle = (checked: boolean) => {
    setSaved(false);
    setError(null);
    // checked = show raw → previewAsNonAdmin false; unchecked = preview as non-admin → true.
    setPreview.mutate({ data: { previewAsNonAdmin: !checked } });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Raw NTRP ratings</CardTitle>
        <CardDescription>
          Raw NTRP values (full precision) are shown to administrators only. Uncheck this to preview
          the non-admin experience on production — bands and confidence only, band-change-only rating
          history, and no calculation breakdown. Only affects your own account.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-center gap-2">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={showRaw}
            disabled={meQuery.isLoading || setPreview.isPending}
            onChange={(e) => onToggle(e.target.checked)}
            aria-label="Show raw NTRP ratings"
          />
          Show raw NTRP ratings
        </label>
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
      </CardContent>
    </Card>
  );
}
