import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { setupUser } from '@/test/user'
import { NewVersionBanner } from './NewVersionBanner'

const { available } = vi.hoisted(() => ({ available: { value: false } }))
vi.mock('@/hooks/useNewVersionAvailable', () => ({
  useNewVersionAvailable: () => available.value,
}))

describe('NewVersionBanner', () => {
  beforeEach(() => {
    available.value = false
    vi.clearAllMocks()
  })

  it('renders nothing while the running bundle is current', () => {
    const { container } = render(<NewVersionBanner />)
    expect(container).toBeEmptyDOMElement()
  })

  it('offers a reload once a newer bundle is deployed', () => {
    available.value = true
    render(<NewVersionBanner />)
    expect(screen.getByText('A new version of Skopeo is available.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
  })

  it('reloads only when asked — never on the user’s behalf', async () => {
    // Auto-reloading would discard a half-filled form to fix a problem the user may not have.
    available.value = true
    const reload = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, reload },
    })
    const user = setupUser()
    render(<NewVersionBanner />)

    expect(reload).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Reload' }))
    expect(reload).toHaveBeenCalled()
  })

  it('can be dismissed so someone mid-entry can finish first', async () => {
    available.value = true
    const user = setupUser()
    render(<NewVersionBanner />)

    await user.click(screen.getByRole('button', { name: 'Not now' }))
    expect(screen.queryByText('A new version of Skopeo is available.')).not.toBeInTheDocument()
  })
})
