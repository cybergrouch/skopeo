import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
import {
  getGetApiV1UsersMeQueryKey,
  useGetApiV1PlayersCodeMatchHistory,
  usePostApiV1UsersClaim,
} from "@/api/generated/users/users";
import type { UserResponse } from "@/api/generated/model";

/** Prefer the server's message (e.g. "code expired", "account not empty"), else a generic fallback. */
function claimErrorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: string } } })
    ?.response?.data?.message;
  return message && message.trim() !== "" ? message : fallback;
}

/**
 * "Claim a placeholder account" (#496, relocated to Profile in #727): any signed-in user pastes the
 * secret code an administrator handed them; on success the placeholder's history is merged into their
 * account. Rejections (bad/expired/consumed code, a non-empty account, etc.) are surfaced from the
 * server message. This card is only rendered while the owner's account is still claim-eligible (empty),
 * so a successful claim — which fills the account with history — makes it disappear.
 */
function ClaimAccountCard() {
  const queryClient = useQueryClient();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [claimed, setClaimed] = useState<UserResponse | null>(null);
  const claim = usePostApiV1UsersClaim();

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = code.trim();
    if (trimmed === "") {
      setError("Enter the claim code you were given.");
      return;
    }
    try {
      const user = await claim.mutateAsync({ data: { code: trimmed } });
      setClaimed(user);
      setCode("");
      // The caller's profile (and its now-merged history) has changed — refresh it everywhere.
      await queryClient.invalidateQueries({
        queryKey: getGetApiV1UsersMeQueryKey(),
      });
    } catch (err) {
      toast.error(
        claimErrorMessage(
          err,
          "That code could not be used. It may be wrong, expired, or already claimed — or your account already has activity.",
        ),
        { duration: 8000 },
      );
    }
  }

  if (claimed) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Account claimed</CardTitle>
          <CardDescription>
            The placeholder&rsquo;s match and rating history is now part of your
            account.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          <p className="text-sm" role="status">
            You&rsquo;re all set.
          </p>
          <Link
            to={`/players/${claimed.publicCode}`}
            className="text-sm text-primary hover:underline"
          >
            View your profile →
          </Link>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Claim a placeholder account</CardTitle>
        <CardDescription>
          Were you added to matches before you had an account? Ask an
          administrator for your one-time claim code and paste it here to adopt
          that player and its history.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="grid gap-3">
          <div className="space-y-1">
            <Label htmlFor="claim-code" className="text-xs">
              Claim code
            </Label>
            <Input
              id="claim-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="Paste your code"
              autoComplete="off"
            />
          </div>
          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
          <Button type="submit" size="sm" disabled={claim.isPending}>
            {claim.isPending ? "Claiming…" : "Claim account"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

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
  // Claim eligibility (#727) mirrors the backend precondition (#496): the account must be empty —
  // no rating AND no match history. We only need the total count here, so ask for the smallest page.
  const matchHistoryQuery = useGetApiV1PlayersCodeMatchHistory(
    publicCode ?? "",
    { limit: 1 },
    { query: { enabled: Boolean(publicCode) } },
  );

  const shareUrl = publicCode
    ? `${window.location.origin}/players/${publicCode}`
    : "";

  const ratings = ratingsQuery.data ?? [];
  const history = historyQuery.data ?? [];
  const hasRating = ratings.length > 0;
  const matchCount = matchHistoryQuery.data?.total ?? 0;
  // Show the claim form only once we know the account is empty — don't flash it while either signal is
  // still loading. An established account (any rating or match) never sees it; a successful claim fills
  // the account, so it disappears on the next render.
  const claimEligible =
    !ratingsQuery.isLoading &&
    !matchHistoryQuery.isLoading &&
    !hasRating &&
    matchCount === 0;

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

      {/* Claim a placeholder account (#727) — self-service, shown only while this account is still
          claim-eligible (empty). Relocated here from the standalone Claim tab. */}
      {claimEligible ? <ClaimAccountCard /> : null}

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
