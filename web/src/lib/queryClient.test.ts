import { describe, it, expect } from 'vitest'
import { createQueryClient, DEFAULT_STALE_TIME_MS } from './queryClient'

/**
 * These defaults apply to every query in the app — public pages, login, the umpire view — so they
 * are pinned rather than left as literals someone can quietly revert (#1093).
 *
 * The pairing is the part worth protecting: `refetchOnWindowFocus` is only affordable *because*
 * there is a staleness window. Re-zeroing `staleTime` while leaving focus-refetching on would turn
 * every alt-tab into a request storm, which is the failure the original config avoided by switching
 * the wrong half off.
 */
describe('createQueryClient', () => {
  it('gives every query a staleness window', () => {
    const defaults = createQueryClient().getDefaultOptions().queries

    expect(defaults?.staleTime).toBe(DEFAULT_STALE_TIME_MS)
    expect(DEFAULT_STALE_TIME_MS).toBeGreaterThan(0)
  })

  it('refetches on window focus, which the staleness window is what makes affordable', () => {
    const defaults = createQueryClient().getDefaultOptions().queries

    expect(defaults?.refetchOnWindowFocus).toBe(true)
  })

  it('retries once, unchanged', () => {
    expect(createQueryClient().getDefaultOptions().queries?.retry).toBe(1)
  })

  it('hands out independent clients, so one test cannot warm the cache of another', () => {
    expect(createQueryClient()).not.toBe(createQueryClient())
  })
})
