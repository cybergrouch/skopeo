import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { interval } from '@/lib/searchInterval'
import type { GetApiV1UsersParams } from '@/api/generated/model'

const SEXES = ['Male', 'Female'] as const

/**
 * The calibration facet (#1065) — a tri-state on the form, because "Any" must send no param at all
 * while `false` ("Settled") is a real filter. An empty string can therefore never be coerced to a
 * boolean here; see `buildParams`.
 */
const CALIBRATION_OPTIONS = [
  { value: 'true', label: 'Calibrating' },
  { value: 'false', label: 'Settled' },
] as const

/** The lifecycle states, in the precedence the backend derives them (#1050). */
const STATUSES = ['ACTIVE', 'UNCLAIMED', 'DELETED', 'MERGED'] as const

const STATUS_LABELS: Record<(typeof STATUSES)[number], string> = {
  ACTIVE: 'Active',
  UNCLAIMED: 'Unclaimed',
  DELETED: 'Deleted',
  MERGED: 'Merged',
}

/**
 * The values each input starts from. One per input rather than the wire's interval strings, because
 * that is what the inputs actually hold — the age and rating ranges are two boxes each.
 */
export interface PlayerSearchFields {
  name: string
  sex: string
  status: string
  inCalibration: string
  ageMin: string
  ageMax: string
  ratingMin: string
  ratingMax: string
}

/** Every field blank: a form nobody has filled in yet, which is the default. */
const BLANK: PlayerSearchFields = {
  name: '',
  sex: '',
  status: '',
  inCalibration: '',
  ageMin: '',
  ageMax: '',
  ratingMin: '',
  ratingMax: '',
}

/**
 * The shared player-search filter form (name, sex, age range, NTRP rating range) used by the Research
 * tab (#107) and the Ratings tab's search-and-rate (#205). On submit it builds the filter params (or
 * null when no filter is set) and hands them to [onApply]; the parent owns pagination + results.
 *
 * [showStatus] adds the lifecycle-state dropdown (#1050) and the calibration dropdown (#1065). Off by
 * default, and deliberately so: the Ratings tab rates active players, and offering it a Deleted/Merged
 * filter there would invite a search whose every result is unratable — while a calibration filter there
 * narrows by a rating-history artefact a rater has no use for. Research turns both on, because Research
 * is where account history is the point. One flag governs both, so the two tabs cannot drift apart.
 *
 * [initial] seeds the inputs for a search that already exists — the Research tab restoring one from
 * the URL (#1054), where results are on screen and the form has to describe *them* rather than sit
 * blank above them. It is optional and defaults to blank, so the Ratings tab, which passes nothing,
 * behaves exactly as before.
 *
 * Note these are `useState` *initial* values, not a controlled mirror: re-seeding whenever [initial]
 * changed would overwrite whatever the user is halfway through typing, and the Research tab rewrites
 * its URL on every search and sort. The form is seeded once per mount, and a mount is precisely when
 * there is nothing to lose — which is also the case this exists for (Back re-mounts the tab).
 */
export function PlayerSearchForm({
  onApply,
  showStatus = false,
  initial = BLANK,
}: {
  onApply: (params: GetApiV1UsersParams | null) => void
  showStatus?: boolean
  initial?: PlayerSearchFields
}) {
  const [name, setName] = useState(initial.name)
  const [sex, setSex] = useState(initial.sex)
  const [status, setStatus] = useState(initial.status)
  const [inCalibration, setInCalibration] = useState(initial.inCalibration)
  const [ageMin, setAgeMin] = useState(initial.ageMin)
  const [ageMax, setAgeMax] = useState(initial.ageMax)
  const [ratingMin, setRatingMin] = useState(initial.ratingMin)
  const [ratingMax, setRatingMax] = useState(initial.ratingMax)

  function buildParams(): GetApiV1UsersParams | null {
    const params: GetApiV1UsersParams = {}
    if (name.trim()) params.name = name.trim()
    if (sex) params.sex = sex as GetApiV1UsersParams['sex']
    const age = interval(ageMin, ageMax)
    if (age) params.age = age
    const rating = interval(ratingMin, ratingMax)
    if (rating) params.rating = rating
    // Only send these where their dropdowns are rendered, so hiding one cannot leave a stale filter on.
    if (showStatus && status) params.status = status as GetApiV1UsersParams['status']
    // `''` is "Any" and sends nothing; `'false'` is a real filter. Hence the string compare rather than
    // a truthiness check, which would silently drop "Settled".
    if (showStatus && inCalibration) params.inCalibration = inCalibration === 'true'
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
      {showStatus ? (
        <div className="space-y-1">
          <Label htmlFor="r-status">Status</Label>
          <select
            id="r-status"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
          >
            <option value="">Any</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </div>
      ) : null}
      {showStatus ? (
        <div className="space-y-1">
          <Label htmlFor="r-calibration">Calibration</Label>
          <select
            id="r-calibration"
            value={inCalibration}
            onChange={(e) => setInCalibration(e.target.value)}
            className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
          >
            <option value="">Any</option>
            {CALIBRATION_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      ) : null}
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
