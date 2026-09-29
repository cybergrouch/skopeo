/**
 * A `FirebaseError`, recognised **without importing `firebase/app`** (#1091).
 *
 * `instanceof FirebaseError` needs the class at runtime, and both callers sit on paths an anonymous
 * visitor reaches — error classification and the auth-error message table. Under the old single
 * `firebase` chunk that one import was enough to pull the whole SDK; with the chunk split it would
 * still pull `firebase/app` onto pages that have no other use for it.
 *
 * A structural check costs nothing and is, if anything, more robust: `instanceof` already fails
 * across duplicate copies of `@firebase/app` in a dependency tree, which is a real way for a
 * `FirebaseError` to stop looking like one.
 *
 * Deliberately narrow — `name === 'FirebaseError'` plus a string `code`. Every Firebase error carries
 * both; an `AxiosError` carries `code` but is named differently, so the two cannot be confused.
 */
export function isFirebaseError(error: unknown): error is { code: string; message: string } {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { name?: unknown }).name === 'FirebaseError' &&
    typeof (error as { code?: unknown }).code === 'string'
  )
}
