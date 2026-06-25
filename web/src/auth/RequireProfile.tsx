import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { isAxiosError } from 'axios'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'

/**
 * Gate for routes that need a provisioned Skopeo profile — not just a Firebase
 * login. A user can be authenticated but unprovisioned: they signed in with
 * Google/Facebook on the login page (which never provisions), or provisioning
 * failed after the Firebase account was created. `GET /api/v1/users/me` returns
 * 404 in that case, so we route them to `/complete-profile` instead of letting
 * them land on a broken dashboard. Use inside `RequireAuth`.
 */
export function RequireProfile({ children }: { children: ReactNode }) {
  const meQuery = useGetApiV1UsersMe()

  if (meQuery.isLoading) {
    return (
      <div className="flex min-h-svh items-center justify-center text-muted-foreground">
        Loading…
      </div>
    )
  }
  if (meQuery.isError) {
    if (isAxiosError(meQuery.error) && meQuery.error.response?.status === 404) {
      return <Navigate to="/complete-profile" replace />
    }
    return (
      <div className="flex min-h-svh items-center justify-center text-muted-foreground">
        Couldn’t load your profile. Please refresh.
      </div>
    )
  }
  return <>{children}</>
}
