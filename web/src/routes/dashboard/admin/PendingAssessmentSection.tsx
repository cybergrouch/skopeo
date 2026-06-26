import { useState, type FormEvent } from 'react'
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
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  getGetApiV1UsersPendingAssessmentQueryKey,
  useGetApiV1UsersPendingAssessment,
  usePutApiV1UsersUserIdRatings,
} from '@/api/generated/ratings/ratings'
import type { PendingAssessmentResponse } from '@/api/generated/model'

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
  // Prefill with the self-reported rating so the admin can approve it as-is (or override).
  const [value, setValue] = useState(user.proposedRating ?? '')
  const [error, setError] = useState<string | null>(null)

  const setRating = usePutApiV1UsersUserIdRatings({
    mutation: {
      onSuccess: () =>
        queryClient.invalidateQueries({
          queryKey: getGetApiV1UsersPendingAssessmentQueryKey(),
        }),
    },
  })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await setRating.mutateAsync({ userId: user.userId, data: { value } })
    } catch {
      setError('Could not set the rating. Check the value and try again.')
    }
  }

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
      <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor={`value-${user.userId}`} className="text-xs">
            Rating
          </Label>
          <Input
            id={`value-${user.userId}`}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="4.0"
            className="w-24"
            required
          />
        </div>
        <Button type="submit" size="sm" disabled={setRating.isPending}>
          {setRating.isPending ? 'Setting…' : 'Set rating'}
        </Button>
      </form>
      {error ? (
        <p className="mt-2 text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
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
          to be scheduled in matches.
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
