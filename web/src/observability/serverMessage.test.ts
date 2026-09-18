import { describe, it, expect } from 'vitest'
import { serverMessage } from './serverMessage'

describe('serverMessage', () => {
  it('returns the API\'s own ErrorResponse message', () => {
    // The sentences this exists to rescue — written by LiveScoringRules and, before #1070, replaced
    // with a generic toast before the umpire ever saw them.
    const error = {
      response: {
        data: {
          error: 'Conflict',
          message: 'Nobody is serving yet. Set who is serving before recording a point.',
        },
      },
    }
    expect(serverMessage(error)).toBe(
      'Nobody is serving yet. Set who is serving before recording a point.',
    )
  })

  it('returns undefined when there is no message to show', () => {
    // Each of these is a real shape: a network failure with no response, a 500 with an empty body,
    // a body without the field, and a thrown non-object. All must fall back to the caller's copy
    // rather than surfacing "undefined" to the user.
    expect(serverMessage(new Error('Network Error'))).toBeUndefined()
    expect(serverMessage({ response: {} })).toBeUndefined()
    expect(serverMessage({ response: { data: {} } })).toBeUndefined()
    expect(serverMessage(undefined)).toBeUndefined()
    expect(serverMessage(null)).toBeUndefined()
    expect(serverMessage('boom')).toBeUndefined()
  })

  it('ignores a non-string or blank message rather than showing it', () => {
    // A blank string would render an empty toast, which is worse than the generic fallback: it tells
    // the user something failed and nothing about what.
    expect(serverMessage({ response: { data: { message: '' } } })).toBeUndefined()
    expect(serverMessage({ response: { data: { message: '   ' } } })).toBeUndefined()
    expect(serverMessage({ response: { data: { message: 42 } } })).toBeUndefined()
    expect(serverMessage({ response: { data: { message: null } } })).toBeUndefined()
  })
})
