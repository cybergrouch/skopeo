import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { usePutApiV1UsersUserIdRatings } from '@/api/generated/ratings/ratings'

/**
 * Set/override a user's NTRP rating (`PUT /api/v1/users/{id}/ratings`). Shared by the Ratings tab's
 * pending-assessment queue (#96) and its search-and-rate results (#205). [initialValue] prefills the
 * input (e.g. a self-reported rating); [onSaved] runs after a successful save (e.g. cache invalidation).
 */
export function SetRatingForm({
  userId,
  initialValue = '',
  onSaved,
}: {
  userId: string
  initialValue?: string
  onSaved?: () => void
}) {
  const [value, setValue] = useState(initialValue)
  const [error, setError] = useState<string | null>(null)
  const setRating = usePutApiV1UsersUserIdRatings()

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await setRating.mutateAsync({ userId, data: { value } })
      onSaved?.()
    } catch {
      setError('Could not set the rating. Check the value and try again.')
    }
  }

  return (
    <>
      <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor={`value-${userId}`} className="text-xs">
            Rating
          </Label>
          <Input
            id={`value-${userId}`}
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
    </>
  )
}
