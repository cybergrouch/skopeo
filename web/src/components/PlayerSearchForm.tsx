import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { interval } from '@/lib/searchInterval'
import type { GetApiV1UsersParams } from '@/api/generated/model'

const SEXES = ['Male', 'Female'] as const

/**
 * The shared player-search filter form (name, sex, age range, NTRP rating range) used by the Research
 * tab (#107) and the Ratings tab's search-and-rate (#205). On submit it builds the filter params (or
 * null when no filter is set) and hands them to [onApply]; the parent owns pagination + results.
 */
export function PlayerSearchForm({
  onApply,
}: {
  onApply: (params: GetApiV1UsersParams | null) => void
}) {
  const [name, setName] = useState('')
  const [sex, setSex] = useState('')
  const [ageMin, setAgeMin] = useState('')
  const [ageMax, setAgeMax] = useState('')
  const [ratingMin, setRatingMin] = useState('')
  const [ratingMax, setRatingMax] = useState('')

  function buildParams(): GetApiV1UsersParams | null {
    const params: GetApiV1UsersParams = {}
    if (name.trim()) params.name = name.trim()
    if (sex) params.sex = sex as GetApiV1UsersParams['sex']
    const age = interval(ageMin, ageMax)
    if (age) params.age = age
    const rating = interval(ratingMin, ratingMax)
    if (rating) params.rating = rating
    return Object.keys(params).length > 0 ? params : null
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    onApply(buildParams())
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <div className="space-y-1">
        <Label htmlFor="r-name">Name</Label>
        <Input id="r-name" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="space-y-1">
        <Label htmlFor="r-sex">Sex</Label>
        <select
          id="r-sex"
          value={sex}
          onChange={(e) => setSex(e.target.value)}
          className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
        >
          <option value="">Any</option>
          {SEXES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>
      <fieldset className="flex items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor="r-age-min">Age from</Label>
          <Input
            id="r-age-min"
            inputMode="numeric"
            className="w-20"
            value={ageMin}
            onChange={(e) => setAgeMin(e.target.value)}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="r-age-max">to</Label>
          <Input
            id="r-age-max"
            inputMode="numeric"
            className="w-20"
            value={ageMax}
            onChange={(e) => setAgeMax(e.target.value)}
          />
        </div>
      </fieldset>
      <fieldset className="flex items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor="r-rating-min">Rating from</Label>
          <Input
            id="r-rating-min"
            inputMode="decimal"
            className="w-20"
            value={ratingMin}
            onChange={(e) => setRatingMin(e.target.value)}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="r-rating-max">to</Label>
          <Input
            id="r-rating-max"
            inputMode="decimal"
            className="w-20"
            value={ratingMax}
            onChange={(e) => setRatingMax(e.target.value)}
          />
        </div>
      </fieldset>
      <Button type="submit" disabled={buildParams() === null}>
        Search
      </Button>
    </form>
  )
}
