import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  getGetApiV1RatingRequestsMeQueryKey,
  useGetApiV1RatingRequestsMe,
  usePostApiV1RatingRequests,
} from '@/api/generated/ratings/ratings'

/**
 * The owner's re-rate request control (#140): if they have an open (PENDING) request, show its
 * status; otherwise let them raise one with a justification. A resolved request's outcome is shown
 * above the form so they can see an approval/denial before raising another.
 */
export function ReRateRequestCard() {
  const queryClient = useQueryClient()
  const [justification, setJustification] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mineQuery = useGetApiV1RatingRequestsMe()
  const mine = mineQuery.data
  const create = usePostApiV1RatingRequests({
    mutation: {
      onSuccess: () => {
        setJustification('')
        void queryClient.invalidateQueries({ queryKey: getGetApiV1RatingRequestsMeQueryKey() })
      },
    },
  })

  const pending = mine?.status === 'PENDING'

  return (
    <Card>
      <CardHeader>
        <CardTitle>Rating reconsideration</CardTitle>
        <CardDescription>
          Think your rating is off? Ask a rater to take another look, with a short justification.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {mine && mine.status === 'APPROVED' ? (
          <p className="text-sm text-muted-foreground" role="status">
            Your last request was <span className="font-medium text-foreground">approved</span>
            {mine.newRating ? ` — new band ${mine.newRating}.` : '.'}
          </p>
        ) : null}
        {mine && mine.status === 'DENIED' ? (
          <p className="text-sm text-muted-foreground" role="status">
            Your last request was <span className="font-medium text-foreground">denied</span>
            {mine.reason ? `: ${mine.reason}` : '.'}
          </p>
        ) : null}

        {pending ? (
          <div className="rounded-lg border border-dashed p-3 text-sm">
            <p className="font-medium text-foreground">Your request is pending review.</p>
            <p className="mt-1 text-muted-foreground">{mine?.justification}</p>
          </div>
        ) : (
          <div className="space-y-2">
            <textarea
              aria-label="Justification"
              className="min-h-20 w-full rounded-md border bg-background p-2 text-sm"
              placeholder="Why should your rating be reconsidered?"
              value={justification}
              onChange={(e) => setJustification(e.target.value)}
            />
            <Button
              type="button"
              size="sm"
              disabled={justification.trim() === '' || create.isPending}
              onClick={() => {
                setError(null)
                create.mutate(
                  { data: { justification: justification.trim() } },
                  { onError: () => setError('Could not submit your request. Please try again.') },
                )
              }}
            >
              Request re-rate
            </Button>
            {error ? (
              <p className="text-sm text-destructive" role="alert">
                {error}
              </p>
            ) : null}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
