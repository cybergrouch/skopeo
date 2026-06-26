import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
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
import { useGetApiV1Users } from '@/api/generated/users/users'
import type {
  GetApiV1UsersParams,
  UserSummaryResponse,
} from '@/api/generated/model'

const SEXES = ['Male', 'Female'] as const

/** "Female · 34" — sex and age, omitting whatever is missing. */
function metaLine(user: UserSummaryResponse): string {
  const parts: string[] = []
  if (user.sex) parts.push(user.sex)
  if (user.age != null) parts.push(String(user.age))
  return parts.join(' · ')
}

/** Build inclusive interval notation from optional min/max (e.g. "[3.0,4.0]", "[3.0,)", "(,30]"). */
function interval(min: string, max: string): string | undefined {
  const lo = min.trim()
  const hi = max.trim()
  if (!lo && !hi) return undefined
  return `${lo ? `[${lo}` : '('},${hi ? `${hi}]` : ')'}`
}

export function ResearchTab() {
  const [name, setName] = useState('')
  const [sex, setSex] = useState('')
  const [ageMin, setAgeMin] = useState('')
  const [ageMax, setAgeMax] = useState('')
  const [ratingMin, setRatingMin] = useState('')
  const [ratingMax, setRatingMax] = useState('')
  const [applied, setApplied] = useState<GetApiV1UsersParams | null>(null)

  const query = useGetApiV1Users(applied ?? {}, { query: { enabled: applied !== null } })
  const results = query.data ?? []

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

  function onSearch(event: FormEvent) {
    event.preventDefault()
    setApplied(buildParams())
  }

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Player research</CardTitle>
          <CardDescription>
            Find members by name, sex, age, and NTRP rating. Combine any filters.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSearch} className="space-y-3">
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
        </CardContent>
      </Card>

      {applied !== null ? (
        <Card>
          <CardHeader>
            <CardTitle>Results</CardTitle>
          </CardHeader>
          <CardContent>
            {query.isLoading ? (
              <p className="text-sm text-muted-foreground">Searching…</p>
            ) : query.isError ? (
              <p className="text-sm text-destructive" role="alert">
                Invalid filters. Check the age/rating ranges.
              </p>
            ) : results.length > 0 ? (
              <ul className="space-y-2">
                {results.map((user) => {
                  const meta = metaLine(user)
                  return (
                    <li key={user.id}>
                      <Link
                        to={`/players/${user.publicCode}`}
                        className="flex items-center gap-3 rounded-lg border p-3 text-sm hover:bg-muted/50"
                      >
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
                        <div className="min-w-0 flex-1">
                          <div className="font-medium">
                            {user.displayName ?? user.id}
                          </div>
                          {meta ? (
                            <div className="text-muted-foreground">{meta}</div>
                          ) : null}
                        </div>
                        {user.rating ? (
                          <span className="shrink-0 font-medium">
                            NTRP {user.rating.level ?? user.rating.value}
                          </span>
                        ) : null}
                      </Link>
                    </li>
                  )
                })}
              </ul>
            ) : (
              <p className="text-sm text-muted-foreground">No matching players.</p>
            )}
          </CardContent>
        </Card>
      ) : null}
    </div>
  )
}
