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
import {
  getGetApiV1UsersMeQueryKey,
  useGetApiV1UsersMe,
} from "@/api/generated/users/users";
import {
  getGetApiV1SettingsAwardRankingPointsQueryKey,
  getGetApiV1SettingsFacebookLoginQueryKey,
  useGetApiV1SettingsAwardRankingPoints,
  useGetApiV1SettingsFacebookLogin,
  usePutApiV1SettingsAwardRankingPoints,
  usePutApiV1SettingsFacebookLogin,
  usePutApiV1UsersMeRatingPreview,
} from "@/api/generated/settings/settings";

/**
 * Consolidated admin "Feature flags" section. Groups the app's toggle-style settings in one card:
 * - Facebook login (#647): a GLOBAL kill-switch — off hides the "Continue with Facebook" buttons on the
 *   sign-in/sign-up pages for everyone (interim, while the Meta app is misconfigured).
 * - Award ranking points (#641): a GLOBAL flag — off hides the "Award Ranking Points" checkbox on the
 *   event-create form so hosts can't opt an event into awarding.
 * - Hide raw NTRP ratings (#583/#743): a PER-ADMIN preference — lets this admin preview the non-admin
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
        <AwardRankingPointsToggle />
        <RawRatingsToggle />
      </CardContent>
    </Card>
  );
}

/** Global feature flag (#641): show/hide the event-create "Award Ranking Points" checkbox. */
function AwardRankingPointsToggle() {
  const queryClient = useQueryClient();
  const flagQuery = useGetApiV1SettingsAwardRankingPoints({ query: { retry: false } });
  // Default to disabled while loading / when unset, matching the backend default.
  const enabled = flagQuery.data?.enabled ?? false;

  const setFlag = usePutApiV1SettingsAwardRankingPoints({
    mutation: {
      onSuccess: () => {
        toast.success("Saved");
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1SettingsAwardRankingPointsQueryKey(),
        });
      },
      onError: (error) =>
        toastError("Could not update the setting. Try again.", { cause: error, duration: 8000 }),
    },
  });

  const onToggle = (checked: boolean) => {
    setFlag.mutate({ data: { enabled: checked } });
  };

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium">Award ranking points (global)</p>
      <p className="text-xs text-muted-foreground">
        When off, the "Award Ranking Points" checkbox is hidden on the event-create form, so no event can
        be set to award points. Turn it on once awarding should be available.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={enabled}
            disabled={flagQuery.isLoading || setFlag.isPending}
            onChange={(e) => onToggle(e.target.checked)}
            aria-label="Enable award ranking points"
          />
          Enable award ranking points
        </label>
      </div>
    </div>
  );
}

/** Global feature flag (#647): show/hide the "Continue with Facebook" sign-in buttons app-wide. */
function FacebookLoginToggle() {
  const queryClient = useQueryClient();
  const flagQuery = useGetApiV1SettingsFacebookLogin({ query: { retry: false } });
  // Default to enabled while loading / when unset, matching the backend default.
  const enabled = flagQuery.data?.enabled ?? true;

  const setFlag = usePutApiV1SettingsFacebookLogin({
    mutation: {
      onSuccess: () => {
        toast.success("Saved");
        void queryClient.invalidateQueries({
          queryKey: getGetApiV1SettingsFacebookLoginQueryKey(),
        });
      },
      onError: (error) =>
        toastError("Could not update the setting. Try again.", { cause: error, duration: 8000 }),
    },
  });

  const onToggle = (checked: boolean) => {
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
      </div>
    </div>
  );
}

/**
 * Per-admin "Hide raw NTRP ratings" toggle (#583/#743). Seeing raw NTRP values is the default for an
 * ADMINISTRATOR; this flag is the opt-out, letting an admin preview the non-admin experience on LIVE
 * (band + confidence + speedometer only, band-jump-only rating history, no calculation breakdown)
 * without affecting anyone else.
 *
 * Phrased as the negative so the checkbox reads the same way the stored field does
 * (`previewRatingsAsNonAdmin`, default false) and starts unchecked. It used to be phrased positively
 * and inverted twice — once on read, once on write — which shipped a default-checked opt-in and made
 * the UI state the opposite polarity to the persisted one.
 */
function RawRatingsToggle() {
  const queryClient = useQueryClient();
  const meQuery = useGetApiV1UsersMe({ query: { retry: false } });
  const previewAsNonAdmin = meQuery.data?.previewRatingsAsNonAdmin ?? false;

  const setPreview = usePutApiV1UsersMeRatingPreview({
    mutation: {
      onSuccess: () => {
        toast.success("Saved");
        // Refresh /me (the toggle state) and any rating-bearing queries so the change shows immediately.
        void queryClient.invalidateQueries({ queryKey: getGetApiV1UsersMeQueryKey() });
        void queryClient.invalidateQueries();
      },
      onError: (error) =>
        toastError("Could not update the setting. Try again.", { cause: error, duration: 8000 }),
    },
  });

  const onToggle = (checked: boolean) => {
    setPreview.mutate({ data: { previewAsNonAdmin: checked } });
  };

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium">Hide raw NTRP ratings for administrators (per-admin)</p>
      <p className="text-xs text-muted-foreground">
        Raw NTRP values (full precision) are shown to administrators by default. Check this to preview
        the non-admin experience on production — bands and confidence only, band-change-only rating
        history, and no calculation breakdown. Only affects your own account.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={previewAsNonAdmin}
            disabled={meQuery.isLoading || setPreview.isPending}
            onChange={(e) => onToggle(e.target.checked)}
            aria-label="Hide raw NTRP ratings"
          />
          Hide raw NTRP ratings
        </label>
      </div>
    </div>
  );
}
