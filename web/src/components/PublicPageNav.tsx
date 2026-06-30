import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '@/auth/useAuth'

/**
 * Top-of-page nav for the public-by-code pages (#193). Logged in → "Back to dashboard". Logged out →
 * a sign-up / log-in call-to-action; the log-in link carries the current location so the user returns
 * here after authenticating.
 */
export function PublicPageNav() {
  const { user } = useAuth()
  const location = useLocation()

  if (user) {
    return (
      <Link to="/dashboard" className="text-sm text-primary hover:underline">
        ← Back to dashboard
      </Link>
    )
  }

  return (
    <div className="rounded-lg border bg-muted/40 p-3 text-sm">
      <span className="text-muted-foreground">
        Sign up to track your own ratings and matches.
      </span>{' '}
      <Link to="/signup" className="font-medium text-primary hover:underline">
        Sign up
      </Link>
      {' · '}
      <Link
        to="/login"
        state={{ from: location }}
        className="font-medium text-primary hover:underline"
      >
        Log in
      </Link>
    </div>
  )
}
