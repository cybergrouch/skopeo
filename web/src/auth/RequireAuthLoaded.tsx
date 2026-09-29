import { useEffect, useState, type ReactNode } from 'react'
import { authLoaded, loadAuth, requestAuth } from './authModule'

/**
 * Renders [children] only once the Firebase Auth SDK is in memory (#1091).
 *
 * The boundary between "pages that need auth" and "pages that must not pay for it". Inside it every
 * auth call behaves exactly as it did when the SDK was a static import — including the synchronous
 * `isSignInLink`, which `InviteAcceptPage` derives during render and which cannot be answered without
 * the module.
 *
 * **Self-contained on purpose.** It resolves the load itself rather than reading `initializing` off
 * the context, because the provider learns about the demand through a store and is therefore one
 * render behind the guard that made it. Gating on the context here would let a protected route see
 * `initializing: false, user: null` in that gap and redirect a signed-in user to the login page — the
 * exact failure this guard exists to rule out.
 *
 * Distinct from [RequireAuth], which additionally requires a *signed-in user*. This one only requires
 * that the question can be asked.
 */
export function RequireAuthLoaded({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(authLoaded)

  useEffect(() => {
    if (ready) return
    let live = true
    requestAuth()
    void loadAuth().then(() => {
      if (live) setReady(true)
    })
    return () => {
      live = false
    }
  }, [ready])

  if (!ready) {
    return (
      <div className="flex min-h-svh items-center justify-center text-muted-foreground">
        Loading…
      </div>
    )
  }
  return <>{children}</>
}
