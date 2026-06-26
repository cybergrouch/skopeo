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
  signInWithFacebook: () => Promise<UserCredential>
  signOut: () => Promise<void>
  /** Send a Firebase email-link to [email] for invite onboarding (issue #74). */
  sendSignInLink: (email: string) => Promise<void>
  /** Whether [url] is a Firebase email sign-in link. */
  isSignInLink: (url: string) => boolean
  /** Complete email-link sign-in (sets email_verified: true). */
  completeSignInLink: (email: string, url: string) => Promise<UserCredential>
  /** Set a password on the currently signed-in user (invitee onboarding). */
  setPassword: (password: string) => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
