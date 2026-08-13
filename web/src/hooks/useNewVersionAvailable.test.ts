import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { useNewVersionAvailable } from './useNewVersionAvailable'
import { APP_BUILD } from '@/lib/appBuild'

// The hook is inert on a dev build (nothing to be behind), and tests run as one — so give the module a
// deployed identity to compare against.
vi.mock('@/lib/appBuild', async () => {
  const actual = await vi.importActual<typeof import('@/lib/appBuild')>('@/lib/appBuild')
  return {
    ...actual,
    APP_BUILD: { version: '1.0.0', commit: 'aaaa111', builtAt: '2026-08-01T00:00:00.000Z' },
    IS_DEV_BUILD: false,
  }
})

function respondWith(body: unknown, ok = true) {
  return vi.fn().mockResolvedValue({
    ok,
    json: async () => body,
  })
}

describe('useNewVersionAvailable', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', respondWith(APP_BUILD))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('stays quiet while the deployed build matches the running one', async () => {
    const { result } = renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(result.current).toBe(false)
  })

  it('reports a newer deployment', async () => {
    vi.stubGlobal(
      'fetch',
      respondWith({ version: '1.1.0', commit: 'bbbb222', builtAt: '2026-08-02T00:00:00.000Z' }),
    )
    const { result } = renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(result.current).toBe(true))
  })

  it('treats a rebuild of the same commit as a new deployment', async () => {
    // Same version and commit, later build: still a different deployment, and the user is still behind.
    vi.stubGlobal(
      'fetch',
      respondWith({ ...APP_BUILD, builtAt: '2026-08-05T00:00:00.000Z' }),
    )
    const { result } = renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(result.current).toBe(true))
  })

  it('bypasses caches so the check itself cannot be served stale', async () => {
    renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    const [url, init] = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls[0]
    expect(url).toMatch(/^\/version\.json\?t=\d+/)
    expect(init.cache).toBe('no-store')
  })

  it('says nothing when the check fails — a false prompt trains people to ignore a true one', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const { result } = renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(result.current).toBe(false)
  })

  it('says nothing on a non-OK response', async () => {
    vi.stubGlobal('fetch', respondWith({}, false))
    const { result } = renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(result.current).toBe(false)
  })

  it('says nothing for a payload with no build time — a half-written version.json is not a deploy', async () => {
    vi.stubGlobal('fetch', respondWith({ version: '9.9.9' }))
    const { result } = renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(result.current).toBe(false)
  })

  it('re-checks when the tab regains focus — the realistic case is a tab reopened days later', async () => {
    const { result } = renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))

    vi.stubGlobal(
      'fetch',
      respondWith({ version: '2.0.0', commit: 'cccc333', builtAt: '2026-09-01T00:00:00.000Z' }),
    )
    act(() => {
      window.dispatchEvent(new Event('focus'))
    })
    await waitFor(() => expect(result.current).toBe(true))
  })

  it('stops checking once it has reported — a third build must not withdraw the prompt', async () => {
    vi.stubGlobal(
      'fetch',
      respondWith({ version: '1.1.0', commit: 'bbbb222', builtAt: '2026-08-02T00:00:00.000Z' }),
    )
    const { result } = renderHook(() => useNewVersionAvailable())
    await waitFor(() => expect(result.current).toBe(true))
    const callsSoFar = (fetch as unknown as { mock: { calls: unknown[] } }).mock.calls.length

    act(() => {
      window.dispatchEvent(new Event('focus'))
    })
    expect((fetch as unknown as { mock: { calls: unknown[] } }).mock.calls.length).toBe(callsSoFar)
    expect(result.current).toBe(true)
  })
})
