import { useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  createUserWithEmailAndPassword,
  isSignInWithEmailLink,
  onAuthStateChanged,
  sendSignInLinkToEmail,
  signInWithEmailAndPassword,
  signInWithEmailLink,
  signInWithPopup,
  signOut as firebaseSignOut,
  updatePassword,
  type User,
} from 'firebase/auth'
import { auth, googleProvider, facebookProvider } from '@/lib/firebase'
import { AuthContext, type AuthContextValue } from './auth-context'

/** Continue URL for an invite email-link; the invitee's accept page reads the email from it. */
function inviteActionCodeSettings(email: string) {
  return {
    url: `${window.location.origin}/invite?email=${encodeURIComponent(email)}`,
    handleCodeInApp: true,
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [initializing, setInitializing] = useState(true)

  useEffect(() => {
    return onAuthStateChanged(auth, (next) => {
      setUser(next)
      setInitializing(false)
    })
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      initializing,
      signUpWithEmail: (email, password) =>
        createUserWithEmailAndPassword(auth, email, password),
      signInWithEmail: (email, password) =>
        signInWithEmailAndPassword(auth, email, password),
      signInWithGoogle: () => signInWithPopup(auth, googleProvider),
      signInWithFacebook: () => signInWithPopup(auth, facebookProvider),
      signOut: () => firebaseSignOut(auth),
      sendSignInLink: (email) =>
        sendSignInLinkToEmail(auth, email, inviteActionCodeSettings(email)).then(() => undefined),
      isSignInLink: (url) => isSignInWithEmailLink(auth, url),
      completeSignInLink: (email, url) => signInWithEmailLink(auth, email, url),
      // Only called right after email-link sign-in, so currentUser is set.
      setPassword: (password) => updatePassword(auth.currentUser as User, password),
    }),
    [user, initializing],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
