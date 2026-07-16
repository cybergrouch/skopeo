import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render } from '@testing-library/react'
import { ThemeProvider } from './ThemeProvider'

const { useGetApiV1Theme } = vi.hoisted(() => ({
  useGetApiV1Theme: vi.fn(),
}))

vi.mock('@/api/generated/settings/settings', () => ({
  useGetApiV1Theme,
  getGetApiV1ThemeQueryKey: () => ['theme'],
}))

function renderProvider() {
  return render(
    <ThemeProvider>
      <div>app</div>
    </ThemeProvider>,
  )
}

describe('ThemeProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // A fixed date inside the January (AO) window, so AUTO resolves deterministically to `ao`.
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 0, 15))
    delete document.documentElement.dataset.theme
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('resolves AUTO by date onto data-theme', () => {
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'AUTO' } })
    renderProvider()
    expect(document.documentElement.dataset.theme).toBe('ao')
  })

  it('maps an explicit theme enum to its data-theme name', () => {
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'US_OPEN' } })
    renderProvider()
    expect(document.documentElement.dataset.theme).toBe('uso')
  })

  it('falls back to the season resolver when the setting is missing/unknown', () => {
    useGetApiV1Theme.mockReturnValue({ data: undefined })
    renderProvider()
    expect(document.documentElement.dataset.theme).toBe('ao')
  })

  it('re-applies when the query data changes', () => {
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'GRASS' } })
    const { rerender } = renderProvider()
    expect(document.documentElement.dataset.theme).toBe('grass')

    useGetApiV1Theme.mockReturnValue({ data: { theme: 'CLAY' } })
    rerender(
      <ThemeProvider>
        <div>app</div>
      </ThemeProvider>,
    )
    expect(document.documentElement.dataset.theme).toBe('clay')
  })

  it('passes polling query options to the generated hook', () => {
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'AUTO' } })
    renderProvider()
    expect(useGetApiV1Theme).toHaveBeenCalledWith({
      query: {
        refetchInterval: 60_000,
        refetchOnWindowFocus: true,
        staleTime: 30_000,
        retry: false,
      },
    })
  })
})
