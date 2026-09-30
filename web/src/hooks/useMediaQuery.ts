import { useCallback, useSyncExternalStore } from 'react'

/** Tailwind's `md:` breakpoint (48rem): the width from which the dashboard shows a persistent rail (#1095). */
export const DESKTOP_QUERY = '(min-width: 48rem)'

function supported(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
}

/**
 * Whether [query] matches, kept current as the viewport changes. False where `matchMedia` is missing
 * (jsdom has none), so an environment that cannot answer gets the small-screen layout, which works at
 * every width.
 *
 * Chosen in JS rather than by CSS-hiding one of two layouts: hiding leaves both in the DOM, so every
 * control exists twice for anything that ignores CSS — the test suite included, where each
 * `getByRole` would match both copies.
 */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (!supported()) return () => {}
      const list = window.matchMedia(query)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    [query],
  )
  return useSyncExternalStore(
    subscribe,
    () => supported() && window.matchMedia(query).matches,
    () => false,
  )
}
