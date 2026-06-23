import { useNavigate } from 'react-router-dom'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/auth/useAuth'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import { useGetApiV1UsersUserIdRatings } from '@/api/generated/ratings/ratings'

export function PendingAssessmentPage() {
  const navigate = useNavigate()
  const { user, signOut } = useAuth()

  const meQuery = useGetApiV1UsersMe()
  const userId = meQuery.data?.id
  const ratingsQuery = useGetApiV1UsersUserIdRatings(userId ?? '', {
    query: { enabled: Boolean(userId) },
  })

  const greeting = user?.displayName ?? user?.email ?? 'player'
  const ratings = ratingsQuery.data ?? []
  const hasRating = ratings.length > 0
  const loading = meQuery.isLoading || ratingsQuery.isLoading

  async function onSignOut() {
    await signOut()
    navigate('/login', { replace: true })
  }

  return (
    <div className="flex min-h-svh items-center justify-center bg-muted/40 p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="text-2xl">Welcome, {greeting}</CardTitle>
          <CardDescription>
            {loading
              ? 'Loading your profile…'
              : hasRating
                ? 'Your rating is set.'
                : 'Your account is awaiting an initial rating.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {!loading && !hasRating ? (
            <div className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">Pending assessment</p>
              <p className="mt-1">
                An administrator will assign your starting rating. Once that's
                done you'll be eligible to be scheduled in matches.
              </p>
            </div>
          ) : null}

          {!loading && hasRating ? (
            <ul className="space-y-2">
              {ratings.map((rating) => (
                <li
                  key={rating.system}
                  className="flex items-center justify-between rounded-lg border p-3 text-sm"
                >
                  <span className="font-medium">{rating.system}</span>
                  <span>
                    {rating.value}
                    {rating.level ? ` · ${rating.level}` : ''}
                  </span>
                </li>
              ))}
            </ul>
          ) : null}

          <Button variant="outline" className="w-full" onClick={onSignOut}>
            Sign out
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
