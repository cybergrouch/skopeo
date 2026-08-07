import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/auth/useAuth";
import { Avatar } from "@/components/Avatar";
import { MatchHistoryCard } from "@/components/MatchHistoryCard";
import { WinLossCard } from "@/components/WinLossCard";
import { UpcomingMatchesCard } from "@/components/UpcomingMatchesCard";
import { EventsHistoryCard } from "@/components/EventsHistoryCard";
import { RatingHistoryCard } from "@/components/RatingHistoryCard";
import { PlayerStandingCard } from "@/components/PlayerStandingCard";
import { PointsAuditCard } from "@/components/PointsAuditCard";
import { RatingBandMeter } from "@/components/RatingBandMeter";
import { formatConfidence } from "@/lib/confidence";
import { ConfidenceValue } from "@/components/ConfidenceValue";
import { ShareCard } from "@/components/ShareCard";
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
            {/* No photo (hidden, or none set) → the display-name/email initial (#303); click to enlarge (#697). */}
            <Avatar
              photoUrl={avatarUrl}
              name={user?.displayName ?? user?.email}
              size="md"
              enlargeable
            />
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
                        {formatConfidence(rating.confidence) ? (
                          <>
                            {" · "}
                            <ConfidenceValue confidence={rating.confidence} />
                          </>
                        ) : null}
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

          {/* Ranking sits directly below Rating in the identity card (#589): band+sex rank + the
              metric backing it (#448), shown here on the owner's own profile. */}
          {publicCode ? (
            <PlayerStandingCard code={publicCode} asSection={true} />
          ) : null}
        </CardContent>
      </Card>

      <UpcomingMatchesCard />

      {/* History sections default to a compact preview on the owner's Profile tab (#589). */}
      <EventsHistoryCard collapsible={true} />

      {publicCode ? (
        <MatchHistoryCard code={publicCode} collapsible={true} />
      ) : null}

      {publicCode ? <WinLossCard code={publicCode} /> : null}

      <RatingHistoryCard
        entries={history}
        isLoading={historyQuery.isLoading}
        description="Changes from your rated matches."
        confidence={ratings[0]?.confidence}
        collapsible={true}
      />

      {/* Active-points audit (#448) — the owner is always viewing their own profile, so it's enabled. */}
      {publicCode ? (
        <PointsAuditCard code={publicCode} enabled={true} collapsible={true} />
      ) : null}

      {/* Share (QR) is the last section (#589). */}
      {publicCode ? (
        <ShareCard
          url={shareUrl}
          title="Share your profile"
          description="Anyone signed in can scan this code or open the link to view your profile."
        />
      ) : null}
    </div>
  );
}
