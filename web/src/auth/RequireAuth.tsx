import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { RequireAuthLoaded } from './RequireAuthLoaded'
import { useAuth } from './useAuth'

/**
 * Gate for routes that need a signed-in Firebase user.
 *
 * Wrapped in [RequireAuthLoaded] since #1091, so the SDK is loaded by *demand* here rather than by
 * the `hasPersistedSession` probe. The probe is a heuristic over a key Firebase owns; if it ever
 * reads false for a genuinely signed-in user, the cost must be a wrong nav on a public page, never a
 * redirect away from the dashboard.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  return (
    <RequireAuthLoaded>
      <RequireSignedIn>{children}</RequireSignedIn>
    </RequireAuthLoaded>
  )
}

function RequireSignedIn({ children }: { children: ReactNode }) {
  const { user, initializing } = useAuth()
  const location = useLocation()

  if (initializing) {
    return (
      <div className="flex min-h-svh items-center justify-center text-muted-foreground">
        Loading…
      </div>
    )
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <>{children}</>
}
