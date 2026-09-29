/**
 * Lazy access to the Firebase Auth SDK (#1091).
 *
 * The SDK is 162 kB gzip — by a wide margin the largest thing the app ships — and an anonymous
 * visitor on a public-by-code page (#193) will never sign in. Those pages are the ones a QR code on a
 * court noticeboard opens, so the people paying for auth they don't need are on the worst connections.
 *
 * Nothing here imports `firebase/auth` statically. The single dynamic `import()` in [loadAuth] is the
 * only entry point, which is what lets Rollup keep the SDK out of the initial graph.
 *
 * **Two ways the SDK gets loaded, and they answer different needs:**
 *
 * - [hasPersistedSession] — a *guess*, made without the SDK, for pages that merely want to render the
 *   right nav. Cheap and allowed to be wrong.
 * - [ensureAuthLoaded] — a *demand*, for surfaces that cannot function without auth. Never guesses.
 *
 * Keeping those separate is deliberate: the probe reads a key Firebase owns, and a format change on
 * their side must not be able to lock a signed-in user out of the dashboard. See [RequireAuth].
 */

/**
 * `firebase/auth` plus our configured providers.
 *
 * Written as `typeof import(...)` so the shape is exact without a value import — a type-only
 * reference is erased entirely, which is the point of this file.
 */
export type AuthModule = typeof import('firebase/auth') & typeof import('@/lib/firebaseAuth')

let pending: Promise<AuthModule> | null = null

/**
 * Load the SDK, at most once per page load.
 *
 * The promise is cached rather than the resolved value, so concurrent callers — `AuthProvider`
 * subscribing while a request interceptor wants a token — share one network fetch instead of racing
 * two.
 */
export function loadAuth(): Promise<AuthModule> {
  pending ??= (async () => {
    const [firebaseAuth, config] = await Promise.all([
      import('firebase/auth'),
      import('@/lib/firebaseAuth'),
    ])
    return { ...firebaseAuth, ...config }
  })().catch((error: unknown) => {
    // Forget the failure so a later attempt can retry. Caching a *rejected* promise would fail
    // every caller for the rest of the page's life, and the usual causes are transient: offline,
    // or a hashed chunk 404ing because a deploy landed mid-session.
    pending = null
    throw error
  })
  return pending
}

/** Whether the SDK is already in memory, so a caller can take a synchronous path when it is. */
export function authLoaded(): boolean {
  return pending !== null
}

/**
 * Whether a signed-in session is probably persisted, decided **without** loading the SDK.
 *
 * Firebase writes its session to `localStorage` under `firebase:authUser:<apiKey>:[DEFAULT]`. Reading
 * that key is the only way to know whether a visitor is signed in before paying 162 kB to ask properly.
 *
 * **This is a heuristic on a key Firebase owns, and is treated as one.** If the format ever changes it
 * returns false for a signed-in visitor, who then sees the signed-out nav on a public page until they
 * act — a cosmetic regression of #1027, not a broken page. It is deliberately NOT what protects the
 * dashboard: [RequireAuth] calls [ensureAuthLoaded] outright, so a wrong guess here can never bounce a
 * genuinely signed-in user to the login screen.
 *
 * Wrapped in try/catch because `localStorage` throws outright in Safari's private mode and under some
 * embedded webviews — a scoreboard on a borrowed phone must still render.
 */
export function hasPersistedSession(): boolean {
  try {
    for (let i = 0; i < window.localStorage.length; i += 1) {
      if (window.localStorage.key(i)?.startsWith('firebase:authUser:')) return true
    }
  } catch {
    // No storage access. Fall through to "no session": the worst case is the signed-out nav, and a
    // visitor who cannot persist a session could not have been signed in across a reload anyway.
  }
  return false
}

/**
 * Demand-side signal: has anything on this page asked for auth for real?
 *
 * A tiny store rather than a prop or a context method, because the callers are route guards scattered
 * through the tree and the subscriber is `AuthProvider` above them — passing a request *up* would mean
 * threading a callback through every guard.
 */
let requested = false
const listeners = new Set<() => void>()

/**
 * Declare that this surface cannot function without auth, and start loading.
 *
 * Idempotent, and safe to call during render: it only mutates when the flag actually flips, so it
 * cannot loop a `useSyncExternalStore` subscriber.
 */
export function requestAuth(): void {
  // Swallowed here on purpose: this only *starts* the load. Whoever awaits `loadAuth()` — the
  // provider, the route guard, the request interceptor — decides what a failure means for them, and
  // a floating promise would otherwise surface as an unhandled rejection with no owner.
  loadAuth().catch(() => undefined)
  if (requested) return
  requested = true
  listeners.forEach((notify) => notify())
}

export function isAuthRequested(): boolean {
  return requested
}

export function subscribeAuthRequested(notify: () => void): () => void {
  listeners.add(notify)
  return () => listeners.delete(notify)
}

/** Test seam: forget the cached load and any demand, so each test starts from a cold module. */
export function resetAuthModuleForTests(): void {
  pending = null
  requested = false
  listeners.clear()
}
