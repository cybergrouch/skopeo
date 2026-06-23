import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './useAuth'

/** Gate for routes that need a signed-in Firebase user. */
export function RequireAuth({ children }: { children: ReactNode }) {
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
