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
import {
  getGetApiV1SettingsFacebookLoginQueryKey,
  useGetApiV1SettingsFacebookLogin,
  usePutApiV1SettingsFacebookLogin,
  usePutApiV1UsersMeRatingPreview,
} from "@/api/generated/settings/settings";

/**
 * Consolidated admin "Feature flags" section. Groups the app's toggle-style settings in one card:
 * - Facebook login (#647): a GLOBAL kill-switch — off hides the "Continue with Facebook" buttons on the
 *   sign-in/sign-up pages for everyone (interim, while the Meta app is misconfigured).
 * - Show raw NTRP ratings (#583): a PER-ADMIN preference — lets this admin preview the non-admin
 *   experience on production without affecting anyone else.
 * The Admin tab is already ADMINISTRATOR-gated, so no extra gating here.
 */
export function FeatureFlagsSection() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Feature flags</CardTitle>
        <CardDescription>
          App-wide and per-admin toggles. Global flags affect every user; per-admin flags affect only
          your own account.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <FacebookLoginToggle />
        <RawRatingsToggle />
      </CardContent>
    </Card>
  );
}

/** Global feature flag (#647): show/hide the "Continue with Facebook" sign-in buttons app-wide. */
function FacebookLoginToggle() {
  const queryClient = useQueryClient();
  const flagQuery = useGetApiV1SettingsFacebookLogin({ query: { retry: false } });
  // Default to enabled while loading / when unset, matching the backend default.
  const enabled = flagQuery.data?.enabled ?? true;
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const setFlag = usePutApiV1SettingsFacebookLogin({
    mutation: {
      onSuccess: () => {
        setSaved(true);
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1SettingsFacebookLoginQueryKey(),
        });
      },
      onError: () => setError("Could not update the setting. Try again."),
    },
  });

  const onToggle = (checked: boolean) => {
    setSaved(false);
    setError(null);
    setFlag.mutate({ data: { enabled: checked } });
  };

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium">Facebook login (global)</p>
      <p className="text-xs text-muted-foreground">
        When off, the "Continue with Facebook" buttons are hidden on the sign-in and sign-up pages for
        all users. Use this while Facebook login is unavailable.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={enabled}
            disabled={flagQuery.isLoading || setFlag.isPending}
            onChange={(e) => onToggle(e.target.checked)}
            aria-label="Enable Facebook login"
          />
          Enable Facebook login
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
      </div>
    </div>
  );
}

/**
 * Per-admin "Show raw NTRP ratings" toggle (#583). Raw NTRP values are visible to ADMINISTRATORs only;
 * this lets an admin preview the non-admin experience on LIVE (band + confidence + speedometer only,
 * band-jump-only rating history, no calculation breakdown) without affecting anyone else. It's a
 * per-admin preference — checked = raw shown (normal); unchecked = preview as a non-admin.
 */
function RawRatingsToggle() {
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
    <div className="space-y-2">
      <p className="text-sm font-medium">Show raw NTRP ratings for administrators (per-admin)</p>
      <p className="text-xs text-muted-foreground">
        Raw NTRP values (full precision) are shown to administrators only. Uncheck to preview the
        non-admin experience on production — bands and confidence only, band-change-only rating history,
        and no calculation breakdown. Only affects your own account.
      </p>
      <div className="flex flex-wrap items-center gap-2">
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
      </div>
    </div>
  );
}
