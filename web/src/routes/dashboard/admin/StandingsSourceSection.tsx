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
 * success the source query is invalidated so the Standings tab picks up the change on its next fetch.
 * The Admin tab is already ADMINISTRATOR-gated, so no extra gating here. Points only takes effect once
 * a points snapshot has been committed; until then the tab safely falls back to the rating snapshot.
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
          points snapshot has been calculated; until then it safely falls back to ratings.
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
