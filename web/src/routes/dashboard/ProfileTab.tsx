import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { useAuth } from '@/auth/useAuth'
import type { Capability } from '@/auth/capabilities'
import {
  useGetApiV1UsersUserIdRatingHistory,
  useGetApiV1UsersUserIdRatings,
} from '@/api/generated/ratings/ratings'

interface ProfileTabProps {
  userId: string
  capabilities: readonly Capability[]
}

export function ProfileTab({ userId, capabilities }: ProfileTabProps) {
  const { user } = useAuth()
  const enabled = Boolean(userId)
  const ratingsQuery = useGetApiV1UsersUserIdRatings(userId, {
    query: { enabled },
  })
  const historyQuery = useGetApiV1UsersUserIdRatingHistory(userId, {
    query: { enabled },
  })

  const ratings = ratingsQuery.data ?? []
  const history = historyQuery.data ?? []
  const hasRating = ratings.length > 0

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-3">
            {user?.photoURL ? (
              <img
                src={user.photoURL}
                alt=""
                referrerPolicy="no-referrer"
                className="h-12 w-12 shrink-0 rounded-full object-cover"
              />
            ) : (
              // No upload support: Google/Facebook supply a photo; everyone else gets initials.
              <div
                aria-hidden="true"
                className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-muted text-lg font-medium text-muted-foreground"
              >
                {(user?.displayName ?? user?.email ?? 'P').charAt(0).toUpperCase()}
              </div>
            )}
            <div className="min-w-0">
              <CardTitle>
                {user?.displayName ?? user?.email ?? 'Player'}
              </CardTitle>
              <CardDescription>{user?.email}</CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          {capabilities.map((capability) => (
            <Badge key={capability} variant="secondary">
              {capability}
            </Badge>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Rating</CardTitle>
          <CardDescription>
            {hasRating
              ? 'Your current rating across systems.'
              : 'Awaiting an initial rating.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {ratingsQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : hasRating ? (
            <ul className="space-y-2">
              {ratings.map((rating) => (
                <li
                  key={rating.value}
                  className="flex items-center justify-between rounded-lg border p-3 text-sm"
                >
                  <span className="font-medium">NTRP</span>
                  <span>
                    {rating.value}
                    {rating.level ? ` · ${rating.level}` : ''}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <div className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">Pending assessment</p>
              <p className="mt-1">
                An administrator will assign your starting rating. Once that's
                done you'll be eligible to be scheduled in matches.
              </p>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Rating history</CardTitle>
          <CardDescription>Changes from your rated matches.</CardDescription>
        </CardHeader>
        <CardContent>
          {historyQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : history.length > 0 ? (
            <ul className="space-y-2">
              {history.map((entry) => (
                <li
                  key={entry.id}
                  className="flex items-center justify-between rounded-lg border p-3 text-sm"
                >
                  <span className="text-muted-foreground">
                    {entry.calculatedAt.slice(0, 10)}
                  </span>
                  <span>
                    {entry.previousRating} → {entry.newRating}
                    {entry.levelChanged && entry.newLevel
                      ? ` (${entry.newLevel})`
                      : ''}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">
              No rating changes yet.
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
