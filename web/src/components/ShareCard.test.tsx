import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ShareCard } from './ShareCard'

describe('ShareCard', () => {
  it('renders the title/description and a QR encoding the url, and copies the link', () => {
    const writeText = vi.fn()
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })

    const { container } = render(
      <ShareCard url="https://x.dev/matches/MTCH01" title="Share this match" description="Scan or copy." />,
    )

    expect(screen.getByText('Share this match')).toBeInTheDocument()
    expect(screen.getByText('Scan or copy.')).toBeInTheDocument()
    // qrcode.react renders an <svg>.
    expect(container.querySelector('svg')).not.toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }))
    expect(writeText).toHaveBeenCalledWith('https://x.dev/matches/MTCH01')
    expect(screen.getByRole('button', { name: 'Copied!' })).toBeInTheDocument()
  })

  it('falls back to default title/description', () => {
    render(<ShareCard url="https://x.dev/players/AAA111" />)
    expect(screen.getByText('Share this page')).toBeInTheDocument()
    expect(screen.getByText(/scan this code or copy the link/i)).toBeInTheDocument()
  })
})
