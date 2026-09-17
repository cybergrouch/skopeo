import { describe, it, expect, beforeEach } from 'vitest'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { NavOriginRecorder } from './NavOriginRecorder'
import { clearOrigin, readOrigin } from '@/lib/navOrigin'

function recordAt(entry: string) {
  render(
    <MemoryRouter initialEntries={[entry]}>
      <NavOriginRecorder />
    </MemoryRouter>,
  )
}

describe('NavOriginRecorder', () => {
  beforeEach(() => clearOrigin())

  it('records a non-public location, query string included (#1027)', () => {
    recordAt('/dashboard?tab=standings')
    expect(readOrigin()).toBe('/dashboard?tab=standings')
  })

  it('does not record a public page, so an excursion cannot overwrite the origin (#1027)', () => {
    // The whole point: the origin must survive an arbitrarily long walk through player/match/event
    // pages, so visiting one must never replace it.
    render(
      <MemoryRouter initialEntries={['/dashboard?tab=standings']}>
        <NavOriginRecorder />
      </MemoryRouter>,
    )
    recordAt('/players/AAA111')
    expect(readOrigin()).toBe('/dashboard?tab=standings')
  })

  it('does not record the login page as an origin (#1027)', () => {
    recordAt('/login')
    expect(readOrigin()).toBeNull()
  })
})
