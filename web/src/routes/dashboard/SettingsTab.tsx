import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { ReRateRequestCard } from "@/components/ReRateRequestCard";
import { ProfileFieldsForm } from "@/components/ProfileFieldsForm";
import { PhotoSettingsForm } from "@/components/PhotoSettingsForm";
import { LocalThemeForm } from "@/components/LocalThemeForm";
import { MatchHistoryVisibilityForm } from "@/components/MatchHistoryVisibilityForm";
import { useGetApiV1UsersUserIdRatings } from "@/api/generated/ratings/ratings";

interface SettingsTabProps {
  /** The signed-in user's id — drives the profile-edit forms and the has-rating gate. */
  userId: string;
}

/**
 * The owner's account *actions* (#589), split out of the Profile tab so Profile stays informational.
 * Holds Rating Reconsideration (only once a rating exists) and "Edit Profile Details" (name/photo/
 * appearance). Gated to PLAYER in {@link DashboardPage} — i.e. every signed-in user.
 */
export function SettingsTab({ userId }: SettingsTabProps) {
  const ratingsQuery = useGetApiV1UsersUserIdRatings(userId, {
    query: { enabled: Boolean(userId) },
  });
  const hasRating = (ratingsQuery.data ?? []).length > 0;

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      {/* Rating reconsideration only makes sense once a starting rating has been assigned. */}
      {hasRating ? <ReRateRequestCard /> : null}

      <Card>
        <CardHeader>
          <CardTitle>Edit profile details</CardTitle>
          <CardDescription>
            Edit your display name and (private) first/last name, plus your date
            of birth and sex.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <ProfileFieldsForm userId={userId} />
          <div className="space-y-1 border-t pt-4">
            <p className="text-sm font-medium">Photo</p>
            <p className="text-xs text-muted-foreground">
              Hide your photo, or show a custom image instead of your
              Google/Facebook one.
            </p>
            <div className="pt-1">
              <PhotoSettingsForm userId={userId} />
            </div>
          </div>
          {/* Match-history privacy (#622): hide your match history from other players. */}
          <div className="space-y-1 border-t pt-4">
            <p className="text-sm font-medium">Privacy</p>
            <p className="text-xs text-muted-foreground">
              Control what other players see on your public profile.
            </p>
            <div className="pt-1">
              <MatchHistoryVisibilityForm userId={userId} />
            </div>
          </div>
          {/* Per-user local theme (#514): override the site theme, or follow the global default. */}
          <div className="space-y-1 border-t pt-4">
            <p className="text-sm font-medium">Appearance</p>
            <p className="text-xs text-muted-foreground">
              Choose your own theme, or follow the site default.
            </p>
            <div className="pt-1">
              <LocalThemeForm />
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
