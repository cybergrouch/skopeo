import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render } from '@testing-library/react'
import { ThemeProvider, LocalThemeApplier } from './ThemeProvider'

const { useGetApiV1Theme, useGetApiV1UsersMeTheme, useAuth } = vi.hoisted(() => ({
  useGetApiV1Theme: vi.fn(),
  useGetApiV1UsersMeTheme: vi.fn(),
  useAuth: vi.fn(),
}))

vi.mock('@/api/generated/settings/settings', () => ({
  useGetApiV1Theme,
  useGetApiV1UsersMeTheme,
}))

vi.mock('@/auth/useAuth', () => ({ useAuth }))

// ThemeProvider sets the global data-theme; LocalThemeApplier (mounted as a child, as in App.tsx)
// overrides it with the effective theme. Rendering both mirrors the real tree.
function renderTree() {
  return render(
    <ThemeProvider>
      <LocalThemeApplier />
    </ThemeProvider>,
  )
}

describe('LocalThemeApplier (per-user local theme, #514)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    delete document.documentElement.dataset.theme
    // A signed-in user by default; individual tests override.
    useAuth.mockReturnValue({ user: { uid: 'u1' } })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('applies a logged-in user local theme over the global one', () => {
    // Aug 20: uso season. Global AUTO, but the user picked GRASS this season → local wins.
    vi.setSystemTime(new Date(2026, 7, 20))
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'AUTO' } })
    useGetApiV1UsersMeTheme.mockReturnValue({
      data: { theme: 'GRASS', setAt: new Date(2026, 7, 10).toISOString() },
    })

    renderTree()
    expect(document.documentElement.dataset.theme).toBe('grass')
  })

  it('a new season takes over when it started after the user set local', () => {
    // User set CLAY in the grass window (Jun 15); now Aug 20 (uso starts Aug 1 > Jun 15) → seasonal.
    vi.setSystemTime(new Date(2026, 7, 20))
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'AUTO' } })
    useGetApiV1UsersMeTheme.mockReturnValue({
      data: { theme: 'CLAY', setAt: new Date(2026, 5, 15).toISOString() },
    })

    renderTree()
    expect(document.documentElement.dataset.theme).toBe('uso')
  })

  it('a fixed global never overrides the local choice', () => {
    vi.setSystemTime(new Date(2026, 7, 20))
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'US_OPEN' } })
    useGetApiV1UsersMeTheme.mockReturnValue({
      data: { theme: 'CLAY', setAt: new Date(2026, 0, 1).toISOString() },
    })

    renderTree()
    expect(document.documentElement.dataset.theme).toBe('clay')
  })

  it('logged-out visitors keep the global-only theme (local query disabled)', () => {
    vi.setSystemTime(new Date(2026, 0, 15)) // AO window
    useAuth.mockReturnValue({ user: null })
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'AUTO' } })
    // Disabled query returns no data.
    useGetApiV1UsersMeTheme.mockReturnValue({ data: undefined })

    renderTree()
    // ThemeProvider's global-only value stands: AUTO → ao.
    expect(document.documentElement.dataset.theme).toBe('ao')

    // The local-theme query is disabled while logged out.
    expect(useGetApiV1UsersMeTheme).toHaveBeenCalledWith({
      query: expect.objectContaining({ enabled: false }),
    })
  })

  it('a logged-in user with no local theme follows the global theme', () => {
    vi.setSystemTime(new Date(2026, 0, 15)) // AO window
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'AUTO' } })
    useGetApiV1UsersMeTheme.mockReturnValue({ data: { theme: null, setAt: null } })

    renderTree()
    expect(document.documentElement.dataset.theme).toBe('ao')
  })
})
