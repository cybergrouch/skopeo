import { describe, it, expect } from 'vitest'

/**
 * A grid item's default `min-width` is `auto`, so a bare `grid gap-4` track refuses to shrink below
 * its widest child. On a phone that means a card holding a table or a long identifier widens the whole
 * page and the user scrolls sideways to read a row (#768) — and it also silently disables the
 * `overflow-x-auto` wrappers the tables already have, because the wrapper's ancestor grew instead of
 * constraining it.
 *
 * This is a source check rather than a rendered one on purpose: jsdom has no layout engine, so
 * `scrollWidth` is always 0 and the real defect is unobservable in a component test. Asserting the
 * class is the only part of it that can be automated; the visual behaviour still needs a real viewport.
 */
const tabSources = import.meta.glob('./*Tab.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

describe('dashboard tab layout', () => {
  it('finds the dashboard tabs to check', () => {
    // Guards the guard: a rename that emptied this list would make the assertion below vacuous.
    expect(Object.keys(tabSources).length).toBeGreaterThan(8)
  })

  it('uses a shrinkable grid track so cards cannot widen the page on a phone (#768)', () => {
    const offenders = Object.entries(tabSources)
      .filter(([, source]) => /className="grid gap-4"/.test(source))
      .map(([path]) => path)

    expect(
      offenders,
      'use `grid grid-cols-[minmax(0,1fr)] gap-4` — a bare `grid gap-4` track cannot shrink below its widest card',
    ).toEqual([])
  })
})
