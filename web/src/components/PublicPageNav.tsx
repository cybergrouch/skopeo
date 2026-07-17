import { useLocation, useNavigate } from 'react-router-dom'
import { ContentLink } from '@/components/ContentLink'
import { useAuth } from '@/auth/useAuth'

/**
 * Top-of-page nav for the public-by-code pages (#193). Logged in → a Back control that returns to
 * wherever the viewer came from (#323). Logged out → a sign-up / log-in call-to-action; the log-in
 * link carries the current location so the user returns here after authenticating.
 */
export function PublicPageNav() {
  const { user } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()

  if (user) {
    // Return to the origin (#323): step back through the in-app history instead of always jumping to
    // /dashboard. A public page is shareable and may be opened cold (pasted link / new tab); the very
    // first history entry has key 'default', so in that case there's nothing to go back to and we
    // fall back to the dashboard.
    if (location.key !== 'default') {
      return (
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="content-link text-sm"
        >
          ← Back
        </button>
      )
    }
    return (
      <ContentLink to="/dashboard" className="text-sm">
        ← Back to dashboard
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
      <ContentLink to="/login" state={{ from: location }} className="font-medium">
        Log in
      </ContentLink>
    </div>
  )
}
