import { describe, it, expect } from 'vitest'
import { isFirebaseError } from './isFirebaseError'

/**
 * Structural rather than `instanceof` since #1091, so these cases pin the shape it recognises — the
 * point being that nothing here imports `firebase/app`.
 */
describe('isFirebaseError', () => {
  it('recognises a Firebase error by name and code', () => {
    const error = Object.assign(new Error('nope'), {
      name: 'FirebaseError',
      code: 'auth/invalid-email',
    })
    expect(isFirebaseError(error)).toBe(true)
  })

  it('rejects an error that merely carries a code', () => {
    // An AxiosError has `code` (ERR_NETWORK, ECONNABORTED) and would be misread by a code-only check,
    // sending network failures down the Firebase branch.
    const axiosish = Object.assign(new Error('timeout'), {
      name: 'AxiosError',
      code: 'ECONNABORTED',
    })
    expect(isFirebaseError(axiosish)).toBe(false)
  })

  it('rejects a Firebase-named object with no code', () => {
    expect(isFirebaseError({ name: 'FirebaseError' })).toBe(false)
  })

  it('rejects the values a catch block actually sees', () => {
    expect(isFirebaseError(null)).toBe(false)
    expect(isFirebaseError(undefined)).toBe(false)
    expect(isFirebaseError('auth/invalid-email')).toBe(false)
    expect(isFirebaseError(new Error('plain'))).toBe(false)
  })
})
