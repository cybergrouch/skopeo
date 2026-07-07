import { Link, useParams } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  useGetApiV1PlayersCode,
  useGetApiV1PlayersCodeRatingHistory,
  useGetApiV1UsersMe,
} from '@/api/generated/users/users'
import { MatchHistoryCard } from '@/components/MatchHistoryCard'
import { WinLossCard } from '@/components/WinLossCard'
import { RatingHistoryCard } from '@/components/RatingHistoryCard'
import { ShareCard } from '@/components/ShareCard'
import { PublicPageNav } from '@/components/PublicPageNav'
import { isAdministrator } from '@/auth/capabilities'

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
  // to check capability, then fetch the precise history only when they're an ADMINISTRATOR.
  const meQuery = useGetApiV1UsersMe()
  const isAdmin = isAdministrator(meQuery.data?.capabilities)
  const ratingHistoryQuery = useGetApiV1PlayersCodeRatingHistory(code, {
    query: { enabled: isAdmin },
  })

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
                {player.photoUrl ? (
                  <img
                    src={player.photoUrl}
                    alt=""
                    referrerPolicy="no-referrer"
                    className="h-14 w-14 shrink-0 rounded-full object-cover"
                  />
                ) : (
                  <div
                    aria-hidden="true"
                    className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-muted text-xl font-medium text-muted-foreground"
                  >
                    {(player.displayName ?? 'P').charAt(0).toUpperCase()}
                  </div>
                )}
                <div className="min-w-0">
                  <CardTitle>{player.displayName ?? 'Player'}</CardTitle>
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
              {player.rating ? (
                <p className="text-sm">
                  <span className="font-medium">NTRP</span>{' '}
                  {player.rating.level ?? player.rating.value}
                </p>
              ) : (
                <p className="text-sm text-muted-foreground">No rating yet.</p>
              )}
            </CardContent>
          </Card>
        ) : null}

        {player && !player.isDisabled ? (
          <ShareCard
            url={`${window.location.origin}/players/${player.publicCode}`}
            title="Share this profile"
            description="Scan this code or copy the link to open this player's profile."
            shareText={`${player.displayName ?? player.publicCode}'s Skopeo profile`}
          />
        ) : null}

        {player && !player.isDisabled ? (
          <MatchHistoryCard code={player.publicCode} />
        ) : null}

        {player && !player.isDisabled ? (
          <WinLossCard code={player.publicCode} />
        ) : null}

        {player && !player.isDisabled && isAdmin ? (
          <RatingHistoryCard
            entries={ratingHistoryQuery.data ?? []}
            isLoading={ratingHistoryQuery.isLoading}
            description="Full rating history (admin view)."
          />
        ) : null}
      </div>
    </div>
  )
}
