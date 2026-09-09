import { useCallback, useEffect, useState } from 'react'

/**
 * Hold the umpire view in **locked landscape, full-screen, with no scrolling in either axis** (#911).
 *
 * Three separate jobs, because no single browser API does all of them:
 *
 * 1. **Fullscreen** — `requestFullscreen`. Must be called from a user gesture, so it cannot be done on
 *    mount; the page shows a "Start scoring" button and calls {@link enter} from its click.
 * 2. **Orientation** — `screen.orientation.lock('landscape')`. Only permitted while fullscreen, and
 *    **not supported on iOS Safari at all**. So the lock is best-effort and the portrait *guard* below
 *    is the actual guarantee, not a fallback for an edge case.
 * 3. **No scrolling** — `overflow: hidden` on both `documentElement` and `body` while mounted. The page
 *    itself is sized in `dvh`/`dvw` so nothing overflows, but a phone's address bar collapsing mid-game
 *    can still produce a rubber-band scroll on iOS; pinning the document removes it.
 *
 * Everything is restored on unmount, including when the user leaves fullscreen with the Escape key or a
 * system gesture — which is why `isFullscreen` is tracked from the `fullscreenchange` event rather than
 * assumed from a successful `requestFullscreen`.
 */
export interface LockedLandscape {
  /** True while the viewport is portrait — the page must show its rotate prompt and nothing else. */
  isPortrait: boolean
  /** True while actually in fullscreen, tracked from the event rather than assumed. */
  isFullscreen: boolean
  /** Enter fullscreen and attempt the orientation lock. Must be called from a user gesture. */
  enter: () => Promise<void>
  /** Leave fullscreen and release the orientation lock. */
  exit: () => Promise<void>
}

/** `screen.orientation.lock` is not in the DOM lib's type and is absent on iOS Safari. */
type LockableOrientation = ScreenOrientation & {
  lock?: (orientation: 'landscape') => Promise<void>
  unlock?: () => void
}

function isPortraitNow(): boolean {
  // matchMedia rather than comparing width to height: it is what actually changes on rotation, and it
  // does not misreport a landscape phone whose on-screen keyboard has squashed the viewport.
  if (typeof window === 'undefined' || !window.matchMedia) return false
  return window.matchMedia('(orientation: portrait)').matches
}

export function useLockedLandscape(): LockedLandscape {
  const [isPortrait, setIsPortrait] = useState(isPortraitNow)
  const [isFullscreen, setIsFullscreen] = useState(false)

  // Pin the document for as long as the umpire view is mounted.
  useEffect(() => {
    const html = document.documentElement
    const previousHtml = html.style.overflow
    const previousBody = document.body.style.overflow
    html.style.overflow = 'hidden'
    document.body.style.overflow = 'hidden'
    return () => {
      html.style.overflow = previousHtml
      document.body.style.overflow = previousBody
    }
  }, [])

  useEffect(() => {
    const query = window.matchMedia('(orientation: portrait)')
    const onOrientation = () => setIsPortrait(query.matches)
    const onFullscreen = () => setIsFullscreen(Boolean(document.fullscreenElement))
    query.addEventListener('change', onOrientation)
    document.addEventListener('fullscreenchange', onFullscreen)
    return () => {
      query.removeEventListener('change', onOrientation)
      document.removeEventListener('fullscreenchange', onFullscreen)
    }
  }, [])

  const enter = useCallback(async () => {
    // Every step is best-effort and independently guarded: a desktop browser may refuse the orientation
    // lock while happily going fullscreen, and losing the whole sequence to one rejection would leave
    // the umpire staring at a dead button.
    try {
      if (!document.fullscreenElement) await document.documentElement.requestFullscreen()
    } catch {
      // Fullscreen refused (or unsupported). The layout still fits; only the chrome remains.
    }
    try {
      const orientation = window.screen?.orientation as LockableOrientation | undefined
      await orientation?.lock?.('landscape')
    } catch {
      // Not permitted (iOS Safari, or not fullscreen). The portrait guard covers it.
    }
  }, [])

  const exit = useCallback(async () => {
    try {
      const orientation = window.screen?.orientation as LockableOrientation | undefined
      orientation?.unlock?.()
    } catch {
      // Nothing to release.
    }
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
    } catch {
      // Already out.
    }
  }, [])

  return { isPortrait, isFullscreen, enter, exit }
}
