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
import { Label } from '@/components/ui/label'
import {
  getGetApiV1SettingsStandingsSourceQueryKey,
  useGetApiV1SettingsStandingsSource,
  usePutApiV1SettingsStandingsSource,
} from '@/api/generated/settings/settings'
import { getGetApiV1StandingsQueryKey } from '@/api/generated/standings/standings'
import type { SetStandingsSourceRequestSource } from '@/api/generated/model'

// The setting enum + human labels. RATING orders standings by current rating; POINTS by the ranking-points ledger.
const SOURCE_OPTIONS: ReadonlyArray<{
  value: SetStandingsSourceRequestSource
  label: string
}> = [
  { value: 'RATING', label: 'Rating' },
  { value: 'POINTS', label: 'Points' },
]

/**
 * Admin standings-source picker (#146): reads the current serving source and writes a new one. On
 * success the source query AND the standings page query are invalidated so the Standings tab reflects the
 * flip without a manual reload (#428). The Admin tab is already ADMINISTRATOR-gated, so no extra gating
 * here. Selecting POINTS before a points snapshot exists shows an explicit empty state on the Standings
 * tab (no silent fall-back to ratings, #428).
 */
export function StandingsSourceSection() {
  const queryClient = useQueryClient()
  const sourceQuery = useGetApiV1SettingsStandingsSource({ query: { retry: false } })
  const current = sourceQuery.data?.source as
    | SetStandingsSourceRequestSource
    | undefined

  // The select shows the admin's pending pick if made, else the loaded setting (defaulting to RATING
  // before it loads) — no effect needed to sync to the fetched value.
  const [selected, setSelected] = useState<SetStandingsSourceRequestSource | null>(
    null,
  )
  const value: SetStandingsSourceRequestSource = selected ?? current ?? 'RATING'
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const setSource = usePutApiV1SettingsStandingsSource({
    mutation: {
      onSuccess: () => {
        setSaved(true)
        queryClient.invalidateQueries({
          queryKey: getGetApiV1SettingsStandingsSourceQueryKey(),
        })
        // Refresh the Standings tab so the new source is reflected without a manual reload (#428).
        queryClient.invalidateQueries({
          queryKey: getGetApiV1StandingsQueryKey(),
        })
      },
      onError: () => setError('Could not set the standings source. Try again.'),
    },
  })

  const onSave = () => {
    setSaved(false)
    setError(null)
    setSource.mutate({ data: { source: value } })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Standings source</CardTitle>
        <CardDescription>
          Choose which ranking the Standings tab serves. Points takes effect only once a
          points snapshot has been calculated; until then the Standings tab shows an empty
          points state rather than falling back to ratings.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor="admin-standings-source" className="text-xs">
            Source
          </Label>
          <select
            id="admin-standings-source"
            className="h-9 rounded-md border bg-transparent px-3 py-1 text-sm"
            value={value}
            disabled={sourceQuery.isLoading}
            onChange={(e) => {
              setSelected(e.target.value as SetStandingsSourceRequestSource)
              setSaved(false)
            }}
          >
            {SOURCE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        <Button type="button" size="sm" onClick={onSave} disabled={setSource.isPending}>
          Save source
        </Button>
        {saved ? (
          <span className="text-xs text-muted-foreground" role="status">
            Saved
          </span>
        ) : null}
        {error ? (
          <span className="text-xs text-destructive" role="alert">
            {error}
          </span>
        ) : null}
      </CardContent>
    </Card>
  )
}
