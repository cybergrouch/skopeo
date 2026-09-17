import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { isOriginCandidate, rememberOrigin } from '@/lib/navOrigin'

/**
 * Records where the viewer is whenever that is somewhere Back could return them to (#1027).
 *
 * Renders nothing; it exists to be mounted inside `<BrowserRouter>` where `useLocation` works. Kept
 * as a component rather than a hook called from `App` because `App` itself sits *outside* the router.
 *
 * Only non-public, non-auth locations are recorded (see `lib/navOrigin.ts`), so visiting a public page
 * never overwrites the origin — that is the whole point: the origin must survive an arbitrarily long
 * excursion through player/match/event/club pages.
 */
export function NavOriginRecorder() {
  const location = useLocation()

  useEffect(() => {
    if (isOriginCandidate(location.pathname)) {
      rememberOrigin(`${location.pathname}${location.search}`)
    }
  }, [location.pathname, location.search])

  return null
}
