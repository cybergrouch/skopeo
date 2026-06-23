import { describe, it, expect } from 'vitest'
import { FirebaseError } from 'firebase/app'
import { authErrorMessage } from './firebase-errors'

describe('authErrorMessage', () => {
  it('maps a known Firebase auth code to a friendly message', () => {
    const error = new FirebaseError('auth/email-already-in-use', 'raw')
    expect(authErrorMessage(error)).toBe(
      'An account with this email already exists.',
    )
  })

  it('collapses wrong-password and user-not-found to one neutral message', () => {
    expect(authErrorMessage(new FirebaseError('auth/wrong-password', ''))).toBe(
      'Incorrect email or password.',
    )
    expect(authErrorMessage(new FirebaseError('auth/user-not-found', ''))).toBe(
      'Incorrect email or password.',
    )
  })

  it('falls back to a generic message for unknown Firebase codes', () => {
    const error = new FirebaseError('auth/some-new-code', 'raw')
    expect(authErrorMessage(error)).toBe(
      'Something went wrong. Please try again.',
    )
  })

  it('uses the message of a plain Error', () => {
    expect(authErrorMessage(new Error('boom'))).toBe('boom')
  })

  it('handles non-error values', () => {
    expect(authErrorMessage('nope')).toBe(
      'Something went wrong. Please try again.',
    )
  })
})
