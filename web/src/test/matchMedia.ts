import { vi } from 'vitest'

/**
 * Installs a controllable `window.matchMedia` (jsdom has none). Every query answers [matches]; `set`
 * moves the "viewport" and fires `change` the way a resize across a breakpoint does. Undo with
 * `vi.unstubAllGlobals()`.
 */
export function stubMatchMedia(matches: boolean) {
  let current = matches
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return current
    },
    media: query,
    addEventListener: (_: 'change', listener: () => void) => listeners.add(listener),
    removeEventListener: (_: 'change', listener: () => void) => listeners.delete(listener),
  }))
  return {
    set(next: boolean) {
      current = next
      for (const listener of [...listeners]) listener()
    },
    listeners: () => listeners.size,
  }
}
