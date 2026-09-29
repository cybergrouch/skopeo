import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RequireAuthLoaded } from './RequireAuthLoaded'

const m = vi.hoisted(() => ({
  loadAuth: vi.fn<() => Promise<unknown>>(),
  requestAuth: vi.fn(),
  authLoaded: vi.fn<() => boolean>(() => false),
}))

vi.mock('./authModule', () => ({
  loadAuth: m.loadAuth,
  requestAuth: m.requestAuth,
  authLoaded: m.authLoaded,
}))

describe('RequireAuthLoaded', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    m.authLoaded.mockReturnValue(false)
  })

  it('holds children back until the SDK is in memory', async () => {
    m.loadAuth.mockResolvedValue({})
    render(
      <RequireAuthLoaded>
        <p>protected</p>
      </RequireAuthLoaded>,
    )

    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(await screen.findByText('protected')).toBeInTheDocument()
  })

  it('demands the SDK rather than waiting for the session probe to guess', async () => {
    m.loadAuth.mockResolvedValue({})
    render(
      <RequireAuthLoaded>
        <p>protected</p>
      </RequireAuthLoaded>,
    )

    // The whole reason this guard exists: a stale `hasPersistedSession` must never be what decides
    // whether a signed-in user reaches the dashboard.
    expect(m.requestAuth).toHaveBeenCalled()
    // Settle the load before the test ends: the resolution lands a state update, and leaving it in
    // flight is what produces an `act(...)` warning and, eventually, a flaky neighbour.
    expect(await screen.findByText('protected')).toBeInTheDocument()
  })

  /**
   * A chunk that 404s — which a deploy landing mid-session causes, since chunk names are hashed —
   * must not leave a permanent spinner in front of a protected route. Rendering on is the honest
   * outcome: `RequireAuth` then redirects to login, which the user can act on.
   */
  it('renders children even when the SDK fails to load, rather than spinning forever', async () => {
    m.loadAuth.mockRejectedValue(new Error('chunk 404'))
    render(
      <RequireAuthLoaded>
        <p>protected</p>
      </RequireAuthLoaded>,
    )

    expect(await screen.findByText('protected')).toBeInTheDocument()
  })

  it('renders immediately when the SDK is already loaded', () => {
    m.authLoaded.mockReturnValue(true)
    render(
      <RequireAuthLoaded>
        <p>protected</p>
      </RequireAuthLoaded>,
    )

    expect(screen.getByText('protected')).toBeInTheDocument()
    expect(m.loadAuth).not.toHaveBeenCalled()
  })
})
