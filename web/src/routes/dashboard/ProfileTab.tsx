import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/auth/useAuth";
import { MatchHistoryCard } from "@/components/MatchHistoryCard";
import { WinLossCard } from "@/components/WinLossCard";
import { UpcomingMatchesCard } from "@/components/UpcomingMatchesCard";
import { EventsHistoryCard } from "@/components/EventsHistoryCard";
import { RatingHistoryCard } from "@/components/RatingHistoryCard";
import { RatingBandMeter } from "@/components/RatingBandMeter";
import { formatConfidence } from "@/lib/confidence";
import { ShareCard } from "@/components/ShareCard";
import { ReRateRequestCard } from "@/components/ReRateRequestCard";
import { ProfileFieldsForm } from "@/components/ProfileFieldsForm";
import { PhotoSettingsForm } from "@/components/PhotoSettingsForm";
import type { Capability } from "@/auth/capabilities";
import {
  useGetApiV1UsersUserIdRatingHistory,
  useGetApiV1UsersUserIdRatings,
} from "@/api/generated/ratings/ratings";

interface ProfileTabProps {
  userId: string;
  capabilities: readonly Capability[];
  /** Short, shareable player code (e.g. "K7Q2MX") others can search to find this player. */
  publicCode?: string;
  /**
   * Effective profile photo from the API (#303) — already respects the hide flag and custom URL.
   * Undefined while the profile loads; null means no photo (hidden or none) → show initials.
   */
  photoUrl?: string | null;
}

export function ProfileTab({
  userId,
  capabilities,
  publicCode,
  photoUrl,
}: ProfileTabProps) {
  const { user } = useAuth();
  // Prefer the API's effective photo (honors hide/custom); fall back to the provider photo from the
  // auth token only while the profile is still loading (photoUrl === undefined).
  const avatarUrl =
    photoUrl !== undefined ? photoUrl : (user?.photoURL ?? null);
  const enabled = Boolean(userId);
  const ratingsQuery = useGetApiV1UsersUserIdRatings(userId, {
    query: { enabled },
  });
  const historyQuery = useGetApiV1UsersUserIdRatingHistory(userId, {
    query: { enabled },
  });

  const shareUrl = publicCode
    ? `${window.location.origin}/players/${publicCode}`
    : "";

  const ratings = ratingsQuery.data ?? [];
  const history = historyQuery.data ?? [];
  const hasRating = ratings.length > 0;

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-3">
            {avatarUrl ? (
              <img
                src={avatarUrl}
                alt=""
                referrerPolicy="no-referrer"
                className="h-12 w-12 shrink-0 rounded-full object-cover"
              />
            ) : (
              // No photo (hidden, or none set): show the display-name/email initial (#303).
              <div
                aria-hidden="true"
                className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-muted text-lg font-medium text-muted-foreground"
              >
                {(user?.displayName ?? user?.email ?? "P")
                  .charAt(0)
                  .toUpperCase()}
              </div>
            )}
            <div className="min-w-0">
              <CardTitle>
                {user?.displayName ?? user?.email ?? "Player"}
              </CardTitle>
              <CardDescription>{user?.email}</CardDescription>
              {publicCode ? (
                <CardDescription className="mt-1">
                  Player ID:{" "}
                  <code className="select-all font-mono font-medium text-foreground">
                    {publicCode}
                  </code>
                </CardDescription>
              ) : null}
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap gap-2">
            {capabilities.map((capability) => (
              <Badge key={capability} variant="secondary">
                {capability}
              </Badge>
            ))}
          </div>

          {/* Rating (band + own-profile speedometer) lives inside the identity card (#111). */}
          <div className="space-y-2 border-t pt-3">
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Rating
            </p>
            {ratingsQuery.isLoading ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : hasRating ? (
              <ul className="space-y-2">
                {ratings.map((rating, index) => (
                  <li
                    key={rating.level ?? index}
                    className="rounded-lg border p-3 text-sm"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-medium">NTRP</span>
                      <span>
                        {rating.level ?? rating.value}
                        {formatConfidence(rating.confidence)
                          ? ` · ${formatConfidence(rating.confidence)}`
                          : ""}
                      </span>
                    </div>
                    {rating.bandPosition != null ? (
                      <div className="mt-2 flex justify-center">
                        <RatingBandMeter position={rating.bandPosition} />
                      </div>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : (
              <div className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
                <p className="font-medium text-foreground">
                  Pending assessment
                </p>
                <p className="mt-1">
                  An administrator will assign your starting rating. Once that's
                  done you'll be eligible to be scheduled in matches.
                </p>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {hasRating ? <ReRateRequestCard /> : null}

      <Card>
        <CardHeader>
          <CardTitle>Profile details</CardTitle>
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
        </CardContent>
      </Card>

      {publicCode ? (
        <ShareCard
          url={shareUrl}
          title="Share your profile"
          description="Anyone signed in can scan this code or open the link to view your profile."
        />
      ) : null}

      <RatingHistoryCard
        entries={history}
        isLoading={historyQuery.isLoading}
        description="Changes from your rated matches."
        confidence={ratings[0]?.confidence}
      />

      <UpcomingMatchesCard />

      {publicCode ? <MatchHistoryCard code={publicCode} /> : null}

      {publicCode ? <WinLossCard code={publicCode} /> : null}

      <EventsHistoryCard />
    </div>
  );
}
