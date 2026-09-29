import { getAuth, GoogleAuthProvider, FacebookAuthProvider } from 'firebase/auth'
import { firebaseApp } from './firebase'

/**
 * The auth instance and sign-in providers (#1091).
 *
 * Split out of `./firebase` so that importing the app handle — which Firestore needs for the public
 * live scoreboard — does not also pull the auth SDK.
 *
 * **Import this only through `authModule.loadAuth()`.** A static import anywhere in the app puts the
 * SDK back in the initial graph and undoes #1091; the single dynamic import there is what keeps it out.
 */
export const auth = getAuth(firebaseApp)
export const googleProvider = new GoogleAuthProvider()
export const facebookProvider = new FacebookAuthProvider()
