import { useState, type FormEvent } from 'react'
import { NtrpDisclaimerInfo } from '@/components/NtrpLabel'
import { toastError } from '@/observability/toastError'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NTRP_LEVELS } from '@/lib/ntrp'
import { usePutApiV1UsersUserIdRatings } from '@/api/generated/ratings/ratings'

/**
 * Set/override a user's NTRP rating (`PUT /api/v1/users/{id}/ratings`) by picking an NTRP band from a
 * dropdown (#206); the backend stores the band MIDPOINT (e.g. 3.5 → 3.75) so the rating sits centered
 * in its band rather than at the edge. Shared by the Ratings tab's pending-assessment queue (#96) and
 * its search-and-rate results (#205). [initialValue] preselects a band (e.g. a self-reported rating or
 * the player's current band); [onSaved] runs after a successful save (e.g. cache invalidation).
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
  const [band, setBand] = useState(initialValue)
  const setRating = usePutApiV1UsersUserIdRatings()

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    try {
      await setRating.mutateAsync({ userId, data: { band } })
      onSaved?.()
    } catch (error) {
      toastError('Could not set the rating. Check the value and try again.', { cause: error, duration: 8000 })
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-2">
      <div className="space-y-1">
        <Label htmlFor={`value-${userId}`} className="text-xs">
          Rating
        </Label>{' '}
        <NtrpDisclaimerInfo />
        <select
          id={`value-${userId}`}
          value={band}
          onChange={(e) => setBand(e.target.value)}
          required
          className="flex h-9 w-24 rounded-md border border-input bg-transparent px-3 text-sm"
        >
          <option value="" disabled>
            NTRP…
          </option>
          {NTRP_LEVELS.map((level) => (
            <option key={level} value={level}>
              {level}
            </option>
          ))}
        </select>
      </div>
      <Button type="submit" size="sm" disabled={setRating.isPending}>
        {setRating.isPending ? 'Setting…' : 'Set rating'}
      </Button>
    </form>
  )
}
