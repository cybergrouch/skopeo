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
import { Input } from '@/components/ui/input'
import {
  getGetApiV1RatingRequestsQueryKey,
  useGetApiV1RatingRequests,
  usePostApiV1RatingRequestsIdApprove,
  usePostApiV1RatingRequestsIdDeny,
} from '@/api/generated/ratings/ratings'
import type { RatingRequestResponse } from '@/api/generated/model'

/** "Name (CODE)" for a resolved requester, or a short id fallback. */
function requesterLabel(r: RatingRequestResponse): string {
  const p = r.requester
  if (!p) return r.userId.slice(0, 8)
  return `${p.displayName ?? p.userId.slice(0, 8)} (${p.publicCode ?? '—'})`
}

function RequestRow({ request }: { request: RatingRequestResponse }) {
  const queryClient = useQueryClient()
  const [rating, setRating] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: getGetApiV1RatingRequestsQueryKey() })
  const approve = usePostApiV1RatingRequestsIdApprove({ mutation: { onSuccess: invalidate } })
  const deny = usePostApiV1RatingRequestsIdDeny({ mutation: { onSuccess: invalidate } })

  return (
    <li className="space-y-2 rounded-lg border p-3 text-sm">
      <div className="font-medium">{requesterLabel(request)}</div>
      <p className="text-muted-foreground">{request.justification}</p>
      <div className="flex flex-wrap items-end gap-2">
        <div className="flex items-end gap-1">
          <Input
            aria-label="New rating"
            className="h-8 w-24"
            placeholder="e.g. 4.5"
            value={rating}
            onChange={(e) => setRating(e.target.value)}
          />
          <Button
            type="button"
            size="sm"
            disabled={rating.trim() === '' || approve.isPending}
            onClick={() => {
              setError(null)
              approve.mutate(
                { id: request.id, data: { rating: rating.trim() } },
                { onError: () => setError('Could not approve. Check the rating value.') },
              )
            }}
          >
            Approve
          </Button>
        </div>
        <div className="flex flex-1 items-end gap-1">
          <Input
            aria-label="Denial reason"
            className="h-8 flex-1"
            placeholder="Reason for denial"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={reason.trim() === '' || deny.isPending}
            onClick={() => {
              setError(null)
              deny.mutate(
                { id: request.id, data: { reason: reason.trim() } },
                { onError: () => setError('Could not deny the request.') },
              )
            }}
          >
            Deny
          </Button>
        </div>
      </div>
      {error ? (
        <p className="text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </li>
  )
}

/**
 * Re-rate request triage (#140): the open requests a RATER approves (applying a new rating) or
 * denies (with a reason). Sits in the Ratings tab beside the pending-assessment queue.
 */
export function RatingRequestSection() {
  const query = useGetApiV1RatingRequests({ status: 'PENDING' })
  const requests = query.data?.items ?? []

  return (
    <Card>
      <CardHeader>
        <CardTitle>Re-rate requests</CardTitle>
        <CardDescription>
          Players asking for a rating reconsideration. Approve with a new rating, or deny with a
          reason.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : requests.length > 0 ? (
          <ul className="space-y-2">
            {requests.map((request) => (
              <RequestRow key={request.id} request={request} />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">No open re-rate requests.</p>
        )}
      </CardContent>
    </Card>
  )
}
