import { Link, useParams } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Avatar } from '@/components/Avatar'
import {
  useGetApiV1PlayersCode,
  useGetApiV1PlayersCodeRatingHistory,
  useGetApiV1UsersMe,
} from '@/api/generated/users/users'
import { MatchHistoryCard } from '@/components/MatchHistoryCard'
import { EventsHistoryCard } from '@/components/EventsHistoryCard'
import { WinLossCard } from '@/components/WinLossCard'
import { RatingHistoryCard } from '@/components/RatingHistoryCard'
import { PlayerStandingCard } from '@/components/PlayerStandingCard'
import { PointsAuditCard } from '@/components/PointsAuditCard'
import { ShareCard } from '@/components/ShareCard'
import { PublicPageNav } from '@/components/PublicPageNav'
import { canSeeRawRatings, canViewPointsAudit } from '@/auth/capabilities'
import { formatConfidence } from '@/lib/confidence'
import { ConfidenceValue } from '@/components/ConfidenceValue'

/**
 * Public player profile reached via the shareable deep link `/players/:code` (issue #61). Viewable
 * without login (#193); anonymous viewers see the privacy-conscious card and a sign-up/login CTA, and
 * `/me` simply returns no profile so the admin-only rating history stays hidden.
 */
export function PlayerProfilePage() {
  const { code = '' } = useParams()
  const query = useGetApiV1PlayersCode(code)
  const player = query.data

  // Rating history is admin-only on the public profile (issue #73): load the viewer's own profile
  // to check capability, then fetch the precise history only when the viewer may see raw ratings — an
  // ADMINISTRATOR who is not previewing as a non-admin (#583/#654), matching the backend reveal rule.
  const meQuery = useGetApiV1UsersMe()
  const showRawRatings = canSeeRawRatings(
    meQuery.data?.capabilities,
    meQuery.data?.previewRatingsAsNonAdmin,
  )
  const ratingHistoryQuery = useGetApiV1PlayersCodeRatingHistory(code, {
    query: { enabled: showRawRatings },
  })

  // The active-points audit (#448) is owner-or-admin only: the viewer owns this profile when their own
  // public code matches, else they must be an ADMINISTRATOR. Other/anonymous viewers see only the
  // public rank + points headline (never the audit) — this also gates the (403) fetch.
  const isOwner =
    meQuery.data?.publicCode !== undefined &&
    player?.publicCode !== undefined &&
    meQuery.data.publicCode === player.publicCode
  const canSeeAudit = canViewPointsAudit(meQuery.data?.capabilities, isOwner)

  return (
    <div className="flex min-h-svh items-start justify-center bg-muted/40 p-4">
      <div className="w-full max-w-sm space-y-4 pt-10">
        <PublicPageNav />

        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading player…</p>
        ) : null}

        {query.isError ? (
          <p className="text-sm text-muted-foreground">
            We couldn’t find or load this player. The link may be wrong, or try
            again.
          </p>
        ) : null}

        {player?.isDisabled ? (
          <Card>
            <CardHeader>
              <CardTitle>This profile has been merged</CardTitle>
              <CardDescription>
                This account ({player.publicCode}) was marked a duplicate and is
                no longer active.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {player.canonical ? (
                <Link
                  to={`/players/${player.canonical.publicCode}`}
                  className="text-sm text-primary hover:underline"
                >
                  View the active profile
                  {player.canonical.displayName
                    ? ` (${player.canonical.displayName})`
                    : ''}{' '}
                  →
                </Link>
              ) : (
                <p className="text-sm text-muted-foreground">
                  The active profile is unavailable.
                </p>
              )}
            </CardContent>
          </Card>
        ) : null}

        {player && !player.isDisabled ? (
          <Card>
            <CardHeader>
              <div className="flex items-center gap-3">
                <Avatar
                  photoUrl={player.photoUrl}
                  name={player.displayName}
                  size="lg"
                  enlargeable
                />
                <div className="min-w-0">
                  <CardTitle>{player.displayName ?? 'Player'}</CardTitle>
                  {/* Registered email (#630): revealed by the API only to the owner or an elevated
                      viewer. Shown under the name as plain text, matching the private profile (#640). */}
                  {player.email ? (
                    <CardDescription>{player.email}</CardDescription>
                  ) : null}
                  <CardDescription>
                    Player ID:{' '}
                    <code className="select-all font-mono font-medium text-foreground">
                      {player.publicCode}
                    </code>
                  </CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {/* Placeholder account (#496): a login-less player awaiting a real person to adopt it.
                  Show an "unclaimed" indicator plus a "Claim this account" entry point that deep-links
                  a signed-in user to the claim page (they need a code from an administrator). */}
              {player.isPlaceholder ? (
                <div className="mb-3 space-y-2 rounded-md border border-amber-500/50 bg-amber-500/10 p-3">
                  <p className="text-sm font-medium">Unclaimed placeholder account</p>
                  <p className="text-xs text-muted-foreground">
                    This player was created without a login. If this is you, ask
                    an administrator for a claim code, then adopt the account and
                    its history.
                  </p>
                  <Link
                    to="/dashboard?tab=claim"
                    className="text-sm text-primary hover:underline"
                  >
                    Claim this account →
                  </Link>
                </div>
              ) : null}
              {player.rating ? (
                <p className="text-sm">
                  <span className="font-medium">NTRP</span>{' '}
                  {player.rating.level ?? player.rating.value}
                  {formatConfidence(player.rating.confidence) ? (
                    <>
                      {' · '}
                      <ConfidenceValue confidence={player.rating.confidence} />
                    </>
                  ) : null}
                </p>
              ) : (
                <p className="text-sm text-muted-foreground">No rating yet.</p>
              )}
            </CardContent>
          </Card>
        ) : null}

        {player && !player.isDisabled ? (
          <PlayerStandingCard code={player.publicCode} />
        ) : null}

        {player && !player.isDisabled && canSeeAudit ? (
          <PointsAuditCard code={player.publicCode} enabled={canSeeAudit} />
        ) : null}

        {player && !player.isDisabled ? (
          <ShareCard
            url={`${window.location.origin}/players/${player.publicCode}`}
            title="Share this profile"
            description="Scan this code or copy the link to open this player's profile."
            shareText={`${player.displayName ?? player.publicCode}'s Skopeo profile`}
          />
        ) : null}

        {/* #622: when the owner has hidden their history, warn them (on their OWN profile) that other
            players don't see it. Other viewers just get an empty history, enforced server-side. */}
        {player && !player.isDisabled && isOwner && player.matchHistoryHidden ? (
          <p
            role="status"
            className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200"
          >
            Your match history is hidden from other players. You can change this in Settings → Privacy.
          </p>
        ) : null}

        {player && !player.isDisabled ? (
          <MatchHistoryCard code={player.publicCode} />
        ) : null}

        {player && !player.isDisabled ? (
          <WinLossCard code={player.publicCode} />
        ) : null}

        {/* Event-participation history (#704): parity with the owner Profile tab, resolved by code. */}
        {player && !player.isDisabled ? (
          <EventsHistoryCard code={player.publicCode} />
        ) : null}

        {player && !player.isDisabled && showRawRatings ? (
          <RatingHistoryCard
            entries={ratingHistoryQuery.data ?? []}
            isLoading={ratingHistoryQuery.isLoading}
            description="Full rating history (admin view)."
            confidence={player.rating?.confidence}
          />
        ) : null}
      </div>
    </div>
  )
}
