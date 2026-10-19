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
import { toast } from 'sonner'
import { toastError } from '@/observability/toastError'
import { THEME_OPTIONS } from './themeOptions'
import { ThemeScheduleEditor } from './ThemeScheduleEditor'

/**
 * Admin theme picker (#378): reads the current global theme and writes a new one. On success the
 * theme query is invalidated so every client (including this one, via the ThemeProvider poll)
 * picks up the change. The Admin tab is already ADMINISTRATOR-gated, so no extra gating here.
 *
 * Below the picker sits the AUTO schedule editor (#1196), which saves separately.
 */
export function ThemeSection() {
  const queryClient = useQueryClient()
  const themeQuery = useGetApiV1Theme({ query: { retry: false } })
  const current = themeQuery.data?.theme as SetThemeRequestTheme | undefined

  // The select shows the admin's pending pick if they've made one, otherwise the loaded setting
  // (defaulting to AUTO before it loads) — no effect needed to sync to the fetched value.
  const [selected, setSelected] = useState<SetThemeRequestTheme | null>(null)
  const value: SetThemeRequestTheme = selected ?? current ?? 'AUTO'

  const setTheme = usePutApiV1Theme({
    mutation: {
      onSuccess: () => {
        toast.success('Saved')
        queryClient.invalidateQueries({ queryKey: getGetApiV1ThemeQueryKey() })
      },
      onError: (error) => toastError('Could not set the theme. Try again.', { cause: error, duration: 8000 }),
    },
  })

  const onSave = () => {
    setTheme.mutate({ data: { theme: value } })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>UI theme</CardTitle>
        <CardDescription>
          Set the global UI theme for every client. Auto follows the season schedule below.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <div className="flex flex-wrap items-end gap-2">
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
        </div>
        <ThemeScheduleEditor schedule={themeQuery.data?.schedule} loading={themeQuery.isLoading} />
      </CardContent>
    </Card>
  )
}
