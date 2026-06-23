import { createContext } from 'react'
import type { User, UserCredential } from 'firebase/auth'

export interface AuthContextValue {
  /** The current Firebase user, or null when signed out. */
  user: User | null
  /** True until the first auth state has been resolved (avoids redirect flicker). */
  initializing: boolean
  signUpWithEmail: (email: string, password: string) => Promise<UserCredential>
  signInWithEmail: (email: string, password: string) => Promise<UserCredential>
  signInWithGoogle: () => Promise<UserCredential>
  signOut: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
