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
  getGetApiV1ThemeQueryKey,
  useGetApiV1Theme,
  usePutApiV1Theme,
} from '@/api/generated/settings/settings'
import type { SetThemeRequestTheme } from '@/api/generated/model'

// The setting enum + human labels. AUTO resolves by date on each client; the rest pin a theme.
const THEME_OPTIONS: ReadonlyArray<{ value: SetThemeRequestTheme; label: string }> = [
  { value: 'AUTO', label: 'Auto (by season)' },
  { value: 'GRASS', label: 'Grass' },
  { value: 'CLAY', label: 'Clay' },
  { value: 'AO', label: 'Australian Open' },
  { value: 'US_OPEN', label: 'US Open' },
  { value: 'OFF_SEASON', label: 'Off-Season' },
  { value: 'CHRISTMAS', label: 'Christmas' },
  { value: 'VALENTINES', label: "Valentine's Day" },
  { value: 'SPRING', label: 'Spring' },
  { value: 'RAINY', label: 'Rainy' },
  { value: 'HALLOWEEN', label: 'Halloween' },
  { value: 'AUTUMN', label: 'Autumn' },
  // Skopeo OG (#512): the original all-white look, manually-selectable (not in the AUTO rotation).
  { value: 'SKOPEO_OG', label: 'Skopeo OG' },
]

/**
 * Admin theme picker (#378): reads the current global theme and writes a new one. On success the
 * theme query is invalidated so every client (including this one, via the ThemeProvider poll)
 * picks up the change. The Admin tab is already ADMINISTRATOR-gated, so no extra gating here.
 */
export function ThemeSection() {
  const queryClient = useQueryClient()
  const themeQuery = useGetApiV1Theme({ query: { retry: false } })
  const current = themeQuery.data?.theme as SetThemeRequestTheme | undefined

  // The select shows the admin's pending pick if they've made one, otherwise the loaded setting
  // (defaulting to AUTO before it loads) — no effect needed to sync to the fetched value.
  const [selected, setSelected] = useState<SetThemeRequestTheme | null>(null)
  const value: SetThemeRequestTheme = selected ?? current ?? 'AUTO'
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const setTheme = usePutApiV1Theme({
    mutation: {
      onSuccess: () => {
        setSaved(true)
        queryClient.invalidateQueries({ queryKey: getGetApiV1ThemeQueryKey() })
      },
      onError: () => setError('Could not set the theme. Try again.'),
    },
  })

  const onSave = () => {
    setSaved(false)
    setError(null)
    setTheme.mutate({ data: { theme: value } })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>UI theme</CardTitle>
        <CardDescription>
          Set the global UI theme for every client. Auto resolves by tennis season.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor="admin-theme" className="text-xs">
            Theme
          </Label>
          <select
            id="admin-theme"
            className="h-9 rounded-md border bg-transparent px-3 py-1 text-sm"
            value={value}
            disabled={themeQuery.isLoading}
            onChange={(e) => {
              setSelected(e.target.value as SetThemeRequestTheme)
              setSaved(false)
            }}
          >
            {THEME_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        <Button type="button" size="sm" onClick={onSave} disabled={setTheme.isPending}>
          Save theme
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
