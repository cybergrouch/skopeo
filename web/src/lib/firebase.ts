import { initializeApp } from 'firebase/app'

// All values are public client config (safe to ship to the browser); the
// sensitive Firebase secret stays server-side in Firebase itself. Supplied via
// Vite env vars (see .env.example) so deploys can target different projects.
const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
}

/**
 * The Firebase app handle — and **nothing auth-related** (#1091).
 *
 * `auth` and the sign-in providers used to live here too, which quietly made this module a back door
 * into the 162 kB auth SDK: `useLiveScore` imports `firebaseApp` for Firestore, so the public match
 * page pulled auth it has no use for. They now live in `./firebaseAuth`, reachable only through
 * `authModule.loadAuth()`.
 *
 * Keep this file free of `firebase/auth` imports. There is no lint rule guarding it — the cost of
 * breaking the rule is a silent 44 kB gzip regression on every public page, visible only in a build diff.
 */
export const firebaseApp = initializeApp(firebaseConfig)
