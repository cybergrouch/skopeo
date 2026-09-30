import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { DESKTOP_QUERY, useMediaQuery } from './useMediaQuery'
import { stubMatchMedia } from '@/test/matchMedia'

describe('useMediaQuery', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('is false where matchMedia does not exist, so the small-screen layout is the fallback', () => {
    vi.stubGlobal('matchMedia', undefined)
    const { result } = renderHook(() => useMediaQuery(DESKTOP_QUERY))
    expect(result.current).toBe(false)
  })

  it('reports whether the query matches', () => {
    stubMatchMedia(true)
    const { result } = renderHook(() => useMediaQuery(DESKTOP_QUERY))
    expect(result.current).toBe(true)
  })

  it('follows the viewport across the breakpoint, and stops listening on unmount', () => {
    const viewport = stubMatchMedia(false)
    const { result, unmount } = renderHook(() => useMediaQuery(DESKTOP_QUERY))
    expect(result.current).toBe(false)

    act(() => viewport.set(true))
    expect(result.current).toBe(true)

    unmount()
    expect(viewport.listeners()).toBe(0)
  })
})
