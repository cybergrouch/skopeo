import {
  browserLocalPersistence,
  browserPopupRedirectResolver,
  FacebookAuthProvider,
  GoogleAuthProvider,
  indexedDBLocalPersistence,
  initializeAuth,
} from 'firebase/auth'
import { firebaseApp } from './firebase'

/**
 * The auth instance and sign-in providers (#1091).
 *
 * Split out of `./firebase` so that importing the app handle — which Firestore needs for the public
 * live scoreboard — does not also pull the auth SDK.
 *
 * **Import this only through `authModule.loadAuth()`.** A static import anywhere in the app puts the
 * SDK back in the initial graph and undoes #1091; the single dynamic import there is what keeps it out.
 *
 * ## Why `localStorage` first (#1141)
 *
 * Public pages decide whether to load this SDK at all with `hasPersistedSession()`, which looks for a
 * `firebase:authUser:` key in **`localStorage`**: that check has to run before 162 kB of SDK, so it
 * cannot ask Firebase. `getAuth()` defaults to **IndexedDB** first, so the key never existed and every
 * fresh load of a public page showed a signed-in user the signed-out state.
 *
 * The order here is what makes the probe true: `localStorage` first, IndexedDB second. Listing IndexedDB
 * too is the migration path. Firebase looks for an existing user in every persistence listed and moves
 * one it finds into the first available, so sessions saved under the old default move across on their
 * next SDK load instead of being signed out. `firebaseAuth.test.ts` pins this order to the probe.
 *
 * `initializeAuth` (unlike `getAuth`) does not supply a popup resolver, and Google/Facebook sign-in use
 * popups, so it is passed explicitly.
 */
export const AUTH_PERSISTENCE = [browserLocalPersistence, indexedDBLocalPersistence]

export const auth = initializeAuth(firebaseApp, {
  persistence: AUTH_PERSISTENCE,
  popupRedirectResolver: browserPopupRedirectResolver,
})
export const googleProvider = new GoogleAuthProvider()
export const facebookProvider = new FacebookAuthProvider()
