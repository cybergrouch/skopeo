import { useEffect } from 'react'
import { useGetApiV1Theme } from '@/api/generated/settings/settings'
import { resolveActiveTheme } from '@/lib/season'

/**
 * Global theme provider (#378). Polls the public `GET /api/v1/theme` setting (~60s, plus on tab
 * focus) and live-swaps the CSS token set by setting `document.documentElement.dataset.theme` —
 * no reload, no flash. An hourly tick re-resolves AUTO across a day-rollover. Mounted inside the
 * QueryClientProvider (the GET needs no auth token), wrapping the rest of the app.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { data } = useGetApiV1Theme({
    query: {
      refetchInterval: 60_000,
      refetchOnWindowFocus: true,
      staleTime: 30_000,
      retry: false,
    },
  })
  const setting = data?.theme

  useEffect(() => {
    const apply = () => {
      document.documentElement.dataset.theme = resolveActiveTheme(setting, new Date())
    }
    apply()
    // Re-resolve periodically so an AUTO day-rollover swaps the season without a refetch.
    const id = setInterval(apply, 60 * 60_000)
    return () => clearInterval(id)
  }, [setting])

  return <>{children}</>
}
