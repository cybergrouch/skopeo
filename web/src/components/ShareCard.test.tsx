import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ShareCard } from './ShareCard'

/** Reset the Web Share API between tests (jsdom has none by default → fallback links render). */
afterEach(() => {
  Object.defineProperty(navigator, 'share', { value: undefined, configurable: true })
})

describe('ShareCard', () => {
  it('renders the title/description and a QR encoding the url, and copies the link', () => {
    const writeText = vi.fn()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })

    const { container } = render(
      <ShareCard url="https://x.dev/matches/MTCH01" title="Share this match" description="Scan or copy." />,
    )

    expect(screen.getByText('Share this match')).toBeInTheDocument()
    expect(screen.getByText('Scan or copy.')).toBeInTheDocument()
    expect(container.querySelector('svg')).not.toBeNull() // qrcode.react renders an <svg>

    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }))
    expect(writeText).toHaveBeenCalledWith('https://x.dev/matches/MTCH01')
    expect(screen.getByRole('button', { name: 'Copied!' })).toBeInTheDocument()
  })

  it('falls back to default title/description', () => {
    render(<ShareCard url="https://x.dev/players/AAA111" />)
    expect(screen.getByText('Share this page')).toBeInTheDocument()
    expect(screen.getByText(/scan this code, copy the link, or share it/i)).toBeInTheDocument()
  })

  it('shows per-network share links with correctly-encoded intents when Web Share is unavailable (#192)', () => {
    const url = 'https://x.dev/matches/MTCH01'
    render(<ShareCard url={url} title="Share this match" shareText="Ana vs Bob on Skopeo" />)

    expect(screen.queryByRole('button', { name: 'Share' })).not.toBeInTheDocument() // no native path
    expect(screen.getByRole('link', { name: 'Facebook' })).toHaveAttribute(
      'href',
      `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(url)}`,
    )
    expect(screen.getByRole('link', { name: 'X' })).toHaveAttribute(
      'href',
      `https://twitter.com/intent/tweet?url=${encodeURIComponent(url)}&text=${encodeURIComponent('Ana vs Bob on Skopeo')}`,
    )
    expect(screen.getByRole('link', { name: 'WhatsApp' })).toHaveAttribute(
      'href',
      `https://wa.me/?text=${encodeURIComponent(`Ana vs Bob on Skopeo ${url}`)}`,
    )
    // Opens safely in a new tab.
    expect(screen.getByRole('link', { name: 'Facebook' })).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('falls back to the title as share text when shareText is omitted', () => {
    const url = 'https://x.dev/players/AAA111'
    render(<ShareCard url={url} title="Share this profile" />)
    expect(screen.getByRole('link', { name: 'X' })).toHaveAttribute(
      'href',
      `https://twitter.com/intent/tweet?url=${encodeURIComponent(url)}&text=${encodeURIComponent('Share this profile')}`,
    )
  })

  it('uses the native share sheet when the Web Share API is available (#192)', () => {
    const share = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { value: share, configurable: true })

    render(<ShareCard url="https://x.dev/matches/MTCH01" title="Share this match" shareText="Ana vs Bob on Skopeo" />)

    // Single native Share button; the per-network fallback links are not shown.
    expect(screen.queryByRole('link', { name: 'Facebook' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Share' }))
    expect(share).toHaveBeenCalledWith({
      title: 'Share this match',
      text: 'Ana vs Bob on Skopeo',
      url: 'https://x.dev/matches/MTCH01',
    })
  })
})
