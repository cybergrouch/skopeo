import { useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  createUserWithEmailAndPassword,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut as firebaseSignOut,
  type User,
} from 'firebase/auth'
import { auth, googleProvider } from '@/lib/firebase'
import { AuthContext, type AuthContextValue } from './auth-context'

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
      signOut: () => firebaseSignOut(auth),
    }),
    [user, initializing],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
