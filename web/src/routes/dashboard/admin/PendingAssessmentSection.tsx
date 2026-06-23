import { useState, type FormEvent } from 'react'
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


function PendingRow({ user }: { user: PendingAssessmentResponse }) {
  const queryClient = useQueryClient()
  const [value, setValue] = useState('')
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

  return (
    <li className="rounded-lg border p-3">
      <div className="mb-2 text-sm font-medium">
        {user.displayName ?? user.userId}
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
  const pendingQuery = useGetApiV1UsersPendingAssessment()
  const pending = pendingQuery.data ?? []

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
        ) : pending.length > 0 ? (
          <ul className="space-y-3">
            {pending.map((user) => (
              <PendingRow key={user.userId} user={user} />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">
            No players are pending assessment.
          </p>
        )}
      </CardContent>
    </Card>
  )
}
