import { useLocation } from 'react-router-dom'
import { ContentLink } from '@/components/ContentLink'
import { useAuth } from '@/auth/useAuth'
import { readOrigin } from '@/lib/navOrigin'

/**
 * Top-of-page nav for the public-by-code pages (#193).
 *
 * Back returns the viewer to **where they entered the public pages from** (#1027) — the recorded
 * origin, which is normally the dashboard including its `?tab=`. That replaces the previous
 * `navigate(-1)` (#323), which stepped back a single history entry: correct for one hop, but it made
 * `dashboard → player → match → player` a three-press walk home, and it could step outside the app
 * entirely because browser history contains entries this app never created. See `lib/navOrigin.ts`.
 *
 * Fallbacks, in order:
 *  - signed in, no recorded origin (cold open, pasted link, new tab) → `/dashboard`
 *  - signed out → `/login`, alongside the sign-up CTA rather than replacing it
 *
 * **Three states, not two.** While Firebase restores a session, `user` is null and `initializing` is
 * true. Treating that as "signed out" rendered the CTA for a moment on every full page load and, worse,
 * a Back clicked in that window sent a signed-in viewer to the login page. `RequireAuth` already waits
 * on `initializing`; this does too (#1027).
 */
export function PublicPageNav() {
  const { user, initializing } = useAuth()
  const location = useLocation()

  // Auth still resolving: render the frame but commit to nothing. Returning null would collapse the
  // layout and shift the page once auth lands.
  if (initializing) {
    return <div className="text-sm text-muted-foreground">&nbsp;</div>
  }

  if (user) {
    const origin = readOrigin()
    return (
      <ContentLink to={origin ?? '/dashboard'} className="text-sm">
        ← Back
      </ContentLink>
    )
  }

  return (
    <div className="rounded-lg border bg-muted/40 p-3 text-sm">
      <span className="text-muted-foreground">
        Sign up to track your own ratings and matches.
      </span>{' '}
      <ContentLink to="/signup" className="font-medium">
        Sign up
      </ContentLink>
      {' · '}
      {/*
        Back goes to /login WITHOUT `state.from`, unlike the log-in link beside it. The distinction is
        deliberate: "Log in" means "sign in and bring me back here", while "Back" means "leave here".
        Carrying `from` on Back would bounce the viewer straight back to the page they just left.
      */}
      <ContentLink to="/login" state={{ from: location }} className="font-medium">
        Log in
      </ContentLink>
      {' · '}
      <ContentLink to="/login" className="font-medium">
        ← Back
      </ContentLink>
    </div>
  )
}
