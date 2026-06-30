import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { SetRatingForm } from '@/components/SetRatingForm'
import {
  getGetApiV1UsersPendingAssessmentQueryKey,
  useGetApiV1UsersPendingAssessment,
} from '@/api/generated/ratings/ratings'
import type { PendingAssessmentResponse } from '@/api/generated/model'
import { NTRP_SELF_RATING_GUIDE_URL } from '@/lib/ntrp'

const PAGE_SIZE = 20

/** "Female · 34 (1990-03-15)" — sex, then age with the birth date, omitting whatever is missing. */
function metaLine(user: PendingAssessmentResponse): string {
  const parts: string[] = []
  if (user.sex) parts.push(user.sex)
  if (user.age != null) {
    parts.push(user.dateOfBirth ? `${user.age} (${user.dateOfBirth})` : String(user.age))
  } else if (user.dateOfBirth) {
    parts.push(user.dateOfBirth)
  }
  return parts.join(' · ')
}

function PendingRow({ user }: { user: PendingAssessmentResponse }) {
  const queryClient = useQueryClient()
  const meta = metaLine(user)

  return (
    <li className="rounded-lg border p-3">
      <div className="mb-2 flex items-center gap-3">
        {user.photoUrl ? (
          <img
            src={user.photoUrl}
            alt=""
            referrerPolicy="no-referrer"
            className="h-9 w-9 shrink-0 rounded-full object-cover"
          />
        ) : (
          <div
            aria-hidden="true"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted text-sm font-medium text-muted-foreground"
          >
            {(user.displayName ?? 'P').charAt(0).toUpperCase()}
          </div>
        )}
        <div className="min-w-0">
          <Link
            to={`/players/${user.publicCode}`}
            className="text-sm font-medium hover:underline"
          >
            {user.displayName ?? user.userId}
          </Link>
          {meta ? (
            <div className="text-xs text-muted-foreground">{meta}</div>
          ) : null}
          {user.proposedRating ? (
            <div className="text-xs text-muted-foreground">
              Self-rated:{' '}
              <span className="font-medium text-foreground">
                {user.proposedRating}
              </span>
            </div>
          ) : null}
        </div>
      </div>
      {/* Prefill with the self-reported rating so the admin can approve it as-is (or override). */}
      <SetRatingForm
        userId={user.userId}
        initialValue={user.proposedRating ?? ''}
        onSaved={() =>
          queryClient.invalidateQueries({ queryKey: getGetApiV1UsersPendingAssessmentQueryKey() })
        }
      />
    </li>
  )
}

export function PendingAssessmentSection() {
  const [page, setPage] = useState(0)
  const pendingQuery = useGetApiV1UsersPendingAssessment({
    limit: PAGE_SIZE,
    offset: page * PAGE_SIZE,
  })
  const items = pendingQuery.data?.items ?? []
  const total = pendingQuery.data?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <Card>
      <CardHeader>
        <CardTitle>Pending assessment</CardTitle>
        <CardDescription>
          Players awaiting an initial rating. Assigning one makes them eligible
          to be scheduled in matches.{' '}
          <a
            href={NTRP_SELF_RATING_GUIDE_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary hover:underline"
          >
            NTRP self-rating guide
          </a>
        </CardDescription>
      </CardHeader>
      <CardContent>
        {pendingQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : items.length > 0 ? (
          <>
            <ul className="space-y-3">
              {items.map((user) => (
                <PendingRow key={user.userId} user={user} />
              ))}
            </ul>
            {total > PAGE_SIZE ? (
              <div className="mt-4 flex items-center justify-between text-sm">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={page === 0}
                  onClick={() => setPage((p) => p - 1)}
                >
                  Previous
                </Button>
                <span className="text-muted-foreground">
                  Page {page + 1} of {pageCount} · {total} total
                </span>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={page >= pageCount - 1}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                </Button>
              </div>
            ) : null}
          </>
        ) : (
          <p className="text-sm text-muted-foreground">
            No players are pending assessment.
          </p>
        )}
      </CardContent>
    </Card>
  )
}
